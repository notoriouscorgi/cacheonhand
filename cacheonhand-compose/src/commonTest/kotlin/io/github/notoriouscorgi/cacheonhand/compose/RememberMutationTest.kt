package io.github.notoriouscorgi.cacheonhand.compose

import androidx.compose.runtime.getValue
import dev.mokkery.matcher.any
import dev.mokkery.spy
import dev.mokkery.verify
import dev.mokkery.verify.VerifyMode.Companion.not
import io.github.notoriouscorgi.cacheonhand.operations.FetchState
import io.github.notoriouscorgi.composetesttools.renderHook
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class RememberMutationTest {
    private val rememberMutationWithInput = composeMutationFactoryOf(composeMutationFactoryWithInput)
    private val rememberMutationNoInput = composeMutationFactoryOf(composeMutationFactoryWithNoInput)
    private val rememberFireAndForgetMutation = composeMutationFactoryOf(composeMutationFactoryWithInputNoOutput)

    @BeforeTest
    fun before() {
        resetComposeTestState()
    }

    @Test
    fun `composable mutation starts in IDLE state`() =
        runTest {
            composeCache.clear()
            val result by renderHook { rememberMutationWithInput() }
            assertEquals(FetchState.IDLE, result?.fetchState)
            assertNull(result?.data)
            assertNull(result?.error)
        }

    @Test
    fun `composable mutation with input sets SUCCESS state and data`() =
        runTest {
            composeCache.clear()
            val result by renderHook { rememberMutationWithInput() }

            actAndSettleSuspend { result?.mutate?.invoke(FakeMutationInput(3), null, null, null) }

            assertEquals(FetchState.SUCCESS, result?.fetchState)
            assertEquals(5, result?.data)
            assertNull(result?.error)
        }

    @Test
    fun `composable mutation with input sets ERROR state on failure`() =
        runTest {
            composeCache.clear()
            val result by renderHook { rememberMutationWithInput() }

            actAndSettleSuspend { result?.mutate?.invoke(FakeMutationInput(3, isError = true), null, null, null) }

            assertEquals(FetchState.ERROR, result?.fetchState)
            assertNotNull(result?.error)
            assertEquals("Boom", result?.error?.message)
        }

    @Test
    fun `composable mutation no input sets SUCCESS state and data`() =
        runTest {
            composeCache.clear()
            val result by renderHook { rememberMutationNoInput() }

            actAndSettleSuspend { result?.mutate?.invoke(null, null, null) }

            assertEquals(FetchState.SUCCESS, result?.fetchState)
            assertEquals(2, result?.data)
        }

    @Test
    fun `composable mutation no input sets ERROR state on failure`() =
        runTest {
            composeCache.clear()
            composeMutationError = true
            val result by renderHook { rememberMutationNoInput() }

            actAndSettleSuspend { result?.mutate?.invoke(null, null, null) }

            assertEquals(FetchState.ERROR, result?.fetchState)
            assertNotNull(result?.error)
        }

    @Test
    fun `composable fire and forget mutation sets SUCCESS state`() =
        runTest {
            composeCache.clear()
            val result by renderHook { rememberFireAndForgetMutation() }

            actAndSettleSuspend { result?.mutate?.invoke(FakeMutationInput(3), null, null, null) }

            assertEquals(FetchState.SUCCESS, result?.fetchState)
            assertNull(result?.error)
        }

    @Test
    fun `composable fire and forget mutation sets ERROR state on failure`() =
        runTest {
            composeCache.clear()
            val result by renderHook { rememberFireAndForgetMutation() }

            actAndSettleSuspend { result?.mutate?.invoke(FakeMutationInput(3, isError = true), null, null, null) }

            assertEquals(FetchState.ERROR, result?.fetchState)
            assertNotNull(result?.error)
        }

    @Test
    fun `composable mutation calls onSuccess callback`() =
        runTest {
            composeCache.clear()
            val onSuccess = spy({ _: Int -> })
            val onError = spy({ _: Exception -> })
            val result by renderHook { rememberMutationWithInput() }

            actAndSettleSuspend {
                result?.mutate?.invoke(FakeMutationInput(3), null, onSuccess, onError)
            }

            verify { onSuccess(5) }
            verify(not) { onError(any()) }
            assertEquals(FetchState.SUCCESS, result?.fetchState)
        }

    @Test
    fun `composable mutation calls onError callback`() =
        runTest {
            composeCache.clear()
            val onSuccess = spy({ _: Int -> })
            val onError = spy({ _: Exception -> })
            val result by renderHook { rememberMutationWithInput() }

            actAndSettleSuspend {
                result?.mutate?.invoke(FakeMutationInput(3, isError = true), null, onSuccess, onError)
            }

            verify(not) { onSuccess(any()) }
            verify { onError(any()) }
            assertEquals("Boom", result?.error?.message)
            assertEquals(FetchState.ERROR, result?.fetchState)
        }

    @Test
    fun `composable mutation with typed optimistic update writes to cache`() =
        runTest {
            composeCache.clear()
            composeCache[FakeInput(3, false)] = 7
            val result by renderHook { rememberMutationWithInput() }

            actAndSettleSuspend {
                result?.mutate?.invoke(
                    FakeMutationInput(3),
                    { _ ->
                        composeQueryFactoryWithInput.optimisticUpdater(FakeInput(3, false)) { current ->
                            (current ?: 0) + 92
                        }
                    },
                    null,
                    null,
                )
            }

            assertEquals(FetchState.SUCCESS, result?.fetchState)
            // 7 + 92 = 99
            assertEquals(99, composeCache.get<Int>(FakeInput(3, false)).value)
        }

    @Test
    fun `composable mutation onSuccess triggers factory refetch`() =
        runTest {
            composeCache.clear()
            val result by renderHook { rememberMutationWithInput() }

            // Subscribe a query so we can verify refetch propagates
            val queryResult by renderHook {
                composeQueryFactoryOf(composeQueryFactoryWithInput)(FakeInput(10), launchImmediately = false)
            }

            actAndSettleSuspend {
                result?.mutate?.invoke(
                    FakeMutationInput(3),
                    null,
                    { _ ->
                        // On mutation success, refetch a query
                        composeQueryFactoryWithInput.refetch(FakeInput(10))
                    },
                    null,
                )
            }

            // Mutation succeeded
            assertEquals(5, result?.data)
            // Refetch wrote to cache: 10 + 2 = 12
            assertEquals(12, composeCache.get<Int>(FakeInput(10)).value)
            // Query subscriber picks up the refetched value
            assertEquals(12, queryResult?.data)
        }

    @Test
    fun `composable mutation rolls back optimistic update on failure`() =
        runTest {
            composeCache.clear()
            composeCache[FakeInput(3, false)] = 7
            val result by renderHook { rememberMutationWithInput() }

            actAndSettleSuspend {
                result?.mutate?.invoke(
                    FakeMutationInput(3, isError = true),
                    { _ -> mapOf(FakeInput(3, false) to 99) },
                    null,
                    null,
                )
            }

            assertEquals(FetchState.ERROR, result?.fetchState)
            // Cache rolled back to original value
            assertEquals(7, composeCache.get<Int>(FakeInput(3, false)).value)
        }
}
