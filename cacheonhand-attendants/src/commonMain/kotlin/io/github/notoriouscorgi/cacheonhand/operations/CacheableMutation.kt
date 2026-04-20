@file:Suppress("UNCHECKED_CAST")

package io.github.notoriouscorgi.cacheonhand.operations

import io.github.notoriouscorgi.cacheonhand.CacheableInput
import io.github.notoriouscorgi.cacheonhand.CacheableInput.MutationInput
import io.github.notoriouscorgi.cacheonhand.OnHandCache
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

data class CacheableFireAndForgetMutationResult<TError : Throwable>(
    override val fetchState: FetchState,
    override val error: TError?,
) : CacheableResult<TError>

data class CacheableMutationResult<TData, TError : Throwable>(
    override val fetchState: FetchState,
    override val error: TError?,
    override val data: TData?,
) : CacheableResultWithData<TData, TError>

class CacheableMutationWithInput<TInput : MutationInput, TData, TError : Throwable>(
    private val launchMutation: suspend (input: TInput) -> TData?,
    private val cache: OnHandCache,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val _result: MutableStateFlow<CacheableMutationResult<TData, TError>> =
        MutableStateFlow(
            CacheableMutationResult(
                fetchState = FetchState.IDLE,
                data = null,
                error = null,
            ),
        )
    val result: StateFlow<CacheableMutationResult<TData, TError>> = _result

    suspend fun mutate(
        queryInput: TInput,
        optimisticUpdate: ((input: TInput) -> Map<out CacheableInput, Any?>)? = null,
        onSuccess: (suspend (TData) -> Unit)? = null,
        onError: (suspend (TError) -> Unit)? = null,
    ) = withContext(dispatcher) {
        val execute =
            suspend {
                _result.value = result.value.copy(fetchState = FetchState.LOADING)
                try {
                    _result.value =
                        result.value.copy(
                            fetchState = FetchState.SUCCESS,
                            data =
                                launchMutation(queryInput).also {
                                    onSuccess?.invoke(it as TData)
                                },
                        )
                } catch (error: Throwable) {
                    _result.value = result.value.copy(error = error as TError?, fetchState = FetchState.ERROR)
                    onError?.invoke(error)
                    throw error
                }
            }

        optimisticUpdate?.let {
            cache.updateWithRollback(updates = it(queryInput), action = execute)
        } ?: run {
            try {
                execute.invoke()
            } catch (error: Throwable) {
                // NO OP, already caught
            }
        }
        Unit
    }

    operator fun component1(): StateFlow<CacheableMutationResult<TData, TError>> = result
}

class CacheableMutationWithNoInput<TData, TError : Throwable>(
    private val launchMutation: suspend () -> TData?,
    private val cache: OnHandCache,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val _result: MutableStateFlow<CacheableMutationResult<TData, TError>> =
        MutableStateFlow(
            CacheableMutationResult(
                fetchState = FetchState.IDLE,
                data = null,
                error = null,
            ),
        )
    val result: StateFlow<CacheableMutationResult<TData, TError>> = _result

    suspend fun mutate(
        optimisticUpdate: (() -> Map<out CacheableInput, Any?>)? = null,
        onSuccess: (suspend (TData) -> Unit)? = null,
        onError: (suspend (TError) -> Unit)? = null,
    ) = withContext(dispatcher) {
        val execute =
            suspend {
                _result.value = result.value.copy(fetchState = FetchState.LOADING)
                try {
                    _result.value =
                        result.value.copy(
                            fetchState = FetchState.SUCCESS,
                            data =
                                launchMutation().also {
                                    onSuccess?.invoke(it as TData)
                                },
                        )
                } catch (error: Throwable) {
                    _result.value = result.value.copy(error = error as TError?, fetchState = FetchState.ERROR)
                    onError?.invoke(error)
                    throw error
                }
            }

        optimisticUpdate?.let {
            cache.updateWithRollback(updates = it(), action = execute)
        } ?: run {
            try {
                execute.invoke()
            } catch (error: Throwable) {
                // NO OP, already caught
            }
        }
        Unit
    }

    operator fun component1(): StateFlow<CacheableMutationResult<TData, TError>> = result
}

class CacheableFireAndForgetMutationWithInput<TInput : MutationInput, TError : Throwable>(
    private val howTo: suspend (input: TInput) -> Unit,
    private val cache: OnHandCache,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val _result: MutableStateFlow<CacheableFireAndForgetMutationResult<TError>> =
        MutableStateFlow(
            CacheableFireAndForgetMutationResult(
                fetchState = FetchState.IDLE,
                error = null,
            ),
        )
    val result: StateFlow<CacheableFireAndForgetMutationResult<TError>> = _result

    suspend fun mutate(
        queryInput: TInput,
        optimisticUpdate: ((input: TInput) -> Map<out CacheableInput, Any?>)? = null,
        onSuccess: (suspend () -> Unit)? = null,
        onError: (suspend (error: TError) -> Unit)? = null,
    ) = withContext(dispatcher) {
        val execute =
            suspend {
                _result.value = _result.value.copy(fetchState = FetchState.LOADING)
                try {
                    howTo(queryInput)
                    _result.value = _result.value.copy(fetchState = FetchState.SUCCESS)
                    onSuccess?.invoke()
                    Unit
                } catch (error: Throwable) {
                    _result.value = _result.value.copy(error = error as TError?, fetchState = FetchState.ERROR)
                    onError?.invoke(error)
                    throw error
                }
            }

        optimisticUpdate?.let {
            cache.updateWithRollback(
                updates = it(queryInput),
                action = execute,
            )
        } ?: run {
            try {
                execute.invoke()
            } catch (_: Throwable) {
                // NO OP, already caught
            }
        }
        Unit
    }

    operator fun component1(): StateFlow<CacheableFireAndForgetMutationResult<TError>> = result
}

class CacheableFireAndForgetMutationWithNoInput<TError : Throwable>(
    private val howTo: suspend () -> Unit,
    private val cache: OnHandCache,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val _result: MutableStateFlow<CacheableFireAndForgetMutationResult<TError>> =
        MutableStateFlow(
            CacheableFireAndForgetMutationResult(
                fetchState = FetchState.IDLE,
                error = null,
            ),
        )
    val result: StateFlow<CacheableFireAndForgetMutationResult<TError>> = _result

    suspend fun mutate(
        optimisticUpdate: (() -> Map<out CacheableInput, Any?>)? = null,
        onSuccess: (suspend () -> Unit)? = null,
        onError: (suspend (error: TError) -> Unit)? = null,
    ) = withContext(dispatcher) {
        val execute =
            suspend {
                _result.value = _result.value.copy(fetchState = FetchState.LOADING)
                try {
                    howTo()
                    _result.value = _result.value.copy(fetchState = FetchState.SUCCESS)
                    onSuccess?.invoke()
                    Unit
                } catch (error: Throwable) {
                    _result.value = _result.value.copy(error = error as TError?, fetchState = FetchState.ERROR)
                    onError?.invoke(error)
                    throw error
                }
            }

        optimisticUpdate?.let {
            cache.updateWithRollback(updates = it(), action = execute)
        } ?: run {
            try {
                execute.invoke()
            } catch (_: Throwable) {
                // NO OP, already caught
            }
        }
        Unit
    }

    operator fun component1(): StateFlow<CacheableFireAndForgetMutationResult<TError>> = result
}

class MutationFactoryWithInput<TInput : MutationInput, TData, TError : Throwable>(
    private val cache: OnHandCache,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val mutation: suspend (input: TInput) -> TData,
) {
    fun create(): CacheableMutationWithInput<TInput, TData, TError> =
        CacheableMutationWithInput(launchMutation = mutation, cache = cache, dispatcher = dispatcher)

    operator fun invoke(): CacheableMutationWithInput<TInput, TData, TError> = create()
}

class MutationFactoryWithNoInput<TData, TError : Throwable>(
    private val cache: OnHandCache,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val mutation: suspend () -> TData,
) {
    fun create(): CacheableMutationWithNoInput<TData, TError> =
        CacheableMutationWithNoInput(cache = cache, launchMutation = mutation, dispatcher = dispatcher)

    operator fun invoke(): CacheableMutationWithNoInput<TData, TError> = create()
}

class FireAndForgetMutationFactoryWithInput<TInput : MutationInput, TError : Throwable>(
    private val cache: OnHandCache,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val mutation: suspend (input: TInput) -> Unit,
) {
    fun create(): CacheableFireAndForgetMutationWithInput<TInput, TError> =
        CacheableFireAndForgetMutationWithInput(howTo = mutation, cache = cache, dispatcher = dispatcher)

    operator fun invoke(): CacheableFireAndForgetMutationWithInput<TInput, TError> = create()
}

class FireAndForgetMutationFactoryWithNoInput<TError : Throwable>(
    private val cache: OnHandCache,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val mutation: suspend () -> Unit,
) {
    fun create(): CacheableFireAndForgetMutationWithNoInput<TError> =
        CacheableFireAndForgetMutationWithNoInput(cache = cache, howTo = mutation, dispatcher = dispatcher)

    operator fun invoke(): CacheableFireAndForgetMutationWithNoInput<TError> = create()
}

fun <TInput : MutationInput, TData, TError : Throwable> mutationFactoryOf(
    cache: OnHandCache,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    query: suspend (input: TInput) -> TData,
): MutationFactoryWithInput<TInput, TData, TError> = MutationFactoryWithInput(cache = cache, dispatcher = dispatcher, mutation = query)

fun <TInput : MutationInput, TError : Throwable> mutationFactoryWithNoOutputOf(
    cache: OnHandCache,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    query: suspend (input: TInput) -> Unit,
): FireAndForgetMutationFactoryWithInput<TInput, TError> =
    FireAndForgetMutationFactoryWithInput(cache = cache, dispatcher = dispatcher, mutation = query)

fun <TData, TError : Throwable> mutationFactoryOf(
    cache: OnHandCache,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    query: suspend () -> TData,
): MutationFactoryWithNoInput<TData, TError> = MutationFactoryWithNoInput(cache = cache, dispatcher = dispatcher, mutation = query)

fun <TError : Throwable> mutationFactoryWithNoOutputOf(
    cache: OnHandCache,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    query: suspend () -> Unit,
): FireAndForgetMutationFactoryWithNoInput<TError> =
    FireAndForgetMutationFactoryWithNoInput(cache = cache, dispatcher = dispatcher, mutation = query)
