package io.github.notoriouscorgi.cacheonhand.operations

import io.github.notoriouscorgi.cacheonhand.CacheableInput.FlowInput
import io.github.notoriouscorgi.cacheonhand.OnHandCache
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/**
 * Cacheable flow operations for subscribing to reactive data sources (SSE, WebSockets, etc.)
 * and writing each emission to the cache.
 *
 * By default, each emission overwrites the previous cached value. If you need to preserve
 * previous emissions (e.g., accumulating a chat history or event log), use [scan] or
 * [runningFold][kotlinx.coroutines.flow.runningFold] on your flow before passing it to the factory:
 *
 * ```
 * flowFactoryOf<MyInput, List<ChatMessage>, Exception>(cache = cache) { input ->
 *     chatWebSocket(input.roomId)
 *         .scan(emptyList()) { acc, message -> acc + message }
 * }
 * ```
 *
 * The cache will then store the full accumulated list as the value for that key. Any other
 * subscriber reading the same cache key (e.g., a different composable) will see the complete
 * accumulated history.
 *
 * To cap the history size, use [takeLast][List.takeLast]:
 *
 * ```
 * .scan(emptyList()) { acc, message -> (acc + message).takeLast(100) }
 * ```
 */

data class CacheableFlowResult<TData, TError : Throwable>(
    override val fetchState: FetchState,
    override val error: TError?,
    override val data: TData?,
) : CacheableResultWithData<TData, TError>

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("UNCHECKED_CAST")
class CacheableFlowWithInput<TInput : FlowInput, TData, TError : Throwable>(
    private val coroutineScope: CoroutineScope,
    private val launchFlow: (input: TInput) -> Flow<TData?>,
    startingCacheKey: TInput? = null,
    private val cache: OnHandCache,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val ttl: kotlin.time.Duration? = null,
    initialFetchState: FetchState = FetchState.IDLE,
) {
    private val activeKey = MutableStateFlow(startingCacheKey)
    private val fetchStateFlow = MutableStateFlow(initialFetchState)
    private val errorFlow = MutableStateFlow<TError?>(null)

    val result: StateFlow<CacheableFlowResult<TData, TError>> =
        combine(
            activeKey.flatMapLatest { key ->
                if (key == null) flowOf<TData?>(null) else cache.observe<TData>(key)
            },
            fetchStateFlow,
            errorFlow,
        ) { data, state, err ->
            CacheableFlowResult(fetchState = state, error = err, data = data)
        }.stateIn(
            scope = coroutineScope,
            started = SharingStarted.Eagerly,
            initialValue =
                CacheableFlowResult(
                    fetchState = initialFetchState,
                    error = null,
                    data = startingCacheKey?.let { cache.getOrNull<TData>(it)?.value },
                ),
        )

    suspend fun launch(
        queryInput: TInput,
        onEachSuccess: (suspend (TData) -> Unit)? = null,
        onError: (suspend (TError) -> Unit)? = null,
    ) {
        activeKey.value = queryInput
        withContext(dispatcher) {
            fetchStateFlow.value = FetchState.LOADING
            errorFlow.value = null
            launchFlow
                .invoke(queryInput)
                .catch {
                    errorFlow.value = it as TError?
                    fetchStateFlow.value = FetchState.ERROR
                    @Suppress("UNCHECKED_CAST")
                    onError?.invoke(it as TError)
                }.collect {
                    cache.setMaybeWithTtl(queryInput, it, ttl)
                    fetchStateFlow.value = FetchState.SUCCESS
                    onEachSuccess?.invoke(it as TData)
                }
        }
    }

    operator fun component1(): StateFlow<CacheableFlowResult<TData, TError>> = result
}

@Suppress("UNCHECKED_CAST")
class CacheableFlowWithNoInput<TData, TError : Throwable>(
    private val coroutineScope: CoroutineScope,
    private val launchFlow: () -> Flow<TData?>,
    val cacheKey: FlowInput,
    private val cache: OnHandCache,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val ttl: kotlin.time.Duration? = null,
    initialFetchState: FetchState = FetchState.IDLE,
) {
    private val fetchStateFlow = MutableStateFlow(initialFetchState)
    private val errorFlow = MutableStateFlow<TError?>(null)

    val result: StateFlow<CacheableFlowResult<TData, TError>> =
        combine(
            cache.observe<TData>(cacheKey),
            fetchStateFlow,
            errorFlow,
        ) { data, state, err ->
            CacheableFlowResult(fetchState = state, error = err, data = data)
        }.stateIn(
            scope = coroutineScope,
            started = SharingStarted.Eagerly,
            initialValue =
                CacheableFlowResult(
                    fetchState = initialFetchState,
                    error = null,
                    data = cache.getOrNull<TData>(cacheKey)?.value,
                ),
        )

    suspend fun launch(
        onEachSuccess: (suspend (TData) -> Unit)? = null,
        onError: (suspend (TError) -> Unit)? = null,
    ) {
        withContext(dispatcher) {
            fetchStateFlow.value = FetchState.LOADING
            errorFlow.value = null
            launchFlow
                .invoke()
                .catch {
                    errorFlow.value = it as TError?
                    fetchStateFlow.value = FetchState.ERROR
                    @Suppress("UNCHECKED_CAST")
                    onError?.invoke(it as TError)
                }.collect {
                    cache.setMaybeWithTtl(cacheKey, it, ttl)
                    fetchStateFlow.value = FetchState.SUCCESS
                    onEachSuccess?.invoke(it as TData)
                }
        }
    }

    operator fun component1(): StateFlow<CacheableFlowResult<TData, TError>> = result
}

/**
 * Factory for creating [CacheableFlowWithInput] instances.
 *
 * The [flow] lambda runs on [Dispatchers.Default]. Do **not** use `launch` or `async`
 * inside the lambda without awaiting — the flow collection must complete sequentially
 * so that state transitions (LOADING → SUCCESS/ERROR) are correct.
 */
class FlowFactoryWithInput<TInput : FlowInput, TData, TError : Throwable>(
    private val cache: OnHandCache,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val ttl: kotlin.time.Duration? = null,
    private val flow: (input: TInput) -> Flow<TData>,
) {
    fun create(
        startingCacheKey: TInput? = null,
        coroutineScope: CoroutineScope,
        initialFetchState: FetchState = FetchState.IDLE,
    ): CacheableFlowWithInput<TInput, TData, TError> =
        CacheableFlowWithInput(
            coroutineScope = coroutineScope,
            launchFlow = flow,
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
    ): CacheableFlowWithInput<TInput, TData, TError> = create(startingCacheKey, coroutineScope, initialFetchState)

    fun optimisticUpdater(
        input: TInput,
        updater: (currentValue: TData?) -> TData,
    ): Map<TInput, TData?> {
        val currentValue = cache.getOrNull<TData?>(input)?.value
        return mapOf(input to updater(currentValue))
    }
}

/**
 * Factory for creating [CacheableFlowWithNoInput] instances.
 *
 * The [flow] lambda runs on [Dispatchers.Default]. Do **not** use `launch` or `async`
 * inside the lambda without awaiting — the flow collection must complete sequentially
 * so that state transitions (LOADING → SUCCESS/ERROR) are correct.
 */
class FlowFactoryWithNoInput<TData, TError : Throwable>(
    private val cache: OnHandCache,
    private val cacheKey: FlowInput,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val ttl: kotlin.time.Duration? = null,
    private val flow: () -> Flow<TData>,
) {
    fun create(
        coroutineScope: CoroutineScope,
        initialFetchState: FetchState = FetchState.IDLE,
    ): CacheableFlowWithNoInput<TData, TError> =
        CacheableFlowWithNoInput(
            coroutineScope = coroutineScope,
            launchFlow = flow,
            cacheKey = cacheKey,
            cache = cache,
            dispatcher = dispatcher,
            ttl = ttl,
            initialFetchState = initialFetchState,
        )

    operator fun invoke(
        coroutineScope: CoroutineScope,
        initialFetchState: FetchState = FetchState.IDLE,
    ): CacheableFlowWithNoInput<TData, TError> = create(coroutineScope, initialFetchState)

    fun optimisticUpdater(updater: (currentValue: TData?) -> TData): Map<FlowInput, TData?> {
        val currentValue = cache.getOrNull<TData?>(cacheKey)?.value
        return mapOf(cacheKey to updater(currentValue))
    }
}

fun <TInput : FlowInput, TData, TError : Throwable> flowFactoryOf(
    cache: OnHandCache,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    ttl: kotlin.time.Duration? = null,
    flow: (input: TInput) -> Flow<TData>,
): FlowFactoryWithInput<TInput, TData, TError> = FlowFactoryWithInput(cache = cache, dispatcher = dispatcher, ttl = ttl, flow = flow)

fun <TInput : FlowInput, TData, TError : Throwable> flowFactoryOf(
    cache: OnHandCache,
    cacheKey: TInput,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    ttl: kotlin.time.Duration? = null,
    flow: () -> Flow<TData>,
): FlowFactoryWithNoInput<TData, TError> =
    FlowFactoryWithNoInput(cache = cache, cacheKey = cacheKey, dispatcher = dispatcher, ttl = ttl, flow = flow)
