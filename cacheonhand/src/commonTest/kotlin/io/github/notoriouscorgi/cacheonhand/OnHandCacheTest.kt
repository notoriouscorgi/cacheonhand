package io.github.notoriouscorgi.cacheonhand

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.parse

@OptIn(ExperimentalCoroutinesApi::class)
class OnHandCacheTest {

    @Test
    fun `get returns a flow holding null for a new key`() = runTest {
        val cache = OnHandCache(timeSource = testScheduler.timeSource)
        val flow = cache.get<Int>(FakeInput(1))
        assertNull(flow.value)
    }

    @Test
    fun `get returns the same flow instance for the same key`() = runTest {
        val cache = OnHandCache(timeSource = testScheduler.timeSource)
        val flow1 = cache.get<Int>(FakeInput(1))
        val flow2 = cache.get<Int>(FakeInput(1))
        assertEquals(flow1, flow2)
    }

    @Test
    fun `get returns different flow instances for different keys`() = runTest {
        val cache = OnHandCache(timeSource = testScheduler.timeSource)
        val flow1 = cache.get<Int>(FakeInput(1))
        val flow2 = cache.get<Int>(FakeInput(2))
        assertNotEquals(flow1, flow2)
    }

    @Test
    fun `set writes a value readable by get`() = runTest {
        val cache = OnHandCache(timeSource = testScheduler.timeSource)
        cache[FakeInput(1)] = 42
        assertEquals(42, cache.get<Int>(FakeInput(1)).value)
    }

    @Test
    fun `set overwrites a previous value`() = runTest {
        val cache = OnHandCache(timeSource = testScheduler.timeSource)
        cache[FakeInput(1)] = 42
        cache[FakeInput(1)] = 99
        assertEquals(99, cache.get<Int>(FakeInput(1)).value)
    }

    @Test
    fun `getOrNull returns null for absent key`() = runTest {
        val cache = OnHandCache(timeSource = testScheduler.timeSource)
        assertNull(cache.getOrNull<Int>(FakeInput(1)))
    }

    @Test
    fun `getOrNull returns flow for present key`() = runTest {
        val cache = OnHandCache(timeSource = testScheduler.timeSource)
        cache[FakeInput(1)] = 42
        val flow = cache.getOrNull<Int>(FakeInput(1))
        assertNotNull(flow)
        assertEquals(42, flow.value)
    }

    @Test
    fun `observe emits current value when collected`() = runTest {
        val cache = OnHandCache(timeSource = testScheduler.timeSource)
        cache[FakeInput(1)] = 42
        val value = cache.observe<Int>(FakeInput(1)).first()
        assertEquals(42, value)
    }

    @Test
    fun `observe emits null for absent key`() = runTest {
        val cache = OnHandCache(timeSource = testScheduler.timeSource)
        val value = cache.observe<Int>(FakeInput(1)).first()
        assertNull(value)
    }

    @Test
    fun `observe emits updates when cache value changes`() = runTest {
        val cache = OnHandCache(timeSource = testScheduler.timeSource)
        val collected = mutableListOf<Int?>()

        val job = launch {
            cache.observe<Int>(FakeInput(1)).collect { collected.add(it) }
        }
        advanceUntilIdle()

        cache[FakeInput(1)] = 10
        advanceUntilIdle()
        cache[FakeInput(1)] = 20
        advanceUntilIdle()

        job.cancel()
        assertEquals(listOf(null, 10, 20), collected)
    }

    @Test
    fun `setWithTtl makes value readable immediately`() = runTest {
        val cache = OnHandCache(timeSource = testScheduler.timeSource)
        cache.setWithTtl(FakeInput(1), 42, parse("100ms"))
        assertEquals(42, cache.get<Int>(FakeInput(1)).value)
    }

    @Test
    fun `setWithTtl evicts value after TTL on get`() = runTest {
        val cache = OnHandCache(timeSource = testScheduler.timeSource)
        cache.setWithTtl(FakeInput(1), 42, parse("100ms"))

        testScheduler.advanceTimeBy(150)

        assertNull(cache.get<Int>(FakeInput(1)).value)
    }

    @Test
    fun `setWithTtl evicts value after TTL on getOrNull`() = runTest {
        val cache = OnHandCache(timeSource = testScheduler.timeSource)
        cache.setWithTtl(FakeInput(1), 42, parse("100ms"))

        testScheduler.advanceTimeBy(150)

        assertNull(cache.getOrNull<Int>(FakeInput(1)))
    }

    @Test
    fun `setWithTtl resets TTL on subsequent write`() = runTest {
        val cache = OnHandCache(timeSource = testScheduler.timeSource)
        cache.setWithTtl(FakeInput(1), 42, parse("100ms"))

        testScheduler.advanceTimeBy(80)
        cache.setWithTtl(FakeInput(1), 99, parse("100ms"))

        testScheduler.advanceTimeBy(80)
        assertEquals(99, cache.getOrNull<Int>(FakeInput(1))?.value)

        testScheduler.advanceTimeBy(50)
        assertNull(cache.getOrNull<Int>(FakeInput(1)))
    }

    @Test
    fun `setMaybeWithTtl delegates to set when ttl is null`() = runTest {
        val cache = OnHandCache(timeSource = testScheduler.timeSource)
        cache.setMaybeWithTtl(FakeInput(1), 42, null)
        assertEquals(42, cache.get<Int>(FakeInput(1)).value)

        // No TTL — value should persist regardless of time
        testScheduler.advanceTimeBy(999999)
        assertEquals(42, cache.getOrNull<Int>(FakeInput(1))?.value)
    }

    @Test
    fun `setMaybeWithTtl delegates to setWithTtl when ttl is provided`() = runTest {
        val cache = OnHandCache(timeSource = testScheduler.timeSource)
        cache.setMaybeWithTtl(FakeInput(1), 42, parse("100ms"))
        assertEquals(42, cache.get<Int>(FakeInput(1)).value)

        testScheduler.advanceTimeBy(150)
        assertNull(cache.getOrNull<Int>(FakeInput(1)))
    }

    @Test
    fun `clear removes all entries`() = runTest {
        val cache = OnHandCache(timeSource = testScheduler.timeSource)
        cache[FakeInput(1)] = 10
        cache[FakeInput(2)] = 20
        cache[FakeInput(3)] = 30

        cache.clear()

        assertNull(cache.getOrNull<Int>(FakeInput(1)))
        assertNull(cache.getOrNull<Int>(FakeInput(2)))
        assertNull(cache.getOrNull<Int>(FakeInput(3)))
    }

    @Test
    fun `clear allows fresh writes after clearing`() = runTest {
        val cache = OnHandCache(timeSource = testScheduler.timeSource)
        cache[FakeInput(1)] = 10
        cache.clear()
        cache[FakeInput(1)] = 99

        assertEquals(99, cache.get<Int>(FakeInput(1)).value)
    }

    @Test
    fun `updateWithRollback applies optimistic updates`() = runTest {
        val cache = OnHandCache(timeSource = testScheduler.timeSource)
        cache[FakeInput(1)] = 10

        cache.updateWithRollback(mapOf(FakeInput(1) to 99)) {
            // action succeeds
        }

        assertEquals(99, cache.get<Int>(FakeInput(1)).value)
    }

    @Test
    fun `updateWithRollback rolls back on action failure`() = runTest {
        val cache = OnHandCache(timeSource = testScheduler.timeSource)
        cache[FakeInput(1)] = 10
        cache[FakeInput(2)] = 20

        cache.updateWithRollback(mapOf(FakeInput(1) to 99, FakeInput(2) to 88)) {
            throw Exception("Boom")
        }

        assertEquals(10, cache.get<Int>(FakeInput(1)).value)
        assertEquals(20, cache.get<Int>(FakeInput(2)).value)
    }

    @Test
    fun `updateWithRollback rolls back to null for keys that had no prior value`() = runTest {
        val cache = OnHandCache(timeSource = testScheduler.timeSource)

        cache.updateWithRollback(mapOf(FakeInput(1) to 99)) {
            throw Exception("Boom")
        }

        assertNull(cache.get<Int>(FakeInput(1)).value)
    }

    @Test
    fun `updateWithRollback applies multiple keys on success`() = runTest {
        val cache = OnHandCache(timeSource = testScheduler.timeSource)

        cache.updateWithRollback(mapOf(FakeInput(1) to 10, FakeInput(2) to 20, FakeInput(3) to 30)) {
            // success
        }

        assertEquals(10, cache.get<Int>(FakeInput(1)).value)
        assertEquals(20, cache.get<Int>(FakeInput(2)).value)
        assertEquals(30, cache.get<Int>(FakeInput(3)).value)
    }

    @Test
    fun `evictStale cleans up expired entries in the background`() = runTest {
        val cache = OnHandCache(
            timeSource = testScheduler.timeSource,
            cacheLifecycleScope = backgroundScope,
        )
        cache.setWithTtl(FakeInput(1), 42, parse("100ms"))
        cache.setWithTtl(FakeInput(2), 99, parse("200ms"))
        cache[FakeInput(3)] = 77 // no TTL

        testScheduler.advanceTimeBy(150)

        // Trigger evictStale via get on an unrelated key
        cache.get<Int>(FakeInput(3))
        advanceUntilIdle()

        // FakeInput(1) should be evicted, FakeInput(2) still alive, FakeInput(3) permanent
        assertNull(cache.getOrNull<Int>(FakeInput(1)))
        assertEquals(99, cache.getOrNull<Int>(FakeInput(2))?.value)
        assertEquals(77, cache.getOrNull<Int>(FakeInput(3))?.value)
    }

    @Test
    fun `evictStale does not evict entry whose TTL was reset`() = runTest {
        val cache = OnHandCache(
            timeSource = testScheduler.timeSource,
            cacheLifecycleScope = backgroundScope,
        )
        cache.setWithTtl(FakeInput(1), 42, parse("100ms"))

        testScheduler.advanceTimeBy(80)
        // Reset TTL before expiry
        cache.setWithTtl(FakeInput(1), 99, parse("100ms"))

        testScheduler.advanceTimeBy(50)

        // Trigger evictStale
        cache.get<Int>(FakeInput(2))
        advanceUntilIdle()

        // Should still be alive — TTL was reset
        assertEquals(99, cache.getOrNull<Int>(FakeInput(1))?.value)
    }
}
