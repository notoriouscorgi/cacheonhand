package io.github.notoriouscorgi.cacheonhand.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import io.github.notoriouscorgi.cacheonhand.CacheableInput
import io.github.notoriouscorgi.cacheonhand.CacheableInput.MutationInput
import io.github.notoriouscorgi.cacheonhand.operations.CacheableResult
import io.github.notoriouscorgi.cacheonhand.operations.CacheableResultWithData
import io.github.notoriouscorgi.cacheonhand.operations.FetchState
import io.github.notoriouscorgi.cacheonhand.operations.FireAndForgetMutationFactoryWithInput
import io.github.notoriouscorgi.cacheonhand.operations.FireAndForgetMutationFactoryWithNoInput
import io.github.notoriouscorgi.cacheonhand.operations.MutationFactoryWithInput
import io.github.notoriouscorgi.cacheonhand.operations.MutationFactoryWithNoInput

data class RememberMutationResult<TInput : MutationInput, TData, TError : Throwable>(
    override val fetchState: FetchState,
    override val error: TError?,
    override val data: TData?,
    val mutate: suspend (
        queryInput: TInput,
        optimisticUpdate: ((input: TInput) -> Map<out CacheableInput, Any?>)?,
        onSuccess: (suspend (data: TData) -> Unit)?,
        onError: (suspend (error: TError) -> Unit)?,
    ) -> Unit,
) : CacheableResultWithData<TData, TError>

data class RememberMutationNoInputResult<TData, TError : Throwable>(
    override val fetchState: FetchState,
    override val error: TError?,
    override val data: TData?,
    val mutate: suspend (
        optimisticUpdate: (() -> Map<out CacheableInput, Any?>)?,
        onSuccess: (suspend (data: TData) -> Unit)?,
        onError: (suspend (error: TError) -> Unit)?,
    ) -> Unit,
) : CacheableResultWithData<TData, TError>

data class RememberFireAndForgetMutationResult<TInput : MutationInput, TError : Throwable>(
    override val fetchState: FetchState,
    override val error: TError?,
    val mutate: suspend (
        queryInput: TInput,
        optimisticUpdate: ((input: TInput) -> Map<out CacheableInput, Any?>)?,
        onSuccess: (suspend () -> Unit)?,
        onError: (suspend (error: TError) -> Unit)?,
    ) -> Unit,
) : CacheableResult<TError>

data class RememberFireAndForgetMutationNoInputResult<TError : Throwable>(
    override val fetchState: FetchState,
    override val error: TError?,
    val mutate: suspend (
        optimisticUpdate: (() -> Map<out CacheableInput, Any?>)?,
        onSuccess: (suspend () -> Unit)?,
        onError: (suspend (error: TError) -> Unit)?,
    ) -> Unit,
) : CacheableResult<TError>

class ComposableMutation<TInput : MutationInput, TData, TError : Throwable>(
    private val block: @Composable () -> RememberMutationResult<TInput, TData, TError>,
) {
    @Composable
    operator fun invoke(): RememberMutationResult<TInput, TData, TError> = block()
}

class ComposableMutationNoInput<TData, TError : Throwable>(
    private val block: @Composable () -> RememberMutationNoInputResult<TData, TError>,
) {
    @Composable
    operator fun invoke(): RememberMutationNoInputResult<TData, TError> = block()
}

class ComposableFireAndForgetMutation<TInput : MutationInput, TError : Throwable>(
    private val block: @Composable () -> RememberFireAndForgetMutationResult<TInput, TError>,
) {
    @Composable
    operator fun invoke(): RememberFireAndForgetMutationResult<TInput, TError> = block()
}

class ComposableFireAndForgetMutationNoInput<TError : Throwable>(
    private val block: @Composable () -> RememberFireAndForgetMutationNoInputResult<TError>,
) {
    @Composable
    operator fun invoke(): RememberFireAndForgetMutationNoInputResult<TError> = block()
}

fun <TInput : MutationInput, TData, TError : Throwable> composeMutationFactoryOf(
    factory: MutationFactoryWithInput<TInput, TData, TError>,
): ComposableMutation<TInput, TData, TError> =
    ComposableMutation {
        val instance = remember { factory.create() }
        val state = instance.result.collectAsState()
        RememberMutationResult(
            fetchState = state.value.fetchState,
            error = state.value.error,
            data = state.value.data,
            mutate = instance::mutate,
        )
    }

fun <TData, TError : Throwable> composeMutationFactoryOf(
    factory: MutationFactoryWithNoInput<TData, TError>,
): ComposableMutationNoInput<TData, TError> =
    ComposableMutationNoInput {
        val instance = remember { factory.create() }
        val state = instance.result.collectAsState()
        RememberMutationNoInputResult(
            fetchState = state.value.fetchState,
            error = state.value.error,
            data = state.value.data,
            mutate = instance::mutate,
        )
    }

fun <TInput : MutationInput, TError : Throwable> composeMutationFactoryOf(
    factory: FireAndForgetMutationFactoryWithInput<TInput, TError>,
): ComposableFireAndForgetMutation<TInput, TError> =
    ComposableFireAndForgetMutation {
        val instance = remember { factory.create() }
        val state = instance.result.collectAsState()
        RememberFireAndForgetMutationResult(
            fetchState = state.value.fetchState,
            error = state.value.error,
            mutate = instance::mutate,
        )
    }

fun <TError : Throwable> composeMutationFactoryOf(
    factory: FireAndForgetMutationFactoryWithNoInput<TError>,
): ComposableFireAndForgetMutationNoInput<TError> =
    ComposableFireAndForgetMutationNoInput {
        val instance = remember { factory.create() }
        val state = instance.result.collectAsState()
        RememberFireAndForgetMutationNoInputResult(
            fetchState = state.value.fetchState,
            error = state.value.error,
            mutate = instance::mutate,
        )
    }
