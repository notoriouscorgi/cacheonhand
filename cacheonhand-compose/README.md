# Cache On Hand - Compose

Compose Multiplatform convenience wrappers for Cache On Hand. Turns query, mutation, flow, and infinite query
factories into `@Composable` hooks with automatic lifecycle management.

## Installation

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.github.notoriouscorgi:cacheonhand-compose:<version>")
    // Transitively includes cacheonhand-attendants and cacheonhand
}
```

**Platforms:** JVM, Android, iOS (x64/arm64/simulator), WASM-JS

## Core Concepts

Each attendant factory gains a `forCompose()` extension that wraps it in a `@Composable` hook. Call
`forCompose()` once when the factory is defined (or in your DI module), then invoke the resulting hook
inside any composable.

```kotlin
// Define factory once — survives the app lifecycle
val GetUser = queryFactoryOf<GetUserInput, User, ApiException>(cache = cache) { input ->
    withContext(Dispatchers.IO) { api.getUser(input.id) }
}

// Wrap it for Compose
val rememberGetUser = GetUser.forCompose()

// Use inside any composable
@Composable
fun UserScreen(userId: String) {
    val result = rememberGetUser(input = GetUserInput(userId))
    // result.data, result.fetchState, result.error, result.cachedDataState
}
```

The hook creates and `remember`s an attendant instance tied to the composable's lifecycle. When the
composable leaves composition the instance is cancelled automatically.

---

# `queryFactoryOf().forCompose()`

### With input example

```kotlin
val GetUser = queryFactoryOf<GetUserInput, User, ApiException>(cache = cache) { input ->
    withContext(Dispatchers.IO) { api.getUser(input.id) }
}

val rememberGetUser = GetUser.forCompose()

@Composable
fun UserProfile(userId: String) {
    val result = rememberGetUser(
        input = GetUserInput(userId), // null = disabled, won't fetch
        enabled = true,               // set false to prevent auto-fetch
        launchImmediately = true,     // fetch on first composition
        refetchInterval = 30.seconds, // optional polling interval
        onSuccess = { user -> /* side effect on each successful fetch */ },
        onError = { error -> /* side effect on each error */ },
    )

    when (result.fetchState) {
        FetchState.IDLE    -> { /* waiting */ }
        FetchState.LOADING -> CircularProgressIndicator()
        FetchState.SUCCESS -> UserCard(result.data!!)
        FetchState.ERROR   -> ErrorMessage(result.error!!)
    }

    // result.data: User?
    // result.error: ApiException?
    // result.cachedDataState: CacheAndFetchState
    // result.query: suspend (GetUserInput) -> Unit  — imperative refetch
}
```

### Without input example

```kotlin
val GetCurrentUser = queryFactoryOf<CurrentUserKey, User, ApiException>(
    cache = cache,
    cacheKey = CurrentUserKey()
) {
    withContext(Dispatchers.IO) { api.getCurrentUser() }
}

val rememberGetCurrentUser = GetCurrentUser.forCompose()

@Composable
fun AppHeader() {
    val result = rememberGetCurrentUser(
        enabled = true,
        launchImmediately = true,
        refetchInterval = null,
        onSuccess = { user -> /* side effect */ },
        onError = { error -> /* side effect */ },
    )

    Text("Welcome, ${result.data?.name ?: "Guest"}")

    // result.query: suspend () -> Unit  — imperative refetch (no input)
}
```

---

# `mutationFactoryOf().forCompose()`

Mutations are imperative — no `enabled` or `launchImmediately`. Call `mutate` when the user takes an action.
The `forCompose()` hook returns a `mutate` function alongside state.

### With input example

```kotlin
val UpdateUser = mutationFactoryOf<UpdateUserInput, User, ApiException>(cache = cache) { input ->
    withContext(Dispatchers.IO) { api.updateUser(input.userId, input.name) }
}

val rememberUpdateUser = UpdateUser.forCompose()

@Composable
fun EditUserForm(userId: String) {
    val mutation = rememberUpdateUser()

    // mutation.fetchState, mutation.data, mutation.error

    Button(
        enabled = mutation.fetchState != FetchState.LOADING,
        onClick = {
            coroutineScope.launch {
                mutation.mutate(
                    queryInput = UpdateUserInput(userId, "New Name"),
                    optimisticUpdate = { input ->
                        GetUser.optimisticUpdater(GetUserInput(input.userId)) { current ->
                            current?.copy(name = input.name)
                        }
                    },
                    onSuccess = { updatedUser -> navigateBack() },
                    onError = { error -> showSnackbar(error.message) },
                )
            }
        }
    ) { Text(if (mutation.fetchState == FetchState.LOADING) "Saving..." else "Save") }
}
```

### Without input example

```kotlin
val Logout = mutationFactoryOf<LogoutResult, ApiException>(cache = cache) {
    withContext(Dispatchers.IO) { api.logout() }
}

val rememberLogout = Logout.forCompose()

@Composable
fun AccountScreen() {
    val mutation = rememberLogout()

    Button(onClick = {
        coroutineScope.launch {
            mutation.mutate(
                optimisticUpdate = {
                    GetCurrentUser.optimisticUpdater { _ -> null }
                },
                onSuccess = { _ -> navigateToLogin() },
                onError = { error -> showError(error) },
            )
        }
    }) { Text("Log out") }
}
```

### Fire-and-forget with input example

```kotlin
val DeleteUser = mutationFactoryWithNoOutputOf<DeleteUserInput, ApiException>(cache = cache) { input ->
    withContext(Dispatchers.IO) { api.deleteUser(input.userId) }
}

val rememberDeleteUser = DeleteUser.forCompose()

@Composable
fun DeleteButton(userId: String) {
    val mutation = rememberDeleteUser()

    // mutation.fetchState, mutation.error (no .data — fire-and-forget)

    Button(onClick = {
        coroutineScope.launch {
            mutation.mutate(
                queryInput = DeleteUserInput(userId),
                optimisticUpdate = { input ->
                    GetUser.optimisticUpdater(GetUserInput(input.userId)) { _ -> null }
                },
                onSuccess = { navigateBack() },
                onError = { error -> showError(error) },
            )
        }
    }) { Text("Delete") }
}
```

### Fire-and-forget without input example

```kotlin
val ClearNotifications = mutationFactoryWithNoOutputOf<ApiException>(cache = cache) {
    withContext(Dispatchers.IO) { api.clearAllNotifications() }
}

val rememberClearNotifications = ClearNotifications.forCompose()

@Composable
fun NotificationsHeader() {
    val mutation = rememberClearNotifications()

    Button(onClick = {
        coroutineScope.launch {
            mutation.mutate(
                optimisticUpdate = {
                    NotificationsQuery.optimisticUpdater { _ -> emptyList() }
                },
                onSuccess = { showToast("Cleared") },
                onError = { error -> showError(error) },
            )
        }
    }) { Text("Clear all") }
}
```

---

# `flowFactoryOf().forCompose()`

### With input example

```kotlin
val PriceStream = flowFactoryOf<PriceStreamInput, Price, ApiException>(cache = cache) { input ->
    api.priceWebSocket(input.ticker) // returns Flow<Price>
}

val rememberPriceStream = PriceStream.forCompose()

@Composable
fun PriceTicker(ticker: String) {
    val result = rememberPriceStream(
        input = PriceStreamInput(ticker),
        enabled = true,
        launchImmediately = true,
        onEachSuccess = { price -> /* called on every emission */ },
        onError = { error -> /* stream closed with error */ },
    )

    when (result.fetchState) {
        FetchState.IDLE    -> { /* not yet launched */ }
        FetchState.LOADING -> { /* waiting for first emission */ }
        FetchState.SUCCESS -> Text("${result.data?.symbol}: $${result.data?.price}")
        FetchState.ERROR   -> Text("Stream error")
    }

    // result.data: Price?  — most recent emission
    // result.launch: suspend (input, onEachSuccess, onError) -> Unit  — imperative re-launch
}
```

### Without input example

```kotlin
val NotificationStream = flowFactoryOf<NotificationStreamKey, AppNotification, ApiException>(
    cache = cache,
    cacheKey = NotificationStreamKey()
) {
    api.notificationStream() // returns Flow<AppNotification>
}

val rememberNotificationStream = NotificationStream.forCompose()

@Composable
fun NotificationBadge() {
    val result = rememberNotificationStream(
        enabled = true,
        launchImmediately = true,
        onEachSuccess = { notification -> /* side effect per emission */ },
        onError = { error -> reconnect() },
    )

    // result.data: AppNotification?  — most recent notification
    // result.launch: suspend (onEachSuccess, onError) -> Unit  — imperative re-launch
    Badge(result.data?.count ?: 0)
}
```

---

# `infiniteQueryFactoryOf().forCompose()`

### With input example

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
    withContext(Dispatchers.IO) { api.getFeed(input.category, page) }
}

val rememberFeed = Feed.forCompose()

@Composable
fun FeedScreen(category: String) {
    val result = rememberFeed(
        input = FeedInput(category),
        enabled = true,
        launchImmediately = true, // fetches first page automatically
        onSuccess = { pageData -> /* called after each page load */ },
        onError = { error -> /* handle */ },
    )

    // result.data: List<PagedData<Int?, FeedItem>>?
    // result.hasNextPage: Boolean
    // result.hasPreviousPage: Boolean
    // result.fetchNextPage: suspend (input, onSuccess, onError) -> Unit
    // result.fetchPreviousPage: suspend (input, onSuccess, onError) -> Unit
    // result.fetchPage: suspend (input, page, onSuccess, onError) -> Unit

    LazyColumn {
        result.data?.forEach { page ->
            item { FeedItemView(page.data) }
        }

        if (result.hasNextPage) {
            item {
                Button(onClick = {
                    coroutineScope.launch {
                        result.fetchNextPage(
                            queryInput = FeedInput(category),
                            onSuccess = { /* page loaded */ },
                            onError = { error -> showError(error) },
                        )
                    }
                }) { Text("Load More") }
            }
        }
    }
}
```

### Without input example

```kotlin
val ActivityFeed = infiniteQueryFactoryOf<Int, ActivityItem, ApiException>(
    cache = cache,
    cacheKey = ActivityFeedKey(),
    initialPageParam = 1,
    getNextPageParam = { pages ->
        val last = pages.lastOrNull()?.page ?: 0
        if (last >= MAX_PAGES) PageParam.None else PageParam.Value(last + 1)
    }
) { page ->
    withContext(Dispatchers.IO) { api.getActivity(page) }
}

val rememberActivityFeed = ActivityFeed.forCompose()

@Composable
fun ActivityScreen() {
    val result = rememberActivityFeed(
        enabled = true,
        launchImmediately = true,
        onSuccess = { pageData -> /* called after each page load */ },
        onError = { error -> /* handle */ },
    )

    // result.fetchNextPage: suspend (onSuccess, onError) -> Unit  — no input parameter
    // result.fetchPreviousPage: suspend (onSuccess, onError) -> Unit
    // result.fetchPage: suspend (page, onSuccess, onError) -> Unit

    LazyColumn {
        result.data?.forEach { page ->
            item { ActivityItemView(page.data) }
        }

        if (result.hasNextPage) {
            item {
                LaunchedEffect(Unit) {
                    result.fetchNextPage(onSuccess = null, onError = null)
                }
            }
        }
    }
}
```

---

## Recipes

### Stale-While-Revalidate

The composable hook reads from cache immediately on first composition, then fetches fresh data in the
background. Use `cachedDataState` to show stale data with a refresh indicator:

```kotlin
@Composable
fun UserCard(userId: String) {
    val result = rememberGetUser(input = GetUserInput(userId))

    when (result.cachedDataState) {
        CacheAndFetchState.NO_DATA_CACHED_AND_LOADING -> Skeleton()
        CacheAndFetchState.DATA_CACHED_AND_LOADING -> {
            UserContent(result.data!!)
            LinearProgressIndicator()
        }
        CacheAndFetchState.DATA_CACHED_AND_SUCCESS -> UserContent(result.data!!)
        CacheAndFetchState.DATA_CACHED_AND_ERROR -> {
            UserContent(result.data!!)
            ErrorBanner(result.error!!)
        }
        CacheAndFetchState.NO_DATA_CACHED_AND_ERROR -> ErrorScreen(result.error!!)
        else -> { }
    }
}
```

### Refetch on Mutation Success

```kotlin
@Composable
fun UserEditScreen(userId: String) {
    val user = rememberGetUser(input = GetUserInput(userId))
    val updateMutation = rememberUpdateUser()

    Button(onClick = {
        coroutineScope.launch {
            updateMutation.mutate(
                queryInput = UpdateUserInput(userId, "Alice"),
                onSuccess = { _ ->
                    // All composables observing this key update automatically
                    GetUser.refetch(GetUserInput(userId))
                },
            )
        }
    }) { Text("Update") }
}
```

### Optimistic Update with Rollback

```kotlin
@Composable
fun UserEditScreen(userId: String) {
    val mutation = rememberUpdateUser()

    Button(onClick = {
        coroutineScope.launch {
            mutation.mutate(
                queryInput = UpdateUserInput(userId, "Alice"),
                optimisticUpdate = { input ->
                    GetUser.optimisticUpdater(GetUserInput(input.userId)) { current ->
                        current?.copy(name = input.name) ?: User(name = input.name)
                    }
                },
                // Cache automatically rolls back if the mutation throws
            )
        }
    }) { Text("Update") }
}
```

### Conditional Fetching

Pass `null` as the input or set `enabled = false` to prevent fetching:

```kotlin
@Composable
fun OrderDetails(orderId: String?) {
    val result = rememberGetOrder(
        input = orderId?.let { GetOrderInput(it) }, // won't fetch until orderId is non-null
    )

    if (orderId == null) {
        Text("Select an order")
    } else {
        OrderContent(result)
    }
}
```

### Polling with refetchInterval

```kotlin
val result = rememberGetUser(
    input = GetUserInput("123"),
    refetchInterval = 10.seconds, // refetches every 10s after first load
)
```

### Accumulated Flow (Chat, Event Log)

Use `scan()` on your flow to accumulate emissions into a list:

```kotlin
val Chat = flowFactoryOf<ChatInput, List<Message>, Exception>(cache = cache) { input ->
    chatWebSocket(input.roomId)
        .scan(emptyList()) { messages, newMessage -> messages + newMessage }
}

val rememberChat = Chat.forCompose()

@Composable
fun ChatScreen(roomId: String) {
    val result = rememberChat(input = ChatInput(roomId))

    LazyColumn {
        result.data?.forEach { message ->
            item { MessageBubble(message) }
        }
    }
}
```

### Shared State Across Composables

Because the cache is the source of truth, two composables observing the same key stay in sync — no
prop-drilling or shared ViewModel required:

```kotlin
// Screen A
@Composable
fun UserHeader(userId: String) {
    val result = rememberGetUser(input = GetUserInput(userId))
    Text(result.data?.name ?: "")
}

// Screen B — different composable, same cache key
@Composable
fun UserBadge(userId: String) {
    val result = rememberGetUser(input = GetUserInput(userId))
    Avatar(result.data?.avatarUrl)
}

// When either triggers a fetch or mutation, both update automatically
```

## Gotchas

- **Input change creates a new instance** — changing the `input` parameter triggers `remember(input)`, creating a
  fresh query/flow instance and cancelling the old one. The new instance starts from cache if the key is already
  populated.
- **`null` input prevents fetching** — passing `null` as input is equivalent to `enabled = false` for the initial
  auto-fetch. The hook stays at IDLE with whatever is currently in cache.
- **`launchImmediately = false`** — use when you want to delay the first fetch (e.g., behind a user action). Call
  `result.query(input)` / `result.launch(input)` imperatively when ready.
- **`launch`/`async` is fine in `onSuccess`/`onError`** — these callbacks run after state transitions are complete,
  so fire-and-forget work (navigation, refetching other queries, analytics) is safe here.
- **Flow deduplication** — if two composables call `launch` for the same input simultaneously, only one
  underlying subscription starts. The second call is a no-op; both composables observe the same cache entry.
