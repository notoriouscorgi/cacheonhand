package io.github.notoriouscorgi.cacheonhand

import io.github.notoriouscorgi.OpenForMokkery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

sealed interface CacheableInput {
    val identifier: String

    interface QueryInput : CacheableInput

    interface FlowInput : CacheableInput

    interface MutationInput : CacheableInput
}

@OpenForMokkery
class OnHandCache(
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val cacheLifecycleScope: CoroutineScope = CoroutineScope(SupervisorJob()),
) {
    private val cache = mutableMapOf<CacheableInput, MutableStateFlow<Any?>>()
    private val mutexMap = mutableMapOf<CacheableInput, Mutex>()
    private val ttlMap = mutableMapOf<CacheableInput, Pair<Duration, TimeMark>>()

    // Global guard for structural changes to the maps (adding keys, clearing).
    // Value emissions via flow.emit() do NOT require this — MutableStateFlow.emit is thread-safe.
    private val mutexAddGuard = Mutex()

    /**
     * Ensure that a mutex exists for this key. Returns the mutex. Creates one under the
     * structural guard if not present.
     */
    private suspend fun ensureMutex(key: CacheableInput): Mutex =
        mutexMap[key] ?: mutexAddGuard.withLock {
            mutexMap.getOrPut(key) { Mutex() }
        }

    /**
     * Ensure that a flow exists for this key. Returns the flow. Creates one under the
     * structural guard if not present.
     */
    private suspend fun ensureFlow(key: CacheableInput): MutableStateFlow<Any?> =
        cache[key] ?: mutexAddGuard.withLock {
            cache.getOrPut(key) { MutableStateFlow(null) }
        }

    /**
     * Check if a key has expired based on its TTL. If expired, evict the entry
     * and return true.
     */
    private fun evictIfExpired(key: CacheableInput): Boolean {
        val (ttl, lastUpdated) = ttlMap[key] ?: return false
        if (lastUpdated.elapsedNow() > ttl) {
            cache.remove(key)
            mutexMap.remove(key)
            ttlMap.remove(key)
            return true
        }
        return false
    }

    /**
     * Evicts all currently stale keys, and ensures the cache is locked during eviction.
     * In the event that an operation on a key is interleaved between the initial ttl check,
     * and the attempt to remove the key's values, we safely ignore the eviction
     */
    private suspend fun evictStale() {
        val expiredKeys = ttlMap.filter { it.value.second.elapsedNow() > it.value.first }.keys
        expiredKeys.sortedBy { it.hashCode() }.forEach { key ->
            ensureMutex(key).let { mutex ->
                // If it's already locked, something else is operating on it
                if (!mutex.isLocked) {
                    mutex.withLock {
                        // Re-check: An interleaved write may have reset TTL while we waited
                        val entry = ttlMap[key]
                        if (entry != null && entry.second.elapsedNow() > entry.first) {
                            cache.remove(key)
                            mutexMap.remove(key)
                            ttlMap.remove(key)
                        }
                    }
                }
            }
        }
    }

    /**
     * External read — always returns a flow. If the key has no entry (or has expired),
     * an empty flow (holding null) is created so subscribers can attach and be notified
     * when a future `set` populates it. Also ensures a mutex exists for this key.
     */
    @Suppress("UNCHECKED_CAST")
    suspend operator fun <TData> get(key: CacheableInput): MutableStateFlow<TData?> {
        evictIfExpired(key)
        ensureMutex(key)
        return (ensureFlow(key) as MutableStateFlow<TData?>).also {
            cacheLifecycleScope.launch { evictStale() }
        }
    }

    /**
     * Non-suspend observation. Returns a cold [Flow] that, when collected, suspends to
     * ensure the underlying MutableStateFlow exists and forwards its emissions. Useful
     * for property initialization where we can't call the suspend `get`.
     */
    fun <TData> observe(key: CacheableInput): Flow<TData?> =
        flow {
            emitAll(get(key))
        }

    /**
     * Internal nullable read — returns null if no entry exists or if the entry has expired,
     * without creating one.
     *
     * NOTE: This is a non-suspend, unsynchronized read from the cache map. It's intentionally
     * "best effort" — a concurrent `set()` on the same key could cause this to return stale
     * or null data. Callers (e.g. query/flow impl constructors reading an initial cache value)
     * should follow up with a reactive `collect` via `get()` in an `init` block, which will
     * immediately emit the current value and keep state in sync going forward. The brief window
     * of staleness at construction time is acceptable for initial-value reads.
     */
    @Suppress("UNCHECKED_CAST")
    fun <TData> getOrNull(key: CacheableInput): MutableStateFlow<TData?>? {
        if (evictIfExpired(key)) return null
        return cache[key] as MutableStateFlow<TData?>?
    }

    /**
     * Thread-safe write. Ensures a mutex and flow exist, then emits under the per-key lock.
     */
    suspend operator fun set(
        key: CacheableInput,
        value: Any?,
    ) {
        val mutex = ensureMutex(key)
        val flow = ensureFlow(key)
        mutex.withLock {
            flow.emit(value)
        }
    }

    /**
     * Thread-safe write with TTL. The entry will be evicted on the next read after
     * `ttl` has elapsed since this write.
     */
    suspend fun setWithTtl(
        key: CacheableInput,
        value: Any?,
        ttl: Duration,
    ) {
        val mutex = ensureMutex(key)
        val flow = ensureFlow(key)
        mutex.withLock {
            flow.emit(value)
            ttlMap[key] = ttl to timeSource.markNow()
        }
    }

    /**
     * Convenience: write with optional TTL. Delegates to [set] or [setWithTtl].
     */
    suspend fun setMaybeWithTtl(
        key: CacheableInput,
        value: Any?,
        ttl: Duration? = null,
    ) {
        if (ttl != null) setWithTtl(key, value, ttl) else set(key, value)
    }

    /**
     * Run the action as a transaction, first performing updates. If action doesn't succeed, perform a rollback
     * of all the values of the given keys. The caller MUST use `setUnsafe()` internally since we already
     * hold the per-key mutexes — calling `set()` would deadlock.
     */
    suspend fun <T : CacheableInput> updateWithRollback(
        updates: Map<out T, Any?>,
        action: suspend () -> Unit,
    ) = coroutineScope {
        // Ensure mutexes and flows exist for every key, then lock them all (ordered to avoid deadlock)
        val orderedKeys = updates.keys.sortedBy { it.hashCode() }
        val mutexes = orderedKeys.map { ensureMutex(it) }
        orderedKeys.forEach { ensureFlow(it) }
        mutexes.forEach { it.lock() }

        try {
            // Snapshot current values for rollback
            val snapshots: Map<CacheableInput, Any?> = updates.keys.associateWith { getSnapshot(it) }

            // Optimistic update — parallel since each key write is independent
            updates.map { (key, value) -> async { setUnsafe(key, value) } }.awaitAll()

            // Run the action, and rollback if things fail
            try {
                action()
            } catch (_: Exception) {
                snapshots.map { (key, value) -> async { setUnsafe(key, value) } }.awaitAll()
            }
        } finally {
            // Unlock in reverse order (sequential — reverse of lock order)
            mutexes.asReversed().forEach { it.unlock() }
        }
    }

    /**
     * Write without acquiring the per-key mutex. The caller must already hold it (e.g. via `updateWithRollback`).
     * The flow is expected to exist; `updateWithRollback` ensures this.
     */
    internal suspend fun setUnsafe(
        key: CacheableInput,
        value: Any?,
    ) {
        ensureFlow(key).emit(value)
    }

    suspend fun clear() {
        mutexAddGuard.withLock {
            cache.clear()
            mutexMap.clear()
            ttlMap.clear()
        }
    }

    private fun getSnapshot(key: CacheableInput): Any? = getOrNull<Any?>(key)?.value
}
