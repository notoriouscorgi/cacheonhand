package io.github.notoriouscorgi.cacheonhand.operations

/**
 * Property to determine the fetch state of your data
 */
enum class FetchState {
    IDLE,
    LOADING,
    SUCCESS,
    ERROR,
}

/**
 * Base interface for all cacheable operation results.
 * Provides the fetch state and error information.
 */
interface CacheableResult<TError : Throwable> {
    val fetchState: FetchState
    val error: TError?
}

/**
 * Extended result interface that includes data.
 * All results with data (query, mutation, flow, infinite query) implement this.
 */
interface CacheableResultWithData<TData, TError : Throwable> : CacheableResult<TError> {
    val data: TData?
}

enum class CacheAndFetchState {
    // Cache hydration event
    DATA_CACHED_AND_IDLE,

    // Data never fetched
    NO_DATA_CACHED_AND_IDLE,

    // Last data fetch successful with fetch in flight
    DATA_CACHED_AND_LOADING,

    // Last data fetch successful but with null results, with fetch in flight
    NO_DATA_CACHED_AND_LOADING,

    // Data fetched at some point succesfully, but last fetch was an error
    DATA_CACHED_AND_ERROR,

    // Data was never fetched successfully or was evicted, but last fetch was an error
    NO_DATA_CACHED_AND_ERROR,

    // Last data fetch was successful
    DATA_CACHED_AND_SUCCESS,

    // Last data fetch was evicted or null, but was successful
    NO_DATA_CACHED_AND_SUCCESS,
}

/**
 * Interface for query-like results used in the compose layer.
 */
interface CacheableQuery<TData, TError : Throwable> : CacheableResultWithData<TData, TError>

/**
 * Convenience property to know if your cache already has data. This can
 * be helpful with optimistic updates so that you don't always show
 * a spinner if there is cache information already, but you are doing a fetch
 * operation
 */
val <TData, TError : Throwable> CacheableResultWithData<TData, TError>.cachedDataState: CacheAndFetchState
    get() =
        when {
            data != null && fetchState == FetchState.IDLE -> {
                CacheAndFetchState.DATA_CACHED_AND_IDLE
            }

            data == null && fetchState == FetchState.IDLE -> {
                CacheAndFetchState.NO_DATA_CACHED_AND_IDLE
            }

            data != null && fetchState == FetchState.LOADING -> {
                CacheAndFetchState.DATA_CACHED_AND_LOADING
            }

            data == null && fetchState == FetchState.LOADING -> {
                CacheAndFetchState.NO_DATA_CACHED_AND_LOADING
            }

            data != null && fetchState == FetchState.ERROR -> {
                CacheAndFetchState.DATA_CACHED_AND_ERROR
            }

            data == null && fetchState == FetchState.ERROR -> {
                CacheAndFetchState.NO_DATA_CACHED_AND_ERROR
            }

            data != null && fetchState == FetchState.SUCCESS -> {
                CacheAndFetchState.DATA_CACHED_AND_SUCCESS
            }

            data == null && fetchState == FetchState.SUCCESS -> {
                CacheAndFetchState.NO_DATA_CACHED_AND_SUCCESS
            }

            else -> {
                throw Error("Unable to determine cached data state state")
            }
        }
