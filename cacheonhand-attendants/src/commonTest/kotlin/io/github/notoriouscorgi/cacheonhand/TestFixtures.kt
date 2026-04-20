package io.github.notoriouscorgi.cacheonhand

import io.github.notoriouscorgi.cacheonhand.CacheableInput.FlowInput
import io.github.notoriouscorgi.cacheonhand.CacheableInput.MutationInput
import io.github.notoriouscorgi.cacheonhand.CacheableInput.QueryInput
import io.github.notoriouscorgi.cacheonhand.operations.PageParam
import io.github.notoriouscorgi.cacheonhand.operations.flowFactoryOf
import io.github.notoriouscorgi.cacheonhand.operations.infiniteQueryFactoryOf
import io.github.notoriouscorgi.cacheonhand.operations.mutationFactoryOf
import io.github.notoriouscorgi.cacheonhand.operations.mutationFactoryWithNoOutputOf
import io.github.notoriouscorgi.cacheonhand.operations.queryFactoryOf
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.scan

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

val cache = OnHandCache()

var isError = false
var isMutationError = false
var flowError = false
var infiniteQueryReturnsNull = false
var infiniteQueryMaxPage: Int? = null
var valueBeforeRollback: Int? = null

val testQueryFactoryWithInput =
    queryFactoryOf<FakeInput, Int, Exception>(cache = cache) { input: FakeInput ->
        if (input.isError) {
            throw Exception("Boom")
        }
        input.value + 2
    }

val testQueryFactoryWithNoInput =
    queryFactoryOf<StringCacheKey, Int, Exception>(cache = cache, cacheKey = StringCacheKey()) {
        if (isError) {
            throw Exception("Boom")
        }
        2
    }

val testMutationFactoryWithInput =
    mutationFactoryOf<FakeMutationInput, Int, Exception>(cache = cache) { input: FakeMutationInput ->
        if (input.isError) {
            valueBeforeRollback = cache.get<Int>(FakeInput(3, false)).value
            throw Exception("Boom")
        }
        input.value + 2
    }

val testMutationFactoryWithInputNoOutput =
    mutationFactoryWithNoOutputOf<FakeMutationInput, Exception>(cache = cache) { input: FakeMutationInput ->
        if (input.isError) {
            valueBeforeRollback = cache.get<Int>(FakeInput(3, false)).value
            throw Exception("Boom")
        }
    }

val testMutationFactoryWithNoInput =
    mutationFactoryOf<Int, Exception>(cache = cache) {
        if (isMutationError) {
            throw Exception("Boom")
        }
        2
    }

val testMutationFactoryWithNoInputNoOutput =
    mutationFactoryWithNoOutputOf<Exception>(cache = cache) {
        if (isMutationError) {
            throw Exception("Boom")
        }
    }

val testFlowFactoryWithInput =
    flowFactoryOf<FakeFlowInput, Int, Exception>(cache = cache) { input ->
        flow {
            for (i in 1..20) {
                if (i == 5 && input.isError) {
                    throw Exception("Boom")
                }
                emit(input.value * i)
            }
        }
    }

val testAccumulatingFlowFactory =
    flowFactoryOf<FakeFlowInput, List<Int>, Exception>(cache = cache) { input ->
        flow {
            for (i in 1..5) {
                emit(input.value * i)
            }
        }.scan(emptyList()) { acc, value -> acc + value }
    }

val testFlowFactoryWithNoInput =
    flowFactoryOf<FakeFlowCacheKey, Int, Exception>(cache = cache, cacheKey = FakeFlowCacheKey()) {
        flow {
            for (i in 1..20) {
                if (i == 5 && flowError) {
                    throw Exception("Boom")
                }
                emit(3 * i)
            }
        }
    }

val testInfiniteQueryFactoryWithInput =
    infiniteQueryFactoryOf<FakePageInput, Int, Int, Exception>(
        cache = cache,
        initialPageParam = 1,
        getNextPageParam = { pages ->
            val next = (pages.lastOrNull()?.page ?: 0) + 1
            val max = infiniteQueryMaxPage
            if (max != null && next > max) PageParam.None else PageParam.Value(next)
        },
        getPreviousPageParam = { pages ->
            val prev = (pages.firstOrNull()?.page ?: 0) - 1
            if (prev < 0) PageParam.None else PageParam.Value(prev)
        },
        query = { input, page ->
            if (input.isError) throw Exception("Boom")
            if (infiniteQueryReturnsNull) null else (page ?: 1) * input.value
        },
    )

val testInfiniteQueryFactoryWithNoInput =
    infiniteQueryFactoryOf<Int, Int, Exception>(
        cache = cache,
        cacheKey = FakePageCacheKey(),
        initialPageParam = 1,
        getNextPageParam = { pages ->
            val next = (pages.lastOrNull()?.page ?: 0) + 1
            val max = infiniteQueryMaxPage
            if (max != null && next > max) PageParam.None else PageParam.Value(next)
        },
        getPreviousPageParam = { pages ->
            val prev = (pages.firstOrNull()?.page ?: 0) - 1
            if (prev < 0) PageParam.None else PageParam.Value(prev)
        },
        query = { page ->
            if (infiniteQueryReturnsNull) null else (page ?: 1) * 10
        },
    )

val testInfiniteQueryForwardOnlyFactory =
    infiniteQueryFactoryOf<FakePageInput, Int, Int, Exception>(
        cache = cache,
        initialPageParam = 1,
        getNextPageParam = { pages -> PageParam.Value((pages.lastOrNull()?.page ?: 0) + 1) },
        query = { input, page -> (page ?: 1) * input.value },
    )

fun resetTestState() {
    isError = false
    isMutationError = false
    flowError = false
    infiniteQueryReturnsNull = false
    infiniteQueryMaxPage = null
    valueBeforeRollback = null
}
