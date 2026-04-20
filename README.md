# Cache On Hand

A local-first caching library for Kotlin Multiplatform with stale-while-revalidate support.
Inspired by [React Query](https://tanstack.com/query)
and [swr-compose](https://github.com/kazakago/swr-compose).

**Platforms:** JVM, Android, iOS (x64/arm64/simulator), WASM-JS

## Modules

Cache On Hand is split into three modules that build on each other. Use only what you need:

| Module                                                         | Description                                          | Use when...                                        |
|----------------------------------------------------------------|------------------------------------------------------|----------------------------------------------------|
| [`cacheonhand`](cacheonhand/README.md)                         | Thread-safe reactive cache with TTL                  | You just need a reactive in-memory cache           |
| [`cacheonhand-attendants`](cacheonhand-attendants/README.md)   | Query, mutation, flow, and infinite query operations | You want managed data fetching with state tracking |
| [`cacheonhand-compose`](cacheonhand-compose/README.md)         | Compose Multiplatform wrappers                       | You're building Compose UI                         |

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
val useGetUser = composeQueryFactoryOf(getUserFactory)

// Use in a composable
@Composable
fun UserScreen(userId: String) {
    val result = useGetUser(input = GetUserInput(userId))

    when (result.fetchState) {
        FetchState.LOADING -> CircularProgressIndicator()
        FetchState.SUCCESS -> Text("Hello, ${result.data?.name}")
        FetchState.ERROR -> Text("Error: ${result.error?.message}")
        FetchState.IDLE -> {}
    }
}
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

### Generate Docs

Generates API documentation from source and READMEs into `docs/` for GitHub Pages:

```bash
./gradlew :dokkaGenerate
```

## License

```
Licensed under the Apache License, Version 2.0
```
