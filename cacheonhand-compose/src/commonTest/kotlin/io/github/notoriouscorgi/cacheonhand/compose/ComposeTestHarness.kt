package io.github.notoriouscorgi.cacheonhand.compose

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.ObserverHandle
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent

private object ImmediateFrameClock : MonotonicFrameClock {
    override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
        return onFrame(0L)
    }
}

/**
 * Take an action, and force any pending tasks from the action to run.
 */
@OptIn(ExperimentalCoroutinesApi::class)
suspend fun TestScope.act(action: suspend () -> Unit) {
    action()
    runCurrent()
}

/**
 * Renders a non-UI composable hook in a unit test environment and returns
 * its value as a [State] that updates across recompositions.
 *
 * Based on the approach from [Molecule](https://github.com/cashapp/molecule).
 *
 * Rules:
 * - The hook should not emit any UI nodes
 * - No uses of android internals (e.g., LocalContext.current) unless mocked
 *
 * Example:
 * ```
 * @Test
 * fun `test composable hook`() = runTest {
 *     val result by renderHook { rememberMyHook() }
 *     actAndSettle()
 *     assertEquals(expected, result)
 * }
 * ```
 */
fun <T> TestScope.renderHook(hook: @Composable () -> T): State<T?> {
    val hookValue = mutableStateOf<T?>(null)

    val context = backgroundScope.coroutineContext + ImmediateFrameClock

    val recomposer = Recomposer(context)
    val composition = Composition(UnitApplier, recomposer)

    var snapshotHandle: ObserverHandle? = null

    backgroundScope.launch(context, start = CoroutineStart.UNDISPATCHED) {
        try {
            recomposer.runRecomposeAndApplyChanges()
        } finally {
            composition.dispose()
            snapshotHandle?.dispose()
        }
    }

    var applyScheduled = false
    snapshotHandle = Snapshot.registerGlobalWriteObserver {
        if (!applyScheduled) {
            applyScheduled = true
            backgroundScope.launch(context) {
                applyScheduled = false
                Snapshot.sendApplyNotifications()
            }
        }
    }

    composition.setContent {
        hookValue.value = hook()
    }

    return hookValue
}

private object UnitApplier : AbstractApplier<Unit>(Unit) {
    override fun insertBottomUp(index: Int, instance: Unit) {}
    override fun insertTopDown(index: Int, instance: Unit) {}
    override fun move(from: Int, to: Int, count: Int) {}
    override fun remove(index: Int, count: Int) {}
    override fun onClear() {}
}
