# Cache On Hand - Attendants

Structured "attendants" to your underlying cache; Attendants provide the core operations for Kotlin Multiplatform —
queries, mutations, flows, and infinite queries built on top of `cacheonhand`.

## Installation

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.github.notoriouscorgi:cacheonhand-attendants:<version>")
    // Transitively includes cacheonhand
}
```

**Platforms:** JVM, Android, iOS (x64/arm64/simulator), WASM-JS

## Core Concepts

### Factory Pattern

Define your data operations once as factories, then create instances wherever needed. Each factory wraps a suspend
lambda and manages caching, state tracking, and error handling.

```kotlin
val cache = OnHandCache()

// Define once
val getUserFactory = queryFactoryOf<GetUserInput, User, ApiException>(
    cache = cache,
) { input ->
    // Choose your appropriate dispatcher here
    withContext(Dispatchers.IO) {
        api.getUser(input.userId)
    }
}

// Use anywhere
val query = getUserFactory.create(
    startingCacheKey = GetUserInput("123"),
    coroutineScope = viewModelScope,
)
// Fetch data, the input can change however you need
query.fetch(GetUserInput("123"))

// Collect results where appropriate
query.result.collect { result ->
    when (result.fetchState) {
        FetchState.IDLE -> { /* waiting */
        }
        FetchState.LOADING -> { /* show spinner */
        }
        FetchState.SUCCESS -> { /* use result.data */
        }
        FetchState.ERROR -> { /* handle result.error */
        }
    }
}
```

### Cache Keys (CacheableInput)

Every operation needs a cache key. The key type determines the operation category:

```kotlin
// For queries and infinite queries
data class GetUserInput(val userId: String) : CacheableInput.QueryInput {
    override val identifier = "GET /api/users/$userId"
}

// For mutations
data class UpdateUserInput(val userId: String, val name: String) : CacheableInput.MutationInput {
    override val identifier = "PUT /api/users/$userId"
}

// For flow subscriptions
data class PriceStreamInput(val ticker: String) : CacheableInput.FlowInput {
    override val identifier = "WS /prices/$ticker"
}
```

### State Tracking

All results implement shared interfaces for consistent state handling:

```kotlin
enum class FetchState { IDLE, LOADING, SUCCESS, ERROR }

// Convenience enum combining data presence with fetch state
enum class CacheAndFetchState {
    DATA_CACHED_AND_IDLE,
    NO_DATA_CACHED_AND_IDLE,
    DATA_CACHED_AND_LOADING,     // refetching with stale data visible
    NO_DATA_CACHED_AND_LOADING,  // first load
    DATA_CACHED_AND_ERROR,       // error with stale data visible
    NO_DATA_CACHED_AND_ERROR,    // error with no data
    DATA_CACHED_AND_SUCCESS,
    NO_DATA_CACHED_AND_SUCCESS,  // query returned null
}

// Use the extension property
val state = queryResult.cachedDataState
```

## Queries

Fetch data on demand with automatic caching.

### With Input

```kotlin
val getUserFactory = queryFactoryOf<GetUserInput, User, ApiException>(
    cache = cache,
    ttl = 5.minutes, // optional TTL
) { input ->
    withContext(Dispatchers.IO) {
        api.getUser(input.userId)
    }
}

// Create an instance
val query = getUserFactory.create(
    startingCacheKey = GetUserInput("123"), // optional, pre-populates from cache
    coroutineScope = viewModelScope,
)

// Fetch
query.fetch(
    queryInput = GetUserInput("123"),
    onSuccess = { user -> /* navigate, log, etc. */ },
    onError = { error -> /* handle */ },
)

// Observe result reactively
query.result.collect { /* CacheableQueryResult<User, ApiException> */ }
```

### Without Input

For queries with a fixed cache key (e.g., current user, app config):

```kotlin
data class CurrentUserKey(val id: String = "me") : CacheableInput.QueryInput {
    override val identifier = "GET /api/me"
}

val currentUserFactory = queryFactoryOf<CurrentUserKey, User, ApiException>(
    cache = cache,
    cacheKey = CurrentUserKey(),
) {
    withContext(Dispatchers.IO) {
        api.getCurrentUser()
    }
}

val query = currentUserFactory.create(coroutineScope = viewModelScope)
query.fetch()
```

### Refetch from Factory

Update the cache for a key without needing a query instance. Useful for refreshing query data after a mutation:

```kotlin
val updateUserFactory = mutationFactoryOf<UpdateUserInput, User, ApiException>(
    cache = cache,
) { input ->
    withContext(Dispatchers.IO) {
        api.updateUser(input.userId, input.name)
    }
}

val mutation = updateUserFactory.create()

mutation.mutate(
    queryInput = UpdateUserInput("123", "New Name"),
    onSuccess = { _ ->
        // Refetch the user query so all subscribers see the updated data
        getUserFactory.refetch(GetUserInput("123"))
    },
)
```

## Mutations

Perform write operations with optimistic updates and rollback.

### With Data Return

```kotlin
val updateUserFactory = mutationFactoryOf<UpdateUserInput, User, ApiException>(
    cache = cache,
) { input ->
    withContext(Dispatchers.IO) {
        api.updateUser(input.userId, input.name)
    }
}

val mutation = updateUserFactory.create()

mutation.mutate(
    queryInput = UpdateUserInput("123", "New Name"),
    optimisticUpdate = { input ->
        // Instantly update the query cache before the API call
        getUserFactory.optimisticUpdater(GetUserInput(input.userId)) { currentUser ->
            currentUser?.copy(name = input.name) ?: User(name = input.name)
        }
    },
    onSuccess = { updatedUser -> /* done */ },
    onError = { error -> /* cache automatically rolled back */ },
)
```

### Fire and Forget

For mutations that don't return data:

```kotlin
val deleteUserFactory = mutationFactoryWithNoOutputOf<DeleteUserInput, ApiException>(
    cache = cache,
) { input ->
    withContext(Dispatchers.IO) {
        api.deleteUser(input.userId)
    }
}
```

### Without Input

```kotlin
val logoutFactory = mutationFactoryOf<Unit, ApiException>(cache = cache) {
    withContext(Dispatchers.IO) {
        api.logout()
    }
}
```

## Flows

Subscribe to reactive data sources (SSE, WebSockets, etc.) with each emission cached.

```kotlin
val priceStreamFactory = flowFactoryOf<PriceStreamInput, Price, ApiException>(
    cache = cache,
) { input ->
    api.priceWebSocket(input.ticker) // returns Flow<Price>
}

val flowInstance = priceStreamFactory.create(
    startingCacheKey = PriceStreamInput("AAPL"),
    coroutineScope = viewModelScope,
)

flowInstance.launch(
    queryInput = PriceStreamInput("AAPL"),
    onEachSuccess = { price -> /* called for every emission */ },
    onError = { error -> /* stream errored */ },
)
```

### Accumulating Emissions

By default, each emission overwrites the previous cached value. To keep a history of emissions, use `scan()` or
`runningFold()` on your flow:

```kotlin
val chatFactory = flowFactoryOf<ChatRoomInput, List<ChatMessage>, ApiException>(
    cache = cache,
) { input ->
    chatWebSocket(input.roomId)
        .scan(emptyList()) { acc, message -> acc + message }
}
```

The cache stores the full accumulated list. Any subscriber reading the same cache key sees the complete history. To cap
the size:

```kotlin
.scan(emptyList()) { acc, message -> (acc + message).takeLast(100) }
```

## Infinite Queries

Paginated data with forward and backward navigation.

```kotlin
data class FeedInput(val category: String) : CacheableInput.QueryInput {
    override val identifier = "GET /api/feed/$category"
}

val feedFactory = infiniteQueryFactoryOf<FeedInput, Int, FeedItem, ApiException>(
    cache = cache,
    initialPageParam = 1,
    getNextPageParam = { pages ->
        val lastPage = pages.lastOrNull()?.page ?: 0
        if (lastPage >= 10) PageParam.None else PageParam.Value(lastPage + 1)
    },
    getPreviousPageParam = { pages ->  // optional, null = forward-only
        val firstPage = pages.firstOrNull()?.page ?: 0
        if (firstPage <= 1) PageParam.None else PageParam.Value(firstPage - 1)
    },
) { input, page ->
    withContext(Dispatchers.IO) {
        api.getFeed(input.category, page = page)
    }
}

val infiniteQuery = feedFactory.create(
    startingCacheKey = FeedInput("trending"),
    coroutineScope = viewModelScope,
)

// Fetch pages
infiniteQuery.fetchNextPage(FeedInput("trending"))
infiniteQuery.fetchPreviousPage(FeedInput("trending"))

// Replace a specific page in-place
infiniteQuery.fetchPage(FeedInput("trending"), page = 3)

// Check pagination state
infiniteQuery.result.collect { result ->
    val hasMore = result.hasNextPage
    val canGoBack = result.hasPreviousPage
    val pages: List<PagedData<Int?, FeedItem>>? = result.data
}
```

## Recipes

### Refetch on Mutation Success

Refresh query data after a mutation completes:

```kotlin
mutation.mutate(
    queryInput = UpdateUserInput("123", "New Name"),
    onSuccess = { _ ->
        // Refetch the query — all active instances observing this key update automatically
        getUserFactory.refetch(GetUserInput("123"))
    },
)
```

Use `launch()` for fire-and-forget refetches, e.g., refreshing data that isn't currently on screen:

```kotlin
mutation.mutate(
    queryInput = UpdateUserInput("123", "New Name"),
    onSuccess = { _ ->
        scope.launch { getUserFactory.refetch(GetUserInput("123")) }
    },
)
```

### Optimistic Update with Rollback

Update the UI instantly, revert if the API call fails:

```kotlin
mutation.mutate(
    queryInput = LikePostInput(postId = "abc"),
    optimisticUpdate = { input ->
        postQueryFactory.optimisticUpdater(GetPostInput(input.postId)) { post ->
            post?.copy(isLiked = true, likeCount = (post.likeCount) + 1)
                ?: Post(isLiked = true, likeCount = 1)
        }
    },
)
// If mutate throws, the post reverts to its previous state automatically
```

### Shared Cache Across Screens

Because the cache is the source of truth, multiple query instances observing the same key stay in sync:

```kotlin
// Screen A
val queryA = getUserFactory.create(GetUserInput("123"), screenAScope)

// Screen B
val queryB = getUserFactory.create(GetUserInput("123"), screenBScope)

// When either screen fetches, both see the update
queryA.fetch(GetUserInput("123"))
// queryB.result also updates — they share the same cache entry
```

### Stale-While-Revalidate with TTL

Show cached data immediately, refetch in the background:

```kotlin
val factory = queryFactoryOf<MyInput, Data, Exception>(
    cache = cache,
    ttl = 30.seconds, // evict after 30s
) { input ->
    withContext(Dispatchers.IO) {
        api.getData(input)
    }
}

// On first load: cache miss, fetches from API
// On subsequent loads within 30s: returns cached data instantly
// After 30s: cache evicted, next load fetches fresh data
```

### IO-Bound Operations

Operations run on `Dispatchers.Default`. For network or disk IO, switch dispatchers inside your lambda:

```kotlin
val factory = queryFactoryOf<MyInput, Data, Exception>(cache = cache) { input ->
    withContext(Dispatchers.IO) {
        api.fetchData(input) // network call on IO dispatcher
    }
}
```

**Important:** Do not use `launch` or `async` inside your factory lambda without awaiting the result. The operation must
complete sequentially so that state transitions (LOADING -> SUCCESS/ERROR) are correct. However, `launch`/`async` is
fine inside `onSuccess`/`onError` callbacks — those run after state transitions are complete.

### CacheAndFetchState for UI

Use `cachedDataState` to drive UI with a single `when` expression:

```kotlin
when (query.result.value.cachedDataState) {
    CacheAndFetchState.NO_DATA_CACHED_AND_IDLE -> ShowEmpty()
    CacheAndFetchState.NO_DATA_CACHED_AND_LOADING -> ShowSkeleton()
    CacheAndFetchState.DATA_CACHED_AND_LOADING -> ShowDataWithRefreshIndicator(data)
    CacheAndFetchState.DATA_CACHED_AND_SUCCESS -> ShowData(data)
    CacheAndFetchState.DATA_CACHED_AND_ERROR -> ShowDataWithErrorBanner(data, error)
    CacheAndFetchState.NO_DATA_CACHED_AND_ERROR -> ShowErrorScreen(error)
    CacheAndFetchState.DATA_CACHED_AND_IDLE -> ShowData(data)
    CacheAndFetchState.NO_DATA_CACHED_AND_SUCCESS -> ShowEmpty() // query returned null
}
```

### External Cache Updates Propagate

Modifying the cache directly updates all active query/flow subscribers observing that key:

```kotlin
val query = getUserFactory.create(GetUserInput("123"), viewModelScope)

// Somewhere else in the app
cache[GetUserInput("123")] = User(name = "Updated externally")
// query.result automatically emits the new value
```

### fetchPage Replaces or Appends

`fetchPage` replaces a page in-place if it exists in the list, or appends it to the end if it doesn't:

```kotlin
// If page 3 exists, it's replaced with fresh data
infiniteQuery.fetchPage(FeedInput("trending"), page = 3)

// If page 10 doesn't exist yet, it's appended
infiniteQuery.fetchPage(FeedInput("trending"), page = 10)
```

### Forward-Only Pagination

Omit `getPreviousPageParam` to disable backward navigation. `hasPreviousPage` will always be `false`:

```kotlin
val forwardOnlyFactory = infiniteQueryFactoryOf<MyInput, Int, Item, Exception>(
    cache = cache,
    initialPageParam = 1,
    getNextPageParam = { pages -> PageParam.Value((pages.lastOrNull()?.page ?: 0) + 1) },
    // getPreviousPageParam omitted — forward only
) { input, page ->
    withContext(Dispatchers.IO) { api.getItems(input, page) }
}
```

## Gotchas

- **`fetch()` with a new key switches observation** — calling `fetch(newKey)` on an existing query instance changes which cache entry it observes. External updates to the old key will no longer propagate to that instance.
- **Infinite query pages grow unbounded** — each `fetchNextPage`/`fetchPreviousPage` appends to the list. There's no built-in cap. Consider clearing and refetching periodically for long-lived infinite scrolls.
- **Flow partial success** — if a flow emits values before throwing, the last successful emission remains cached. State shows ERROR with data still present (`DATA_CACHED_AND_ERROR`).
- **Error state clears on next fetch** — calling `fetch()` again after an error resets the error and sets LOADING. No manual error clearing needed.
