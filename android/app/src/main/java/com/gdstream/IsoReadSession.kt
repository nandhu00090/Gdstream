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
internal class IsoReadSession(val pcrProbe: Boolean = false, val publishPrefix: ((ByteArray, Int) -> Unit)? = null) {
    companion object {
        private val ids = AtomicLong()
        private val socketClosers = java.util.concurrent.Executors.newCachedThreadPool { task ->
            Thread(task, "ISOSocketClose").apply { isDaemon = true }
        }
    }
    val id = ids.incrementAndGet()
    @Volatile var isCancelled = false
        private set
    private val lock = Any()
    private val connections = LinkedHashMap<HttpURLConnection, Permit?>()
    private val leases = LinkedHashSet<Permit>()
    private val cancellations = LinkedHashMap<Any, () -> Unit>()

    internal class Permit(private val release: () -> Unit) : AutoCloseable {
        private val closed = AtomicBoolean()
        override fun close() { if (closed.compareAndSet(false, true)) release() }
    }

    fun log(tag: String, message: String) = DebugLogger.log(tag, "request=$id $message")
    fun checkActive() {
        if (isCancelled || Thread.currentThread().isInterrupted) throw InterruptedIOException("ISO request $id cancelled")
    }

    fun awaitRetry(millis: Long) {
        val wake = java.util.concurrent.CountDownLatch(1)
        val registration = onCancel { wake.countDown() }
        try { wake.await(millis, TimeUnit.MILLISECONDS); checkActive() }
        finally { registration.close() }
    }

    fun acquire(semaphore: Semaphore): Permit {
        checkActive()
        log("ISO_UPSTREAM_WAIT", "availablePermits=${semaphore.availablePermits()}")
        while (!semaphore.tryAcquire(100, TimeUnit.MILLISECONDS)) checkActive()
        lateinit var permit: Permit
        permit = Permit {
            semaphore.release()
            synchronized(lock) { leases.remove(permit) }
        }
        synchronized(lock) {
            if (!isCancelled) { leases.add(permit); return permit }
        }
        permit.close()
        checkActive()
        throw InterruptedIOException("ISO request cancelled")
    }

    // Register BEFORE responseCode: a cancelled probe can be blocked connecting or reading headers.
    fun track(connection: HttpURLConnection, hostSlots: Semaphore? = null) {
        val permit = hostSlots?.let {
            checkActive()
            while (!it.tryAcquire(100, TimeUnit.MILLISECONDS)) checkActive()
            Permit(it::release)
        }
        synchronized(lock) {
            if (!isCancelled) {
                if (connection !in connections) connections[connection] = permit else permit?.close()
                return
            }
        }
        permit?.close()
        disconnect(connection)
        checkActive()
    }

    fun disconnect(connection: HttpURLConnection) {
        val permit = synchronized(lock) { connections.remove(connection) }
        try { connection.disconnect() } catch (error: Exception) {
            log("ISO_UPSTREAM_CLOSE_ERR", "url=${safeStreamUrl(connection.url.toString())} ${isoFailureDetails(error)}")
        } finally { permit?.close() }
    }

    fun onCancel(action: () -> Unit): AutoCloseable {
        val key = Any()
        val invokeNow = synchronized(lock) {
            if (isCancelled) true else { cancellations[key] = action; false }
        }
        if (invokeNow) action()
        return AutoCloseable { synchronized(lock) { cancellations.remove(key) }; Unit }
    }

    fun cancel(reason: String = "response finished") {
        val resources = synchronized(lock) {
            if (isCancelled) return
            isCancelled = true
            Triple(leases.toList(), connections.keys.toList(), cancellations.values.toList()).also {
                leases.clear(); cancellations.clear()
            }
        }
        log("ISO_REQUEST_CLOSE", reason)
        // Release independently of HttpURLConnection teardown, which can contend with a blocked read.
        // Worker finally blocks also close these leases; each lease releases exactly once.
        resources.first.forEach { it.close() }
        resources.third.forEach { it() }
        // Android disconnect can contend with responseCode. Let sibling flights release their
        // permits immediately even if this socket is still unwinding on another thread.
        resources.second.forEach { conn ->
            if (resources.first.isEmpty()) disconnect(conn) else socketClosers.execute { disconnect(conn) }
        }
    }
}

/** Peek without consuming a subsequent keep-alive request. Stop before handing input to the parser. */
internal class IsoClientWatch(socket: Socket, session: IsoReadSession, executor: ExecutorService,
                              private val input: java.io.PushbackInputStream = java.io.PushbackInputStream(socket.getInputStream())) : AutoCloseable {
    private val finished = AtomicBoolean()
    private val job = executor.submit {
        try {
            socket.soTimeout = 250
            while (!finished.get()) {
                try {
                    val next = input.read()
                    if (next < 0) {
                        if (!finished.get()) session.cancel("client closed socket")
                    } else input.unread(next)
                    break
                } catch (_: SocketTimeoutException) { }
            }
        } catch (error: java.io.IOException) {
            if (!finished.get()) session.cancel("client socket: ${isoFailureDetails(error)}")
        }
    }
    override fun close() {
        finished.set(true)
        // Interrupt alone does not unblock SocketInputStream. Join after its bounded read timeout.
        try { job.get(1, TimeUnit.SECONDS) } catch (_: Exception) { job.cancel(true) }
    }
}
