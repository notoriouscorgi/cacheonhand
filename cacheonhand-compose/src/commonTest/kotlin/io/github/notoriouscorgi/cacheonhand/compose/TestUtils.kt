package io.github.notoriouscorgi.cacheonhand.compose

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle

@OptIn(ExperimentalCoroutinesApi::class)
suspend fun TestScope.actAndSettle(action: suspend () -> Unit = {}) {
    action()
    advanceUntilIdle()
    act {}
    advanceUntilIdle()
    act {}
}
