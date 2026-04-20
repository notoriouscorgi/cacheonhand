# Cache On Hand - Compose

Compose Multiplatform convenience wrappers for Cache On Hand. Turns query, mutation, flow, and infinite query factories into composable hooks with automatic lifecycle management.

## Installation

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.github.notoriouscorgi:cacheonhand-compose:<version>")
    // Transitively includes cacheonhand-attendants and cacheonhand
}
```

**Platforms:** JVM, Android, iOS (x64/arm64/simulator), WASM-JS

## Quick Start

```kotlin
// 1. Define your cache and factory (typically in a DI module)
val cache = OnHandCache()

val getUserFactory = queryFactoryOf<GetUserInput, User, ApiException>(
    cache = cache,
) { input ->
    withContext(Dispatchers.IO) {
        api.getUser(input.userId)
    }
}

// 2. Create a composable wrapper
val rememberGetUser = composeQueryFactoryOf(getUserFactory)

// 3. Use in any composable
@Composable
fun UserScreen(userId: String) {
    val result = rememberGetUser(input = GetUserInput(userId))

    when (result.fetchState) {
        FetchState.LOADING -> CircularProgressIndicator()
        FetchState.SUCCESS -> Text("Hello, ${result.data?.name}")
        FetchState.ERROR -> Text("Error: ${result.error?.message}")
        FetchState.IDLE -> { }
    }
}
```

## Composable Query

```kotlin
val rememberGetUser = composeQueryFactoryOf(getUserFactory)

@Composable
fun UserProfile(userId: String) {
    val result = rememberGetUser(
        input = GetUserInput(userId),  // null = disabled (won't fetch)
        enabled = true,                // set false to prevent auto-fetch
        launchImmediately = true,      // fetch on first composition
        refetchInterval = 30.seconds,  // optional polling interval
        onSuccess = { user -> /* side effect */ },
        onError = { error -> /* side effect */ },
    )

    // result.data: User?
    // result.fetchState: FetchState
    // result.error: ApiException?
    // result.cachedDataState: CacheAndFetchState
    // result.query: suspend (input) -> Unit  (manual refetch)
}
```

### Without Input

```kotlin
val rememberCurrentUser = composeQueryFactoryOf(currentUserFactory)

@Composable
fun AppHeader() {
    val result = rememberCurrentUser()
    Text("Welcome, ${result.data?.name ?: "Guest"}")
}
```

### Conditional Fetching

Pass `null` as the input or set `enabled = false` to prevent fetching:

```kotlin
@Composable
fun ConditionalQuery(userId: String?) {
    // Won't fetch until userId is non-null
    val result = rememberGetUser(input = userId?.let { GetUserInput(it) })
}
```

### Polling with refetchInterval

Automatically refetch on an interval after the initial fetch:

```kotlin
val result = rememberGetUser(
    input = GetUserInput("123"),
    refetchInterval = 10.seconds, // refetches every 10s after first load
)
```

## Composable Mutation

Mutations are imperative — no `enabled` or `launchImmediately`. Call `mutate` when the user takes an action.

```kotlin
val rememberUpdateUser = composeMutationFactoryOf(updateUserFactory)

@Composable
fun EditUserForm(userId: String) {
    val mutation = rememberUpdateUser()

    Button(onClick = {
        coroutineScope.launch {
            mutation.mutate(
                queryInput = UpdateUserInput(userId, "New Name"),
                optimisticUpdate = { input ->
                    getUserFactory.optimisticUpdater(GetUserInput(input.userId)) { user ->
                        user?.copy(name = input.name) ?: User(name = input.name)
                    }
                },
                onSuccess = { user -> /* navigate back */ },
                onError = { error -> /* show snackbar */ },
            )
        }
    }) {
        Text(if (mutation.fetchState == FetchState.LOADING) "Saving..." else "Save")
    }
}
```

### Fire and Forget

For mutations that don't return data:

```kotlin
val rememberDeleteUser = composeMutationFactoryOf(deleteUserFactory)

@Composable
fun DeleteButton(userId: String) {
    val mutation = rememberDeleteUser()

    Button(onClick = {
        coroutineScope.launch {
            mutation.mutate(
                queryInput = DeleteUserInput(userId),
                onSuccess = { /* navigate */ },
            )
        }
    }) { Text("Delete") }
}
```

## Composable Flow

Subscribe to reactive data sources with automatic lifecycle management.

```kotlin
val rememberPriceStream = composeFlowFactoryOf(priceStreamFactory)

@Composable
fun PriceTicker(ticker: String) {
    val result = rememberPriceStream(
        input = PriceStreamInput(ticker),
        launchImmediately = true,
        onEachSuccess = { price -> /* log each emission */ },
        onError = { error -> /* handle stream error */ },
    )

    Text("${result.data?.symbol}: $${result.data?.price}")
}
```

## Composable Infinite Query

Paginated data with forward/backward navigation.

```kotlin
val rememberFeed = composeInfiniteQueryFactoryOf(feedFactory)

@Composable
fun FeedScreen(category: String) {
    val result = rememberFeed(input = FeedInput(category))

    LazyColumn {
        result.data?.forEach { page ->
            item { FeedItem(page.data) }
        }

        if (result.hasNextPage) {
            item {
                Button(onClick = {
                    coroutineScope.launch {
                        result.fetchNextPage(FeedInput(category), null, null)
                    }
                }) { Text("Load More") }
            }
        }
    }

    if (result.fetchState == FetchState.LOADING) {
        CircularProgressIndicator()
    }
}
```

## Recipes

### Loading State with Stale Data

Show cached data while refetching using `cachedDataState`:

```kotlin
@Composable
fun UserCard(userId: String) {
    val result = rememberGetUser(input = GetUserInput(userId))

    when (result.cachedDataState) {
        CacheAndFetchState.NO_DATA_CACHED_AND_LOADING -> Skeleton()
        CacheAndFetchState.DATA_CACHED_AND_LOADING -> {
            UserContent(result.data!!) // show stale data
            LinearProgressIndicator()  // with refresh indicator
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

Refresh query data after a mutation completes by calling `refetch` in `onSuccess`:

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
                    // Refetch the query — all composables observing this key update automatically
                    getUserFactory.refetch(GetUserInput(userId))

                    // Or use launch() for fire-and-forget (e.g., refreshing data not on screen)
                    // scope.launch { otherFactory.refetch(OtherInput()) }
                },
            )
        }
    }) { Text("Update") }
}
```

### Optimistic Update with Rollback

For instant UI feedback, use `optimisticUpdate` instead of `refetch`. The cache updates immediately and rolls back if the mutation fails:

```kotlin
@Composable
fun UserEditScreen(userId: String) {
    val user = rememberGetUser(input = GetUserInput(userId))
    val updateMutation = rememberUpdateUser()

    Button(onClick = {
        coroutineScope.launch {
            updateMutation.mutate(
                queryInput = UpdateUserInput(userId, "Alice"),
                optimisticUpdate = { input ->
                    getUserFactory.optimisticUpdater(GetUserInput(input.userId)) { current ->
                        current?.copy(name = input.name) ?: User(name = input.name)
                    }
                },
            )
        }
    }) { Text("Update") }
}
```

### Chat with Accumulated Messages

Use `scan()` to accumulate flow emissions into a list:

```kotlin
val chatFactory = flowFactoryOf<ChatInput, List<Message>, Exception>(
    cache = cache,
) { input ->
    chatWebSocket(input.roomId)
        .scan(emptyList()) { messages, newMessage -> messages + newMessage }
}

val rememberChat = composeFlowFactoryOf(chatFactory)

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

### Disabled Until Ready

Defer fetching until a dependency is available:

```kotlin
@Composable
fun OrderDetails(orderId: String?) {
    val result = rememberGetOrder(
        input = orderId?.let { GetOrderInput(it) },
        // Won't fetch until orderId is non-null
    )

    if (orderId == null) {
        Text("Select an order")
    } else {
        OrderContent(result)
    }
}
```

## Gotchas

- **Input change creates a new instance** — changing the `input` parameter triggers `remember(input)`, creating a fresh query/flow instance. The old instance's scope is cleaned up by Compose.
- **`null` input prevents fetching** — passing null as input is equivalent to `enabled = false` for the initial auto-fetch. The query stays in IDLE with no data.
- **`launch`/`async` is fine in `onSuccess`/`onError`** — these callbacks run after state transitions are complete, so fire-and-forget work (navigation, refetching other queries, analytics) is safe here.
