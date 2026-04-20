package io.github.notoriouscorgi.cacheonhand.compose

import io.github.notoriouscorgi.composetesttools.act
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle

@OptIn(ExperimentalCoroutinesApi::class)
fun TestScope.actAndSettle(action: () -> Unit = {}) {
    action()
    advanceUntilIdle()
    act {}
    advanceUntilIdle()
    act {}
}

@OptIn(ExperimentalCoroutinesApi::class)
suspend fun TestScope.actAndSettleSuspend(action: suspend () -> Unit) {
    action()
    advanceUntilIdle()
    act {}
    advanceUntilIdle()
    act {}
}
