package io.github.notoriouscorgi.cacheonhand

import dev.mokkery.matcher.any
import dev.mokkery.spy
import dev.mokkery.verify
import dev.mokkery.verify.VerifyMode.Companion.not
import io.github.notoriouscorgi.cacheonhand.operations.CacheAndFetchState
import io.github.notoriouscorgi.cacheonhand.operations.FetchState
import io.github.notoriouscorgi.cacheonhand.operations.QueryFactoryWithInput
import io.github.notoriouscorgi.cacheonhand.operations.cachedDataState
import io.github.notoriouscorgi.cacheonhand.operations.queryFactoryOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.parse

class QueryTest {
    @BeforeTest
    fun before() {
        resetTestState()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Test that query factory performs functions on success`() =
        runTest {
            cache.clear()
            val queryInstance = testQueryFactoryWithInput.create(null, backgroundScope)
            val onSuccess = spy({ _: Int -> })
            val onError = spy({ _: Exception -> })

            assertNull(queryInstance.result.value.error)
            assertEquals(queryInstance.result.value.fetchState, FetchState.IDLE)

            queryInstance.fetch(FakeInput(3), onSuccess, onError)

            runCurrent()
            verify { onSuccess(5) }
            verify(not) {
                onError(Exception("Boom"))
            }
            assertEquals(cache.get<Int>(FakeInput(3)).value, 5)
            assertNull(queryInstance.result.value.error)
            assertEquals(5, queryInstance.result.value.data)
            assertEquals(queryInstance.result.value.fetchState, FetchState.SUCCESS)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Test that query factory performs functions on failure`() =
        runTest {
            cache.clear()
            val queryInstance = testQueryFactoryWithInput.create(null, backgroundScope)
            val onSuccess = spy({ _: Int -> })
            val onError = spy({ _: Exception -> })

            assertNull(queryInstance.result.value.error)
            assertEquals(queryInstance.result.value.fetchState, FetchState.IDLE)

            queryInstance.fetch(FakeInput(3, true), onSuccess, onError)

            runCurrent()
            verify(not) { onSuccess(5) }
            verify { onError(any()) }
            assertEquals(cache.get<Int>(FakeInput(3)).value, null)
            assertNotNull(queryInstance.result.value.error)
            assertNull(queryInstance.result.value.data)
            assertEquals(queryInstance.result.value.fetchState, FetchState.ERROR)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Test that query factory with key shows previous data and performs functions on failure`() =
        runTest {
            cache.clear()
            cache[FakeInput(3, true)] = 3
            val queryInstance = testQueryFactoryWithInput.create(FakeInput(3, true), backgroundScope)
            assertEquals(3, cache.get<Int>(FakeInput(3, true)).value)
            assertEquals(null, cache.get<Int>(FakeInput(3, false)).value)
            val onSuccess = spy({ _: Int -> })
            val onError = spy({ _: Exception -> })

            assertEquals(3, queryInstance.result.value.data)
            assertNull(queryInstance.result.value.error)
            assertEquals(queryInstance.result.value.fetchState, FetchState.IDLE)

            queryInstance.fetch(FakeInput(3, true), onSuccess, onError)

            runCurrent()
            verify(not) { onSuccess(5) }
            verify { onError(any()) }
            assertEquals(3, cache.get<Int>(FakeInput(3, true)).value)
            assertNotNull(queryInstance.result.value.error)
            assertEquals(3, queryInstance.result.value.data)
            assertEquals(queryInstance.result.value.fetchState, FetchState.ERROR)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Test that query factory no input performs functions on success`() =
        runTest {
            cache.clear()
            val queryInstance = testQueryFactoryWithNoInput.create(backgroundScope)
            val onSuccess = spy({ _: Int -> })
            val onError = spy({ _: Exception -> })

            assertNull(queryInstance.result.value.error)
            assertEquals(queryInstance.result.value.fetchState, FetchState.IDLE)

            queryInstance.fetch(onSuccess, onError)

            runCurrent()
            verify { onSuccess(2) }
            verify(not) {
                onError(Exception("Boom"))
            }
            assertNull(queryInstance.result.value.error)
            assertEquals(2, queryInstance.result.value.data)
            assertEquals(cache.get<Int>(StringCacheKey()).value, 2)
            assertEquals(queryInstance.result.value.fetchState, FetchState.SUCCESS)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Test that query factory no input performs functions on failure`() =
        runTest {
            cache.clear()
            isError = true
            val queryInstance = testQueryFactoryWithNoInput.create(backgroundScope)
            val onSuccess = spy({ _: Int -> })
            val onError = spy({ _: Exception -> })

            assertNull(queryInstance.result.value.error)
            assertEquals(queryInstance.result.value.fetchState, FetchState.IDLE)

            queryInstance.fetch(onSuccess, onError)

            runCurrent()
            verify(not) { onSuccess(5) }
            verify { onError(any()) }
            assertNotNull(queryInstance.result.value.error)
            assertNull(queryInstance.result.value.data)
            assertEquals(cache.get<Int>(StringCacheKey()).value, null)
            assertEquals(queryInstance.result.value.fetchState, FetchState.ERROR)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Test that existing cache value comes through`() =
        runTest {
            cache.clear()
            cache[FakeInput(3, false)] = 7
            val queryInstance = testQueryFactoryWithInput.create(null, backgroundScope)
            assertEquals(cache.get<Int>(FakeInput(3, false)).value, 7)

            val onSuccess = spy({ _: Int -> })
            val onError = spy({ _: Exception -> })

            assertNull(queryInstance.result.value.error)
            assertEquals(queryInstance.result.value.fetchState, FetchState.IDLE)

            queryInstance.fetch(FakeInput(3), onSuccess, onError)

            runCurrent()
            verify { onSuccess(5) }
            verify(not) {
                onError(Exception("Boom"))
            }
            assertEquals(cache.get<Int>(FakeInput(3)).value, 5)
            assertNull(queryInstance.result.value.error)
            assertEquals(5, queryInstance.result.value.data)
            assertEquals(queryInstance.result.value.fetchState, FetchState.SUCCESS)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Test that query data updates when cache changes externally`() =
        runTest {
            cache.clear()
            cache[FakeInput(3, false)] = 10
            val queryInstance = testQueryFactoryWithInput.create(FakeInput(3, false), backgroundScope)
            runCurrent()
            assertEquals(10, queryInstance.result.value.data)

            cache[FakeInput(3, false)] = 20
            runCurrent()
            assertEquals(20, queryInstance.result.value.data)

            cache[FakeInput(3, false)] = 42
            runCurrent()
            assertEquals(42, queryInstance.result.value.data)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Test that factory refetch updates cache and active subscribers see the change`() =
        runTest {
            cache.clear()
            val queryInstance = testQueryFactoryWithInput.create(FakeInput(3, false), backgroundScope)
            runCurrent()
            assertNull(queryInstance.result.value.data)

            testQueryFactoryWithInput.refetch(FakeInput(3, false))
            runCurrent()
            assertEquals(5, queryInstance.result.value.data)

            testQueryFactoryWithInput.refetch(FakeInput(10, false))
            runCurrent()
            assertEquals(5, queryInstance.result.value.data)
            assertEquals(12, cache.get<Int>(FakeInput(10, false)).value)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Test that factory refetch with no input updates cache and subscribers see the change`() =
        runTest {
            cache.clear()
            val queryInstance = testQueryFactoryWithNoInput.create(backgroundScope)
            runCurrent()
            assertNull(queryInstance.result.value.data)

            testQueryFactoryWithNoInput.refetch()
            runCurrent()
            assertEquals(2, queryInstance.result.value.data)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Test that factory refetch updates multiple active subscribers`() =
        runTest {
            cache.clear()
            val queryInstance1 = testQueryFactoryWithInput.create(FakeInput(3, false), backgroundScope)
            val queryInstance2 = testQueryFactoryWithInput.create(FakeInput(3, false), backgroundScope)
            runCurrent()
            assertNull(queryInstance1.result.value.data)
            assertNull(queryInstance2.result.value.data)

            testQueryFactoryWithInput.refetch(FakeInput(3, false))
            runCurrent()
            assertEquals(5, queryInstance1.result.value.data)
            assertEquals(5, queryInstance2.result.value.data)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Test that query switches active key when fetching different input`() =
        runTest {
            cache.clear()
            val queryInstance = testQueryFactoryWithInput.create(FakeInput(3, false), backgroundScope)

            // Fetch with input 3
            queryInstance.fetch(FakeInput(3))
            runCurrent()
            assertEquals(5, queryInstance.result.value.data)

            // Fetch with input 10 — should switch observed key
            queryInstance.fetch(FakeInput(10))
            runCurrent()
            assertEquals(12, queryInstance.result.value.data)

            // External update to new key propagates
            cache[FakeInput(10)] = 99
            runCurrent()
            assertEquals(99, queryInstance.result.value.data)

            // External update to old key does NOT propagate (no longer observed)
            cache[FakeInput(3)] = 42
            runCurrent()
            assertEquals(99, queryInstance.result.value.data)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Test that cache entry with TTL is evicted after expiry`() =
        runTest {
            val ttlCache = OnHandCache(timeSource = testScheduler.timeSource)

            ttlCache.setWithTtl(FakeInput(3), 42, parse("100ms"))

            // Value is immediately readable
            assertEquals(42, ttlCache.getOrNull<Int>(FakeInput(3))?.value)

            // Advance past TTL
            testScheduler.advanceTimeBy(150)

            // Value should be evicted on next read
            assertNull(ttlCache.getOrNull<Int>(FakeInput(3)))
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Test that cache entry TTL resets on write`() =
        runTest {
            val ttlCache = OnHandCache(timeSource = testScheduler.timeSource)

            ttlCache.setWithTtl(FakeInput(3), 42, parse("100ms"))

            // Advance 80ms — not expired yet
            testScheduler.advanceTimeBy(80)
            assertEquals(42, ttlCache.getOrNull<Int>(FakeInput(3))?.value)

            // Write again — resets TTL
            ttlCache.setWithTtl(FakeInput(3), 99, parse("100ms"))

            // Advance another 80ms (160ms total, but only 80ms since last write)
            testScheduler.advanceTimeBy(80)
            assertEquals(99, ttlCache.getOrNull<Int>(FakeInput(3))?.value)

            // Advance past TTL from last write
            testScheduler.advanceTimeBy(50)
            assertNull(ttlCache.getOrNull<Int>(FakeInput(3)))
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Test that factory with TTL evicts cache entries after expiry`() =
        runTest {
            val ttlCache = OnHandCache(timeSource = testScheduler.timeSource)

            val ttlFactory =
                queryFactoryOf<FakeInput, Int, Exception>(
                    cache = ttlCache,
                    ttl = parse("100ms"),
                ) { input -> input.value + 2 }

            val queryInstance = ttlFactory.create(FakeInput(3), backgroundScope)
            queryInstance.fetch(FakeInput(3))
            runCurrent()
            assertEquals(5, queryInstance.result.value.data)

            // Advance past TTL
            testScheduler.advanceTimeBy(150)

            // Cache entry should be evicted
            assertNull(ttlCache.getOrNull<Int>(FakeInput(3)))
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Test that query with initialFetchState LOADING starts in LOADING`() =
        runTest {
            cache.clear()
            val queryInstance =
                testQueryFactoryWithInput.create(
                    FakeInput(3),
                    backgroundScope,
                    initialFetchState = FetchState.LOADING,
                )
            assertEquals(FetchState.LOADING, queryInstance.result.value.fetchState)
            assertEquals(CacheAndFetchState.NO_DATA_CACHED_AND_LOADING, queryInstance.result.value.cachedDataState)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Test that cachedDataState reflects correct states through query lifecycle`() =
        runTest {
            cache.clear()
            val queryInstance = testQueryFactoryWithInput.create(null, backgroundScope)

            // Initial: no data, idle
            assertEquals(CacheAndFetchState.NO_DATA_CACHED_AND_IDLE, queryInstance.result.value.cachedDataState)

            // After fetch
            queryInstance.fetch(FakeInput(3))
            runCurrent()
            assertEquals(CacheAndFetchState.DATA_CACHED_AND_SUCCESS, queryInstance.result.value.cachedDataState)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Test that cachedDataState shows DATA_CACHED_AND_IDLE when starting with cached key`() =
        runTest {
            cache.clear()
            cache[FakeInput(3)] = 42
            val queryInstance = testQueryFactoryWithInput.create(FakeInput(3), backgroundScope)
            assertEquals(CacheAndFetchState.DATA_CACHED_AND_IDLE, queryInstance.result.value.cachedDataState)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Test that cachedDataState shows DATA_CACHED_AND_ERROR when fetch fails with existing data`() =
        runTest {
            cache.clear()
            cache[FakeInput(3, true)] = 42
            val queryInstance = testQueryFactoryWithInput.create(FakeInput(3, true), backgroundScope)
            assertEquals(CacheAndFetchState.DATA_CACHED_AND_IDLE, queryInstance.result.value.cachedDataState)

            queryInstance.fetch(FakeInput(3, true))
            runCurrent()
            assertEquals(CacheAndFetchState.DATA_CACHED_AND_ERROR, queryInstance.result.value.cachedDataState)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Test that cachedDataState shows NO_DATA_CACHED_AND_ERROR when first fetch fails`() =
        runTest {
            cache.clear()
            val queryInstance = testQueryFactoryWithInput.create(null, backgroundScope)

            queryInstance.fetch(FakeInput(3, true))
            runCurrent()
            assertEquals(CacheAndFetchState.NO_DATA_CACHED_AND_ERROR, queryInstance.result.value.cachedDataState)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Test that cachedDataState shows DATA_CACHED_AND_LOADING during refetch with existing data`() =
        runTest {
            cache.clear()
            val gate = CompletableDeferred<Unit>()
            var shouldSuspend = false
            val slowFactory =
                QueryFactoryWithInput<FakeInput, Int, Exception>(
                    cache = cache,
                    dispatcher = Dispatchers.Unconfined,
                ) { input ->
                    if (shouldSuspend) gate.await()
                    input.value + 2
                }

            val queryInstance = slowFactory.create(FakeInput(3), backgroundScope)

            // First fetch completes immediately
            queryInstance.fetch(FakeInput(3))
            runCurrent()
            assertEquals(CacheAndFetchState.DATA_CACHED_AND_SUCCESS, queryInstance.result.value.cachedDataState)

            // Second fetch suspends on gate — state should be LOADING with data still present
            shouldSuspend = true
            launch { queryInstance.fetch(FakeInput(3)) }
            runCurrent()
            assertEquals(CacheAndFetchState.DATA_CACHED_AND_LOADING, queryInstance.result.value.cachedDataState)

            // Release the gate so the test can clean up
            gate.complete(Unit)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Test that cachedDataState shows NO_DATA_CACHED_AND_SUCCESS when fetch returns null`() =
        runTest {
            cache.clear()
            val nullFactory =
                queryFactoryOf<FakeInput, Int?, Exception>(
                    cache = cache,
                ) { _ -> null }

            val queryInstance = nullFactory.create(null, backgroundScope)
            queryInstance.fetch(FakeInput(3))
            runCurrent()
            assertEquals(CacheAndFetchState.NO_DATA_CACHED_AND_SUCCESS, queryInstance.result.value.cachedDataState)
        }
}
