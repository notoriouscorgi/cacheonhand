# Cache On Hand

A thread-safe, reactive in-memory cache with TTL support for Kotlin Multiplatform. If you only want
transactional optimistic updates, use this alone.

## Installation

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.github.notoriouscorgi:cacheonhand:<version>")
}
```

**Platforms:** JVM, Android, iOS (x64/arm64/simulator), WASM-JS

## Quick Start

```kotlin
// 1. Define a cache key
data class UserKey(val userId: String) : CacheableInput.QueryInput {
    override val identifier = "GET /users/$userId"
}

// 2. Create a cache instance
val cache = OnHandCache()

// 3. Write and read
suspend fun example() {
    cache[UserKey("123")] = User(name = "Alice")
    val user = cache.get<User>(UserKey("123")).value // Alice
}
```

## API Reference

### OnHandCache

```kotlin
val cache = OnHandCache(
    timeSource = TimeSource.Monotonic,       // injectable for testing
    cacheLifecycleScope = CoroutineScope(SupervisorJob()), // scope for background eviction
)
```

#### Writing

```kotlin
// Basic write
cache[key] = value

// Write with TTL — evicted after duration
cache.setWithTtl(key, value, 5.minutes)

// Convenience — delegates based on TTL presence
cache.setMaybeWithTtl(key, value, ttl = null) // same as cache[key] = value
cache.setMaybeWithTtl(key, value, ttl = 5.minutes) // same as setWithTtl
```

#### Reading

```kotlin
// Suspend read — always returns a flow (creates one if absent)
val flow: MutableStateFlow<User?> = cache.get<User>(key)

// Non-suspend read — returns null if no entry exists
val flow: MutableStateFlow<User?>? = cache.getOrNull<User>(key)

// Cold Flow observation — useful for property initialization
val userFlow: Flow<User?> = cache.observe<User>(key)
```

#### Observing Changes

The cache is reactive. When a value changes, all subscribers are notified:

```kotlin
launch {
    cache.observe<User>(UserKey("123")).collect { user ->
        println("User updated: $user")
    }
}

cache[UserKey("123")] = User(name = "Bob") // triggers collection above
```

#### Optimistic Updates with Rollback

Perform transactional updates that automatically roll back on failure:

```kotlin
cache.updateWithRollback(
    updates = mapOf(
        UserKey("123") to User(name = "Optimistic Name"),
        BalanceKey("123") to 500,
    )
) {
    // If this throws, all keys are restored to their previous values
    api.updateUser(userId = "123", name = "Optimistic Name")
}
```

#### TTL and Eviction

Entries with TTL are lazily evicted on the next read after expiry. A background eviction sweep also runs on each `get()`
call to clean up stale entries across the cache.

```kotlin
cache.setWithTtl(key, value, 30.seconds)

// After 30 seconds, the next read returns null
val result = cache.get<User>(key).value // null
```

#### Clearing

```kotlin
cache.clear() // removes all entries, mutexes, and TTL metadata
```

### CacheableInput

All cache keys must implement `CacheableInput` with an `identifier` property. Use the sub-interface that matches your
operation type:

```kotlin
sealed interface CacheableInput {
    val identifier: String

    interface QueryInput : CacheableInput   // for queries and infinite queries
    interface FlowInput : CacheableInput    // for flow subscriptions
    interface MutationInput : CacheableInput // for mutations
}
```

The `identifier` is a human-readable string describing the cache key's purpose (e.g., the API endpoint). This is useful
for debugging.

```kotlin
data class GetUserQuery(val userId: String) : CacheableInput.QueryInput {
    override val identifier = "GET /api/users/$userId"
}

data class ChatMessages(val roomId: String) : CacheableInput.FlowInput {
    override val identifier = "WS /chat/$roomId"
}

data class UpdateUserMutation(val userId: String) : CacheableInput.MutationInput {
    override val identifier = "PUT /api/users/$userId"
}
```

## Thread Safety

`OnHandCache` is safe for concurrent access:

- **Per-key mutexes** protect individual writes from interleaving
- **Structural guard** protects map modifications (adding keys, clearing)
- **Ordered lock acquisition** in `updateWithRollback` prevents deadlock
- **`MutableStateFlow.emit()`** is inherently thread-safe for value emissions

### Gotchas

- **`getOrNull()` is unsynchronized** — it reads directly from the map without acquiring a mutex. This is intentional for use in property initializers where `suspend` isn't available. The value may be briefly stale if a concurrent `set()` is in progress. Always follow up with a reactive `observe()` or `get()` collection for live data.
- **Don't call `set()` inside `updateWithRollback`** — the rollback already holds per-key mutexes. Calling `set()` would deadlock. Use `setUnsafe()` internally (this is handled for you by the library).
- **TTL eviction is lazy** — expired entries are not removed on a timer. They are evicted on the next `get()`, `getOrNull()`, or background sweep triggered by `get()`. An expired entry still occupies memory until a read triggers cleanup.
- **`clear()` acquires the structural guard** — if another coroutine is in `ensureMutex()` or `ensureFlow()`, `clear()` will suspend until it completes. Don't call `clear()` in a tight loop.
- **TTL entries persist in memory until read** — even after expiry, entries occupy memory until a `get()` triggers cleanup. Call `clear()` if you need to free memory for inactive keys.
- **Flow identity** — `get()` returns the same `MutableStateFlow` instance for the same key. Multiple subscribers share the same flow.

## Testing

Inject `testScheduler.timeSource` for deterministic TTL testing:

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
@Test
fun `cache entry expires after TTL`() = runTest {
        val cache = OnHandCache(timeSource = testScheduler.timeSource)

        cache.setWithTtl(MyKey(), "hello", 100.milliseconds.toIsoString().let { parse(it) })

        testScheduler.advanceTimeBy(150)

        assertNull(cache.getOrNull<String>(MyKey()))
    }
```
