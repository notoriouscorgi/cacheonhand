package io.github.notoriouscorgi.cacheonhand

import dev.mokkery.matcher.any
import dev.mokkery.spy
import dev.mokkery.verify
import dev.mokkery.verify.VerifyMode.Companion.not
import io.github.notoriouscorgi.cacheonhand.operations.CacheAndFetchState
import io.github.notoriouscorgi.cacheonhand.operations.FetchState
import io.github.notoriouscorgi.cacheonhand.operations.cachedDataState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FlowTest {
    @BeforeTest
    fun before() {
        resetTestState()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Test that flow factory with input performs functions on success`() =
        runTest {
            cache.clear()
            val flowInstance = testFlowFactoryWithInput.create(null, backgroundScope)
            val onSuccess = spy({ _: Int -> })
            val onError = spy({ _: Exception -> })

            assertNull(flowInstance.result.value.error)
            assertEquals(flowInstance.result.value.fetchState, FetchState.IDLE)

            flowInstance.launch(FakeFlowInput(3, false), onSuccess, onError)

            runCurrent()
            (3..60 step 3).forEach {
                verify { onSuccess(it) }
            }

            verify(not) {
                onError(Exception("Boom"))
            }
            assertEquals(flowInstance.result.value.data, 60)
            assertNull(flowInstance.result.value.error)
            assertEquals(flowInstance.result.value.fetchState, FetchState.SUCCESS)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Test that flow factory with input performs functions on error`() =
        runTest {
            cache.clear()
            val flowInstance = testFlowFactoryWithInput.create(null, backgroundScope)
            val onSuccess = spy({ _: Int -> })
            val onError = spy({ _: Exception -> })

            assertNull(flowInstance.result.value.error)
            assertEquals(flowInstance.result.value.fetchState, FetchState.IDLE)

            flowInstance.launch(FakeFlowInput(3, true), onSuccess, onError)

            runCurrent()
            (3..12 step 3).forEach {
                verify { onSuccess(it) }
            }

            verify {
                onError(any())
            }
            assertEquals(12, flowInstance.result.value.data)
            assertEquals(
                "Boom",
                flowInstance.result.value.error
                    ?.message,
            )
            assertEquals(flowInstance.result.value.fetchState, FetchState.ERROR)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Test that flow factory no input performs functions on success`() =
        runTest {
            cache.clear()
            val flowInstance = testFlowFactoryWithNoInput.create(backgroundScope)
            val onSuccess = spy({ _: Int -> })
            val onError = spy({ _: Exception -> })

            assertNull(flowInstance.result.value.error)
            assertEquals(flowInstance.result.value.fetchState, FetchState.IDLE)

            flowInstance.launch(onSuccess, onError)

            runCurrent()
            (3..60 step 3).forEach {
                verify { onSuccess(it) }
            }

            verify(not) {
                onError(Exception("Boom"))
            }
            assertEquals(flowInstance.result.value.data, 60)
            assertNull(flowInstance.result.value.error)
            assertEquals(flowInstance.result.value.fetchState, FetchState.SUCCESS)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Test that flow factory no input performs functions on error`() =
        runTest {
            cache.clear()
            flowError = true
            val flowInstance = testFlowFactoryWithNoInput.create(backgroundScope)
            val onSuccess = spy({ _: Int -> })
            val onError = spy({ _: Exception -> })

            assertNull(flowInstance.result.value.error)
            assertEquals(flowInstance.result.value.fetchState, FetchState.IDLE)

            flowInstance.launch(onSuccess, onError)

            runCurrent()
            (3..12 step 3).forEach {
                verify { onSuccess(it) }
            }

            verify {
                onError(any())
            }
            assertEquals(12, flowInstance.result.value.data)
            assertEquals(
                "Boom",
                flowInstance.result.value.error
                    ?.message,
            )
            assertEquals(flowInstance.result.value.fetchState, FetchState.ERROR)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Test that flow factory with starting cache key shows previous data`() =
        runTest {
            cache.clear()
            cache[FakeFlowInput(3, false)] = 99
            val flowInstance = testFlowFactoryWithInput.create(FakeFlowInput(3, false), backgroundScope)
            assertEquals(99, flowInstance.result.value.data)
            assertNull(flowInstance.result.value.error)
            assertEquals(FetchState.IDLE, flowInstance.result.value.fetchState)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Test that flow factory no input existing cache value comes through`() =
        runTest {
            cache.clear()
            cache[FakeFlowCacheKey()] = 99
            val flowInstance = testFlowFactoryWithNoInput.create(backgroundScope)
            assertEquals(99, flowInstance.result.value.data)
            assertNull(flowInstance.result.value.error)
            assertEquals(FetchState.IDLE, flowInstance.result.value.fetchState)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Test that flow data updates when cache changes externally`() =
        runTest {
            cache.clear()
            cache[FakeFlowInput(3, false)] = 10
            val flowInstance = testFlowFactoryWithInput.create(FakeFlowInput(3, false), backgroundScope)
            runCurrent()
            assertEquals(10, flowInstance.result.value.data)

            cache[FakeFlowInput(3, false)] = 20
            runCurrent()
            assertEquals(20, flowInstance.result.value.data)

            cache[FakeFlowInput(3, false)] = 42
            runCurrent()
            assertEquals(42, flowInstance.result.value.data)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Test that flow no input data updates when cache changes externally`() =
        runTest {
            cache.clear()
            cache[FakeFlowCacheKey()] = 10
            val flowInstance = testFlowFactoryWithNoInput.create(backgroundScope)
            runCurrent()
            assertEquals(10, flowInstance.result.value.data)

            cache[FakeFlowCacheKey()] = 20
            runCurrent()
            assertEquals(20, flowInstance.result.value.data)

            cache[FakeFlowCacheKey()] = 42
            runCurrent()
            assertEquals(42, flowInstance.result.value.data)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Test that flow factory writes emissions to cache`() =
        runTest {
            cache.clear()
            val flowInstance = testFlowFactoryWithInput.create(null, backgroundScope)
            val onSuccess = spy({ _: Int -> })
            val onError = spy({ _: Exception -> })

            flowInstance.launch(FakeFlowInput(3, false), onSuccess, onError)
            runCurrent()

            assertEquals(60, cache.get<Int>(FakeFlowInput(3, false)).value)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Test that flow factory no input writes emissions to cache`() =
        runTest {
            cache.clear()
            val flowInstance = testFlowFactoryWithNoInput.create(backgroundScope)
            val onSuccess = spy({ _: Int -> })
            val onError = spy({ _: Exception -> })

            flowInstance.launch(onSuccess, onError)
            runCurrent()

            assertEquals(60, cache.get<Int>(FakeFlowCacheKey()).value)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Test that flow with scan accumulates all emissions in cache`() =
        runTest {
            cache.clear()
            val flowInstance = testAccumulatingFlowFactory.create(null, backgroundScope)

            flowInstance.launch(FakeFlowInput(2))
            runCurrent()

            val expected = listOf(
                listOf(2),
                listOf(2, 4),
                listOf(2, 4, 6),
                listOf(2, 4, 6, 8),
                listOf(2, 4, 6, 8, 10),
            ).last()
            assertEquals(expected, flowInstance.result.value.data)
            assertEquals(expected, cache.get<List<Int>>(FakeFlowInput(2)).value)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Test that flow with scan is readable from cache by a separate subscriber`() =
        runTest {
            cache.clear()
            val flowInstance = testAccumulatingFlowFactory.create(null, backgroundScope)

            flowInstance.launch(FakeFlowInput(3))
            runCurrent()

            // A separate read from the cache sees the full accumulated list
            val cachedValue = cache.get<List<Int>>(FakeFlowInput(3)).value
            assertEquals(listOf(3, 6, 9, 12, 15), cachedValue)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Test that flow with initialFetchState LOADING starts in LOADING`() =
        runTest {
            cache.clear()
            val flowInstance = testFlowFactoryWithInput.create(
                FakeFlowInput(3),
                backgroundScope,
                initialFetchState = FetchState.LOADING,
            )
            assertEquals(FetchState.LOADING, flowInstance.result.value.fetchState)
            assertEquals(CacheAndFetchState.NO_DATA_CACHED_AND_LOADING, flowInstance.result.value.cachedDataState)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Test that cachedDataState reflects correct states through flow lifecycle`() =
        runTest {
            cache.clear()
            val flowInstance = testFlowFactoryWithInput.create(null, backgroundScope)

            assertEquals(CacheAndFetchState.NO_DATA_CACHED_AND_IDLE, flowInstance.result.value.cachedDataState)

            flowInstance.launch(FakeFlowInput(3))
            runCurrent()
            assertEquals(CacheAndFetchState.DATA_CACHED_AND_SUCCESS, flowInstance.result.value.cachedDataState)
        }
}
