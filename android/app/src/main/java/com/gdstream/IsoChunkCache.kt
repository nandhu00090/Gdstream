package com.gdstream

import java.io.EOFException

/** Keep the current source warm. Two recently closed sources keep only their startup block. */
internal class IsoChunkCaches {
    private data class Source(val url: String, val size: Long)
    private val blocks = IsoBlockStore()
    private class Disc(val chunks: IsoChunkCache, var readers: Int = 0)
    private val discs = LinkedHashMap<Source, Disc>(4, 0.75f, true)
    private var latest: Source? = null

    @Synchronized
    fun acquire(url: String, session: IsoReadSession, size: Long = 0): IsoChunkCache {
        session.checkActive()
        val source = Source(canonicalIsoSourceUrl(url), size)
        val disc = discs.getOrPut(source) { Disc(IsoChunkCache(blocks = blocks)) }
        val changed = latest != source
        latest = source
        disc.readers++
        if (changed) trimIdle()
        session.onCancel {
            synchronized(this) {
                disc.readers--
                if (disc.readers == 0 && source != latest) trimIdle()
            }
        }
        return disc.chunks
    }

    private fun trimIdle() {
        val idle = discs.entries.filter { it.key != latest && it.value.readers == 0 }
        idle.dropLast(2).forEach { discs.remove(it.key)?.chunks?.clear() }
        idle.takeLast(2).forEach { it.value.chunks.retainStartupBlock() }
    }

    @Synchronized
    fun evict() { blocks.evict() }

    @Synchronized
    fun clear() {
        discs.values.forEach { it.chunks.clear() }
        discs.clear()
        latest = null
    }
}

internal data class IsoBlockKey(val owner: IsoChunkCache, val start: Long, val end: Long)

/** A shared slot covers an 8 MiB fetch from allocation until eviction. Demand
 * readers can still finish copying their bytes after an entry is evicted. */
internal class IsoBlockStore(private val maxBytes: Int = 48 * 1024 * 1024) {
    private val capacity = (maxBytes / IsoChunkCache.BLOCK_BYTES).coerceIn(1, 6)
    private val slots = java.util.concurrent.Semaphore(capacity, true)
    private val cached = LinkedHashMap<IsoBlockKey, ByteArray>(8, 0.75f, true)
    private var fetching = 0
    private var generation = 0L

    fun reserve(): Long {
        slots.acquire()
        synchronized(this) {
            fetching++
            while (cached.size + fetching > capacity) {
                val victim = cached.keys.firstOrNull { it.start != 0L } ?: cached.keys.first()
                cached.remove(victim)
            }
            return generation
        }
    }

    @Synchronized fun finish(key: IsoBlockKey, epoch: Long, bytes: ByteArray?) {
        if (epoch == generation && bytes != null && bytes.size <= minOf(maxBytes, IsoChunkCache.BLOCK_BYTES)) cached[key] = bytes
        fetching--
        slots.release()
    }

    @Synchronized fun get(key: IsoBlockKey) = cached[key]
    @Synchronized fun bytes(owner: IsoChunkCache) = cached.entries.sumOf { if (it.key.owner === owner) it.value.size else 0 }
    @Synchronized fun remove(owner: IsoChunkCache, keepStartup: Boolean = false) {
        cached.keys.removeAll { it.owner === owner && (!keepStartup || it.start != 0L) }
    }
    @Synchronized fun evict() {
        generation++
        cached.clear()
    }
}

/** Physical blocks are immutable here: packet rewriting happens on each caller's copy. */
internal class IsoChunkCache(
    maxBytes: Int = 48 * 1024 * 1024,
    private val executor: java.util.concurrent.ExecutorService = sharedExecutor,
    private val blocks: IsoBlockStore = IsoBlockStore(maxBytes)
) {
    companion object {
        const val BLOCK_BYTES = 8 * 1024 * 1024
        const val METADATA_BLOCK_BYTES = 2 * 1024 * 1024
        private val orphanTimer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "ISOIdleRange").apply { isDaemon = true }
        }
        private val warmWorkers = java.util.concurrent.Executors.newFixedThreadPool(IsoRangeReader.MAX_CONCURRENT_READS) { task ->
            Thread(task, "ISOReadAhead").apply { isDaemon = true }
        }
        private val sharedExecutor = java.util.concurrent.Executors.newFixedThreadPool(IsoRangeReader.MAX_CONCURRENT_READS) { task ->
            Thread(task, "ISOChunkFetch").apply { isDaemon = true }
        }
    }
    private fun key(start: Long, end: Long) = IsoBlockKey(this, start, end)

    /** Share the exact probe window with other inspections and playback of the same source. */
    fun readProbe(start: Long, end: Long, session: IsoReadSession, fetch: (Long, Long, IsoReadSession) -> ByteArray) =
        read(start, end, start, end, session, borrowFullBlock = true, fetch = fetch)
    private class Flight {
        val progress = Object()
        var buffer: ByteArray? = null
        @Volatile var available = 0
        val owner = IsoReadSession { data, count ->
            synchronized(progress) {
                buffer = data
                available = count
                progress.notifyAll()
            }
        }
        lateinit var task: java.util.concurrent.FutureTask<ByteArray>
        var readers = 0
        var idleGeneration = 0L
        val warmers = HashMap<IsoReadSession, AutoCloseable>()
        var scheduled = false
        var foreground = false
        var retireAfterDemand = false
        var discSeek = false
        fun abort(reason: String) {
            owner.cancel(reason)
            task.cancel(true)
        }
    }
    private val lock = Any()
    private val flights = HashMap<IsoBlockKey, Flight>()
    private var window = emptyList<LongRange>()

    fun prefetch(ranges: List<LongRange>, sourceSize: Long, session: IsoReadSession,
                 priority: Boolean = false, fetch: (Long, Long, IsoReadSession) -> ByteArray) =
        prefetchBlocks(ranges, sourceSize, session, priority, discSeek = false, fetch)

    fun prefetchSeek(file: IsoVirtualFile, start: Long, sourceSize: Long, session: IsoReadSession,
                     fetch: (Long, Long, IsoReadSession) -> ByteArray) =
        prefetchBlocks(file.seekRanges(start), sourceSize, session, priority = true, discSeek = true, fetch)

    private fun prefetchBlocks(ranges: List<LongRange>, sourceSize: Long, session: IsoReadSession, priority: Boolean,
                               discSeek: Boolean, fetch: (Long, Long, IsoReadSession) -> ByteArray): List<java.util.concurrent.Future<ByteArray>> {
        val target = ranges.firstOrNull()?.first ?: return emptyList()
        val desired = LinkedHashSet<IsoBlockKey>()
        ranges@ for (range in ranges) {
            var start = range.first / BLOCK_BYTES * BLOCK_BYTES
            while (start <= range.last && start < sourceSize) {
                val full = key(start, minOf(start + BLOCK_BYTES, sourceSize) - 1)
                desired.add(full)
                if (discSeek && desired.size == IsoRangeReader.MAX_CONCURRENT_READS) break@ranges
                start += BLOCK_BYTES
            }
        }
        var abandoned: Collection<Flight> = emptyList()
        try {
            return synchronized(lock) {
                session.checkActive()
                if (!priority && flights.any { it.value.discSeek } && desired.none { flights[it]?.discSeek == true })
                    return emptyList()
                val far = window.isNotEmpty() && window.all {
                    target < it.first - 32L * 1024 * 1024 || target > it.last + 32L * 1024 * 1024
                }
                window = ranges
                if (discSeek) {
                    // The player can close the old socket just after its timeline gesture.
                    // Keep shared demand alive until it detaches, then skip the orphan grace period.
                    flights.forEach { (key, flight) ->
                        flight.retireAfterDemand = key !in desired
                        flight.discSeek = key in desired
                    }
                }
                abandoned = if (priority || far) flights.filter { (key, flight) -> flight.readers == 0 && key !in desired }
                    .also { flights.keys.removeAll(it.keys) }.values else emptyList()
                desired.mapNotNull { key ->
                    if (blocks.get(key) != null) return@mapNotNull null
                    val flight = flight(key, fetch)
                    if (discSeek) flight.discSeek = true
                    keepWarm(key, flight, session)
                    schedule(flight, priority)
                    flight.task
                }
            }
        } finally {
            abandoned.forEach { it.abort(if (priority) "ISO timeline seek" else "abandoned read-ahead after seek") }
        }
    }

    // Called under lock. Keep a progressive download alive between consecutive socket writes.
    private fun keepWarm(key: IsoBlockKey, flight: Flight, session: IsoReadSession) {
        if (session in flight.warmers) return
        val registration = session.onCancel { release(key, flight, session) }
        session.checkActive()
        flight.warmers[session] = registration
    }

    // FutureTask runs only once, even when demand promotes a queued speculative block.
    private fun schedule(flight: Flight, priority: Boolean) {
        if (priority && !flight.foreground) {
            flight.foreground = true
            executor.execute(flight.task)
        } else if (!priority && !flight.foreground && !flight.scheduled) {
            flight.scheduled = true
            warmWorkers.execute(flight.task)
        }
    }

    private fun flight(key: IsoBlockKey, fetch: (Long, Long, IsoReadSession) -> ByteArray): Flight =
        flights.getOrPut(key) {
            Flight().also { created ->
                created.task = object : java.util.concurrent.FutureTask<ByteArray>({
                    created.owner.checkActive()
                    val generation = blocks.reserve()
                    var completed: ByteArray? = null
                    try {
                        created.owner.checkActive()
                        val data = fetch(key.start, key.end, created.owner)
                        if (data.size.toLong() != key.end - key.start + 1) throw EOFException("Incomplete ISO cache block")
                        completed = data
                        data
                    } finally {
                        synchronized(lock) {
                            blocks.finish(key, generation, completed.takeIf { flights[key] === created })
                        }
                    }
                }) {
                    override fun setException(error: Throwable) {
                        // Future.get() may return before done() runs. A retry must see a new flight.
                        synchronized(lock) { flights.remove(key, created) }
                        super.setException(error)
                    }

                    override fun done() {
                        synchronized(created.progress) { created.progress.notifyAll() }
                        synchronized(lock) {
                            flights.remove(key, created)
                            created.warmers.values.forEach { it.close() }
                            created.warmers.clear()
                        }
                    }
                }
            }
        }

    private fun release(key: IsoBlockKey, flight: Flight, warmer: IsoReadSession?) {
        synchronized(lock) {
            if (warmer == null) flight.readers-- else flight.warmers.remove(warmer)
            if (flight.readers != 0 || flights[key] !== flight) return
            if (flight.retireAfterDemand) {
                flights.remove(key)
                return@synchronized
            }
            if (flight.warmers.isNotEmpty()) return
            val generation = ++flight.idleGeneration
            // A completed download can leave the LRU before this grace period expires.
            val idleFlight = java.lang.ref.WeakReference(flight)
            orphanTimer.schedule({
                val candidate = idleFlight.get() ?: return@schedule
                val orphan = synchronized(lock) {
                    generation == candidate.idleGeneration && candidate.readers == 0 &&
                        candidate.warmers.isEmpty() && flights.remove(key, candidate)
                }
                if (orphan) candidate.abort("last proxy client closed")
            }, 1500, java.util.concurrent.TimeUnit.MILLISECONDS)
            return
        }
        flight.abort("abandoned demand after ISO seek")
    }

    fun read(start: Long, end: Long, extentStart: Long, extentEnd: Long,
             session: IsoReadSession, blockBytes: Int = BLOCK_BYTES, borrowFullBlock: Boolean = false,
             fetch: (Long, Long, IsoReadSession) -> ByteArray): ByteArray {
        require(start >= extentStart && end >= start && end <= extentEnd && end - start < Int.MAX_VALUE)
        if (borrowFullBlock && (start - extentStart) % blockBytes == 0L &&
            end - start == minOf(blockBytes - 1L, extentEnd - start)) {
            // IsoVirtualFile copies physical extents before stripping packet headers or editing the PMT.
            return block(key(start, end), (end - start + 1).toInt(), session, fetch)
        }
        val result = ByteArray((end - start + 1).toInt())
        var cursor = start
        while (cursor <= end) {
            session.checkActive()
            val from = extentStart + ((cursor - extentStart) / blockBytes) * blockBytes
            val to = from + minOf(blockBytes.toLong() - 1, extentEnd - from)
            val key = key(from, to)
            val required = (minOf(end, to) - key.start + 1).toInt()
            val data = block(key, required, session, fetch)
            session.checkActive()
            val offset = (cursor - key.start).toInt()
            val count = minOf(end - cursor + 1, (data.size - offset).toLong()).toInt()
            if (count <= 0) throw EOFException("Short cached ISO block")
            data.copyInto(result, (cursor - start).toInt(), offset, offset + count)
            cursor += count
        }
        return result
    }

    private fun block(
        key: IsoBlockKey,
        required: Int,
        session: IsoReadSession,
        fetch: (Long, Long, IsoReadSession) -> ByteArray
    ): ByteArray {
        val flight = synchronized(lock) {
            session.checkActive()
            blocks.get(key)?.let { return it }
            flight(key, fetch).also { it.readers++; schedule(it, true) }
        }
        val released = java.util.concurrent.atomic.AtomicBoolean()
        fun detach() { if (released.compareAndSet(false, true)) release(key, flight, null) }
        val registration = session.onCancel {
            synchronized(flight.progress) { flight.progress.notifyAll() }
            detach()
        }
        try {
            val prefix = synchronized(flight.progress) {
                while (!flight.task.isDone && flight.available < required) {
                    session.checkActive()
                    flight.progress.wait()
                }
                session.checkActive()
                flight.buffer?.takeIf { required < it.size && flight.available >= required }
            }
            if (prefix != null) {
                synchronized(lock) { if (flights[key] === flight) keepWarm(key, flight, session) }
                return prefix
            }
            try { return flight.task.get() }
            catch (error: java.util.concurrent.ExecutionException) { throw (error.cause ?: error) }
        } finally {
            registration.close()
            detach()
        }
    }

    internal fun activeReaders(): Int = synchronized(lock) { flights.values.sumOf { it.readers } }
    internal fun cachedBytes() = blocks.bytes(this)
    fun retainStartupBlock() = blocks.remove(this, keepStartup = true)

    fun cancelFlights() {
        val active = synchronized(lock) {
            window = emptyList()
            flights.values.toList().also { flights.clear() }
        }
        active.forEach { it.abort("playback session ended") }
    }
    fun clear() {
        cancelFlights()
        blocks.remove(this)
    }
}
