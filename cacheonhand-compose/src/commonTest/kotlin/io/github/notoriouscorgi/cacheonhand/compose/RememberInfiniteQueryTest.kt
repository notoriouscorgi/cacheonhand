package io.github.notoriouscorgi.cacheonhand.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.mokkery.matcher.any
import dev.mokkery.spy
import dev.mokkery.verify
import dev.mokkery.verify.VerifyMode.Companion.not
import io.github.notoriouscorgi.cacheonhand.operations.CacheAndFetchState
import io.github.notoriouscorgi.cacheonhand.operations.FetchState
import io.github.notoriouscorgi.cacheonhand.operations.cachedDataState
import io.github.notoriouscorgi.cacheonhand.operations.PagedData
import io.github.notoriouscorgi.composetesttools.renderHook
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RememberInfiniteQueryTest {
    private val rememberInfiniteQueryWithInput =
        composeInfiniteQueryFactoryOf(composeInfiniteQueryFactoryWithInput)
    private val rememberInfiniteQueryNoInput =
        composeInfiniteQueryFactoryOf(composeInfiniteQueryFactoryWithNoInput)

    @BeforeTest
    fun before() {
        resetComposeTestState()
    }

    @Test
    fun `composable infinite query starts in IDLE with hasNextPage true`() =
        runTest {
            composeCache.clear()
            val result by renderHook {
                rememberInfiniteQueryWithInput(FakePageInput(3), launchImmediately = false)
            }
            assertEquals(FetchState.IDLE, result?.fetchState)
            assertNull(result?.data)
            assertTrue(result?.hasNextPage == true)
            assertTrue(result?.hasPreviousPage == false)
        }

    @Test
    fun `composable infinite query fetches first page on mount`() =
        runTest {
            composeCache.clear()
            val result by renderHook { rememberInfiniteQueryWithInput(FakePageInput(3)) }

            actAndSettle()
            assertEquals(FetchState.SUCCESS, result?.fetchState)
            assertEquals(listOf(PagedData<Int?, Int>(1, 3)), result?.data)
        }

    @Test
    fun `composable infinite query exposes fetchNextPage for manual pagination`() =
        runTest {
            composeCache.clear()
            val result by renderHook {
                rememberInfiniteQueryWithInput(FakePageInput(3), launchImmediately = false)
            }

            // Manually fetch first page
            actAndSettleSuspend { result?.fetchNextPage?.invoke(FakePageInput(3), null, null) }
            assertEquals(listOf(PagedData<Int?, Int>(1, 3)), result?.data)

            // Fetch second page
            actAndSettleSuspend { result?.fetchNextPage?.invoke(FakePageInput(3), null, null) }
            assertEquals(
                listOf(PagedData<Int?, Int>(1, 3), PagedData<Int?, Int>(2, 6)),
                result?.data,
            )
        }

    @Test
    fun `composable infinite query exposes fetchPreviousPage`() =
        runTest {
            composeCache.clear()
            val result by renderHook {
                rememberInfiniteQueryWithInput(FakePageInput(3), launchImmediately = false)
            }

            actAndSettleSuspend { result?.fetchNextPage?.invoke(FakePageInput(3), null, null) }
            actAndSettleSuspend { result?.fetchPreviousPage?.invoke(FakePageInput(3), null, null) }

            assertEquals(
                listOf(PagedData<Int?, Int>(0, 0), PagedData<Int?, Int>(1, 3)),
                result?.data,
            )
        }

    @Test
    fun `composable infinite query with enabled false does not fetch`() =
        runTest {
            composeCache.clear()
            val result by renderHook {
                rememberInfiniteQueryWithInput(FakePageInput(3), enabled = false)
            }

            actAndSettle()
            assertEquals(FetchState.IDLE, result?.fetchState)
            assertNull(result?.data)
        }

    @Test
    fun `composable infinite query no input fetches first page on mount`() =
        runTest {
            composeCache.clear()
            val result by renderHook { rememberInfiniteQueryNoInput() }

            actAndSettle()
            assertEquals(FetchState.SUCCESS, result?.fetchState)
            assertEquals(listOf(PagedData<Int?, Int>(1, 10)), result?.data)
        }

    @Test
    fun `composable infinite query no input manual pagination`() =
        runTest {
            composeCache.clear()
            val result by renderHook {
                rememberInfiniteQueryNoInput(launchImmediately = false)
            }

            actAndSettleSuspend { result?.fetchNextPage?.invoke(null, null) }
            actAndSettleSuspend { result?.fetchNextPage?.invoke(null, null) }

            assertEquals(
                listOf(PagedData<Int?, Int>(1, 10), PagedData<Int?, Int>(2, 20)),
                result?.data,
            )
        }

    @Test
    fun `composable infinite query calls onSuccess callback`() =
        runTest {
            composeCache.clear()
            val onSuccess = spy({ _: PagedData<Int?, Int>? -> })
            val onError = spy({ _: Exception -> })
            val result by renderHook {
                rememberInfiniteQueryWithInput(
                    FakePageInput(3),
                    onSuccess = onSuccess,
                    onError = onError,
                )
            }

            actAndSettle()
            verify { onSuccess(PagedData<Int?, Int>(1, 3)) }
            verify(not) { onError(any()) }
            assertEquals(FetchState.SUCCESS, result?.fetchState)
        }

    @Test
    fun `composable infinite query calls onSuccess on each fetchNextPage`() =
        runTest {
            composeCache.clear()
            val onSuccess = spy({ _: PagedData<Int?, Int>? -> })
            val result by renderHook {
                rememberInfiniteQueryWithInput(FakePageInput(3), launchImmediately = false)
            }

            actAndSettleSuspend {
                result?.fetchNextPage?.invoke(FakePageInput(3), onSuccess, null)
            }
            actAndSettleSuspend {
                result?.fetchNextPage?.invoke(FakePageInput(3), onSuccess, null)
            }

            verify { onSuccess(PagedData<Int?, Int>(1, 3)) }
            verify { onSuccess(PagedData<Int?, Int>(2, 6)) }
        }

    @Test
    fun `composable infinite query calls onError callback`() =
        runTest {
            composeCache.clear()
            val onSuccess = spy({ _: PagedData<Int?, Int>? -> })
            val onError = spy({ _: Exception -> })
            val result by renderHook {
                rememberInfiniteQueryWithInput(
                    FakePageInput(3, isError = true),
                    onSuccess = onSuccess,
                    onError = onError,
                )
            }

            actAndSettle()
            verify(not) { onSuccess(any()) }
            verify { onError(any()) }
            assertEquals("Boom", result?.error?.message)
            assertEquals(FetchState.ERROR, result?.fetchState)
        }

    @Test
    fun `composable infinite query hasNextPage and hasPreviousPage update reactively`() =
        runTest {
            composeCache.clear()
            val result by renderHook {
                rememberInfiniteQueryWithInput(FakePageInput(3), launchImmediately = false)
            }

            // Initial: can fetch next, can't go back
            assertEquals(true, result?.hasNextPage)
            assertEquals(false, result?.hasPreviousPage)

            // Fetch page 1 — getPreviousPageParam returns Value(0) since 1-1=0 >= 0
            actAndSettleSuspend { result?.fetchNextPage?.invoke(FakePageInput(3), null, null) }
            assertEquals(true, result?.hasNextPage)
            assertEquals(true, result?.hasPreviousPage)

            // Fetch previous page (0) — getPreviousPageParam returns None since 0-1=-1 < 0
            actAndSettleSuspend { result?.fetchPreviousPage?.invoke(FakePageInput(3), null, null) }
            assertEquals(true, result?.hasNextPage)
            assertEquals(false, result?.hasPreviousPage)
        }

    @Test
    fun `composable infinite query fetchNextPage sets ERROR state on failure`() =
        runTest {
            composeCache.clear()
            val result by renderHook {
                rememberInfiniteQueryWithInput(FakePageInput(3, isError = true), launchImmediately = false)
            }

            actAndSettleSuspend { result?.fetchNextPage?.invoke(FakePageInput(3, isError = true), null, null) }

            assertEquals(FetchState.ERROR, result?.fetchState)
            assertNotNull(result?.error)
            assertEquals("Boom", result?.error?.message)
        }

    @Test
    fun `composable infinite query with starting cache key shows previous data`() =
        runTest {
            composeCache.clear()
            composeCache[FakePageInput(3)] = listOf(PagedData<Int?, Int>(1, 3))
            val result by renderHook {
                rememberInfiniteQueryWithInput(FakePageInput(3), launchImmediately = false)
            }

            actAndSettle()
            assertEquals(listOf(PagedData<Int?, Int>(1, 3)), result?.data)
        }

    @Test
    fun `composable infinite query refetches first page when input changes`() =
        runTest {
            composeCache.clear()
            var input by mutableStateOf(FakePageInput(3))
            val result by renderHook { rememberInfiniteQueryWithInput(input) }

            actAndSettle()
            // First input: page 1 with value 3*1 = 3
            assertEquals(listOf(PagedData<Int?, Int>(1, 3)), result?.data)

            input = FakePageInput(5)
            actAndSettle()
            // New input: page 1 with value 5*1 = 5
            assertEquals(listOf(PagedData<Int?, Int>(1, 5)), result?.data)
        }

    @Test
    fun `composable infinite query with null input does not fetch and fetches when input becomes non-null`() =
        runTest {
            composeCache.clear()
            var input by mutableStateOf<FakePageInput?>(null)
            val result by renderHook { rememberInfiniteQueryWithInput(input) }

            actAndSettle()
            assertEquals(FetchState.IDLE, result?.fetchState)
            assertNull(result?.data)

            input = FakePageInput(3)
            actAndSettle()
            assertEquals(FetchState.SUCCESS, result?.fetchState)
            assertEquals(listOf(PagedData<Int?, Int>(1, 3)), result?.data)
        }

    @Test
    fun `composable infinite query fetchPage replaces an existing page`() =
        runTest {
            composeCache.clear()
            val result by renderHook {
                rememberInfiniteQueryWithInput(FakePageInput(3), launchImmediately = false)
            }

            // Fetch pages 1 and 2
            actAndSettleSuspend { result?.fetchNextPage?.invoke(FakePageInput(3), null, null) }
            actAndSettleSuspend { result?.fetchNextPage?.invoke(FakePageInput(3), null, null) }
            assertEquals(
                listOf(PagedData<Int?, Int>(1, 3), PagedData<Int?, Int>(2, 6)),
                result?.data,
            )

            // Re-fetch page 1 with value=5 → data = 5*1 = 5
            actAndSettleSuspend { result?.fetchPage?.invoke(FakePageInput(5), 1, null, null) }
            assertEquals(
                listOf(PagedData<Int?, Int>(1, 5), PagedData<Int?, Int>(2, 6)),
                result?.data,
            )
        }

    @Test
    fun `composable infinite query starts in LOADING when launchImmediately is true`() =
        runTest {
            composeCache.clear()
            val result by renderHook { rememberInfiniteQueryWithInput(FakePageInput(3)) }
            assertEquals(FetchState.LOADING, result?.fetchState)
        }

    @Test
    fun `composable infinite query cachedDataState shows DATA_CACHED_AND_SUCCESS after fetch`() =
        runTest {
            composeCache.clear()
            val result by renderHook { rememberInfiniteQueryWithInput(FakePageInput(3)) }

            actAndSettle()
            assertEquals(CacheAndFetchState.DATA_CACHED_AND_SUCCESS, result?.cachedDataState)
        }
}
