package io.github.notoriouscorgi.cacheonhand.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import io.github.notoriouscorgi.cacheonhand.CacheableInput.QueryInput
import io.github.notoriouscorgi.cacheonhand.operations.CacheableQuery
import io.github.notoriouscorgi.cacheonhand.operations.FetchState
import io.github.notoriouscorgi.cacheonhand.operations.QueryFactoryWithInput
import io.github.notoriouscorgi.cacheonhand.operations.QueryFactoryWithNoInput
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.delay
import kotlin.time.Duration

data class RememberQueryResult<TInput : QueryInput, TData, TError : Throwable>(
    override val fetchState: FetchState,
    override val error: TError?,
    override val data: TData?,
    val query: suspend (input: TInput) -> Unit,
) : CacheableQuery<TData, TError>

class ComposableQuery<TInput : QueryInput, TData, TError : Throwable>(
    private val block: @Composable (
        input: TInput?,
        enabled: Boolean,
        launchImmediately: Boolean,
        refetchInterval: Duration?,
        onSuccess: (suspend (data: TData) -> Unit)?,
        onError: (suspend (error: TError) -> Unit)?,
    ) -> RememberQueryResult<TInput, TData, TError>,
) {
    @Composable
    operator fun invoke(
        input: TInput? = null,
        enabled: Boolean = true,
        launchImmediately: Boolean = true,
        refetchInterval: Duration? = null,
        onSuccess: (suspend (data: TData) -> Unit)? = null,
        onError: (suspend (error: TError) -> Unit)? = null,
    ): RememberQueryResult<TInput, TData, TError> = block(input, enabled, launchImmediately, refetchInterval, onSuccess, onError)
}

fun <TInput : QueryInput, TData, TError : Throwable> composeQueryFactoryOf(
    factory: QueryFactoryWithInput<TInput, TData, TError>,
): ComposableQuery<TInput, TData, TError> {
    @Composable
    fun rememberQuery(
        input: TInput? = null,
        enabled: Boolean = true,
        launchImmediately: Boolean = true,
        refetchInterval: Duration? = null,
        onSuccess: (suspend (data: TData) -> Unit)? = null,
        onError: (suspend (error: TError) -> Unit)? = null,
    ): RememberQueryResult<TInput, TData, TError> {
        val wasLaunched = remember(input) { atomic(false) }

        val scope = rememberCoroutineScope()
        val initialState = if (enabled && launchImmediately && input != null) FetchState.LOADING else FetchState.IDLE
        val query = remember(input) { factory.create(input, scope, initialState) }
        val state = query.result.collectAsState()

        LaunchedEffect(enabled, launchImmediately, input) {
            if (enabled && launchImmediately && !wasLaunched.value) {
                input?.let {
                    query.fetch(queryInput = it, onSuccess = onSuccess, onError = onError)
                    wasLaunched.value = true
                }
            }
        }

        if (refetchInterval != null) {
            LaunchedEffect(enabled, input, refetchInterval, wasLaunched.value) {
                if (enabled && input != null && wasLaunched.value) {
                    while (true) {
                        delay(refetchInterval)
                        query.fetch(queryInput = input)
                    }
                }
            }
        }

        return RememberQueryResult(
            fetchState = state.value.fetchState,
            error = state.value.error,
            data = state.value.data,
            query = query::fetch,
        )
    }

    return ComposableQuery { input, enabled, launchImmediately, refetchInterval, onSuccess, onError ->
        rememberQuery(input, enabled, launchImmediately, refetchInterval, onSuccess, onError)
    }
}

data class RememberQueryNoInputResult<TData, TError : Throwable>(
    override val fetchState: FetchState,
    override val error: TError?,
    override val data: TData?,
    val query: suspend () -> Unit,
) : CacheableQuery<TData, TError>

class ComposableQueryNoInput<TData, TError : Throwable>(
    private val block: @Composable (
        enabled: Boolean,
        launchImmediately: Boolean,
        refetchInterval: Duration?,
        onSuccess: (suspend (data: TData) -> Unit)?,
        onError: (suspend (error: TError) -> Unit)?,
    ) -> RememberQueryNoInputResult<TData, TError>,
) {
    @Composable
    operator fun invoke(
        enabled: Boolean = true,
        launchImmediately: Boolean = true,
        refetchInterval: Duration? = null,
        onSuccess: (suspend (data: TData) -> Unit)? = null,
        onError: (suspend (error: TError) -> Unit)? = null,
    ): RememberQueryNoInputResult<TData, TError> = block(enabled, launchImmediately, refetchInterval, onSuccess, onError)
}

fun <TData, TError : Throwable> composeQueryFactoryOf(
    factory: QueryFactoryWithNoInput<TData, TError>,
): ComposableQueryNoInput<TData, TError> {
    @Composable
    fun rememberQuery(
        enabled: Boolean = true,
        launchImmediately: Boolean = true,
        refetchInterval: Duration? = null,
        onSuccess: (suspend (data: TData) -> Unit)? = null,
        onError: (suspend (error: TError) -> Unit)? = null,
    ): RememberQueryNoInputResult<TData, TError> {
        val wasLaunched = remember { atomic(false) }

        val scope = rememberCoroutineScope()
        val initialState = if (enabled && launchImmediately) FetchState.LOADING else FetchState.IDLE
        val query = remember { factory.create(scope, initialState) }
        val state = query.result.collectAsState()

        LaunchedEffect(enabled, launchImmediately) {
            if (enabled && launchImmediately && !wasLaunched.value) {
                query.fetch(onSuccess = onSuccess, onError = onError)
                wasLaunched.value = true
            }
        }

        if (refetchInterval != null) {
            LaunchedEffect(enabled, refetchInterval, wasLaunched.value) {
                if (enabled && wasLaunched.value) {
                    while (true) {
                        delay(refetchInterval)
                        query.fetch()
                    }
                }
            }
        }

        return RememberQueryNoInputResult(
            fetchState = state.value.fetchState,
            error = state.value.error,
            data = state.value.data,
            query = query::fetch,
        )
    }

    return ComposableQueryNoInput { enabled, launchImmediately, refetchInterval, onSuccess, onError ->
        rememberQuery(enabled, launchImmediately, refetchInterval, onSuccess, onError)
    }
}
