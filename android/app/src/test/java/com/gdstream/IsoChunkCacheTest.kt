package com.gdstream

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class IsoChunkCacheTest {
    private val base = 42_000_000_000L
    private val block = IsoRangeReader.WINDOW_BYTES.toLong()

    private fun awaitReaders(cache: IsoChunkCache, count: Int) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (cache.activeReaders() != count && System.nanoTime() < until) Thread.yield()
        assertEquals(count, cache.activeReaders())
    }

    @Test fun eachActiveDiscKeepsItsOwnCacheAndTheCurrentDiscSurvivesBetweenRequests() {
        val caches = IsoChunkCaches()
        val a = IsoReadSession()
        val b = IsoReadSession()
        val seek = IsoReadSession()
        try {
            val discA = caches.acquire("https://origin/iso?id=a#title", a)
            val discB = caches.acquire("https://origin/iso?id=b", b)
            assertNotSame(discA, discB)
            assertSame(discA, caches.acquire("https://origin/iso?id=a", seek))
            a.cancel()
            b.cancel()
            seek.cancel()
            assertSame(discA, caches.acquire("https://origin/iso?id=a", IsoReadSession()))
            assertNotSame(discB, caches.acquire("https://origin/iso?id=b", IsoReadSession()))
        } finally { caches.clear() }
    }

    @Test fun sixReadersShareOneFetchAndOneAbortDoesNotCancelTheOthers() {
        val workers = Executors.newFixedThreadPool(4)
        val consumers = Executors.newFixedThreadPool(6)
        val cache = IsoChunkCache(4 * block.toInt(), workers)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val sessions = List(6) { IsoReadSession() }
        try {
            val tasks = sessions.map { session -> consumers.submit<ByteArray> {
                cache.read(base + 17, base + 100, base, base + 4 * block - 1, session) { from, to, owner ->
                    calls.incrementAndGet(); entered.countDown()
                    assertTrue(release.await(3, TimeUnit.SECONDS))
                    assertFalse(owner.isCancelled)
                    ByteArray((to - from + 1).toInt()) { 7 }
                }
            } }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            awaitReaders(cache, 6)
            sessions[0].cancel("aborted probe")
            awaitReaders(cache, 5)
            release.countDown()
            assertThrows(java.util.concurrent.ExecutionException::class.java) { tasks[0].get(1, TimeUnit.SECONDS) }
            tasks.drop(1).forEach { assertArrayEquals(ByteArray(84) { 7 }, it.get(3, TimeUnit.SECONDS)) }
            assertEquals(1, calls.get())
        } finally { release.countDown(); cache.clear(); workers.shutdownNow(); consumers.shutdownNow() }
    }

    @Test fun seekAfterLastReaderClosesRejoinsThePendingBlock() {
        val workers = Executors.newSingleThreadExecutor()
        val consumers = Executors.newCachedThreadPool()
        val cache = IsoChunkCache(executor = workers)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val session = IsoReadSession()
        val calls = AtomicInteger()
        fun read(reader: IsoReadSession) = consumers.submit<ByteArray> {
            cache.read(base, base + 1, base, base + block - 1, reader) { from, to, owner ->
                calls.incrementAndGet()
                entered.countDown()
                assertTrue(release.await(3, TimeUnit.SECONDS))
                owner.checkActive()
                ByteArray((to - from + 1).toInt()) { 9 }
            }
        }
        try {
            val previous = read(session)
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            session.cancel("seek")
            assertThrows(java.util.concurrent.ExecutionException::class.java) { previous.get(1, TimeUnit.SECONDS) }
            val next = read(IsoReadSession())
            awaitReaders(cache, 1)
            release.countDown()
            assertArrayEquals(byteArrayOf(9, 9), next.get(3, TimeUnit.SECONDS))
            assertEquals(1, calls.get())
        } finally { release.countDown(); cache.clear(); workers.shutdownNow(); consumers.shutdownNow() }
    }

    @Test fun cacheIsLruBoundedCopySafeAndDoesNotRetainFailures() {
        val workers = Executors.newFixedThreadPool(4)
        val cache = IsoChunkCache(2 * block.toInt(), workers)
        val calls = AtomicInteger()
        fun read(index: Int): ByteArray = cache.read(base + index * block, base + index * block + 9,
            base, base + 4 * block - 1, IsoReadSession()) { from, to, _ -> calls.incrementAndGet(); ByteArray((to - from + 1).toInt()) { 3 } }
        try {
            read(0)[0] = 99
            assertEquals(3.toByte(), read(0)[0])
            read(1); read(0); read(2)
            assertEquals(3, calls.get())
            assertTrue(cache.cachedBytes() <= 2 * block)
            read(1) // Block zero was touched most recently, so block one was evicted.
            assertEquals(4, calls.get())
            assertThrows(java.io.IOException::class.java) {
                cache.read(base + 4 * block, base + 4 * block, base + 4 * block, base + 5 * block - 1, IsoReadSession()) { _, _, _ -> throw java.io.IOException("origin failed") }
            }
            assertArrayEquals(byteArrayOf(8), cache.read(base + 4 * block, base + 4 * block, base + 4 * block, base + 5 * block - 1, IsoReadSession()) { from, to, _ -> ByteArray((to - from + 1).toInt()) { 8 } })
        } finally { cache.clear(); workers.shutdownNow() }
    }
}
