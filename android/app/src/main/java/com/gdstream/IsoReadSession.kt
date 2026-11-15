package com.gdstream

import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Cancellation owns sockets AND permits; Future.cancel alone cannot interrupt an Android HTTP read. */
internal class IsoReadSession {
    companion object { private val ids = AtomicLong() }
    val id = ids.incrementAndGet()
    @Volatile var isCancelled = false
        private set
    private val lock = Any()
    private val connections = LinkedHashSet<HttpURLConnection>()
    private val leases = LinkedHashSet<Permit>()

    internal class Permit(private val release: () -> Unit) : AutoCloseable {
        private val closed = AtomicBoolean()
        override fun close() { if (closed.compareAndSet(false, true)) release() }
    }

    fun log(tag: String, message: String) = DebugLogger.log(tag, "request=$id $message")
    fun checkActive() {
        if (isCancelled || Thread.currentThread().isInterrupted) throw InterruptedIOException("ISO request $id cancelled")
    }

    fun acquire(semaphore: Semaphore): Permit {
        checkActive()
        log("ISO_UPSTREAM_WAIT", "availablePermits=${semaphore.availablePermits()}")
        while (!semaphore.tryAcquire(100, TimeUnit.MILLISECONDS)) checkActive()
        lateinit var permit: Permit
        permit = Permit {
            semaphore.release()
            synchronized(lock) { leases.remove(permit) }
            log("ISO_UPSTREAM_RELEASE", "availablePermits=${semaphore.availablePermits()}")
        }
        synchronized(lock) {
            if (!isCancelled) { leases.add(permit); return permit }
        }
        permit.close()
        checkActive()
        throw InterruptedIOException("ISO request cancelled")
    }

    // Register BEFORE responseCode: a cancelled probe can be blocked connecting or reading headers.
    fun track(connection: HttpURLConnection) {
        synchronized(lock) {
            if (!isCancelled) { connections.add(connection); return }
        }
        disconnect(connection)
        checkActive()
    }

    fun disconnect(connection: HttpURLConnection) {
        synchronized(lock) { connections.remove(connection) }
        try { connection.disconnect() } catch (error: Exception) {
            log("ISO_UPSTREAM_CLOSE_ERR", "url=${safeStreamUrl(connection.url.toString())} ${isoFailureDetails(error)}")
        }
    }

    fun cancel(reason: String = "response finished") {
        val resources = synchronized(lock) {
            if (isCancelled) return
            isCancelled = true
            Pair(leases.toList(), connections.toList()).also { leases.clear(); connections.clear() }
        }
        log("ISO_REQUEST_CLOSE", reason)
        // Release independently of HttpURLConnection teardown, which can contend with a blocked read.
        // Worker finally blocks also close these leases; each lease releases exactly once.
        resources.first.forEach { it.close() }
        resources.second.forEach { disconnect(it) }
    }
}

/** GET/HEAD use Connection: close. Watch abandoned probes even while waiting for their first byte. */
internal class IsoClientWatch(socket: Socket, session: IsoReadSession, executor: ExecutorService) : AutoCloseable {
    private val finished = AtomicBoolean()
    private val job = executor.submit {
        try {
            socket.soTimeout = 1000
            val input = socket.getInputStream()
            while (!finished.get()) {
                try {
                    if (input.read() < 0) {
                        if (!finished.get()) session.cancel("client closed socket")
                        break
                    }
                } catch (_: SocketTimeoutException) { /* Recheck completion without polling the origin. */ }
            }
        } catch (error: java.io.IOException) {
            if (!finished.get()) session.cancel("client socket: ${isoFailureDetails(error)}")
        }
    }
    override fun close() { finished.set(true); job.cancel(true) }
}
