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

With just `CacheOnHand`, we already have a way to centralize data updatess and
publish results to observers right away. But how should we operate on our cache predictably? That's
where the `Attendants` come in.

### The `Attendants`

The `Attendants` library is a set of prescriptive patterns for connecting your global cache to common data fetching,
mutation and subscription patterns. You will find these in the form of:

`CacheableQuery` via `queryFactoryOf()`

- For normal data fetching (HTTP GET's, etc)

`CacheableInfiniteQuery`  via `infiniteQueryFactoryOf()`

- For fetching paginated data

`CacheableFlow`  via `flowFactoryOf()`

- For subscribing to data

`CacheableMutation` via `mutationFactoryOf()`

- For changing data else where (HTTP POST / PUT / PATCH etc)
- Additionally, can define `dependentActions` for performing `refetch()`es of
  queries

Each factory requires you to define:

- Your data fetching logic
- The shape of your cache key (should be the same as the input to your fetching logic)

Optionally, you can define:

- A TTL for determining when a cache value should be evicted
- Any dependent actions that should *always* happen on success (only for mutations)

Factories provide the following outputs:

1) An `invoke()` method, which creates a single `CacheableX` instance, for calling and observing your data
2) A typed `optimisticUpdater` method for query and infinite query data, usually useful during mutations
3) A typed `refetch` method, also useful for once mutations complete

Once defined, a factory can be instantiated once, or many times in your app. The instances
provide the following:

- A function to `fetch()` , `mutate()`, or `launch()` your interaction, and subsequently interact
  with the cache appropriately
- A `StateFlow` to observe the data's `FetchState`, `Error` state, and successful `Data` value
- `onSuccess` and `onError` hooks to perform follow up actions
- The handling of `dependentActions` for mutations
-

### Why this is powerful

When organized this way, organization of data services becomes clean because:

- Your cache is the definitive center of your data flow operation, which maintains one way data flow
- All data fetching definitions are defined once, usable in many places
- Factories can be easily injected via DI and used to create `dependentActions`, `optimisticUpdate`s, or data `refetch`
  es
- `Cacheable` instances control the data fetching lifecycle, operate on your cache, and provide data observabilty
  so that you don't have to keep redefining similar operations
- Mutations can now have defined `dependentActions`, making it easy to connect a query refetch to it's
  mutation

# `queryFactoryOf()`

A query factory is the top level definition for a plan to fetch "data". This frequently is an api call, but can be any
suspend function that returns data. There are 2 versions of `queryFactoryOf`; 1 that accepts input data to fetch
and 1 that requires no input data to fetch. The query factory definition allows your instances
to understand how a query is fetched, what cache key it should overwrite, and how long data for a cache key
is valid.

### `queryFactoryOf()` with input example

Given I have an api fetch that returns a `User` based on `UserInput`, and can throw and `ApiException`:

```kotlin
// Defined or injected at the top of my application to survive the app lifecycle
val cache = OnHandCache()

// Define my input
data class GetUserInput(
    val id: Int,
) {
    override val identifier: String = "/user/$id"
}
// Defined outside the application and injected at callsites
val GetUser = queryFactoryOf<GetUserInput, User, ApiException>(
    cache = cache, // or injectable
    // The cache will evict this on the subsequent cache operation after the TTL 
    ttl = 10.minutes,
    // Concurrent fetches for the same key within this window share one in-flight request.
    // Set to null to disable deduplication entirely.
    dedupingInterval = 2.seconds
) { input ->
    // Choose your appropriate dispatcher here
    withContext(Dispatchers.IO) {
        api.getUser(input.userId)
    }
}

// Use in a ViewModel or elsewhere
class MyViewModel : ViewModel {
    // Contains fetchState, data, error information as a flow
    private val userResult = GetUser(
        // Look for this cache key to start. It's important to match this with your user input 
        // for the actual fetch
        startingCacheKey = GetUserInput("123"),
        coroutineScope = viewModelScope,
        // Set your FetchState to LOADING right away if you plan to query in an init {} block
        initialFetchState = FetchState.LOADING
    )

    init {
        // Run your query
        viewModelScope.launch {
            // Collect results where appropriate
            userResult.result.collect { result ->
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

            userResult.fetch(
                queryInput = GetUserInput("123"),
                onSuccess = { data -> doAnAction(data) },
                onError = { error -> logAnError(error) }
            )
        }
    }
}
```

### `queryFactoryOf()` without input example

Given I have an api fetch that always returns the currently-authenticated `User` (no input id needed):

```kotlin
data class CurrentUserKey(val id: String = "me") : CacheableInput.QueryInput {
    override val identifier = "GET /api/me"
}

val GetCurrentUser = queryFactoryOf<CurrentUserKey, User, ApiException>(
    cache = cache,
    cacheKey = CurrentUserKey(), // fixed cache key — every caller shares this entry
    ttl = 5.minutes
) {
    withContext(Dispatchers.IO) {
        api.getCurrentUser()
    }
}

class MyViewModel : ViewModel {
    private val currentUserResult = GetCurrentUser(
        coroutineScope = viewModelScope,
        initialFetchState = FetchState.LOADING
    )

    init {
        viewModelScope.launch {
            currentUserResult.result.collect { result ->
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

            currentUserResult.fetch(
                onSuccess = { user -> doAnAction(user) },
                onError = { error -> logAnError(error) }
            )
        }
    }
}
```

---

# mutationFactoryOf()

`mutationFactoryOf()`
A mutation factory defines an operation that most likely writes data elsewhere — typically a POST, PUT, or DELETE — .
In addition, mutations are special in that they can be provided `optimisticUpdate`s, allowing for cache manipulation
*BEFORE* a mutation runs when you need a snappier UI experience

There are 4 variants: with or without input, and with or without a data return value.

### `mutationFactoryOf()` with input example

Given I have an api call that updates a `User` given `UpdateUserInput` and returns the updated `User`:

```kotlin
val UpdateUser = mutationFactoryOf<UpdateUserInput, User, ApiException>(
    cache = cache,
    // Run these suspend functions after a successful mutation, e.g. invalidate related queries
    dependentActions = listOf { updatedUser -> analytics.track("user_updated", updatedUser.id) }
) { input ->
    withContext(Dispatchers.IO) {
        api.updateUser(input.userId, input.name)
    }
}

class MyViewModel : ViewModel {
    private val updateUserMutation = UpdateUser()

    init {
        viewModelScope.launch {
            updateUserMutation.result.collect { result ->
                when (result.fetchState) {
                    FetchState.IDLE -> { /* waiting */
                    }
                    FetchState.LOADING -> { /* show saving indicator */
                    }
                    FetchState.SUCCESS -> { /* use result.data (the updated User) */
                    }
                    FetchState.ERROR -> { /* handle result.error */
                    }
                }
            }
        }
    }

    fun onSave(userId: String, name: String) {
        viewModelScope.launch {
            updateUserMutation.mutate(
                input = UpdateUserInput(userId, name),
                optimisticUpdate = { input ->
                    // Instantly update the cache before the API responds
                    GetUser.optimisticUpdater(GetUserInput(input.userId)) { current ->
                        current?.copy(name = input.name)
                    }
                },
                onSuccess = { updatedUser ->
                    // Refresh any other queries that show this user
                    GetUser.refetch(GetUserInput(updatedUser.id))
                },
                onError = { error ->
                    // Cache automatically rolled back to its pre-optimistic state
                    logError(error)
                }
            )
        }
    }
}
```

### `mutationFactoryOf()` without input example

Given I have an api call that logs the user out (no input needed) and returns a confirmation `LogoutResult`:

```kotlin
val Logout = mutationFactoryOf<LogoutResult, ApiException>(cache = cache) {
    withContext(Dispatchers.IO) {
        api.logout()
    }
}

class MyViewModel : ViewModel {
    private val logoutMutation = Logout()

    init {
        viewModelScope.launch {
            logoutMutation.result.collect { result ->
                when (result.fetchState) {
                    FetchState.IDLE -> { /* waiting */
                    }
                    FetchState.LOADING -> { /* show spinner */
                    }
                    FetchState.SUCCESS -> { /* use result.data (LogoutResult) */
                    }
                    FetchState.ERROR -> { /* handle result.error */
                    }
                }
            }
        }
    }

    fun onLogoutClick() {
        viewModelScope.launch {
            logoutMutation.mutate(
                optimisticUpdate = {
                    // Optionally clear user-related cache entries before the API call
                    GetCurrentUser.optimisticUpdater { _ -> null }
                },
                onSuccess = { _ -> navigateToLogin() },
                onError = { error -> showError(error) }
            )
        }
    }
}
```

### `mutationFactoryWithNoOutputOf()` with input example

Given I have an api call that deletes a post by id and returns nothing meaningful:

```kotlin
val DeletePost = mutationFactoryWithNoOutputOf<DeletePostInput, ApiException>(
    cache = cache,
    // dependentActions on fire-and-forget mutations take no data parameter
    dependentActions = listOf { analytics.track("post_deleted") }
) { input ->
    withContext(Dispatchers.IO) {
        api.deletePost(input.postId)
    }
}

class MyViewModel : ViewModel {
    private val deletePostMutation = DeletePost()

    init {
        viewModelScope.launch {
            deletePostMutation.result.collect { result ->
                when (result.fetchState) {
                    FetchState.IDLE -> { /* waiting */
                    }
                    FetchState.LOADING -> { /* show deleting indicator */
                    }
                    FetchState.SUCCESS -> { /* done — no data returned */
                    }
                    FetchState.ERROR -> { /* handle result.error */
                    }
                }
            }
        }
    }

    fun onDeleteClick(postId: String) {
        viewModelScope.launch {
            deletePostMutation.mutate(
                input = DeletePostInput(postId),
                optimisticUpdate = { input ->
                    // Remove the post from the feed cache immediately
                    FeedQuery.optimisticUpdater(FeedInput()) { currentFeed ->
                        currentFeed?.filter { it.id != input.postId }
                    }
                },
                onSuccess = { navigateBack() },
                onError = { error ->
                    // Feed cache automatically restored — no manual rollback needed
                    showError(error)
                }
            )
        }
    }
}
```

### `mutationFactoryWithNoOutputOf()` without input example

Given I have an api call that clears all notifications and returns nothing:

```kotlin
val ClearNotifications = mutationFactoryWithNoOutputOf<ApiException>(
    cache = cache,
    // dependentActions on fire-and-forget mutations take no data parameter
    dependentActions = listOf { analytics.track("notifications_cleared") }
) {
    withContext(Dispatchers.IO) {
        api.clearAllNotifications()
    }
}

class MyViewModel : ViewModel {
    private val clearMutation = ClearNotifications()

    init {
        viewModelScope.launch {
            clearMutation.result.collect { result ->
                when (result.fetchState) {
                    FetchState.IDLE -> { /* waiting */
                    }
                    FetchState.LOADING -> { /* show clearing indicator */
                    }
                    FetchState.SUCCESS -> { /* done — no data returned */
                    }
                    FetchState.ERROR -> { /* handle result.error */
                    }
                }
            }
        }
    }

    fun onClearAll() {
        viewModelScope.launch {
            clearMutation.mutate(
                optimisticUpdate = {
                    // Clear the notifications list in cache before the API call
                    NotificationsQuery.optimisticUpdater { _ -> emptyList() }
                },
                onSuccess = { showToast("Cleared") },
                onError = { error -> showError(error) }
            )
        }
    }
}
```

---

# `flowFactoryOf()`

A flow factory subscribes to a reactive data source — WebSocket, SSE, or any `Flow<T>` — and writes each emission
to the cache. Every subscriber observing the same cache key sees updates in real time.
There are 2 versions: with input (e.g., ticker symbol) and without input (e.g., a single global event stream).

### `flowFactoryOf()` with input example

Given I have a WebSocket that streams live `Price` updates for a given ticker symbol:

```kotlin
val PriceStream = flowFactoryOf<PriceStreamInput, Price, ApiException>(cache = cache) { input ->
    api.priceWebSocket(input.ticker) // returns Flow<Price>
}

class MyViewModel : ViewModel {
    private val priceStream = PriceStream(
        startingCacheKey = PriceStreamInput("AAPL"), // pre-populate from cache if present
        coroutineScope = viewModelScope
    )

    init {
        viewModelScope.launch {
            priceStream.result.collect { result ->
                when (result.fetchState) {
                    FetchState.IDLE -> { /* not yet launched */
                    }
                    FetchState.LOADING -> { /* waiting for first emission */
                    }
                    FetchState.SUCCESS -> { /* use result.data (latest Price) */
                    }
                    FetchState.ERROR -> { /* stream errored — result.data holds last good value */
                    }
                }
            }
        }

        viewModelScope.launch {
            priceStream.launch(
                queryInput = PriceStreamInput("AAPL"),
                onEachSuccess = { price -> /* called on every emission */ },
                onError = { error -> /* stream closed with error */ }
            )
        }
    }
}
```

### `flowFactoryOf()` without input example

Given I have a subscription like service (server-sent event stream) that broadcasts global `AppNotification` events to
all users:

```kotlin
data class NotificationStreamKey(val id: String = "notifications") : CacheableInput.FlowInput {
    override val identifier = "SSE /api/notifications"
}

val NotificationStream = flowFactoryOf<NotificationStreamKey, AppNotification, ApiException>(
    cache = cache,
    cacheKey = NotificationStreamKey()
) {
    api.notificationStream() // returns Flow<AppNotification>
}

class MyViewModel : ViewModel {
    private val stream = NotificationStream(coroutineScope = viewModelScope)

    init {
        viewModelScope.launch {
            stream.result.collect { result ->
                when (result.fetchState) {
                    FetchState.IDLE -> { /* not yet launched */
                    }
                    FetchState.LOADING -> { /* waiting for first emission */
                    }
                    FetchState.SUCCESS -> { /* use result.data (latest AppNotification) */
                    }
                    FetchState.ERROR -> { /* stream errored */
                    }
                }
            }
        }

        viewModelScope.launch {
            stream.launch(
                onEachSuccess = { notification -> showBadge(notification) },
                onError = { error -> reconnect() }
            )
        }
    }
}
```

### Accumulating flow emissions

By default each emission overwrites the cached value. Use `scan()` on your flow before passing it to the factory
to accumulate a history — useful for chat rooms, event logs, or any append-only stream:

```kotlin
val ChatHistory = flowFactoryOf<ChatRoomInput, List<ChatMessage>, ApiException>(cache = cache) { input ->
    chatWebSocket(input.roomId)
        .scan(emptyList()) { acc, message -> acc + message }
}

// Each emission replaces the cache entry with the full accumulated list.
// All subscribers observing the same cache key see the complete history.
// To cap the size:
// .scan(emptyList()) { acc, message -> (acc + message).takeLast(100) }
```

---

# `infiniteQueryFactoryOf()`

An infinite query factory handles paginated data — loading the next or previous page on demand, with each page
appended to a running list in the cache. There are 2 versions: with input (e.g., search term or category) and
without input (e.g., a global activity feed).

### `infiniteQueryFactoryOf()` with input example

Given I have a paginated api that returns a page of `FeedItem`s given a `FeedInput` and an `Int` page number:

```kotlin
val Feed = infiniteQueryFactoryOf<FeedInput, Int, FeedItem, ApiException>(
    cache = cache,
    initialPageParam = 1,
    getNextPageParam = { pages ->
        val last = pages.lastOrNull()?.page ?: 0
        if (last >= MAX_PAGES) PageParam.None else PageParam.Value(last + 1)
    },
    getPreviousPageParam = { pages ->
        val first = pages.firstOrNull()?.page ?: 0
        if (first <= 1) PageParam.None else PageParam.Value(first - 1)
    }
) { input, page ->
    withContext(Dispatchers.IO) {
        api.getFeed(category = input.category, page = page)
    }
}

class MyViewModel : ViewModel {
    private val feed = Feed(
        startingCacheKey = FeedInput("trending"),
        coroutineScope = viewModelScope,
        initialFetchState = FetchState.LOADING
    )

    init {
        viewModelScope.launch {
            feed.result.collect { result ->
                when (result.fetchState) {
                    FetchState.IDLE -> { /* waiting */
                    }
                    FetchState.LOADING -> { /* show skeleton */
                    }
                    FetchState.SUCCESS -> {
                        // result.data is List<PagedData<Int?, FeedItem>>
                        val items = result.data?.flatMap { listOfNotNull(it.data) }
                        val canLoadMore = result.hasNextPage
                        val canLoadPrev = result.hasPreviousPage
                    }
                    FetchState.ERROR -> { /* handle result.error */
                    }
                }
            }

            // Load the first page
            feed.fetchNextPage(
                queryInput = FeedInput("trending"),
                onSuccess = { pageData -> /* pageData: PagedData<Int?, FeedItem>? */ },
                onError = { error -> /* handle */ }
            )
        }
    }

    fun onScrolledToBottom() {
        viewModelScope.launch {
            feed.fetchNextPage(
                queryInput = FeedInput("trending"),
                onSuccess = { pageData -> /* pageData: PagedData<Int?, FeedItem>? */ },
                onError = { error -> /* handle */ }
            )
        }
    }

    fun onScrolledToTop() {
        viewModelScope.launch {
            feed.fetchPreviousPage(
                queryInput = FeedInput("trending"),
                onSuccess = { pageData -> /* pageData: PagedData<Int?, FeedItem>? */ },
                onError = { error -> /* handle */ }
            )
        }
    }

    fun onRefreshPage(page: Int) {
        viewModelScope.launch {
            // Replaces the page in-place if it exists, otherwise appends it
            feed.fetchPage(
                queryInput = FeedInput("trending"),
                page = page,
                onSuccess = { pageData -> /* pageData: PagedData<Int?, FeedItem>? */ },
                onError = { error -> /* handle */ }
            )
        }
    }
}
```

### `infiniteQueryFactoryOf()` without input example

Given I have a paginated api that returns a page of `ActivityItem`s for the current user (no input needed):

```kotlin
data class ActivityFeedKey(val id: String = "activity") : CacheableInput.QueryInput {
    override val identifier = "GET /api/me/activity"
}

val ActivityFeed = infiniteQueryFactoryOf<Int, ActivityItem, ApiException>(
    cache = cache,
    cacheKey = ActivityFeedKey(),
    initialPageParam = 1,
    getNextPageParam = { pages ->
        val last = pages.lastOrNull()?.page ?: 0
        if (last >= MAX_PAGES) PageParam.None else PageParam.Value(last + 1)
    }
) { page ->
    withContext(Dispatchers.IO) {
        api.getActivity(page = page)
    }
}

class MyViewModel : ViewModel {
    private val activityFeed = ActivityFeed(coroutineScope = viewModelScope)

    init {
        viewModelScope.launch {
            activityFeed.result.collect { result ->
                val items = result.data?.flatMap { listOfNotNull(it.data) }
                val canLoadMore = result.hasNextPage
            }

            activityFeed.fetchNextPage(
                onSuccess = { pageData -> /* pageData: PagedData<Int?, ActivityItem>? */ },
                onError = { error -> /* handle */ }
            )
        }
    }

    fun onScrolledToBottom() {
        viewModelScope.launch {
            activityFeed.fetchNextPage(
                onSuccess = { pageData -> /* pageData: PagedData<Int?, ActivityItem>? */ },
                onError = { error -> /* handle */ }
            )
        }
    }

    fun onRefreshPage(page: Int) {
        viewModelScope.launch {
            activityFeed.fetchPage(
                page = page,
                onSuccess = { pageData -> /* pageData: PagedData<Int?, ActivityItem>? */ },
                onError = { error -> /* handle */ }
            )
        }
    }
}
```

---

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
val queryA = getUserFactory(GetUserInput("123"), screenAScope)

// Screen B
val queryB = getUserFactory(GetUserInput("123"), screenBScope)

// When either screen fetches, both see the update
queryA.fetch(GetUserInput("123"))
// queryB.result also updates — they share the same cache entry
```

### TTL

TTL allows for refetches to happen automatically on the given TTL cadence. This is useful
for polling workflows

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

### External Cache Updates Propagate

Modifying the cache directly updates all active query/flow subscribers observing that key:

```kotlin
val query = getUserFactory(GetUserInput("123"), viewModelScope)

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

### Stale-While-Revalidate

Pass `startingCacheKey` when creating an instance to immediately read the cached value (if present) while the
fresh fetch runs in the background. The result flow emits the cached data right away, then emits again when the
fetch completes. Use `cachedDataState` to distinguish the two states:

```kotlin
val query = GetUser(
    startingCacheKey = GetUserInput("123"), // reads cache synchronously on creation
    coroutineScope = viewModelScope,
    initialFetchState = FetchState.LOADING  // show LOADING state immediately
)

viewModelScope.launch {
    query.result.collect { result ->
        when (result.cachedDataState) {
            CacheAndFetchState.DATA_CACHED_AND_LOADING -> showStaleDataWithSpinner(result.data)
            CacheAndFetchState.DATA_CACHED_AND_SUCCESS -> showFreshData(result.data)
            CacheAndFetchState.NO_DATA_CACHED_AND_LOADING -> showSkeleton()
            CacheAndFetchState.DATA_CACHED_AND_ERROR -> showStaleDataWithError(result.data, result.error)
            CacheAndFetchState.NO_DATA_CACHED_AND_ERROR -> showErrorScreen(result.error)
            else -> { /* handle remaining states */
            }
        }
    }

    query.fetch(queryInput = GetUserInput("123"))
}
```

### `cachedDataState` for UI

`cachedDataState` is a convenience extension that combines cache presence with `fetchState` in a single enum,
letting you drive UI with one `when` expression:

```kotlin
when (query.result.value.cachedDataState) {
    CacheAndFetchState.NO_DATA_CACHED_AND_IDLE -> showEmpty()
    CacheAndFetchState.NO_DATA_CACHED_AND_LOADING -> showSkeleton()
    CacheAndFetchState.DATA_CACHED_AND_LOADING -> showDataWithRefreshIndicator(result.data)
    CacheAndFetchState.DATA_CACHED_AND_SUCCESS -> showData(result.data)
    CacheAndFetchState.DATA_CACHED_AND_ERROR -> showDataWithErrorBanner(result.data, result.error)
    CacheAndFetchState.NO_DATA_CACHED_AND_ERROR -> showErrorScreen(result.error)
    CacheAndFetchState.DATA_CACHED_AND_IDLE -> showData(result.data)
    CacheAndFetchState.NO_DATA_CACHED_AND_SUCCESS -> showEmpty() // query returned null
}
```

### Deduplication Interval

Concurrent fetches for the same cache key within `dedupingInterval` share a single in-flight request — the
second caller awaits the first rather than firing a duplicate network call. The default is `2.seconds`.

Set to `null` to disable deduplication and always run a fresh fetch:

```kotlin
val GetUser = queryFactoryOf<GetUserInput, User, ApiException>(
    cache = cache,
    dedupingInterval = null // every call runs independently
) { input ->
    withContext(Dispatchers.IO) { api.getUser(input.id) }
}
```

Reduce the window if your data changes frequently and stale joins are unacceptable:

```kotlin
val GetLiveScore = queryFactoryOf<MatchInput, Score, ApiException>(
    cache = cache,
    dedupingInterval = 500.milliseconds
) { input ->
    withContext(Dispatchers.IO) { api.getLiveScore(input.matchId) }
}
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

- **`fetch()` with a new key switches observation** — calling `fetch(newKey)` on an existing query instance changes
  which cache entry it observes. External updates to the old key will no longer propagate to that instance.
- **Infinite query pages grow unbounded** — each `fetchNextPage`/`fetchPreviousPage` appends to the list. There's no
  built-in cap. Consider clearing and refetching periodically for long-lived infinite scrolls.
- **Flow partial success** — if a flow emits values before throwing, the last successful emission remains cached. State
  shows ERROR with data still present (`DATA_CACHED_AND_ERROR`).
- **Error state clears on next fetch** — calling `fetch()` again after an error resets the error and sets LOADING. No
  manual error clearing needed.
