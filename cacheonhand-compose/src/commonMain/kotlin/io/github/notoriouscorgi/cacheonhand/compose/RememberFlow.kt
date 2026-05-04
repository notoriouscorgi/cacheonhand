package io.github.notoriouscorgi.cacheonhand.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import io.github.notoriouscorgi.cacheonhand.CacheableInput.FlowInput
import io.github.notoriouscorgi.cacheonhand.operations.CacheableResultWithData
import io.github.notoriouscorgi.cacheonhand.operations.FetchState
import io.github.notoriouscorgi.cacheonhand.operations.FlowFactoryWithInput
import io.github.notoriouscorgi.cacheonhand.operations.FlowFactoryWithNoInput
import kotlinx.atomicfu.atomic

data class RememberFlowResult<TInput : FlowInput, TData, TError : Throwable>(
    override val fetchState: FetchState,
    override val error: TError?,
    override val data: TData?,
    val launch: suspend (
        queryInput: TInput,
        onEachSuccess: (suspend (data: TData) -> Unit)?,
        onError: (suspend (error: TError) -> Unit)?,
    ) -> Unit,
) : CacheableResultWithData<TData, TError>

data class RememberFlowNoInputResult<TData, TError : Throwable>(
    override val fetchState: FetchState,
    override val error: TError?,
    override val data: TData?,
    val launch: suspend (
        onEachSuccess: (suspend (data: TData) -> Unit)?,
        onError: (suspend (error: TError) -> Unit)?,
    ) -> Unit,
) : CacheableResultWithData<TData, TError>

class ComposableFlow<TInput : FlowInput, TData, TError : Throwable>(
    private val block: @Composable (
        input: TInput?,
        enabled: Boolean,
        launchImmediately: Boolean,
        onEachSuccess: (suspend (data: TData) -> Unit)?,
        onError: (suspend (error: TError) -> Unit)?,
    ) -> RememberFlowResult<TInput, TData, TError>,
) {
    @Composable
    operator fun invoke(
        input: TInput? = null,
        enabled: Boolean = true,
        launchImmediately: Boolean = true,
        onEachSuccess: (suspend (data: TData) -> Unit)? = null,
        onError: (suspend (error: TError) -> Unit)? = null,
    ): RememberFlowResult<TInput, TData, TError> = block(input, enabled, launchImmediately, onEachSuccess, onError)
}

class ComposableFlowNoInput<TData, TError : Throwable>(
    private val block: @Composable (
        enabled: Boolean,
        launchImmediately: Boolean,
        onEachSuccess: (suspend (data: TData) -> Unit)?,
        onError: (suspend (error: TError) -> Unit)?,
    ) -> RememberFlowNoInputResult<TData, TError>,
) {
    @Composable
    operator fun invoke(
        enabled: Boolean = true,
        launchImmediately: Boolean = true,
        onEachSuccess: (suspend (data: TData) -> Unit)? = null,
        onError: (suspend (error: TError) -> Unit)? = null,
    ): RememberFlowNoInputResult<TData, TError> = block(enabled, launchImmediately, onEachSuccess, onError)
}

fun <TInput : FlowInput, TData, TError : Throwable> FlowFactoryWithInput<TInput, TData, TError>.forCompose() = composeFlowFactoryOf(this)

fun <TInput : FlowInput, TData, TError : Throwable> composeFlowFactoryOf(
    factory: FlowFactoryWithInput<TInput, TData, TError>,
): ComposableFlow<TInput, TData, TError> {
    @Composable
    fun rememberFlow(
        input: TInput? = null,
        enabled: Boolean = true,
        launchImmediately: Boolean = true,
        onEachSuccess: (suspend (data: TData) -> Unit)? = null,
        onError: (suspend (error: TError) -> Unit)? = null,
    ): RememberFlowResult<TInput, TData, TError> {
        val wasLaunched = remember(input) { atomic(false) }

        val scope = rememberCoroutineScope()
        val initialState = if (enabled && launchImmediately && input != null) FetchState.LOADING else FetchState.IDLE
        val instance = remember(input) { factory.create(input, scope, initialState) }
        val state = instance.result.collectAsState()

        LaunchedEffect(enabled, launchImmediately, input) {
            if (enabled && launchImmediately && !wasLaunched.value) {
                input?.let {
                    instance.launch(queryInput = it, onEachSuccess = onEachSuccess, onError = onError)
                    wasLaunched.value = true
                }
            }
        }

        return RememberFlowResult(
            fetchState = state.value.fetchState,
            error = state.value.error,
            data = state.value.data,
            launch = instance::launch,
        )
    }

    return ComposableFlow { input, enabled, launchImmediately, onEachSuccess, onError ->
        rememberFlow(input, enabled, launchImmediately, onEachSuccess, onError)
    }
}

fun <TData, TError : Throwable> FlowFactoryWithNoInput<TData, TError>.forCompose() = composeFlowFactoryOf(this)

fun <TData, TError : Throwable> composeFlowFactoryOf(
    factory: FlowFactoryWithNoInput<TData, TError>,
): ComposableFlowNoInput<TData, TError> {
    @Composable
    fun rememberFlow(
        enabled: Boolean = true,
        launchImmediately: Boolean = true,
        onEachSuccess: (suspend (data: TData) -> Unit)? = null,
        onError: (suspend (error: TError) -> Unit)? = null,
    ): RememberFlowNoInputResult<TData, TError> {
        val wasLaunched = remember { atomic(false) }

        val scope = rememberCoroutineScope()
        val initialState = if (enabled && launchImmediately) FetchState.LOADING else FetchState.IDLE
        val instance = remember { factory.create(scope, initialState) }
        val state = instance.result.collectAsState()

        LaunchedEffect(enabled, launchImmediately) {
            if (enabled && launchImmediately && !wasLaunched.value) {
                instance.launch(onEachSuccess = onEachSuccess, onError = onError)
                wasLaunched.value = true
            }
        }

        return RememberFlowNoInputResult(
            fetchState = state.value.fetchState,
            error = state.value.error,
            data = state.value.data,
            launch = instance::launch,
        )
    }

    return ComposableFlowNoInput { enabled, launchImmediately, onEachSuccess, onError ->
        rememberFlow(enabled, launchImmediately, onEachSuccess, onError)
    }
}
