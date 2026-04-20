# Cache On Hand - Attendants

Reactive caching operations for Kotlin Multiplatform — queries, mutations, flows, and infinite queries built on top of `cacheonhand`.

Inspired by [React Query](https://tanstack.com/query) and [SWR](https://swr.vercel.app/).

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

Define your data operations once as factories, then create instances wherever needed. Each factory wraps a suspend lambda and manages caching, state tracking, and error handling.

```kotlin
val cache = OnHandCache()

// Define once
val getUserFactory = queryFactoryOf<GetUserInput, User, ApiException>(
    cache = cache,
) { input ->
    api.getUser(input.userId) // runs on Dispatchers.Default
}

// Use anywhere
val query = getUserFactory.create(
    startingCacheKey = GetUserInput("123"),
    coroutineScope = viewModelScope,
)

query.result.collect { result ->
    when (result.fetchState) {
        FetchState.IDLE -> { /* waiting */ }
        FetchState.LOADING -> { /* show spinner */ }
        FetchState.SUCCESS -> { /* use result.data */ }
        FetchState.ERROR -> { /* handle result.error */ }
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
    api.getUser(input.userId)
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
    api.getCurrentUser()
}

val query = currentUserFactory.create(coroutineScope = viewModelScope)
query.fetch()
```

### Refetch from Factory

Update the cache for a key without needing a query instance:

```kotlin
getUserFactory.refetch(GetUserInput("123"))
// All active query instances observing this key will update automatically
```

## Mutations

Perform write operations with optimistic updates and rollback.

### With Data Return

```kotlin
val updateUserFactory = mutationFactoryOf<UpdateUserInput, User, ApiException>(
    cache = cache,
) { input ->
    api.updateUser(input.userId, input.name)
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
    api.deleteUser(input.userId)
}
```

### Without Input

```kotlin
val logoutFactory = mutationFactoryOf<Unit, ApiException>(cache = cache) {
    api.logout()
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

By default, each emission overwrites the previous cached value. To keep a history of emissions, use `scan()` or `runningFold()` on your flow:

```kotlin
val chatFactory = flowFactoryOf<ChatRoomInput, List<ChatMessage>, ApiException>(
    cache = cache,
) { input ->
    chatWebSocket(input.roomId)
        .scan(emptyList()) { acc, message -> acc + message }
}
```

The cache stores the full accumulated list. Any subscriber reading the same cache key sees the complete history. To cap the size:

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
    api.getFeed(input.category, page = page)
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
) { input -> api.getData(input) }

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

**Important:** Do not use `launch` or `async` inside your lambda without awaiting the result. The operation must complete sequentially so that state transitions (LOADING -> SUCCESS/ERROR) are correct.

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
