<p align="center">
  <img src="assets/cacheonhandcorgi.png" alt="Cache On Hand" width="200" />
</p>

# Cache On Hand

A local-first caching library for Kotlin Multiplatform with stale-while-revalidate support.
Inspired by [React Query](https://tanstack.com/query)
and [swr-compose](https://github.com/kazakago/swr-compose).

**Platforms:** JVM, Android, iOS (x64/arm64/simulator), WASM-JS

## Modules

Cache On Hand is split into three modules that build on each other. Use only what you need:

| Module                                                       | Description                                          | Use when...                                        |
|--------------------------------------------------------------|------------------------------------------------------|----------------------------------------------------|
| [`cacheonhand`](cacheonhand/README.md)                       | Thread-safe reactive cache with TTL                  | You just need a reactive in-memory cache           |
| [`cacheonhand-attendants`](cacheonhand-attendants/README.md) | Query, mutation, flow, and infinite query operations | You want managed data fetching with state tracking |
| [`cacheonhand-compose`](cacheonhand-compose/README.md)       | Compose Multiplatform wrappers                       | You're building Compose UI                         |

Each module transitively includes its dependencies — adding `cacheonhand-compose` gives you everything.

## Installation

```kotlin
// build.gradle.kts
dependencies {
    // Pick one — each includes its dependencies
    implementation("io.github.notoriouscorgi:cacheonhand:<version>")              // cache only
    implementation("io.github.notoriouscorgi:cacheonhand-attendants:<version>")  // cache + operations
    implementation("io.github.notoriouscorgi:cacheonhand-compose:<version>")     // cache + operations + compose
}
```

## Quick Example

```kotlin
// Define a cache key
data class GetUserInput(val userId: String) : CacheableInput.QueryInput {
    override val identifier = "GET /api/users/$userId"
}

// Create a shared cache
val cache = OnHandCache()

// Define a query factory
val getUserFactory = queryFactoryOf<GetUserInput, User, ApiException>(
    cache = cache,
) { input -> api.getUser(input.userId) }

// Wrap for Compose
val rememberGetUser = composeQueryFactoryOf(getUserFactory)

// Use in a composable
@Composable
fun UserScreen(userId: String) {
    val result = rememberGetUser(input = GetUserInput(userId))

    when (result.fetchState) {
        FetchState.LOADING -> CircularProgressIndicator()
        FetchState.SUCCESS -> Text("Hello, ${result.data?.name}")
        FetchState.ERROR -> Text("Error: ${result.error?.message}")
        FetchState.IDLE -> {}
    }
}
```

## Refetch on Mutation

Refresh query data after a mutation by calling `refetch` in `onSuccess`:

```kotlin
val mutation = updateUserFactory.create()

mutation.mutate(
    queryInput = UpdateUserInput("123", "New Name"),
    onSuccess = { _ ->
        getUserFactory.refetch(GetUserInput("123"))
        // All active query instances observing this key update automatically
    },
)

// Use launch() for fire-and-forget refetches (e.g., refreshing data not currently on screen)
mutation.mutate(
    queryInput = UpdateUserInput("123", "New Name"),
    onSuccess = { _ ->
        scope.launch { getUserFactory.refetch(GetUserInput("123")) }
    },
)
```

## Features

- **Queries** — fetch data with automatic caching, TTL, and refetch
- **Mutations** — write operations with optimistic updates and automatic rollback
- **Flows** — subscribe to reactive sources (SSE, WebSockets) with cached emissions
- **Infinite Queries** — paginated data with forward/backward navigation
- **Optimistic Updates** — instant UI updates that revert on failure
- **Stale-While-Revalidate** — show cached data while refetching in the background
- **TTL Eviction** — automatic cache entry expiry
- **Thread Safety** — per-key mutex locking with deadlock prevention
- **Compose Integration** — composable hooks with lifecycle-aware scoping
- **Type-Safe Errors** — generic `TError` parameter instead of raw `Throwable`
- **Factory Pattern** — define once, use everywhere

## Rules

These assumptions apply across all modules:

- **Cache keys must be data classes** — the cache uses HashMap internally. Regular classes without proper `equals`/`hashCode` will silently create duplicate entries. Always use `data class` for your `CacheableInput` implementations.
- **Operations run on `Dispatchers.Default`** — use `withContext(Dispatchers.IO)` inside your query/mutation/flow lambda for network or disk calls.
- **Don't use `launch`/`async` without awaiting inside factory lambdas** — the operation must complete sequentially so state transitions (LOADING -> SUCCESS/ERROR) are correct. However, `launch`/`async` is fine inside `onSuccess`/`onError` callbacks — those run after state transitions are complete.
- **`refetch()` throws on failure** — unlike `fetch()` which catches errors and sets ERROR state, `refetch()` propagates exceptions to the caller. Wrap in try-catch or `scope.launch`.
- **Null data is not an error** — a query or flow returning null sets state to SUCCESS with null data, not ERROR. This is intentional for distinguishing "no data exists" from "fetch failed".

## Development

### Build

```bash
./gradlew build
```

### Run Tests

```bash
# All modules
./gradlew cacheonhand:jvmTest cacheonhand-attendants:jvmTest cacheonhand-compose:jvmTest

# Single module
./gradlew cacheonhand:jvmTest
./gradlew cacheonhand-attendants:jvmTest
./gradlew cacheonhand-compose:jvmTest
```

### Release

Create a release and publish to Maven Central:

```bash
./scripts/release.sh         # auto-increments minor version (0.1.0 → 0.2.0)
./scripts/release.sh 1.0.0   # explicit version
```

Or trigger directly from GitHub Actions (Actions -> Publish -> Run workflow) with an optional version input.

The workflow runs tests first, then publishes all three modules to Maven Central.

### Generate Docs

Generates API documentation from source and READMEs into `docs/` for GitHub Pages:

```bash
./gradlew :dokkaGenerate
```

## License

```
Licensed under the Apache License, Version 2.0
```
