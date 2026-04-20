@file:Suppress("UNCHECKED_CAST")

package io.github.notoriouscorgi.cacheonhand.operations

import io.github.notoriouscorgi.cacheonhand.CacheableInput.QueryInput
import io.github.notoriouscorgi.cacheonhand.OnHandCache
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlin.time.Duration

data class CacheableQueryResult<TData, TError : Throwable>(
    override val fetchState: FetchState,
    override val error: TError?,
    override val data: TData?,
) : CacheableResultWithData<TData, TError>

@OptIn(ExperimentalCoroutinesApi::class)
class CacheableQueryWithInput<TInput : QueryInput, TData, TError : Throwable>(
    private val coroutineScope: CoroutineScope,
    private val launchQuery: suspend (input: TInput) -> TData?,
    startingCacheKey: TInput? = null,
    private val cache: OnHandCache,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val ttl: Duration? = null,
    initialFetchState: FetchState = FetchState.IDLE,
) {
    private val activeKey = MutableStateFlow(startingCacheKey)
    private val fetchStateFlow = MutableStateFlow(initialFetchState)
    private val errorFlow = MutableStateFlow<TError?>(null)

    val result: StateFlow<CacheableQueryResult<TData, TError>> =
        combine(
            activeKey.flatMapLatest { key ->
                if (key == null) flowOf<TData?>(null) else cache.observe(key)
            },
            fetchStateFlow,
            errorFlow,
        ) { data, state, err ->
            CacheableQueryResult(fetchState = state, error = err, data = data)
        }.stateIn(
            scope = coroutineScope,
            started = SharingStarted.Eagerly,
            initialValue =
                CacheableQueryResult(
                    fetchState = initialFetchState,
                    error = null,
                    data = startingCacheKey?.let { cache.getOrNull<TData>(it)?.value },
                ),
        )

    suspend fun fetch(
        queryInput: TInput,
        onSuccess: (suspend (TData) -> Unit)? = null,
        onError: (suspend (TError) -> Unit)? = null,
    ) {
        activeKey.value = queryInput
        withContext(dispatcher) {
            fetchStateFlow.value = FetchState.LOADING
            errorFlow.value = null
            try {
                launchQuery(queryInput).also {
                    cache.setMaybeWithTtl(queryInput, it, ttl)
                    onSuccess?.invoke(it as TData)
                }
                fetchStateFlow.value = FetchState.SUCCESS
            } catch (error: Throwable) {
                errorFlow.value = error as TError?
                fetchStateFlow.value = FetchState.ERROR
                onError?.invoke(error)
            }
        }
    }

    operator fun component1(): StateFlow<CacheableQueryResult<TData, TError>> = result
}

class CacheableQueryWithNoInput<TData, TError : Throwable>(
    private val coroutineScope: CoroutineScope,
    private val launchQuery: suspend () -> TData?,
    val cacheKey: QueryInput,
    private val cache: OnHandCache,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val ttl: Duration? = null,
    initialFetchState: FetchState = FetchState.IDLE,
) {
    private val fetchStateFlow = MutableStateFlow(initialFetchState)
    private val errorFlow = MutableStateFlow<TError?>(null)

    val result: StateFlow<CacheableQueryResult<TData, TError>> =
        combine(
            cache.observe<TData>(cacheKey),
            fetchStateFlow,
            errorFlow,
        ) { data, state, err ->
            CacheableQueryResult(fetchState = state, error = err, data = data)
        }.stateIn(
            scope = coroutineScope,
            started = SharingStarted.Eagerly,
            initialValue =
                CacheableQueryResult(
                    fetchState = initialFetchState,
                    error = null,
                    data = cache.getOrNull<TData>(cacheKey)?.value,
                ),
        )

    suspend fun fetch(
        onSuccess: (suspend (TData) -> Unit)? = null,
        onError: (suspend (TError) -> Unit)? = null,
    ) {
        withContext(dispatcher) {
            fetchStateFlow.value = FetchState.LOADING
            errorFlow.value = null
            try {
                launchQuery().also {
                    cache.setMaybeWithTtl(cacheKey, it, ttl)
                    onSuccess?.invoke(it as TData)
                }
                fetchStateFlow.value = FetchState.SUCCESS
            } catch (error: Throwable) {
                errorFlow.value = error as TError?
                fetchStateFlow.value = FetchState.ERROR
                onError?.invoke(error)
            }
        }
    }

    operator fun component1(): StateFlow<CacheableQueryResult<TData, TError>> = result
}

/**
 * Factory for creating [CacheableQueryWithInput] instances.
 *
 * The [query] lambda runs on [Dispatchers.Default]. If your query performs IO (network, disk),
 * use `withContext(Dispatchers.IO)` inside your lambda. Do **not** use `launch` or `async`
 * inside the lambda without awaiting — the fetch must complete sequentially so that state
 * transitions (LOADING → SUCCESS/ERROR) are correct.
 */
class QueryFactoryWithInput<TInput : QueryInput, TData, TError : Throwable>(
    private val cache: OnHandCache,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val ttl: Duration? = null,
    private val query: suspend (input: TInput) -> TData,
) {
    fun create(
        startingCacheKey: TInput? = null,
        coroutineScope: CoroutineScope,
        initialFetchState: FetchState = FetchState.IDLE,
    ): CacheableQueryWithInput<TInput, TData, TError> =
        CacheableQueryWithInput(
            coroutineScope = coroutineScope,
            launchQuery = query,
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
    ): CacheableQueryWithInput<TInput, TData, TError> = create(startingCacheKey, coroutineScope, initialFetchState)

    suspend fun refetch(input: TInput) {
        withContext(dispatcher) {
            cache.setMaybeWithTtl(input, query(input), ttl)
        }
    }

    fun optimisticUpdater(
        input: TInput,
        updater: (currentValue: TData?) -> TData,
    ): Map<TInput, TData?> {
        val currentValue = cache.getOrNull<TData?>(input)?.value
        return mapOf(input to updater(currentValue))
    }
}

/**
 * Factory for creating [CacheableQueryWithNoInput] instances.
 *
 * The [query] lambda runs on [Dispatchers.Default]. If your query performs IO (network, disk),
 * use `withContext(Dispatchers.IO)` inside your lambda. Do **not** use `launch` or `async`
 * inside the lambda without awaiting — the fetch must complete sequentially so that state
 * transitions (LOADING → SUCCESS/ERROR) are correct.
 */
class QueryFactoryWithNoInput<TData, TError : Throwable>(
    private val cache: OnHandCache,
    private val cacheKey: QueryInput,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val ttl: Duration? = null,
    private val query: suspend () -> TData,
) {
    fun create(
        coroutineScope: CoroutineScope,
        initialFetchState: FetchState = FetchState.IDLE,
    ): CacheableQueryWithNoInput<TData, TError> =
        CacheableQueryWithNoInput(
            coroutineScope = coroutineScope,
            launchQuery = query,
            cacheKey = cacheKey,
            cache = cache,
            dispatcher = dispatcher,
            ttl = ttl,
            initialFetchState = initialFetchState,
        )

    operator fun invoke(
        coroutineScope: CoroutineScope,
        initialFetchState: FetchState = FetchState.IDLE,
    ): CacheableQueryWithNoInput<TData, TError> = create(coroutineScope, initialFetchState)

    suspend fun refetch() {
        withContext(dispatcher) {
            cache.setMaybeWithTtl(cacheKey, query(), ttl)
        }
    }
}

/**
 * Creates a [QueryFactoryWithInput].
 *
 * @param dispatcher The dispatcher operations run on. Defaults to [Dispatchers.Default].
 *   Generally should not need to be changed — use `withContext(Dispatchers.IO)` inside
 *   your [query] lambda for IO-bound work instead.
 */
fun <TInput : QueryInput, TData, TError : Throwable> queryFactoryOf(
    cache: OnHandCache,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    ttl: Duration? = null,
    query: suspend (input: TInput) -> TData,
): QueryFactoryWithInput<TInput, TData, TError> = QueryFactoryWithInput(cache = cache, dispatcher = dispatcher, ttl = ttl, query = query)

/**
 * Creates a [QueryFactoryWithNoInput].
 *
 * @param dispatcher The dispatcher operations run on. Defaults to [Dispatchers.Default].
 *   Generally should not need to be changed — use `withContext(Dispatchers.IO)` inside
 *   your [query] lambda for IO-bound work instead.
 */
fun <TInput : QueryInput, TData, TError : Throwable> queryFactoryOf(
    cache: OnHandCache,
    cacheKey: TInput,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    ttl: Duration? = null,
    query: suspend () -> TData,
): QueryFactoryWithNoInput<TData, TError> =
    QueryFactoryWithNoInput(cache = cache, cacheKey = cacheKey, dispatcher = dispatcher, ttl = ttl, query = query)
