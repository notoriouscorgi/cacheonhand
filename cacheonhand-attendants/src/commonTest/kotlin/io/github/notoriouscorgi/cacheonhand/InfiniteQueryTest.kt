package io.github.notoriouscorgi.cacheonhand

import dev.mokkery.matcher.any
import dev.mokkery.spy
import dev.mokkery.verify
import dev.mokkery.verify.VerifyMode.Companion.not
import io.github.notoriouscorgi.cacheonhand.operations.CacheAndFetchState
import io.github.notoriouscorgi.cacheonhand.operations.FetchState
import io.github.notoriouscorgi.cacheonhand.operations.PageParam
import io.github.notoriouscorgi.cacheonhand.operations.PagedData
import io.github.notoriouscorgi.cacheonhand.operations.cachedDataState
import io.github.notoriouscorgi.cacheonhand.operations.infiniteQueryFactoryOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class InfiniteQueryTest {
    @BeforeTest
    fun before() {
        resetTestState()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Test that infinite query factory fetches a single next page`() =
        runTest(UnconfinedTestDispatcher()) {
            cache.clear()
            val infiniteInstance = testInfiniteQueryFactoryWithInput.create(null, backgroundScope)
            val onSuccess = spy({ _: PagedData<Int?, Int>? -> })
            val onError = spy({ _: Exception -> })

            assertNull(infiniteInstance.result.value.data)
            assertEquals(FetchState.IDLE, infiniteInstance.result.value.fetchState)

            infiniteInstance.fetchNextPage(FakePageInput(3), onSuccess, onError)
            runCurrent()

            verify { onSuccess(PagedData(1, 3)) }
            verify(not) { onError(any()) }
            assertEquals(FetchState.SUCCESS, infiniteInstance.result.value.fetchState)
            assertEquals(listOf(PagedData<Int?, Int>(1, 3)), infiniteInstance.result.value.data)
            assertEquals(
                listOf(PagedData<Int?, Int>(1, 3)),
                cache.get<List<PagedData<Int?, Int>>>(FakePageInput(3)).value,
            )
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Test that infinite query factory accumulates pages on subsequent next fetches`() =
        runTest(UnconfinedTestDispatcher()) {
            cache.clear()
            val infiniteInstance = testInfiniteQueryFactoryWithInput.create(null, backgroundScope)
            val onSuccess = spy({ _: PagedData<Int?, Int>? -> })
            val onError = spy({ _: Exception -> })

            infiniteInstance.fetchNextPage(FakePageInput(3), onSuccess, onError)
            advanceUntilIdle()
            infiniteInstance.fetchNextPage(FakePageInput(3), onSuccess, onError)
            advanceUntilIdle()
            infiniteInstance.fetchNextPage(FakePageInput(3), onSuccess, onError)
            advanceUntilIdle()

            val expected =
                listOf(
                    PagedData<Int?, Int>(1, 3),
                    PagedData<Int?, Int>(2, 6),
                    PagedData<Int?, Int>(3, 9),
                )
            assertEquals(expected, infiniteInstance.result.value.data)
            assertEquals(expected, cache.get<List<PagedData<Int?, Int>>>(FakePageInput(3)).value)
            assertEquals(FetchState.SUCCESS, infiniteInstance.result.value.fetchState)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Test that infinite query factory prepends previous pages`() =
        runTest(UnconfinedTestDispatcher()) {
            cache.clear()
            val infiniteInstance = testInfiniteQueryFactoryWithInput.create(null, backgroundScope)
            val onSuccess = spy({ _: PagedData<Int?, Int>? -> })
            val onError = spy({ _: Exception -> })

            infiniteInstance.fetchNextPage(FakePageInput(3), onSuccess, onError)
            advanceUntilIdle()
            infiniteInstance.fetchPreviousPage(FakePageInput(3), onSuccess, onError)
            advanceUntilIdle()

            val expected =
                listOf<PagedData<Int?, Int>>(
                    PagedData(0, 0),
                    PagedData(1, 3),
                )
            assertEquals(expected, infiniteInstance.result.value.data)
            assertEquals(expected, cache.get<List<PagedData<Int?, Int>>>(FakePageInput(3)).value)
            assertEquals(FetchState.SUCCESS, infiniteInstance.result.value.fetchState)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Test that infinite query factory handles null returned data`() =
        runTest(UnconfinedTestDispatcher()) {
            cache.clear()
            infiniteQueryReturnsNull = true
            val infiniteInstance = testInfiniteQueryFactoryWithInput.create(null, backgroundScope)
            val onSuccess = spy({ _: PagedData<Int?, Int>? -> })
            val onError = spy({ _: Exception -> })

            infiniteInstance.fetchNextPage(FakePageInput(3), onSuccess, onError)
            runCurrent()

            verify { onSuccess(PagedData(1, null)) }
            verify(not) { onError(any()) }
            assertEquals(listOf(PagedData<Int?, Int>(1, null)), infiniteInstance.result.value.data)
            assertEquals(
                listOf(PagedData<Int?, Int>(1, null)),
                cache.get<List<PagedData<Int?, Int>>>(FakePageInput(3)).value,
            )
            assertEquals(FetchState.SUCCESS, infiniteInstance.result.value.fetchState)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Test that infinite query factory no input accumulates next pages`() =
        runTest(UnconfinedTestDispatcher()) {
            cache.clear()
            val infiniteInstance = testInfiniteQueryFactoryWithNoInput.create(backgroundScope)
            val onSuccess = spy({ _: PagedData<Int?, Int>? -> })
            val onError = spy({ _: Exception -> })

            infiniteInstance.fetchNextPage(onSuccess, onError)
            advanceUntilIdle()
            infiniteInstance.fetchNextPage(onSuccess, onError)
            advanceUntilIdle()

            val expected =
                listOf(
                    PagedData<Int?, Int>(1, 10),
                    PagedData<Int?, Int>(2, 20),
                )
            assertEquals(expected, infiniteInstance.result.value.data)
            assertEquals(expected, cache.get<List<PagedData<Int?, Int>>>(FakePageCacheKey()).value)
            assertEquals(FetchState.SUCCESS, infiniteInstance.result.value.fetchState)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Test that infinite query factory no input prepends previous pages`() =
        runTest(UnconfinedTestDispatcher()) {
            cache.clear()
            val infiniteInstance = testInfiniteQueryFactoryWithNoInput.create(backgroundScope)
            val onSuccess = spy({ _: PagedData<Int?, Int>? -> })
            val onError = spy({ _: Exception -> })

            infiniteInstance.fetchNextPage(onSuccess, onError)
            advanceUntilIdle()
            infiniteInstance.fetchPreviousPage(onSuccess, onError)
            advanceUntilIdle()

            val expected =
                listOf(
                    PagedData<Int?, Int>(0, 0),
                    PagedData<Int?, Int>(1, 10),
                )
            assertEquals(expected, infiniteInstance.result.value.data)
            assertEquals(expected, cache.get<List<PagedData<Int?, Int>>>(FakePageCacheKey()).value)
            assertEquals(FetchState.SUCCESS, infiniteInstance.result.value.fetchState)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Test that infinite query stops fetching when getNextPageParam returns None`() =
        runTest(UnconfinedTestDispatcher()) {
            cache.clear()
            infiniteQueryMaxPage = 2
            val infiniteInstance = testInfiniteQueryFactoryWithInput.create(null, backgroundScope)
            val onSuccess = spy({ _: PagedData<Int?, Int>? -> })
            val onError = spy({ _: Exception -> })

            infiniteInstance.fetchNextPage(FakePageInput(3), onSuccess, onError)
            advanceUntilIdle()
            infiniteInstance.fetchNextPage(FakePageInput(3), onSuccess, onError)
            advanceUntilIdle()
            assertEquals(false, infiniteInstance.result.value.hasNextPage)

            val pagesBefore = infiniteInstance.result.value.data
            infiniteInstance.fetchNextPage(FakePageInput(3), onSuccess, onError)
            advanceUntilIdle()
            assertEquals(pagesBefore, infiniteInstance.result.value.data)
            assertEquals(
                2,
                infiniteInstance.result.value.data
                    ?.size,
            )
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Test that infinite query without getPreviousPageParam only paginates forward`() =
        runTest(UnconfinedTestDispatcher()) {
            cache.clear()
            val infiniteInstance = testInfiniteQueryForwardOnlyFactory.create(null, backgroundScope)
            val onSuccess = spy({ _: PagedData<Int?, Int>? -> })
            val onError = spy({ _: Exception -> })

            assertEquals(false, infiniteInstance.result.value.hasPreviousPage)

            infiniteInstance.fetchNextPage(FakePageInput(3), onSuccess, onError)
            assertEquals(listOf(PagedData<Int?, Int>(1, 3)), infiniteInstance.result.value.data)
            assertEquals(false, infiniteInstance.result.value.hasPreviousPage)

            infiniteInstance.fetchPreviousPage(FakePageInput(3), onSuccess, onError)
            assertEquals(listOf(PagedData<Int?, Int>(1, 3)), infiniteInstance.result.value.data)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Test that infinite query hasNextPage and hasPreviousPage reflect page param state`() =
        runTest(UnconfinedTestDispatcher()) {
            cache.clear()
            val infiniteInstance = testInfiniteQueryFactoryWithInput.create(null, backgroundScope)
            val onSuccess = spy({ _: PagedData<Int?, Int>? -> })
            val onError = spy({ _: Exception -> })

            assertEquals(true, infiniteInstance.result.value.hasNextPage)
            assertEquals(false, infiniteInstance.result.value.hasPreviousPage)

            infiniteInstance.fetchNextPage(FakePageInput(3), onSuccess, onError)
            advanceUntilIdle()
            assertEquals(true, infiniteInstance.result.value.hasNextPage)
            assertEquals(true, infiniteInstance.result.value.hasPreviousPage)

            infiniteInstance.fetchPreviousPage(FakePageInput(3), onSuccess, onError)
            advanceUntilIdle()
            assertEquals(true, infiniteInstance.result.value.hasNextPage)
            assertEquals(false, infiniteInstance.result.value.hasPreviousPage)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Test that infinite query sets ERROR state when fetchNextPage throws`() =
        runTest(UnconfinedTestDispatcher()) {
            cache.clear()
            val infiniteInstance = testInfiniteQueryFactoryWithInput.create(null, backgroundScope)
            val onSuccess = spy({ _: PagedData<Int?, Int>? -> })
            val onError = spy({ _: Exception -> })

            infiniteInstance.fetchNextPage(FakePageInput(3, isError = true), onSuccess, onError)
            advanceUntilIdle()

            verify(not) { onSuccess(any()) }
            verify { onError(any()) }
            assertEquals(FetchState.ERROR, infiniteInstance.result.value.fetchState)
            assertNotNull(infiniteInstance.result.value.error)
            assertEquals(
                "Boom",
                infiniteInstance.result.value.error
                    ?.message,
            )
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Test that infinite query recovers after error and can fetch next page`() =
        runTest(UnconfinedTestDispatcher()) {
            cache.clear()
            var shouldError = false
            val errorControlledFactory =
                infiniteQueryFactoryOf<FakePageInput, Int, Int, Exception>(
                    cache = cache,
                    initialPageParam = 1,
                    getNextPageParam = { pages -> PageParam.Value((pages.lastOrNull()?.page ?: 0) + 1) },
                    query = { input, page ->
                        if (shouldError) throw Exception("Boom")
                        (page ?: 1) * input.value
                    },
                )
            val infiniteInstance = errorControlledFactory.create(FakePageInput(3), backgroundScope)
            val onSuccess = spy({ _: PagedData<Int?, Int>? -> })
            val onError = spy({ _: Exception -> })

            // First fetch succeeds
            infiniteInstance.fetchNextPage(FakePageInput(3), onSuccess, onError)
            advanceUntilIdle()
            assertEquals(FetchState.SUCCESS, infiniteInstance.result.value.fetchState)

            // Second fetch fails — same key, controlled by flag
            shouldError = true
            infiniteInstance.fetchNextPage(FakePageInput(3), onSuccess, onError)
            advanceUntilIdle()
            assertEquals(FetchState.ERROR, infiniteInstance.result.value.fetchState)

            // Third fetch succeeds — should recover
            shouldError = false
            infiniteInstance.fetchNextPage(FakePageInput(3), onSuccess, onError)
            advanceUntilIdle()
            assertEquals(FetchState.SUCCESS, infiniteInstance.result.value.fetchState)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Test that fetchPage replaces an existing page in the list`() =
        runTest(UnconfinedTestDispatcher()) {
            cache.clear()
            val infiniteInstance = testInfiniteQueryFactoryWithInput.create(null, backgroundScope)
            val onSuccess = spy({ _: PagedData<Int?, Int>? -> })
            val onError = spy({ _: Exception -> })

            // Fetch pages 1 and 2
            infiniteInstance.fetchNextPage(FakePageInput(3), onSuccess, onError)
            advanceUntilIdle()
            infiniteInstance.fetchNextPage(FakePageInput(3), onSuccess, onError)
            advanceUntilIdle()

            assertEquals(
                listOf(PagedData<Int?, Int>(1, 3), PagedData<Int?, Int>(2, 6)),
                infiniteInstance.result.value.data,
            )

            // Re-fetch page 1 with different input (value=5 → page 1 data = 5*1 = 5)
            infiniteInstance.fetchPage(FakePageInput(5), 1, onSuccess, onError)
            advanceUntilIdle()

            // Page 1 should be replaced, page 2 unchanged
            assertEquals(
                listOf(PagedData<Int?, Int>(1, 5), PagedData<Int?, Int>(2, 6)),
                infiniteInstance.result.value.data,
            )
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Test that fetchPage appends if page does not exist`() =
        runTest(UnconfinedTestDispatcher()) {
            cache.clear()
            val infiniteInstance = testInfiniteQueryFactoryWithInput.create(null, backgroundScope)
            val onSuccess = spy({ _: PagedData<Int?, Int>? -> })
            val onError = spy({ _: Exception -> })

            // Fetch page 1
            infiniteInstance.fetchNextPage(FakePageInput(3), onSuccess, onError)
            advanceUntilIdle()

            // Fetch page 5 directly (doesn't exist in list)
            infiniteInstance.fetchPage(FakePageInput(3), 5, onSuccess, onError)
            advanceUntilIdle()

            assertEquals(
                listOf(PagedData<Int?, Int>(1, 3), PagedData<Int?, Int>(5, 15)),
                infiniteInstance.result.value.data,
            )
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Test that infinite query with initialFetchState LOADING starts in LOADING`() =
        runTest(UnconfinedTestDispatcher()) {
            cache.clear()
            val infiniteInstance =
                testInfiniteQueryFactoryWithInput.create(
                    null,
                    backgroundScope,
                    initialFetchState = FetchState.LOADING,
                )
            assertEquals(FetchState.LOADING, infiniteInstance.result.value.fetchState)
            assertEquals(CacheAndFetchState.NO_DATA_CACHED_AND_LOADING, infiniteInstance.result.value.cachedDataState)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Test that cachedDataState reflects correct states through infinite query lifecycle`() =
        runTest(UnconfinedTestDispatcher()) {
            cache.clear()
            val infiniteInstance = testInfiniteQueryFactoryWithInput.create(null, backgroundScope)
            val onSuccess = spy({ _: PagedData<Int?, Int>? -> })
            val onError = spy({ _: Exception -> })

            assertEquals(CacheAndFetchState.NO_DATA_CACHED_AND_IDLE, infiniteInstance.result.value.cachedDataState)

            infiniteInstance.fetchNextPage(FakePageInput(3), onSuccess, onError)
            advanceUntilIdle()
            assertEquals(CacheAndFetchState.DATA_CACHED_AND_SUCCESS, infiniteInstance.result.value.cachedDataState)
        }
}
