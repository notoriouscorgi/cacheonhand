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
import io.github.notoriouscorgi.cacheonhand.compose.renderHook
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.parse

@OptIn(ExperimentalCoroutinesApi::class)
class RememberQueryTest {
    private val rememberQueryWithInput = composeQueryFactoryOf(composeQueryFactoryWithInput)
    private val rememberQueryNoInput = composeQueryFactoryOf(composeQueryFactoryWithNoInput)

    @BeforeTest
    fun before() {
        resetComposeTestState()
    }

    @Test
    fun `composable query starts in IDLE state when not launched`() =
        runTest {
            composeCache.clear()
            val result by renderHook { rememberQueryWithInput(FakeInput(3), launchImmediately = false) }
            assertEquals(FetchState.IDLE, result?.fetchState)
            assertNull(result?.error)
        }

    @Test
    fun `composable query with launchImmediately fetches on mount`() =
        runTest {
            composeCache.clear()
            val result by renderHook { rememberQueryWithInput(FakeInput(3), launchImmediately = true) }

            actAndSettle()
            assertEquals(FetchState.SUCCESS, result?.fetchState)
            assertEquals(5, result?.data)
            assertNull(result?.error)
        }

    @Test
    fun `composable query with launchImmediately false does not fetch on mount`() =
        runTest {
            composeCache.clear()
            val result by renderHook { rememberQueryWithInput(FakeInput(3), launchImmediately = false) }

            actAndSettle()
            assertEquals(FetchState.IDLE, result?.fetchState)
            assertNull(result?.data)
        }

    @Test
    fun `composable query with enabled false does not fetch`() =
        runTest {
            composeCache.clear()
            val result by renderHook { rememberQueryWithInput(FakeInput(3), enabled = false) }

            actAndSettle()
            assertEquals(FetchState.IDLE, result?.fetchState)
            assertNull(result?.data)
        }

    @Test
    fun `composable query sets ERROR state on failure`() =
        runTest {
            composeCache.clear()
            val result by renderHook { rememberQueryWithInput(FakeInput(3, isError = true)) }

            actAndSettle()
            assertEquals(FetchState.ERROR, result?.fetchState)
            assertNotNull(result?.error)
            assertEquals("Boom", result?.error?.message)
        }

    @Test
    fun `composable query no input fetches on mount`() =
        runTest {
            composeCache.clear()
            val result by renderHook { rememberQueryNoInput() }

            actAndSettle()
            assertEquals(FetchState.SUCCESS, result?.fetchState)
            assertEquals(2, result?.data)
        }

    @Test
    fun `composable query no input sets ERROR state on failure`() =
        runTest {
            composeCache.clear()
            composeIsError = true
            val result by renderHook { rememberQueryNoInput() }

            actAndSettle()
            assertEquals(FetchState.ERROR, result?.fetchState)
            assertNotNull(result?.error)
        }

    @Test
    fun `composable query exposes manual refetch function`() =
        runTest {
            composeCache.clear()
            val result by renderHook { rememberQueryWithInput(FakeInput(3), launchImmediately = false) }

            actAndSettle()
            assertNull(result?.data)

            actAndSettle { result?.query?.invoke(FakeInput(3)) }
            assertEquals(5, result?.data)
        }

    @Test
    fun `composable query calls onSuccess callback`() =
        runTest {
            composeCache.clear()
            val onSuccess = spy({ _: Int -> })
            val onError = spy({ _: Exception -> })
            val result by renderHook {
                rememberQueryWithInput(
                    FakeInput(3),
                    onSuccess = onSuccess,
                    onError = onError,
                )
            }

            actAndSettle()
            verify { onSuccess(5) }
            verify(not) { onError(any()) }
            assertEquals(5, result?.data)
        }

    @Test
    fun `composable query calls onError callback`() =
        runTest {
            composeCache.clear()
            val onSuccess = spy({ _: Int -> })
            val onError = spy({ _: Exception -> })
            val result by renderHook {
                rememberQueryWithInput(
                    FakeInput(3, isError = true),
                    onSuccess = onSuccess,
                    onError = onError,
                )
            }

            actAndSettle()
            verify(not) { onSuccess(any()) }
            verify { onError(any()) }
            assertNotNull(result?.error)
        }

    @Test
    fun `composable query with starting cache key shows previous data`() =
        runTest {
            composeCache.clear()
            composeCache[FakeInput(3)] = 99
            val result by renderHook { rememberQueryWithInput(FakeInput(3), launchImmediately = false) }

            actAndSettle()
            assertEquals(99, result?.data)
        }

    @Test
    fun `composable query data updates when cache changes externally`() =
        runTest {
            composeCache.clear()
            composeCache[FakeInput(3)] = 10
            val result by renderHook { rememberQueryWithInput(FakeInput(3), launchImmediately = false) }

            actAndSettle()
            assertEquals(10, result?.data)

            composeCache[FakeInput(3)] = 42
            actAndSettle()
            assertEquals(42, result?.data)
        }

    @Test
    fun `composable query refetches when input changes`() =
        runTest {
            composeCache.clear()
            var input by mutableStateOf(FakeInput(3))
            val result by renderHook { rememberQueryWithInput(input) }

            actAndSettle()
            assertEquals(5, result?.data) // 3 + 2

            input = FakeInput(10)
            actAndSettle()
            assertEquals(12, result?.data) // 10 + 2
        }

    @Test
    fun `composable query with null input does not fetch and fetches when input becomes non-null`() =
        runTest {
            composeCache.clear()
            var input by mutableStateOf<FakeInput?>(null)
            val result by renderHook { rememberQueryWithInput(input) }

            actAndSettle()
            assertEquals(FetchState.IDLE, result?.fetchState)
            assertNull(result?.data)

            input = FakeInput(3)
            actAndSettle()
            assertEquals(FetchState.SUCCESS, result?.fetchState)
            assertEquals(5, result?.data)
        }

    @Test
    fun `composable query with refetchInterval refetches periodically`() =
        runTest {
            composeCache.clear()
            val rememberRefetchQuery = composeQueryFactoryOf(composeRefetchQueryFactory)

            val result by renderHook {
                rememberRefetchQuery(
                    FakeInput(3),
                    refetchInterval = parse("100ms"),
                )
            }

            actAndSettle()
            val firstValue = result?.data
            assertNotNull(firstValue)

            // Advance past one interval
            testScheduler.advanceTimeBy(150)
            actAndSettle()
            val secondValue = result?.data
            assertNotNull(secondValue)
            assertTrue(secondValue != firstValue, "Expected data to change after refetch interval")
        }

    @Test
    fun `composable query starts in LOADING when launchImmediately is true`() =
        runTest {
            composeCache.clear()
            val result by renderHook { rememberQueryWithInput(FakeInput(3)) }
            // Before any coroutine advances, state should be LOADING (not IDLE)
            assertEquals(FetchState.LOADING, result?.fetchState)
        }

    @Test
    fun `composable query cachedDataState reflects lifecycle`() =
        runTest {
            composeCache.clear()
            val result by renderHook { rememberQueryWithInput(FakeInput(3), launchImmediately = false) }
            assertEquals(CacheAndFetchState.NO_DATA_CACHED_AND_IDLE, result?.cachedDataState)

            actAndSettle()
            assertEquals(CacheAndFetchState.NO_DATA_CACHED_AND_IDLE, result?.cachedDataState)
        }

    @Test
    fun `composable query cachedDataState shows DATA_CACHED_AND_SUCCESS after fetch`() =
        runTest {
            composeCache.clear()
            val result by renderHook { rememberQueryWithInput(FakeInput(3)) }

            actAndSettle()
            assertEquals(CacheAndFetchState.DATA_CACHED_AND_SUCCESS, result?.cachedDataState)
        }

    @Test
    fun `composable query cachedDataState shows DATA_CACHED_AND_IDLE with starting cache key`() =
        runTest {
            composeCache.clear()
            composeCache[FakeInput(3)] = 42
            val result by renderHook { rememberQueryWithInput(FakeInput(3), launchImmediately = false) }

            actAndSettle()
            assertEquals(CacheAndFetchState.DATA_CACHED_AND_IDLE, result?.cachedDataState)
        }
}
