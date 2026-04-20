package io.github.notoriouscorgi.cacheonhand.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import io.github.notoriouscorgi.cacheonhand.CacheableInput.QueryInput
import io.github.notoriouscorgi.cacheonhand.operations.CacheableResultWithData
import io.github.notoriouscorgi.cacheonhand.operations.FetchState
import io.github.notoriouscorgi.cacheonhand.operations.InfiniteQueryFactoryWithInput
import io.github.notoriouscorgi.cacheonhand.operations.InfiniteQueryFactoryWithNoInput
import io.github.notoriouscorgi.cacheonhand.operations.PagedData
import kotlinx.atomicfu.atomic

data class RememberInfiniteQueryResult<TInput : QueryInput, TPageParam, TData, TError : Throwable>(
    override val fetchState: FetchState,
    override val error: TError?,
    override val data: List<PagedData<TPageParam?, TData>>?,
    val hasNextPage: Boolean,
    val hasPreviousPage: Boolean,
    val fetchNextPage: suspend (
        queryInput: TInput,
        onSuccess: (suspend (pageData: PagedData<TPageParam?, TData>?) -> Unit)?,
        onError: (suspend (error: TError) -> Unit)?,
    ) -> Unit,
    val fetchPreviousPage: suspend (
        queryInput: TInput,
        onSuccess: (suspend (pageData: PagedData<TPageParam?, TData>?) -> Unit)?,
        onError: (suspend (error: TError) -> Unit)?,
    ) -> Unit,
    val fetchPage: suspend (
        queryInput: TInput,
        page: TPageParam,
        onSuccess: (suspend (pageData: PagedData<TPageParam?, TData>?) -> Unit)?,
        onError: (suspend (error: TError) -> Unit)?,
    ) -> Unit,
) : CacheableResultWithData<List<PagedData<TPageParam?, TData>>, TError>

data class RememberInfiniteQueryNoInputResult<TPageParam, TData, TError : Throwable>(
    override val fetchState: FetchState,
    override val error: TError?,
    override val data: List<PagedData<TPageParam?, TData>>?,
    val hasNextPage: Boolean,
    val hasPreviousPage: Boolean,
    val fetchNextPage: suspend (
        onSuccess: (suspend (pageData: PagedData<TPageParam?, TData>?) -> Unit)?,
        onError: (suspend (error: TError) -> Unit)?,
    ) -> Unit,
    val fetchPreviousPage: suspend (
        onSuccess: (suspend (pageData: PagedData<TPageParam?, TData>?) -> Unit)?,
        onError: (suspend (error: TError) -> Unit)?,
    ) -> Unit,
    val fetchPage: suspend (
        page: TPageParam,
        onSuccess: (suspend (pageData: PagedData<TPageParam?, TData>?) -> Unit)?,
        onError: (suspend (error: TError) -> Unit)?,
    ) -> Unit,
) : CacheableResultWithData<List<PagedData<TPageParam?, TData>>, TError>

class ComposableInfiniteQuery<TInput : QueryInput, TPageParam, TData, TError : Throwable>(
    private val block: @Composable (
        input: TInput?,
        enabled: Boolean,
        launchImmediately: Boolean,
        onSuccess: (suspend (pageData: PagedData<TPageParam?, TData>?) -> Unit)?,
        onError: (suspend (error: TError) -> Unit)?,
    ) -> RememberInfiniteQueryResult<TInput, TPageParam, TData, TError>,
) {
    @Composable
    operator fun invoke(
        input: TInput? = null,
        enabled: Boolean = true,
        launchImmediately: Boolean = true,
        onSuccess: (suspend (pageData: PagedData<TPageParam?, TData>?) -> Unit)? = null,
        onError: (suspend (error: TError) -> Unit)? = null,
    ): RememberInfiniteQueryResult<TInput, TPageParam, TData, TError> = block(input, enabled, launchImmediately, onSuccess, onError)
}

class ComposableInfiniteQueryNoInput<TPageParam, TData, TError : Throwable>(
    private val block: @Composable (
        enabled: Boolean,
        launchImmediately: Boolean,
        onSuccess: (suspend (pageData: PagedData<TPageParam?, TData>?) -> Unit)?,
        onError: (suspend (error: TError) -> Unit)?,
    ) -> RememberInfiniteQueryNoInputResult<TPageParam, TData, TError>,
) {
    @Composable
    operator fun invoke(
        enabled: Boolean = true,
        launchImmediately: Boolean = true,
        onSuccess: (suspend (pageData: PagedData<TPageParam?, TData>?) -> Unit)? = null,
        onError: (suspend (error: TError) -> Unit)? = null,
    ): RememberInfiniteQueryNoInputResult<TPageParam, TData, TError> = block(enabled, launchImmediately, onSuccess, onError)
}

fun <TInput : QueryInput, TPageParam, TData, TError : Throwable> composeInfiniteQueryFactoryOf(
    factory: InfiniteQueryFactoryWithInput<TInput, TPageParam, TData, TError>,
): ComposableInfiniteQuery<TInput, TPageParam, TData, TError> {
    @Composable
    fun rememberInfiniteQuery(
        input: TInput? = null,
        enabled: Boolean = true,
        launchImmediately: Boolean = true,
        onSuccess: (suspend (pageData: PagedData<TPageParam?, TData>?) -> Unit)? = null,
        onError: (suspend (error: TError) -> Unit)? = null,
    ): RememberInfiniteQueryResult<TInput, TPageParam, TData, TError> {
        val wasLaunched = remember(input) { atomic(false) }

        val scope = rememberCoroutineScope()
        val initialState = if (enabled && launchImmediately && input != null) FetchState.LOADING else FetchState.IDLE
        val instance = remember(input) { factory.create(input, scope, initialState) }
        val state = instance.result.collectAsState()

        LaunchedEffect(enabled, launchImmediately, input) {
            if (enabled && launchImmediately && !wasLaunched.value) {
                input?.let {
                    instance.fetchNextPage(queryInput = it, onSuccess = onSuccess, onError = onError)
                    wasLaunched.value = true
                }
            }
        }

        return RememberInfiniteQueryResult(
            fetchState = state.value.fetchState,
            error = state.value.error,
            data = state.value.data,
            hasNextPage = state.value.hasNextPage,
            hasPreviousPage = state.value.hasPreviousPage,
            fetchNextPage = instance::fetchNextPage,
            fetchPreviousPage = instance::fetchPreviousPage,
            fetchPage = instance::fetchPage,
        )
    }

    return ComposableInfiniteQuery { input, enabled, launchImmediately, onSuccess, onError ->
        rememberInfiniteQuery(input, enabled, launchImmediately, onSuccess, onError)
    }
}

fun <TPageParam, TData, TError : Throwable> composeInfiniteQueryFactoryOf(
    factory: InfiniteQueryFactoryWithNoInput<TPageParam, TData, TError>,
): ComposableInfiniteQueryNoInput<TPageParam, TData, TError> {
    @Composable
    fun rememberInfiniteQuery(
        enabled: Boolean = true,
        launchImmediately: Boolean = true,
        onSuccess: (suspend (pageData: PagedData<TPageParam?, TData>?) -> Unit)? = null,
        onError: (suspend (error: TError) -> Unit)? = null,
    ): RememberInfiniteQueryNoInputResult<TPageParam, TData, TError> {
        val wasLaunched = remember { atomic(false) }

        val scope = rememberCoroutineScope()
        val initialState = if (enabled && launchImmediately) FetchState.LOADING else FetchState.IDLE
        val instance = remember { factory.create(scope, initialState) }
        val state = instance.result.collectAsState()

        LaunchedEffect(enabled, launchImmediately) {
            if (enabled && launchImmediately && !wasLaunched.value) {
                instance.fetchNextPage(onSuccess = onSuccess, onError = onError)
                wasLaunched.value = true
            }
        }

        return RememberInfiniteQueryNoInputResult(
            fetchState = state.value.fetchState,
            error = state.value.error,
            data = state.value.data,
            hasNextPage = state.value.hasNextPage,
            hasPreviousPage = state.value.hasPreviousPage,
            fetchNextPage = instance::fetchNextPage,
            fetchPreviousPage = instance::fetchPreviousPage,
            fetchPage = instance::fetchPage,
        )
    }

    return ComposableInfiniteQueryNoInput { enabled, launchImmediately, onSuccess, onError ->
        rememberInfiniteQuery(enabled, launchImmediately, onSuccess, onError)
    }
}
