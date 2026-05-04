@file:Suppress("UNCHECKED_CAST")

package io.github.notoriouscorgi.cacheonhand.operations

import io.github.notoriouscorgi.cacheonhand.CacheableInput.QueryInput
import io.github.notoriouscorgi.cacheonhand.OnHandCache
import io.github.notoriouscorgi.cacheonhand.operations.RefetchableFactory.RefetchableFactoryWithInput
import io.github.notoriouscorgi.cacheonhand.operations.RefetchableFactory.RefetchableFactoryWithNoInput
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Sealed type for representing the next or previous page param in infinite queries.
 * Use [Value] to signal that a page is available to fetch, and [None] to signal
 * that no more pages exist in that direction.
 */
sealed class PageParam<out T> {
    data class Value<T>(
        val value: T,
    ) : PageParam<T>()

    object None : PageParam<Nothing>()
}

data class CacheableInfiniteQueryResult<TPageParam, TData, TError : Throwable>(
    override val fetchState: FetchState,
    override val error: TError?,
    override val data: List<PagedData<TPageParam?, TData>>? = null,
    val hasNextPage: Boolean = true,
    val hasPreviousPage: Boolean = false,
) : CacheableResultWithData<List<PagedData<TPageParam?, TData>>, TError>

data class PagedData<TPageParam, TData>(
    val page: TPageParam,
    val data: TData?,
)

@OptIn(ExperimentalCoroutinesApi::class)
class CacheableInfiniteQueryWithInput<TInput : QueryInput, TPageParam, TData, TError : Throwable>(
    private val coroutineScope: CoroutineScope,
    private val launchQuery: suspend (input: TInput, page: TPageParam?) -> TData?,
    private val initialPageParam: TPageParam? = null,
    private val getNextPageParam: suspend (pages: List<PagedData<TPageParam?, TData>>) -> PageParam<TPageParam>,
    private val getPreviousPageParam: (suspend (pages: List<PagedData<TPageParam?, TData>>) -> PageParam<TPageParam>)? = null,
    startingCacheKey: TInput? = null,
    private val cache: OnHandCache,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val ttl: kotlin.time.Duration? = null,
    initialFetchState: FetchState = FetchState.IDLE,
) {
    private val activeKey = MutableStateFlow(startingCacheKey)
    private val fetchStateFlow = MutableStateFlow(initialFetchState)
    private val errorFlow = MutableStateFlow<TError?>(null)

    val result: StateFlow<CacheableInfiniteQueryResult<TPageParam, TData, TError>> =
        combine(
            activeKey.flatMapLatest { key ->
                if (key == null) {
                    flowOf<List<PagedData<TPageParam?, TData>>?>(null)
                } else {
                    cache.observe<List<PagedData<TPageParam?, TData>>>(key)
                }
            },
            fetchStateFlow,
            errorFlow,
        ) { data, state, err ->
            Triple(data, state, err)
        }.map { (data, state, err) ->
            val hasNext = if (data == null) true else getNextPageParam(data) is PageParam.Value
            val hasPrev =
                if (data == null || getPreviousPageParam == null) {
                    false
                } else {
                    getPreviousPageParam.invoke(data) is PageParam.Value
                }
            CacheableInfiniteQueryResult(
                fetchState = state,
                error = err,
                data = data,
                hasNextPage = hasNext,
                hasPreviousPage = hasPrev,
            )
        }.stateIn(
            scope = coroutineScope,
            started = SharingStarted.Eagerly,
            initialValue =
                CacheableInfiniteQueryResult(
                    fetchState = initialFetchState,
                    error = null,
                    data = startingCacheKey?.let { cache.getOrNull<List<PagedData<TPageParam?, TData>>>(it)?.value },
                    hasNextPage = true,
                    hasPreviousPage = false,
                ),
        )

    suspend fun fetchNextPage(
        queryInput: TInput,
        onSuccess: (suspend (pageData: PagedData<TPageParam?, TData>?) -> Unit)? = null,
        onError: (suspend (TError) -> Unit)? = null,
    ) {
        activeKey.value = queryInput
        withContext(dispatcher) {
            val nextPageParam =
                when (val paramResult = calculateNextPageParam(queryInput)) {
                    is PageParam.None -> return@withContext
                    is PageParam.Value -> paramResult.value
                }
            fetchStateFlow.value = FetchState.LOADING
            errorFlow.value = null
            try {
                launchQuery(queryInput, nextPageParam).also {
                    val newPageData = PagedData<TPageParam?, TData>(nextPageParam, it)
                    val existing = cache.get<List<PagedData<TPageParam?, TData>>>(queryInput).value ?: emptyList()
                    cache.setMaybeWithTtl(queryInput, existing + newPageData, ttl)
                    onSuccess?.invoke(newPageData)
                    fetchStateFlow.value = FetchState.SUCCESS
                }
            } catch (error: Throwable) {
                @Suppress("UNCHECKED_CAST")
                errorFlow.value = error as TError?
                fetchStateFlow.value = FetchState.ERROR
                @Suppress("UNCHECKED_CAST")
                onError?.invoke(error as TError)
            }
        }
    }

    suspend fun fetchPreviousPage(
        queryInput: TInput,
        onSuccess: (suspend (pageData: PagedData<TPageParam?, TData>?) -> Unit)? = null,
        onError: (suspend (TError) -> Unit)? = null,
    ) {
        activeKey.value = queryInput
        withContext(dispatcher) {
            val prevPageParam =
                when (val paramResult = calculatePreviousPageParam(queryInput)) {
                    is PageParam.None -> return@withContext
                    is PageParam.Value -> paramResult.value
                }
            fetchStateFlow.value = FetchState.LOADING
            errorFlow.value = null
            try {
                launchQuery(queryInput, prevPageParam).also {
                    val newPageData = PagedData<TPageParam?, TData>(prevPageParam, it)
                    val existing = cache.get<List<PagedData<TPageParam?, TData>>>(queryInput).value ?: emptyList()
                    cache.setMaybeWithTtl(queryInput, listOf(newPageData) + existing, ttl)
                    onSuccess?.invoke(newPageData)
                    fetchStateFlow.value = FetchState.SUCCESS
                }
            } catch (error: Throwable) {
                @Suppress("UNCHECKED_CAST")
                errorFlow.value = error as TError?
                fetchStateFlow.value = FetchState.ERROR
                @Suppress("UNCHECKED_CAST")
                onError?.invoke(error as TError)
            }
        }
    }

    operator fun component1(): StateFlow<CacheableInfiniteQueryResult<TPageParam, TData, TError>> = result

    private suspend fun calculateNextPageParam(input: TInput): PageParam<TPageParam?> =
        cache.getOrNull<List<PagedData<TPageParam?, TData>>>(input)?.value?.let {
            getNextPageParam(it)
        } ?: PageParam.Value(initialPageParam)

    private suspend fun calculatePreviousPageParam(input: TInput): PageParam<TPageParam?> {
        val getPrev = getPreviousPageParam ?: return PageParam.None
        return cache.getOrNull<List<PagedData<TPageParam?, TData>>>(input)?.value?.let {
            getPrev(it)
        } ?: PageParam.Value(initialPageParam)
    }

    suspend fun fetchPage(
        queryInput: TInput,
        page: TPageParam,
        onSuccess: (suspend (pageData: PagedData<TPageParam?, TData>?) -> Unit)? = null,
        onError: (suspend (TError) -> Unit)? = null,
    ) {
        val cacheKey = activeKey.value ?: queryInput
        withContext(dispatcher) {
            fetchStateFlow.value = FetchState.LOADING
            errorFlow.value = null
            try {
                launchQuery(queryInput, page).also {
                    val newPageData = PagedData<TPageParam?, TData>(page, it)
                    val existing = cache.get<List<PagedData<TPageParam?, TData>>>(cacheKey).value ?: emptyList()
                    val updated =
                        existing
                            .map { entry ->
                                if (entry.page == page) newPageData else entry
                            }.let { mapped ->
                                if (mapped.none { entry -> entry.page == page }) mapped + newPageData else mapped
                            }
                    cache.setMaybeWithTtl(cacheKey, updated, ttl)
                    onSuccess?.invoke(newPageData)
                    fetchStateFlow.value = FetchState.SUCCESS
                }
            } catch (error: Throwable) {
                @Suppress("UNCHECKED_CAST")
                errorFlow.value = error as TError?
                fetchStateFlow.value = FetchState.ERROR
                @Suppress("UNCHECKED_CAST")
                onError?.invoke(error as TError)
            }
        }
    }
}

class CacheableInfiniteQueryWithNoInput<TPageParam, TData, TError : Throwable>(
    private val coroutineScope: CoroutineScope,
    private val launchQuery: suspend (page: TPageParam?) -> TData?,
    private val initialPageParam: TPageParam? = null,
    private val getNextPageParam: suspend (pages: List<PagedData<TPageParam?, TData>>) -> PageParam<TPageParam>,
    private val getPreviousPageParam: (suspend (pages: List<PagedData<TPageParam?, TData>>) -> PageParam<TPageParam>)? = null,
    val cacheKey: QueryInput,
    private val cache: OnHandCache,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val ttl: kotlin.time.Duration? = null,
    initialFetchState: FetchState = FetchState.IDLE,
) {
    private val fetchStateFlow = MutableStateFlow(initialFetchState)
    private val errorFlow = MutableStateFlow<TError?>(null)

    val result: StateFlow<CacheableInfiniteQueryResult<TPageParam, TData, TError>> =
        combine(
            cache.observe<List<PagedData<TPageParam?, TData>>>(cacheKey),
            fetchStateFlow,
            errorFlow,
        ) { data, state, err ->
            Triple(data, state, err)
        }.map { (data, state, err) ->
            val hasNext = if (data == null) true else getNextPageParam(data) is PageParam.Value
            val hasPrev =
                if (data == null || getPreviousPageParam == null) {
                    false
                } else {
                    getPreviousPageParam.invoke(data) is PageParam.Value
                }
            CacheableInfiniteQueryResult(
                fetchState = state,
                error = err,
                data = data,
                hasNextPage = hasNext,
                hasPreviousPage = hasPrev,
            )
        }.stateIn(
            scope = coroutineScope,
            started = SharingStarted.Eagerly,
            initialValue =
                CacheableInfiniteQueryResult(
                    fetchState = initialFetchState,
                    error = null,
                    data = cache.getOrNull<List<PagedData<TPageParam?, TData>>>(cacheKey)?.value,
                    hasNextPage = true,
                    hasPreviousPage = false,
                ),
        )

    suspend fun fetchNextPage(
        onSuccess: (suspend (pageData: PagedData<TPageParam?, TData>?) -> Unit)? = null,
        onError: (suspend (TError) -> Unit)? = null,
    ) = withContext(dispatcher) {
        val nextPageParam =
            when (val paramResult = calculateNextPageParam()) {
                is PageParam.None -> return@withContext
                is PageParam.Value -> paramResult.value
            }
        fetchStateFlow.value = FetchState.LOADING
        errorFlow.value = null
        try {
            launchQuery(nextPageParam).also {
                val newPageData = PagedData(nextPageParam, it)
                val existing = cache.get<List<PagedData<TPageParam?, TData>>>(cacheKey).value ?: emptyList()
                cache.setMaybeWithTtl(cacheKey, existing + newPageData, ttl)
                onSuccess?.invoke(newPageData)
                fetchStateFlow.value = FetchState.SUCCESS
            }
        } catch (error: Throwable) {
            @Suppress("UNCHECKED_CAST")
            errorFlow.value = error as TError?
            fetchStateFlow.value = FetchState.ERROR
            onError?.invoke(error)
        }
    }

    suspend fun fetchPreviousPage(
        onSuccess: (suspend (pageData: PagedData<TPageParam?, TData>?) -> Unit)? = null,
        onError: (suspend (TError) -> Unit)? = null,
    ) = withContext(dispatcher) {
        val prevPageParam =
            when (val paramResult = calculatePreviousPageParam()) {
                is PageParam.None -> return@withContext
                is PageParam.Value -> paramResult.value
            }
        fetchStateFlow.value = FetchState.LOADING
        errorFlow.value = null
        try {
            launchQuery(prevPageParam).also {
                val newPageData = PagedData(prevPageParam, it)
                val existing = cache.get<List<PagedData<TPageParam?, TData>>>(cacheKey).value ?: emptyList()
                cache.setMaybeWithTtl(cacheKey, listOf(newPageData) + existing, ttl)
                onSuccess?.invoke(newPageData)
                fetchStateFlow.value = FetchState.SUCCESS
            }
        } catch (error: Throwable) {
            @Suppress("UNCHECKED_CAST")
            errorFlow.value = error as TError?
            fetchStateFlow.value = FetchState.ERROR
            onError?.invoke(error)
        }
    }

    operator fun component1(): StateFlow<CacheableInfiniteQueryResult<TPageParam, TData, TError>> = result

    private suspend fun calculateNextPageParam(): PageParam<TPageParam?> =
        cache.getOrNull<List<PagedData<TPageParam?, TData>>>(cacheKey)?.value?.let {
            getNextPageParam(it)
        } ?: PageParam.Value(initialPageParam)

    private suspend fun calculatePreviousPageParam(): PageParam<TPageParam?> {
        val getPrev = getPreviousPageParam ?: return PageParam.None
        return cache.getOrNull<List<PagedData<TPageParam?, TData>>>(cacheKey)?.value?.let {
            getPrev(it)
        } ?: PageParam.Value(initialPageParam)
    }

    suspend fun fetchPage(
        page: TPageParam,
        onSuccess: (suspend (pageData: PagedData<TPageParam?, TData>?) -> Unit)? = null,
        onError: (suspend (TError) -> Unit)? = null,
    ) = withContext(dispatcher) {
        fetchStateFlow.value = FetchState.LOADING
        errorFlow.value = null
        try {
            launchQuery(page).also {
                val newPageData = PagedData<TPageParam?, TData>(page, it)
                val existing = cache.get<List<PagedData<TPageParam?, TData>>>(cacheKey).value ?: emptyList()
                val updated =
                    existing
                        .map { entry ->
                            if (entry.page == page) newPageData else entry
                        }.let { mapped ->
                            if (mapped.none { entry -> entry.page == page }) mapped + newPageData else mapped
                        }
                cache.setMaybeWithTtl(cacheKey, updated, ttl)
                onSuccess?.invoke(newPageData)
                fetchStateFlow.value = FetchState.SUCCESS
            }
        } catch (error: Throwable) {
            @Suppress("UNCHECKED_CAST")
            errorFlow.value = error as TError?
            fetchStateFlow.value = FetchState.ERROR
            @Suppress("UNCHECKED_CAST")
            onError?.invoke(error as TError)
        }
        Unit
    }
}

class InfiniteQueryFactoryWithInput<TInput : QueryInput, TPageParam, TData, TError : Throwable>(
    private val cache: OnHandCache,
    private val initialPageParam: TPageParam? = null,
    private val getNextPageParam: suspend (pages: List<PagedData<TPageParam?, TData>>) -> PageParam<TPageParam>,
    private val getPreviousPageParam: (suspend (pages: List<PagedData<TPageParam?, TData>>) -> PageParam<TPageParam>)? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val ttl: kotlin.time.Duration? = null,
    private val dedupingInterval: kotlin.time.Duration? = 2.seconds,
    internal val timeSource: TimeSource = TimeSource.Monotonic,
    private val query: suspend (input: TInput, page: TPageParam?) -> TData?,
) : RefetchableFactoryWithInput<TInput> {
    private val inFlight = mutableMapOf<Pair<TInput, TPageParam?>, Pair<CompletableDeferred<TData?>, TimeMark>>()
    private val inFlightLock = Mutex()

    internal suspend fun dedupedQuery(input: TInput, page: TPageParam?): TData? {
        val key = Pair(input, page)
        val (deferred, isNew) = inFlightLock.withLock {
            val existing = inFlight[key]
            val interval = dedupingInterval
            if (existing != null && (interval == null || existing.second.elapsedNow() <= interval)) {
                Pair(existing.first, false)
            } else {
                val completable = CompletableDeferred<TData?>()
                inFlight[key] = Pair(completable, timeSource.markNow())
                Pair(completable, true)
            }
        }
        if (isNew) {
            try {
                deferred.complete(query(input, page))
            } catch (e: Throwable) {
                deferred.completeExceptionally(e)
            } finally {
                inFlightLock.withLock {
                    if (inFlight[key]?.first === deferred) inFlight.remove(key)
                }
            }
        }
        return deferred.await()
    }

    fun create(
        startingCacheKey: TInput? = null,
        coroutineScope: CoroutineScope,
        initialFetchState: FetchState = FetchState.IDLE,
    ): CacheableInfiniteQueryWithInput<TInput, TPageParam, TData, TError> =
        CacheableInfiniteQueryWithInput(
            coroutineScope = coroutineScope,
            launchQuery = ::dedupedQuery,
            initialPageParam = initialPageParam,
            getNextPageParam = getNextPageParam,
            getPreviousPageParam = getPreviousPageParam,
            cache = cache,
            dispatcher = dispatcher,
            startingCacheKey = startingCacheKey,
            ttl = ttl,
            initialFetchState = initialFetchState,
        )

    operator fun invoke(
        startingCacheKey: TInput? = null,
        coroutineScope: CoroutineScope,
        initialFetchState: FetchState = FetchState.IDLE,
    ): CacheableInfiniteQueryWithInput<TInput, TPageParam, TData, TError> = create(startingCacheKey, coroutineScope, initialFetchState)

    fun optimisticUpdater(
        input: TInput,
        updater: (currentValue: List<PagedData<TPageParam?, TData>>?) -> List<PagedData<TPageParam?, TData>>,
    ): Map<TInput, List<PagedData<TPageParam?, TData>>?> {
        val currentValue = cache.getOrNull<List<PagedData<TPageParam?, TData>>>(input)?.value
        return mapOf(input to updater(currentValue))
    }

    override suspend fun refetch(input: TInput) =
        coroutineScope {
            withContext(dispatcher) {
                val currentValue = cache.getOrNull<List<PagedData<TPageParam?, TData>>>(input)?.value
                val fetchCalls =
                    currentValue
                        ?.map {
                            async {
                                runCatching { dedupedQuery(input, it.page) }
                            }
                        }?.awaitAll()
                val updatedValue =
                    fetchCalls?.mapIndexed { index, newValue ->
                        val currentValAtIdx = currentValue[index]
                        if (newValue.isSuccess) {
                            PagedData(currentValAtIdx.page, newValue.getOrThrow())
                        } else {
                            currentValAtIdx
                        }
                    }
                cache[input] = updatedValue
            }
        }
}

class InfiniteQueryFactoryWithNoInput<TPageParam, TData, TError : Throwable>(
    private val cache: OnHandCache,
    private val cacheKey: QueryInput,
    private val initialPageParam: TPageParam? = null,
    private val getNextPageParam: suspend (pages: List<PagedData<TPageParam?, TData>>) -> PageParam<TPageParam>,
    private val getPreviousPageParam: (suspend (pages: List<PagedData<TPageParam?, TData>>) -> PageParam<TPageParam>)? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val ttl: kotlin.time.Duration? = null,
    private val dedupingInterval: kotlin.time.Duration? = 2.seconds,
    internal val timeSource: TimeSource = TimeSource.Monotonic,
    private val query: suspend (page: TPageParam?) -> TData?,
) : RefetchableFactoryWithNoInput {
    private val inFlight = mutableMapOf<TPageParam?, Pair<CompletableDeferred<TData?>, TimeMark>>()
    private val inFlightLock = Mutex()

    internal suspend fun dedupedQuery(page: TPageParam?): TData? {
        val (deferred, isNew) = inFlightLock.withLock {
            val existing = inFlight[page]
            val interval = dedupingInterval
            if (existing != null && (interval == null || existing.second.elapsedNow() <= interval)) {
                Pair(existing.first, false)
            } else {
                val completable = CompletableDeferred<TData?>()
                inFlight[page] = Pair(completable, timeSource.markNow())
                Pair(completable, true)
            }
        }
        if (isNew) {
            try {
                deferred.complete(query(page))
            } catch (e: Throwable) {
                deferred.completeExceptionally(e)
            } finally {
                inFlightLock.withLock {
                    if (inFlight[page]?.first === deferred) inFlight.remove(page)
                }
            }
        }
        return deferred.await()
    }

    fun create(
        coroutineScope: CoroutineScope,
        initialFetchState: FetchState = FetchState.IDLE,
    ): CacheableInfiniteQueryWithNoInput<TPageParam, TData, TError> =
        CacheableInfiniteQueryWithNoInput(
            coroutineScope = coroutineScope,
            launchQuery = ::dedupedQuery,
            initialPageParam = initialPageParam,
            getNextPageParam = getNextPageParam,
            getPreviousPageParam = getPreviousPageParam,
            cacheKey = cacheKey,
            cache = cache,
            dispatcher = dispatcher,
            ttl = ttl,
            initialFetchState = initialFetchState,
        )

    operator fun invoke(
        coroutineScope: CoroutineScope,
        initialFetchState: FetchState = FetchState.IDLE,
    ): CacheableInfiniteQueryWithNoInput<TPageParam, TData, TError> = create(coroutineScope, initialFetchState)

    fun optimisticUpdater(
        updater: (currentValue: List<PagedData<TPageParam?, TData>>?) -> List<PagedData<TPageParam?, TData>>,
    ): Map<QueryInput, List<PagedData<TPageParam?, TData>>?> {
        val currentValue = cache.getOrNull<List<PagedData<TPageParam?, TData>>>(cacheKey)?.value
        return mapOf(cacheKey to updater(currentValue))
    }

    override suspend fun refetch() =
        coroutineScope {
            withContext(dispatcher) {
                val currentValue = cache.getOrNull<List<PagedData<TPageParam?, TData>>>(cacheKey)?.value
                val fetchCalls =
                    currentValue
                        ?.map {
                            async {
                                runCatching { dedupedQuery(it.page) }
                            }
                        }?.awaitAll()
                val updatedValue =
                    fetchCalls?.mapIndexed { index, newValue ->
                        val currentValAtIdx = currentValue[index]
                        if (newValue.isSuccess) {
                            PagedData(currentValAtIdx.page, newValue.getOrThrow())
                        } else {
                            currentValAtIdx
                        }
                    }
                cache[cacheKey] = updatedValue
            }
        }
}

fun <TInput : QueryInput, TPageParam, TData, TError : Throwable> infiniteQueryFactoryOf(
    cache: OnHandCache,
    initialPageParam: TPageParam? = null,
    getNextPageParam: suspend (pages: List<PagedData<TPageParam?, TData>>) -> PageParam<TPageParam>,
    getPreviousPageParam: (suspend (pages: List<PagedData<TPageParam?, TData>>) -> PageParam<TPageParam>)? = null,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    ttl: kotlin.time.Duration? = null,
    dedupingInterval: kotlin.time.Duration? = 2.seconds,
    query: suspend (input: TInput, page: TPageParam?) -> TData?,
): InfiniteQueryFactoryWithInput<TInput, TPageParam, TData, TError> =
    InfiniteQueryFactoryWithInput(
        cache = cache,
        initialPageParam = initialPageParam,
        getNextPageParam = getNextPageParam,
        getPreviousPageParam = getPreviousPageParam,
        dispatcher = dispatcher,
        ttl = ttl,
        dedupingInterval = dedupingInterval,
        query = query,
    )

fun <TPageParam, TData, TError : Throwable> infiniteQueryFactoryOf(
    cache: OnHandCache,
    cacheKey: QueryInput,
    initialPageParam: TPageParam? = null,
    getNextPageParam: suspend (pages: List<PagedData<TPageParam?, TData>>) -> PageParam<TPageParam>,
    getPreviousPageParam: (suspend (pages: List<PagedData<TPageParam?, TData>>) -> PageParam<TPageParam>)? = null,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    ttl: kotlin.time.Duration? = null,
    dedupingInterval: kotlin.time.Duration? = 2.seconds,
    query: suspend (page: TPageParam?) -> TData?,
): InfiniteQueryFactoryWithNoInput<TPageParam, TData, TError> =
    InfiniteQueryFactoryWithNoInput(
        cache = cache,
        cacheKey = cacheKey,
        initialPageParam = initialPageParam,
        getNextPageParam = getNextPageParam,
        getPreviousPageParam = getPreviousPageParam,
        dispatcher = dispatcher,
        ttl = ttl,
        dedupingInterval = dedupingInterval,
        query = query,
    )
