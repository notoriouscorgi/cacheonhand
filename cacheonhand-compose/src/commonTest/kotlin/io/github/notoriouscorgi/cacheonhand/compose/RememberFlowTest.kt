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

@OptIn(ExperimentalCoroutinesApi::class)
class RememberFlowTest {
    private val rememberFlowWithInput = composeFlowFactoryOf(composeFlowFactoryWithInput)
    private val rememberFlowNoInput = composeFlowFactoryOf(composeFlowFactoryWithNoInput)

    @BeforeTest
    fun before() {
        resetComposeTestState()
    }

    @Test
    fun `composable flow starts in IDLE when not launched`() =
        runTest {
            composeCache.clear()
            val result by renderHook { rememberFlowWithInput(FakeFlowInput(3), launchImmediately = false) }
            assertEquals(FetchState.IDLE, result?.fetchState)
            assertNull(result?.data)
        }

    @Test
    fun `composable flow with launchImmediately collects on mount`() =
        runTest {
            composeCache.clear()
            val result by renderHook { rememberFlowWithInput(FakeFlowInput(3)) }

            actAndSettle()
            assertEquals(FetchState.SUCCESS, result?.fetchState)
            assertEquals(15, result?.data)
            assertNull(result?.error)
        }

    @Test
    fun `composable flow sets ERROR state on mid-stream failure`() =
        runTest {
            composeCache.clear()
            val result by renderHook { rememberFlowWithInput(FakeFlowInput(3, isError = true)) }

            actAndSettle()
            assertEquals(FetchState.ERROR, result?.fetchState)
            assertNotNull(result?.error)
            assertEquals("Boom", result?.error?.message)
            assertEquals(6, result?.data)
        }

    @Test
    fun `composable flow with enabled false does not launch`() =
        runTest {
            composeCache.clear()
            val result by renderHook { rememberFlowWithInput(FakeFlowInput(3), enabled = false) }

            actAndSettle()
            assertEquals(FetchState.IDLE, result?.fetchState)
            assertNull(result?.data)
        }

    @Test
    fun `composable flow no input collects on mount`() =
        runTest {
            composeCache.clear()
            val result by renderHook { rememberFlowNoInput() }

            actAndSettle()
            assertEquals(FetchState.SUCCESS, result?.fetchState)
            assertEquals(15, result?.data)
        }

    @Test
    fun `composable flow no input sets ERROR state on failure`() =
        runTest {
            composeCache.clear()
            composeFlowError = true
            val result by renderHook { rememberFlowNoInput() }

            actAndSettle()
            assertEquals(FetchState.ERROR, result?.fetchState)
            assertNotNull(result?.error)
        }

    @Test
    fun `composable flow calls onEachSuccess callback`() =
        runTest {
            composeCache.clear()
            val onEachSuccess = spy({ _: Int -> })
            val onError = spy({ _: Exception -> })
            val result by renderHook {
                rememberFlowWithInput(
                    FakeFlowInput(3),
                    onEachSuccess = onEachSuccess,
                    onError = onError,
                )
            }

            actAndSettle()
            (3..15 step 3).forEach { verify { onEachSuccess(it) } }
            verify(not) { onError(any()) }
            assertEquals(FetchState.SUCCESS, result?.fetchState)
        }

    @Test
    fun `composable flow calls onError callback`() =
        runTest {
            composeCache.clear()
            val onEachSuccess = spy({ _: Int -> })
            val onError = spy({ _: Exception -> })
            val result by renderHook {
                rememberFlowWithInput(
                    FakeFlowInput(3, isError = true),
                    onEachSuccess = onEachSuccess,
                    onError = onError,
                )
            }

            actAndSettle()
            // First 2 emissions succeed before error at i==3
            verify { onEachSuccess(3) }
            verify { onEachSuccess(6) }
            verify { onError(any()) }
            assertEquals("Boom", result?.error?.message)
            assertEquals(FetchState.ERROR, result?.fetchState)
        }

    @Test
    fun `composable flow with starting cache key shows previous data`() =
        runTest {
            composeCache.clear()
            composeCache[FakeFlowInput(3)] = 99
            val result by renderHook {
                rememberFlowWithInput(FakeFlowInput(3), launchImmediately = false)
            }

            actAndSettle()
            assertEquals(99, result?.data)
            assertEquals(FetchState.IDLE, result?.fetchState)
        }

    @Test
    fun `composable flow data updates when cache changes externally`() =
        runTest {
            composeCache.clear()
            composeCache[FakeFlowInput(3)] = 10
            val result by renderHook {
                rememberFlowWithInput(FakeFlowInput(3), launchImmediately = false)
            }

            actAndSettle()
            assertEquals(10, result?.data)

            composeCache[FakeFlowInput(3)] = 42
            actAndSettle()
            assertEquals(42, result?.data)
        }

    @Test
    fun `composable flow writes emissions to cache`() =
        runTest {
            composeCache.clear()
            val result by renderHook { rememberFlowWithInput(FakeFlowInput(3)) }

            actAndSettle()
            assertEquals(15, composeCache.get<Int>(FakeFlowInput(3)).value)
        }

    @Test
    fun `composable flow relaunches when input changes`() =
        runTest {
            composeCache.clear()
            var input by mutableStateOf(FakeFlowInput(3))
            val result by renderHook { rememberFlowWithInput(input) }

            actAndSettle()
            // Flow with value=3 emits 3,6,9,12,15 — final is 15
            assertEquals(15, result?.data)

            input = FakeFlowInput(5)
            actAndSettle()
            // Flow with value=5 emits 5,10,15,20,25 — final is 25
            assertEquals(25, result?.data)
        }

    @Test
    fun `composable flow with null input does not launch and launches when input becomes non-null`() =
        runTest {
            composeCache.clear()
            var input by mutableStateOf<FakeFlowInput?>(null)
            val result by renderHook { rememberFlowWithInput(input) }

            actAndSettle()
            assertEquals(FetchState.IDLE, result?.fetchState)
            assertNull(result?.data)

            input = FakeFlowInput(3)
            actAndSettle()
            assertEquals(FetchState.SUCCESS, result?.fetchState)
            assertEquals(15, result?.data)
        }

    @Test
    fun `composable flow starts in LOADING when launchImmediately is true`() =
        runTest {
            composeCache.clear()
            val result by renderHook { rememberFlowWithInput(FakeFlowInput(3)) }
            assertEquals(FetchState.LOADING, result?.fetchState)
        }

    @Test
    fun `composable flow cachedDataState shows DATA_CACHED_AND_SUCCESS after collection`() =
        runTest {
            composeCache.clear()
            val result by renderHook { rememberFlowWithInput(FakeFlowInput(3)) }

            actAndSettle()
            assertEquals(CacheAndFetchState.DATA_CACHED_AND_SUCCESS, result?.cachedDataState)
        }
}
