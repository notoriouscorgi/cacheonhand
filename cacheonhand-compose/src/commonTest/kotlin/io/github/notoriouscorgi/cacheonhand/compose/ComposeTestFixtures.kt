package io.github.notoriouscorgi.cacheonhand.compose

import io.github.notoriouscorgi.cacheonhand.CacheableInput.FlowInput
import io.github.notoriouscorgi.cacheonhand.CacheableInput.MutationInput
import io.github.notoriouscorgi.cacheonhand.CacheableInput.QueryInput
import io.github.notoriouscorgi.cacheonhand.OnHandCache
import io.github.notoriouscorgi.cacheonhand.operations.PageParam
import io.github.notoriouscorgi.cacheonhand.operations.flowFactoryOf
import io.github.notoriouscorgi.cacheonhand.operations.infiniteQueryFactoryOf
import io.github.notoriouscorgi.cacheonhand.operations.mutationFactoryOf
import io.github.notoriouscorgi.cacheonhand.operations.mutationFactoryWithNoOutputOf
import io.github.notoriouscorgi.cacheonhand.operations.queryFactoryOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flow

data class FakeInput(
    val value: Int,
    val isError: Boolean = false,
) : QueryInput {
    override val identifier: String = "FakeInput"
}

data class StringCacheKey(
    val key: String = "fake",
) : QueryInput {
    override val identifier: String = "StringCacheKey"
}

data class FakeMutationInput(
    val value: Int,
    val isError: Boolean = false,
) : MutationInput {
    override val identifier: String = "FakeMutationInput"
}

data class FakeFlowInput(
    val value: Int,
    val isError: Boolean = false,
) : FlowInput {
    override val identifier: String = "FakeFlowInput"
}

data class FakeFlowCacheKey(
    val key: String = "FakeFlow",
) : FlowInput {
    override val identifier: String = "FakeFlowCacheKey"
}

data class FakePageInput(
    val value: Int,
    val isError: Boolean = false,
) : QueryInput {
    override val identifier: String = "FakePageInput"
}

data class FakePageCacheKey(
    val key: String = "FakePage",
) : QueryInput {
    override val identifier: String = "FakePageCacheKey"
}

val composeCache = OnHandCache()

var composeIsError = false
var composeMutationError = false
var composeFlowError = false

val composeQueryFactoryWithInput =
    queryFactoryOf<FakeInput, Int, Exception>(
        cache = composeCache,
        dispatcher = Dispatchers.Unconfined,
    ) { input: FakeInput ->
        if (input.isError) throw Exception("Boom")
        input.value + 2
    }

val composeQueryFactoryWithNoInput =
    queryFactoryOf<StringCacheKey, Int, Exception>(
        cache = composeCache,
        cacheKey = StringCacheKey(),
        dispatcher = Dispatchers.Unconfined,
    ) {
        if (composeIsError) throw Exception("Boom")
        2
    }

val composeMutationFactoryWithInput =
    mutationFactoryOf<FakeMutationInput, Int, Exception>(
        cache = composeCache,
        dispatcher = Dispatchers.Unconfined,
    ) { input: FakeMutationInput ->
        if (input.isError) throw Exception("Boom")
        input.value + 2
    }

val composeMutationFactoryWithNoInput =
    mutationFactoryOf<Int, Exception>(
        cache = composeCache,
        dispatcher = Dispatchers.Unconfined,
    ) {
        if (composeMutationError) throw Exception("Boom")
        2
    }

val composeMutationFactoryWithInputNoOutput =
    mutationFactoryWithNoOutputOf<FakeMutationInput, Exception>(
        cache = composeCache,
        dispatcher = Dispatchers.Unconfined,
    ) { input: FakeMutationInput ->
        if (input.isError) throw Exception("Boom")
    }

val composeFlowFactoryWithInput =
    flowFactoryOf<FakeFlowInput, Int, Exception>(
        cache = composeCache,
        dispatcher = Dispatchers.Unconfined,
    ) { input ->
        flow {
            for (i in 1..5) {
                if (i == 3 && input.isError) throw Exception("Boom")
                emit(input.value * i)
            }
        }
    }

val composeFlowFactoryWithNoInput =
    flowFactoryOf<FakeFlowCacheKey, Int, Exception>(
        cache = composeCache,
        cacheKey = FakeFlowCacheKey(),
        dispatcher = Dispatchers.Unconfined,
    ) {
        flow {
            for (i in 1..5) {
                if (i == 3 && composeFlowError) throw Exception("Boom")
                emit(3 * i)
            }
        }
    }

val composeInfiniteQueryFactoryWithInput =
    infiniteQueryFactoryOf<FakePageInput, Int, Int, Exception>(
        cache = composeCache,
        initialPageParam = 1,
        getNextPageParam = { pages -> PageParam.Value((pages.lastOrNull()?.page ?: 0) + 1) },
        getPreviousPageParam = { pages ->
            val prev = (pages.firstOrNull()?.page ?: 0) - 1
            if (prev < 0) PageParam.None else PageParam.Value(prev)
        },
        dispatcher = Dispatchers.Unconfined,
        query = { input, page ->
            if (input.isError) throw Exception("Boom")
            (page ?: 1) * input.value
        },
    )

val composeInfiniteQueryFactoryWithNoInput =
    infiniteQueryFactoryOf<Int, Int, Exception>(
        cache = composeCache,
        cacheKey = FakePageCacheKey(),
        initialPageParam = 1,
        getNextPageParam = { pages -> PageParam.Value((pages.lastOrNull()?.page ?: 0) + 1) },
        dispatcher = Dispatchers.Unconfined,
        query = { page -> (page ?: 1) * 10 },
    )

var composeRefetchCount = 0

val composeRefetchQueryFactory =
    queryFactoryOf<FakeInput, Int, Exception>(
        cache = composeCache,
        dispatcher = Dispatchers.Unconfined,
    ) { input ->
        composeRefetchCount++
        input.value + composeRefetchCount
    }

fun resetComposeTestState() {
    composeIsError = false
    composeMutationError = false
    composeFlowError = false
    composeRefetchCount = 0
}
