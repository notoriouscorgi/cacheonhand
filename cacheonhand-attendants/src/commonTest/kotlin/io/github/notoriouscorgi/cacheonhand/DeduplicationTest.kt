package io.github.notoriouscorgi.cacheonhand

import io.github.notoriouscorgi.cacheonhand.operations.FlowFactoryWithInput
import io.github.notoriouscorgi.cacheonhand.operations.FlowFactoryWithNoInput
import io.github.notoriouscorgi.cacheonhand.operations.InfiniteQueryFactoryWithInput
import io.github.notoriouscorgi.cacheonhand.operations.InfiniteQueryFactoryWithNoInput
import io.github.notoriouscorgi.cacheonhand.operations.PageParam
import io.github.notoriouscorgi.cacheonhand.operations.QueryFactoryWithInput
import io.github.notoriouscorgi.cacheonhand.operations.QueryFactoryWithNoInput
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class DeduplicationTest {
    @BeforeTest
    fun before() {
        resetTestState()
    }

    // ── Query with input ──────────────────────────────────────────────────────

    @Test
    fun `Test that concurrent query fetches for the same input are deduplicated`() =
        runTest {
            cache.clear()
            var fetchCount = 0
            val gate = CompletableDeferred<Unit>()

            val factory =
                QueryFactoryWithInput<FakeInput, Int, Exception>(
                    cache = cache,
                    dispatcher = Dispatchers.Unconfined,
                    dedupingInterval = 2.seconds,
                    query = {
                        fetchCount++
                        gate.await()
                        it.value + 2
                    },
                )

            val instance = factory.create(null, backgroundScope)

            val job1 = launch { instance.fetch(FakeInput(3)) }
            val job2 = launch { instance.fetch(FakeInput(3)) }

            runCurrent()
            gate.complete(Unit)
            runCurrent()

            job1.join()
            job2.join()

            assertEquals(1, fetchCount)
            assertEquals(5, cache.get<Int>(FakeInput(3)).value)
        }

    @Test
    fun `Test that concurrent query refetches for the same input are deduplicated`() =
        runTest {
            cache.clear()
            var fetchCount = 0
            val gate = CompletableDeferred<Unit>()

            val factory =
                QueryFactoryWithInput<FakeInput, Int, Exception>(
                    cache = cache,
                    dispatcher = Dispatchers.Unconfined,
                    dedupingInterval = 2.seconds,
                    query = {
                        fetchCount++
                        gate.await()
                        it.value + 2
                    },
                )

            val job1 = launch { factory.refetch(FakeInput(3)) }
            val job2 = launch { factory.refetch(FakeInput(3)) }

            runCurrent()
            gate.complete(Unit)
            runCurrent()

            job1.join()
            job2.join()

            assertEquals(1, fetchCount)
        }

    @Test
    fun `Test that sequential query fetches each run when deduplication interval is null`() =
        runTest {
            cache.clear()
            var fetchCount = 0

            val factory =
                QueryFactoryWithInput<FakeInput, Int, Exception>(
                    cache = cache,
                    dispatcher = Dispatchers.Unconfined,
                    dedupingInterval = null,
                    query = { fetchCount++; it.value + 2 },
                )

            val instance = factory.create(null, backgroundScope)
            instance.fetch(FakeInput(3))
            instance.fetch(FakeInput(3))

            assertEquals(2, fetchCount)
        }

    // ── Query with no input ───────────────────────────────────────────────────

    @Test
    fun `Test that concurrent no-input query fetches are deduplicated`() =
        runTest {
            cache.clear()
            var fetchCount = 0
            val gate = CompletableDeferred<Unit>()

            val factory =
                QueryFactoryWithNoInput<Int, Exception>(
                    cache = cache,
                    cacheKey = StringCacheKey(),
                    dispatcher = Dispatchers.Unconfined,
                    dedupingInterval = 2.seconds,
                    query = {
                        fetchCount++
                        gate.await()
                        42
                    },
                )

            val instance = factory.create(backgroundScope)

            val job1 = launch { instance.fetch() }
            val job2 = launch { instance.fetch() }

            runCurrent()
            gate.complete(Unit)
            runCurrent()

            job1.join()
            job2.join()

            assertEquals(1, fetchCount)
            assertEquals(42, cache.get<Int>(StringCacheKey()).value)
        }

    // ── Infinite query with input ─────────────────────────────────────────────

    @Test
    fun `Test that concurrent next page fetches for the same input and page are deduplicated`() =
        runTest {
            cache.clear()
            var fetchCount = 0
            val gate = CompletableDeferred<Unit>()

            val factory =
                InfiniteQueryFactoryWithInput<FakePageInput, Int, Int, Exception>(
                    cache = cache,
                    initialPageParam = 1,
                    getNextPageParam = { pages -> PageParam.Value((pages.lastOrNull()?.page ?: 0) + 1) },
                    dispatcher = Dispatchers.Unconfined,
                    dedupingInterval = 2.seconds,
                    query = { input, page ->
                        fetchCount++
                        gate.await()
                        (page ?: 1) * input.value
                    },
                )

            val instance = factory.create(null, backgroundScope)

            val job1 = launch { instance.fetchNextPage(FakePageInput(3)) }
            val job2 = launch { instance.fetchNextPage(FakePageInput(3)) }

            runCurrent()
            gate.complete(Unit)
            runCurrent()

            job1.join()
            job2.join()

            assertEquals(1, fetchCount)
        }

    // ── Infinite query with no input ──────────────────────────────────────────

    @Test
    fun `Test that concurrent no-input next page fetches for the same page are deduplicated`() =
        runTest {
            cache.clear()
            var fetchCount = 0
            val gate = CompletableDeferred<Unit>()

            val factory =
                InfiniteQueryFactoryWithNoInput<Int, Int, Exception>(
                    cache = cache,
                    cacheKey = FakePageCacheKey(),
                    initialPageParam = 1,
                    getNextPageParam = { pages -> PageParam.Value((pages.lastOrNull()?.page ?: 0) + 1) },
                    dispatcher = Dispatchers.Unconfined,
                    dedupingInterval = 2.seconds,
                    query = { page ->
                        fetchCount++
                        gate.await()
                        (page ?: 1) * 10
                    },
                )

            val instance = factory.create(backgroundScope)

            val job1 = launch { instance.fetchNextPage() }
            val job2 = launch { instance.fetchNextPage() }

            runCurrent()
            gate.complete(Unit)
            runCurrent()

            job1.join()
            job2.join()

            assertEquals(1, fetchCount)
        }

    // ── Flow with input ───────────────────────────────────────────────────────

    @Test
    fun `Test that concurrent flow launches for the same input start only one collection`() =
        runTest {
            cache.clear()
            var collectionCount = 0
            val gate = CompletableDeferred<Unit>()

            val factory =
                FlowFactoryWithInput<FakeFlowInput, Int, Exception>(
                    cache = cache,
                    dispatcher = Dispatchers.Unconfined,
                    flow = { input ->
                        collectionCount++
                        flow {
                            emit(input.value)
                            gate.await()
                        }
                    },
                )

            val instance1 = factory.create(null, backgroundScope)
            val instance2 = factory.create(null, backgroundScope)

            val job1 = launch { instance1.launch(FakeFlowInput(3)) }
            val job2 = launch { instance2.launch(FakeFlowInput(3)) }

            runCurrent()

            assertEquals(1, collectionCount)

            gate.complete(Unit)
            runCurrent()
            job1.join()
            job2.join()
        }

    @Test
    fun `Test that a second flow launch for the same input starts a new collection after the first completes`() =
        runTest {
            cache.clear()
            var collectionCount = 0

            val factory =
                FlowFactoryWithInput<FakeFlowInput, Int, Exception>(
                    cache = cache,
                    dispatcher = Dispatchers.Unconfined,
                    flow = { input ->
                        collectionCount++
                        flow { emit(input.value) }
                    },
                )

            val instance = factory.create(null, backgroundScope)

            instance.launch(FakeFlowInput(3))
            instance.launch(FakeFlowInput(3))

            assertEquals(2, collectionCount)
        }

    // ── Flow with no input ────────────────────────────────────────────────────

    @Test
    fun `Test that concurrent no-input flow launches start only one collection`() =
        runTest {
            cache.clear()
            var collectionCount = 0
            val gate = CompletableDeferred<Unit>()

            val factory =
                FlowFactoryWithNoInput<Int, Exception>(
                    cache = cache,
                    cacheKey = FakeFlowCacheKey(),
                    dispatcher = Dispatchers.Unconfined,
                    flow = {
                        collectionCount++
                        flow {
                            emit(3)
                            gate.await()
                        }
                    },
                )

            val instance1 = factory.create(backgroundScope)
            val instance2 = factory.create(backgroundScope)

            val job1 = launch { instance1.launch() }
            val job2 = launch { instance2.launch() }

            runCurrent()

            assertEquals(1, collectionCount)

            gate.complete(Unit)
            runCurrent()
            job1.join()
            job2.join()
        }
}
