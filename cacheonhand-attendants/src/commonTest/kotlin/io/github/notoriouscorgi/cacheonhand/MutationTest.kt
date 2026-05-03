package io.github.notoriouscorgi.cacheonhand

import dev.mokkery.matcher.any
import dev.mokkery.resetCalls
import dev.mokkery.spy
import dev.mokkery.verify
import dev.mokkery.verify.VerifyMode.Companion.not
import dev.mokkery.verifySuspend
import io.github.notoriouscorgi.cacheonhand.CacheableInput.MutationInput
import io.github.notoriouscorgi.cacheonhand.operations.FetchState
import io.github.notoriouscorgi.cacheonhand.operations.PagedData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MutationTest {
    @BeforeTest
    fun before() {
        resetTestState()
        resetCalls(dependentFunctionMock)
        resetCalls(dependentNoArgFunctionMock)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Test that mutation factory performs functions on success`() =
        runTest {
            cache.clear()
            val mutationInstance = testMutationFactoryWithInput.create()
            val onSuccess = spy({ _: Int -> })
            val onError = spy({ _: Exception -> })
            val optimisticUpdate: ((input: MutationInput) -> Map<CacheableInput, Any?>) = spy({ _ -> emptyMap() })

            assertNull(mutationInstance.result.value.error)
            assertEquals(mutationInstance.result.value.fetchState, FetchState.IDLE)

            mutationInstance.mutate(FakeMutationInput(3), optimisticUpdate, onSuccess, onError)

            runCurrent()
            verify { onSuccess(5) }
            verify { optimisticUpdate(FakeMutationInput(3)) }
            verify(not) {
                onError(Exception("Boom"))
            }
            verifySuspend { dependentFunctionMock.invoke(5) }
            assertNull(mutationInstance.result.value.error)
            assertEquals(5, mutationInstance.result.value.data)
            assertEquals(mutationInstance.result.value.fetchState, FetchState.SUCCESS)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Test that mutation factory with input updates cache values`() =
        runTest {
            cache.clear()
            cache[FakeInput(3, false)] = 7
            val mutationInstance = testMutationFactoryWithInput.create()
            val onSuccess = spy({ _: Int -> })
            val onError = spy({ _: Exception -> })
            assertNull(mutationInstance.result.value.error)
            assertEquals(mutationInstance.result.value.fetchState, FetchState.IDLE)

            mutationInstance.mutate(
                FakeMutationInput(3),
                optimisticUpdate = { _ ->
                    testQueryFactoryWithInput.optimisticUpdater(FakeInput(3, false)) { _ -> 5 }
                },
                onSuccess = onSuccess,
                onError = onError,
            )

            runCurrent()
            verify { onSuccess(5) }
            assertEquals(5, cache.get<Int>(FakeInput(3, false)).value)
            verify(not) {
                onError(Exception("Boom"))
            }
            assertNull(mutationInstance.result.value.error)
            assertEquals(5, mutationInstance.result.value.data)
            assertEquals(mutationInstance.result.value.fetchState, FetchState.SUCCESS)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Test that mutation factory with no input updates cache values`() =
        runTest {
            cache.clear()
            cache[FakeInput(3, false)] = 7
            val mutationInstance = testMutationFactoryWithNoInput.create()
            val onSuccess = spy({ _: Int -> })
            val onError = spy({ _: Exception -> })
            val optimisticUpdate: (() -> Map<CacheableInput, Any?>) = spy({ mapOf(FakeInput(3, false) to 5) })

            assertNull(mutationInstance.result.value.error)
            assertEquals(mutationInstance.result.value.fetchState, FetchState.IDLE)

            mutationInstance.mutate(optimisticUpdate, onSuccess, onError)

            runCurrent()
            verify { onSuccess(2) }
            assertEquals(5, cache.get<Int>(FakeInput(3, false)).value)
            verify { optimisticUpdate() }
            verify(not) {
                onError(Exception("Boom"))
            }
            verifySuspend { dependentFunctionMock.invoke(2) }
            assertNull(mutationInstance.result.value.error)
            assertEquals(2, mutationInstance.result.value.data)
            assertEquals(mutationInstance.result.value.fetchState, FetchState.SUCCESS)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Test that mutation factory rolls back on failure`() =
        runTest {
            cache.clear()
            cache[FakeInput(3, false)] = 7
            val mutationInstance = testMutationFactoryWithInput.create()
            val onSuccess = spy({ _: Int -> })
            val onError = spy({ _: Exception -> })
            val optimisticUpdate: ((input: MutationInput) -> Map<CacheableInput, Any?>) =
                spy({ _ -> mapOf(FakeInput(3, false) to 5) })

            assertNull(mutationInstance.result.value.error)
            assertEquals(mutationInstance.result.value.fetchState, FetchState.IDLE)

            mutationInstance.mutate(FakeMutationInput(3, true), optimisticUpdate, onSuccess, onError)

            runCurrent()
            verify(not) { onSuccess(any()) }
            assertEquals(5, valueBeforeRollback)
            assertEquals(7, cache.get<Int>(FakeInput(3, false)).value)
            verify { optimisticUpdate(FakeMutationInput(3, true)) }
            verify { onError(any()) }
            assertNotNull(mutationInstance.result.value.error)
            assertNull(mutationInstance.result.value.data)
            assertEquals(mutationInstance.result.value.fetchState, FetchState.ERROR)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Test that mutation factory performs functions on failure`() =
        runTest {
            cache.clear()
            val mutationInstance = testMutationFactoryWithInput.create()
            val onSuccess = spy({ _: Int -> })
            val onError = spy({ _: Exception -> })
            val optimisticUpdate: ((input: MutationInput) -> Map<CacheableInput, Any?>) = spy({ _ -> emptyMap() })

            assertNull(mutationInstance.result.value.error)
            assertEquals(mutationInstance.result.value.fetchState, FetchState.IDLE)

            mutationInstance.mutate(FakeMutationInput(3, true), optimisticUpdate, onSuccess, onError)

            runCurrent()
            verify(not) { onSuccess(any()) }
            verify { optimisticUpdate(FakeMutationInput(3, true)) }
            verify { onError(any()) }
            assertNotNull(mutationInstance.result.value.error)
            assertNull(mutationInstance.result.value.data)
            assertEquals(mutationInstance.result.value.fetchState, FetchState.ERROR)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Test that mutation factory no input performs functions on success`() =
        runTest {
            cache.clear()
            val mutationInstance = testMutationFactoryWithNoInput.create()
            val onSuccess = spy({ _: Int -> })
            val onError = spy({ _: Exception -> })
            val optimisticUpdate: (() -> Map<CacheableInput, Any?>) = spy({ emptyMap() })

            assertNull(mutationInstance.result.value.error)
            assertEquals(mutationInstance.result.value.fetchState, FetchState.IDLE)

            mutationInstance.mutate(optimisticUpdate, onSuccess, onError)

            runCurrent()
            verify { onSuccess(2) }
            verify { optimisticUpdate() }
            verify(not) {
                onError(Exception("Boom"))
            }
            verifySuspend { dependentFunctionMock.invoke(2) }
            assertNull(mutationInstance.result.value.error)
            assertEquals(2, mutationInstance.result.value.data)
            assertEquals(mutationInstance.result.value.fetchState, FetchState.SUCCESS)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Test that mutation factory no input performs functions on failure`() =
        runTest {
            cache.clear()
            isMutationError = true
            val mutationInstance = testMutationFactoryWithNoInput.create()
            val onSuccess = spy({ _: Int -> })
            val onError = spy({ _: Exception -> })
            val optimisticUpdate: (() -> Map<CacheableInput, Any?>) = spy({ emptyMap() })

            assertNull(mutationInstance.result.value.error)
            assertEquals(mutationInstance.result.value.fetchState, FetchState.IDLE)

            mutationInstance.mutate(optimisticUpdate, onSuccess, onError)

            runCurrent()
            verify(not) { onSuccess(5) }
            verify { optimisticUpdate() }
            verify { onError(any()) }
            assertNotNull(mutationInstance.result.value.error)
            assertNull(mutationInstance.result.value.data)
            assertEquals(mutationInstance.result.value.fetchState, FetchState.ERROR)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Test that mutation factory no output performs functions on success`() =
        runTest {
            cache.clear()
            val mutationInstance = testMutationFactoryWithInputNoOutput.create()
            val onSuccess = spy({ -> })
            val onError = spy({ _: Exception -> })
            val optimisticUpdate: ((input: MutationInput) -> Map<CacheableInput, Any?>) = spy({ _ -> emptyMap() })

            assertNull(mutationInstance.result.value.error)
            assertEquals(mutationInstance.result.value.fetchState, FetchState.IDLE)

            mutationInstance.mutate(FakeMutationInput(3), optimisticUpdate, onSuccess, onError)

            runCurrent()
            verify { onSuccess() }
            verify { optimisticUpdate(FakeMutationInput(3)) }
            verify(not) {
                onError(Exception("Boom"))
            }
            verifySuspend { dependentNoArgFunctionMock.invoke() }
            assertNull(mutationInstance.result.value.error)
            assertEquals(mutationInstance.result.value.fetchState, FetchState.SUCCESS)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Test that mutation factory no input no output performs functions on success`() =
        runTest {
            cache.clear()
            val mutationInstance = testMutationFactoryWithNoInputNoOutput.create()
            val onSuccess = spy({ })
            val onError = spy({ _: Exception -> })
            val optimisticUpdate: (() -> Map<CacheableInput, Any?>) = spy({ emptyMap() })

            assertNull(mutationInstance.result.value.error)
            assertEquals(mutationInstance.result.value.fetchState, FetchState.IDLE)

            mutationInstance.mutate(optimisticUpdate, onSuccess, onError)

            runCurrent()
            verify { onSuccess() }
            verify { optimisticUpdate() }
            verify(not) {
                onError(Exception("Boom"))
            }
            verifySuspend { dependentNoArgFunctionMock.invoke() }
            assertNull(mutationInstance.result.value.error)
            assertEquals(mutationInstance.result.value.fetchState, FetchState.SUCCESS)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Test that mutation factory no output performs functions on failure`() =
        runTest {
            cache.clear()
            val mutationInstance = testMutationFactoryWithInputNoOutput.create()
            val onSuccess = spy({ -> })
            val onError = spy({ _: Exception -> })
            val optimisticUpdate: ((input: MutationInput) -> Map<CacheableInput, Any?>) = spy({ _ -> emptyMap() })

            assertNull(mutationInstance.result.value.error)
            assertEquals(mutationInstance.result.value.fetchState, FetchState.IDLE)

            mutationInstance.mutate(FakeMutationInput(3, true), optimisticUpdate, onSuccess, onError)

            runCurrent()
            verify(not) { onSuccess() }
            verify { optimisticUpdate(FakeMutationInput(3, true)) }
            verify { onError(any()) }
            assertNotNull(mutationInstance.result.value.error)
            assertEquals(FetchState.ERROR, mutationInstance.result.value.fetchState)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Test that mutation factory no input no output performs functions on failure`() =
        runTest {
            cache.clear()
            isMutationError = true
            val mutationInstance = testMutationFactoryWithNoInputNoOutput.create()
            val onSuccess = spy({ })
            val onError = spy({ _: Exception -> })
            val optimisticUpdate: (() -> Map<CacheableInput, Any?>) = spy({ emptyMap() })

            assertNull(mutationInstance.result.value.error)
            assertEquals(mutationInstance.result.value.fetchState, FetchState.IDLE)

            mutationInstance.mutate(optimisticUpdate, onSuccess, onError)

            runCurrent()
            verify(not) { onSuccess() }
            verify { optimisticUpdate() }
            verify { onError(any()) }
            assertNotNull(mutationInstance.result.value.error)
            assertEquals(FetchState.ERROR, mutationInstance.result.value.fetchState)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Test that mutation factory no input rolls back on failure`() =
        runTest {
            cache.clear()
            cache[FakeInput(3, false)] = 7
            isMutationError = true
            val mutationInstance = testMutationFactoryWithNoInput.create()
            val onSuccess = spy({ _: Int -> })
            val onError = spy({ _: Exception -> })
            val optimisticUpdate: (() -> Map<CacheableInput, Any?>) =
                spy({ mapOf(FakeInput(3, false) to 5) })

            mutationInstance.mutate(optimisticUpdate, onSuccess, onError)

            runCurrent()
            verify(not) { onSuccess(any()) }
            assertEquals(7, cache.get<Int>(FakeInput(3, false)).value)
            verify { optimisticUpdate() }
            verify { onError(any()) }
            assertNotNull(mutationInstance.result.value.error)
            assertEquals(FetchState.ERROR, mutationInstance.result.value.fetchState)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Test that mutation can optimistically update a flow cache entry using typed updater`() =
        runTest {
            cache.clear()
            cache[FakeFlowInput(3, false)] = 100
            val flowInstance = testFlowFactoryWithInput.create(FakeFlowInput(3, false), backgroundScope)
            runCurrent()
            assertEquals(100, flowInstance.result.value.data)

            val mutationInstance = testMutationFactoryWithNoInput.create()
            val onSuccess = spy({ _: Int -> })
            val onError = spy({ _: Exception -> })

            mutationInstance.mutate(
                optimisticUpdate = {
                    testFlowFactoryWithInput.optimisticUpdater(FakeFlowInput(3, false)) { current ->
                        (current ?: 0) + 899
                    }
                },
                onSuccess = onSuccess,
                onError = onError,
            )
            runCurrent()

            // 100 + 899 = 999
            assertEquals(999, flowInstance.result.value.data)
            assertEquals(999, cache.get<Int>(FakeFlowInput(3, false)).value)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Test that flow cache entry rolls back on mutation failure`() =
        runTest {
            cache.clear()
            cache[FakeFlowInput(3, false)] = 100
            val flowInstance = testFlowFactoryWithInput.create(FakeFlowInput(3, false), backgroundScope)
            runCurrent()
            assertEquals(100, flowInstance.result.value.data)

            isMutationError = true
            val mutationInstance = testMutationFactoryWithNoInput.create()

            mutationInstance.mutate(
                optimisticUpdate = {
                    testFlowFactoryWithInput.optimisticUpdater(FakeFlowInput(3, false)) { _ -> 999 }
                },
            )
            runCurrent()

            assertEquals(100, flowInstance.result.value.data)
            assertEquals(100, cache.get<Int>(FakeFlowInput(3, false)).value)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Test that mutation can optimistically update an infinite query cache entry using typed updater`() =
        runTest {
            cache.clear()
            // Pre-populate with one page
            cache[FakePageInput(3)] = listOf(PagedData<Int?, Int>(1, 3))
            val infiniteInstance = testInfiniteQueryFactoryWithInput.create(FakePageInput(3), backgroundScope)
            runCurrent()
            assertEquals(listOf(PagedData<Int?, Int>(1, 3)), infiniteInstance.result.value.data)

            val mutationInstance = testMutationFactoryWithNoInput.create()

            mutationInstance.mutate(
                optimisticUpdate = {
                    testInfiniteQueryFactoryWithInput.optimisticUpdater(FakePageInput(3)) { currentPages ->
                        (currentPages ?: emptyList()) + PagedData<Int?, Int>(2, 99)
                    }
                },
            )
            runCurrent()

            val expected = listOf(PagedData<Int?, Int>(1, 3), PagedData<Int?, Int>(2, 99))
            assertEquals(expected, infiniteInstance.result.value.data)
            assertEquals(expected, cache.get<List<PagedData<Int?, Int>>>(FakePageInput(3)).value)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Test that infinite query cache entry rolls back on mutation failure`() =
        runTest {
            cache.clear()
            cache[FakePageInput(3)] = listOf(PagedData<Int?, Int>(1, 3))
            val infiniteInstance = testInfiniteQueryFactoryWithInput.create(FakePageInput(3), backgroundScope)
            runCurrent()

            isMutationError = true
            val mutationInstance = testMutationFactoryWithNoInput.create()

            mutationInstance.mutate(
                optimisticUpdate = {
                    testInfiniteQueryFactoryWithInput.optimisticUpdater(FakePageInput(3)) { currentPages ->
                        (currentPages ?: emptyList()) + PagedData<Int?, Int>(2, 99)
                    }
                },
            )
            runCurrent()

            // Rolled back to original
            assertEquals(listOf(PagedData<Int?, Int>(1, 3)), infiniteInstance.result.value.data)
            assertEquals(
                listOf(PagedData<Int?, Int>(1, 3)),
                cache.get<List<PagedData<Int?, Int>>>(FakePageInput(3)).value,
            )
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Test that mutation can optimistically update a flow no-input cache entry`() =
        runTest {
            cache.clear()
            cache[FakeFlowCacheKey()] = 100
            val flowInstance = testFlowFactoryWithNoInput.create(backgroundScope)
            runCurrent()
            assertEquals(100, flowInstance.result.value.data)

            val mutationInstance = testMutationFactoryWithNoInput.create()

            mutationInstance.mutate(
                optimisticUpdate = {
                    testFlowFactoryWithNoInput.optimisticUpdater { current ->
                        (current ?: 0) + 50
                    }
                },
            )
            runCurrent()

            assertEquals(150, flowInstance.result.value.data)
            assertEquals(150, cache.get<Int>(FakeFlowCacheKey()).value)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Test that flow no-input cache entry rolls back on mutation failure`() =
        runTest {
            cache.clear()
            cache[FakeFlowCacheKey()] = 100
            val flowInstance = testFlowFactoryWithNoInput.create(backgroundScope)
            runCurrent()
            assertEquals(100, flowInstance.result.value.data)

            isMutationError = true
            val mutationInstance = testMutationFactoryWithNoInput.create()

            mutationInstance.mutate(
                optimisticUpdate = {
                    testFlowFactoryWithNoInput.optimisticUpdater { _ -> 999 }
                },
            )
            runCurrent()

            assertEquals(100, flowInstance.result.value.data)
            assertEquals(100, cache.get<Int>(FakeFlowCacheKey()).value)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Test that mutation can optimistically update an infinite query no-input cache entry`() =
        runTest {
            cache.clear()
            cache[FakePageCacheKey()] = listOf(PagedData<Int?, Int>(1, 10))
            val infiniteInstance = testInfiniteQueryFactoryWithNoInput.create(backgroundScope)
            runCurrent()
            assertEquals(listOf(PagedData<Int?, Int>(1, 10)), infiniteInstance.result.value.data)

            val mutationInstance = testMutationFactoryWithNoInput.create()

            mutationInstance.mutate(
                optimisticUpdate = {
                    testInfiniteQueryFactoryWithNoInput.optimisticUpdater { currentPages ->
                        (currentPages ?: emptyList()) + PagedData<Int?, Int>(2, 99)
                    }
                },
            )
            runCurrent()

            val expected = listOf(PagedData<Int?, Int>(1, 10), PagedData<Int?, Int>(2, 99))
            assertEquals(expected, infiniteInstance.result.value.data)
            assertEquals(expected, cache.get<List<PagedData<Int?, Int>>>(FakePageCacheKey()).value)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Test that infinite query no-input cache entry rolls back on mutation failure`() =
        runTest {
            cache.clear()
            cache[FakePageCacheKey()] = listOf(PagedData<Int?, Int>(1, 10))
            val infiniteInstance = testInfiniteQueryFactoryWithNoInput.create(backgroundScope)
            runCurrent()

            isMutationError = true
            val mutationInstance = testMutationFactoryWithNoInput.create()

            mutationInstance.mutate(
                optimisticUpdate = {
                    testInfiniteQueryFactoryWithNoInput.optimisticUpdater { currentPages ->
                        (currentPages ?: emptyList()) + PagedData<Int?, Int>(2, 99)
                    }
                },
            )
            runCurrent()

            assertEquals(listOf(PagedData<Int?, Int>(1, 10)), infiniteInstance.result.value.data)
            assertEquals(
                listOf(PagedData<Int?, Int>(1, 10)),
                cache.get<List<PagedData<Int?, Int>>>(FakePageCacheKey()).value,
            )
        }
}
