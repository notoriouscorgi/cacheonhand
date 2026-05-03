package io.github.notoriouscorgi.cacheonhand.operations

import io.github.notoriouscorgi.cacheonhand.CacheableInput

sealed interface RefetchableFactory<TInput> {
    interface RefetchableFactoryWithInput<TInput : CacheableInput.QueryInput> : RefetchableFactory<TInput> {
        suspend fun refetch(input: TInput)
    }

    interface RefetchableFactoryWithNoInput : RefetchableFactory<Nothing> {
        suspend fun refetch()
    }
}
