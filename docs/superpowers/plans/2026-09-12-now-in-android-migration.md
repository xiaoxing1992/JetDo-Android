# Now in Android 风格架构迁移实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不改变 JetDo Android 通知功能行为的前提下，建立 Now in Android 风格的多模块边界，并完成通知垂直切片迁移。

**Architecture:** 新增 `core:common`、`core:model`、`core:network`、`core:data`、`core:datastore`、`core:designsystem` 和 `feature:notification`。网络桥接由 `app` 提供实现并绑定到 `core:network` 契约；通知 Repository 位于 `core:data`；通知状态、ViewModel 和 Compose 页面位于 `feature:notification`；`app` 只负责 Hilt 组装、当前用户适配和顶层导航。

**Tech Stack:** Kotlin 2.4.10, AGP 9.3.1, Jetpack Compose BOM 2026.06.01, Hilt 2.60.1, Kotlin Coroutines 1.8.0, kotlinx.serialization, Compose Navigation。

**Spec:** `docs/superpowers/specs/2026-09-12-now-in-android-migration-design.md`

## Global Constraints

- 保留当前 `ArkWebNetworkBridge`、Cloudflare Challenge 流程和通知用户行为。
- 不修改或回滚迁移分支上已有的未提交业务改动。
- `feature:notification` 不依赖 `:app`、`ArkWebNetworkBridge`、旧 `NotificationApi` 或 `NotificationEventBus`。
- Repository 返回 feature 可消费的稳定模型和错误结果，不泄漏网络 DTO。
- 每个新行为先写失败测试并运行确认失败，再写最小实现。
- 每个任务完成后运行该任务指定的测试或编译命令。

---

### Task 1: 建立 Gradle 多模块骨架

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `gradle/libs.versions.toml`
- Create: `core/model/build.gradle.kts`
- Create: `core/network/build.gradle.kts`
- Create: `core/data/build.gradle.kts`
- Create: `core/datastore/build.gradle.kts`
- Create: `core/designsystem/build.gradle.kts`
- Create: `feature/notification/build.gradle.kts`

**Interfaces:**
- Produces Gradle modules named `:core:common`, `:core:model`, `:core:network`, `:core:data`, `:core:datastore`, `:core:designsystem`, and `:feature:notification`.
- Produces dependency direction `feature:notification -> core:data -> core:network/core:model`.

- [x] **Step 1: Write the module-graph verification test**

Create a lightweight Gradle configuration check in the plan execution notes by asserting the expected module names with `./gradlew projects`; no production behavior is changed by this check.

- [x] **Step 2: Run the verification before adding modules**

Run: `./gradlew projects --console=plain`

Expected: output contains only `:app` and does not contain `:core:model`.

- [x] **Step 3: Add module declarations and version aliases**

Add the seven modules to `settings.gradle.kts`. Add the Coroutines version and library aliases needed by `core:data` and tests. Each Android library module uses `com.android.library`, Kotlin Compose only where Compose code exists, and the existing Hilt/KSP plugin aliases where ViewModel injection is used.

- [x] **Step 4: Add minimal module build files**

Configure:

```kotlin
// core/model: Kotlin/Android library with kotlinx serialization
// core/network: Android library, depends on core:model only if a shared model is needed
// core/data: depends on core:model and core:network; includes serialization and coroutines
// core/datastore: Android library placeholder for the next migration slice
// core/designsystem: Android library with Compose, Coil, and Timber
// feature/notification: Android library with Compose, Hilt, lifecycle-compose, core modules
```

Use the existing `compileSdk`, Java 17, Compose BOM, Hilt, KSP, and test libraries. Do not add a new third-party dependency beyond the Coroutines alias already present in the local Gradle cache.

- [x] **Step 5: Verify the new graph resolves**

Run: `./gradlew projects --console=plain`

Expected: all seven modules are listed and configuration exits with code 0.

---

### Task 2: Extract stable model and network contracts

**Files:**
- Create: `core/model/src/main/kotlin/com/arkdo/android/core/model/NotificationItem.kt`
- Create: `core/network/src/main/kotlin/com/arkdo/android/core/network/NetworkTypes.kt`
- Modify: `app/src/main/kotlin/com/arkdo/android/services/network/NetworkTypes.kt`
- Modify: `gradle/libs.versions.toml` only if the module test dependency aliases are missing

**Interfaces:**
- Produces `com.arkdo.android.core.model.NotificationItem` with the existing notification display fields.
- Produces `com.arkdo.android.core.network.JsonNetworkClient` with the existing request methods and `NetworkResult`/`NetworkError` types.
- The app's old `services.network` package remains source-compatible as a parallel legacy contract; the new notification slice uses the canonical `core:network` contract through an app-local adapter.

- [x] **Step 1: Write model compatibility tests**

Add `core/model/src/test/kotlin/com/arkdo/android/core/model/NotificationItemTest.kt` with a test that constructs a notification item and verifies `copy(read = true)` changes only the read field.

- [x] **Step 2: Run the model test to verify the missing module fails**

Run: `./gradlew :core:model:test --console=plain`

Expected: fail because the module and `NotificationItem` do not exist yet.

- [x] **Step 3: Implement the stable model and network contracts**

Move only the presentation-neutral `NotificationItem` shape into `core:model`. Copy the existing network contract into `core:network` without importing any app package. Keep the old app declarations as a source-compatible parallel contract while the migrated notification path uses the canonical types through its adapter.

- [x] **Step 4: Run the model test and app compilation**

Run: `./gradlew :core:model:test :app:compileDebugKotlin --console=plain`

Expected: the model test passes and all existing app services compile through the compatibility aliases.

---

### Task 3: Add the notification Repository contract and implementation

**Files:**
- Create: `core/data/src/main/kotlin/com/arkdo/android/core/data/notification/NotificationRepository.kt`
- Create: `core/data/src/main/kotlin/com/arkdo/android/core/data/notification/DefaultNotificationRepository.kt`
- Create: `core/data/src/test/kotlin/com/arkdo/android/core/data/notification/DefaultNotificationRepositoryTest.kt`
- Create: `core/data/src/test/kotlin/com/arkdo/android/core/data/notification/FakeJsonNetworkClient.kt`

**Interfaces:**

```kotlin
interface NotificationRepository {
    suspend fun load(limit: Int, offset: Int, recent: Boolean): NotificationPageResult
    suspend fun markRead(id: Long): Boolean
    suspend fun markAllRead(): Boolean
}

data class NotificationPage(val items: List<NotificationItem>)

sealed interface NotificationPageResult {
    data class Success(val page: NotificationPage) : NotificationPageResult
    data class Failure(val message: String, val requiresChallenge: Boolean) : NotificationPageResult
}
```

The Repository implementation owns Discourse notification DTOs, JSON parsing, visual metadata mapping, and conversion of network failures into `NotificationPageResult`. It must not expose `RawNotification`, `NetworkResult`, or `JsonNetworkClient` to feature modules.

- [x] **Step 1: Write the failing repository tests**

Cover these exact behaviors:

```kotlin
@Test fun load_maps_valid_response_to_notification_items()
@Test fun load_returns_failure_when_network_client_fails()
@Test fun load_ignores_notifications_without_id_or_type()
@Test fun markRead_returns_the_network_success_flag()
```

The fake client returns a JSON payload containing one actor notification and one malformed notification. Assertions verify the mapped actor, title, icon metadata, filtered malformed row, and failure message.

- [x] **Step 2: Run the repository tests to verify failure**

Run: `./gradlew :core:data:test --tests '*DefaultNotificationRepositoryTest' --console=plain`

Expected: fail because `NotificationRepository`, `DefaultNotificationRepository`, and the fake client do not exist.

- [x] **Step 3: Implement the Repository and private DTOs**

Port the existing notification parsing and type-to-visual mapping into `core:data`. Keep DTOs private to the data package. Use the canonical `core:network` contract and return stable failure messages. Preserve the existing API paths, page size semantics, recent flag, mark-read path, referrer, and unknown-type fallback behavior.

- [x] **Step 4: Run repository tests to verify green**

Run: `./gradlew :core:data:test --tests '*DefaultNotificationRepositoryTest' --console=plain`

Expected: all repository tests pass.

---

### Task 4: Move notification state and ViewModel to `feature:notification`

**Files:**
- Create: `feature/notification/src/main/kotlin/com/arkdo/android/feature/notification/NotificationViewModel.kt`
- Create: `feature/notification/src/test/kotlin/com/arkdo/android/feature/notification/NotificationViewModelTest.kt`
- Create: `feature/notification/src/test/kotlin/com/arkdo/android/feature/notification/FakeNotificationRepository.kt`
- Delete: `app/src/main/kotlin/com/arkdo/android/ui/notification/NotificationViewModel.kt`

**Interfaces:**

```kotlin
data class NotificationUiState(
    val items: List<NotificationItem> = emptyList(),
    val initialLoading: Boolean = false,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = true,
    val error: String? = null
)

class NotificationViewModel(
    repository: NotificationRepository,
    unreadStore: NotificationUnreadStore
) : ViewModel()
```

`NotificationUnreadStore` is a small interface in `core:data` with `publish(unread, highPriority)`, `decrement(highPriority)`, and `currentHighPriority()`. The feature depends on this interface, not the app event bus.

- [x] **Step 1: Write failing ViewModel tests**

Cover initial load success, failed initial load, retry after failure, append load-more behavior, optimistic item read rollback, and mark-all-read rollback. Tests use a fake Repository and fake unread store, and assert state values rather than implementation calls.

- [x] **Step 2: Run ViewModel tests to verify failure**

Run: `./gradlew :feature:notification:test --tests '*NotificationViewModelTest' --console=plain`

Expected: fail because the feature ViewModel and contracts do not exist.

- [x] **Step 3: Implement the feature ViewModel**

Port the current state transitions to the Repository contract. Use `viewModelScope`, preserve the single in-flight load guard, page offsets, optimistic read behavior, and unread-store updates. Add `@HiltViewModel` and constructor injection in the feature module.

- [x] **Step 4: Run ViewModel tests to verify green**

Run: `./gradlew :feature:notification:test --tests '*NotificationViewModelTest' --console=plain`

Expected: all ViewModel tests pass.

---

### Task 5: Move notification Compose UI and expose an app adapter

**Files:**
- Create: `core/common/src/main/kotlin/com/arkdo/android/core/common/RelativeTime.kt`
- Create: `core/designsystem/src/main/kotlin/com/arkdo/android/core/designsystem/FaIcon.kt`
- Create: `core/designsystem/src/main/kotlin/com/arkdo/android/core/designsystem/NotificationAvatar.kt`
- Create: `feature/notification/src/main/kotlin/com/arkdo/android/feature/notification/NotificationScreen.kt`
- Create: `feature/notification/src/main/kotlin/com/arkdo/android/feature/notification/NotificationRoute.kt`
- Replace: `app/src/main/kotlin/com/arkdo/android/ui/notification/NotificationScreen.kt` with a thin compatibility wrapper

**Interfaces:**

```kotlin
@Composable
fun NotificationRoute(
    loggedIn: Boolean,
    onRequestLogin: () -> Unit,
    onOpenTopic: (Long, String?, String?, Int?) -> Unit,
    onOpenUser: (String, String?, String?) -> Unit,
    showTopBar: Boolean = true
)
```

The feature screen consumes only `NotificationUiState`, `NotificationItem`, core design-system components, and callbacks. The app wrapper reads `CurrentUserHolder` and calls `LoginGate.requestLogin()`, preserving the existing caller signature in `RootTabScreen` while keeping those app concerns out of the feature module.

- [x] **Step 1: Add the UI test before moving the screen**

Create a Compose test in `feature/notification/src/androidTest` or a JVM state-level test when the local emulator is unavailable. The required assertions are: a successful state renders the notification title, and an error state renders the retry action.

- [x] **Step 2: Run the UI test to verify the moved screen is absent**

Run: `./gradlew :feature:notification:testDebugUnitTest --console=plain`

Expected: fail because the feature screen and test harness do not exist.

- [x] **Step 3: Move the screen and shared notification-only UI helpers**

Move the current notification layout without changing user-facing strings, row behavior, pagination trigger, pull-to-refresh, mark-all-read action, topic navigation arguments, or user navigation arguments. Replace app-only `UserAvatar` with the shared `NotificationAvatar` and use `core:common` for relative time formatting. Keep the app wrapper source-compatible.

- [x] **Step 4: Run feature compilation and UI/state tests**

Run: `./gradlew :feature:notification:testDebugUnitTest :feature:notification:compileDebugKotlin --console=plain`

Expected: tests pass and the feature compiles without an `app` dependency.

---

### Task 6: Wire Hilt, app navigation, and compatibility boundaries

**Files:**
- Modify: `app/src/main/kotlin/com/arkdo/android/di/AppModule.kt`
- Create: `app/src/main/kotlin/com/arkdo/android/di/NotificationModule.kt`
- Modify: `app/src/main/kotlin/com/arkdo/android/ui/screens/RootTabScreen.kt` only if the compatibility wrapper signature requires it
- Modify: `app/src/main/kotlin/com/arkdo/android/ui/notification/NotificationScreen.kt`
- Modify: `app/build.gradle.kts`

**Interfaces:**
- Hilt provides `NotificationRepository` using `DefaultNotificationRepository`.
- Hilt provides the core `JsonNetworkClient` through an app-local adapter delegating to `ArkWebNetworkBridge`.
- Hilt provides `NotificationUnreadStore` through an app-local adapter delegating to `NotificationEventBus`.
- `feature:notification` is added as an app dependency; `app` no longer provides the old `NotificationApi` for the notification screen.

- [x] **Step 1: Add a DI boundary test/compile check**

Use the feature compilation plus app compile as the dependency-boundary check. Add no test-only production coupling to `ArkWebNetworkBridge`.

- [x] **Step 2: Run the check before wiring**

Run: `./gradlew :feature:notification:compileDebugKotlin :app:compileDebugKotlin --console=plain`

Expected: fail because the feature dependency and Hilt bindings are not wired.

- [x] **Step 3: Add the Hilt providers and app adapter**

Implement the network adapter by delegating every method of the core `JsonNetworkClient` to the existing app bridge. Implement the unread-store adapter by forwarding to `NotificationEventBus`. Add module dependencies and replace the old `NotificationApi` provider only after the new Repository is available.

- [x] **Step 4: Wire the compatibility wrapper and remove old ViewModel usage**

Keep `RootTabScreen` behavior unchanged. The old app `NotificationScreen` function delegates to `NotificationRoute`, passing current login state and callbacks. Remove the old app ViewModel and old screen-private notification state logic.

- [x] **Step 5: Verify the app and feature together**

Run: `./gradlew :feature:notification:compileDebugKotlin :app:compileDebugKotlin --console=plain`

Expected: both modules compile and no feature source imports an app package.

---

### Task 7: Full verification and migration handoff

**Files:**
- Modify: `ARCHITECTURE.md` to document the new module graph and migration rule.
- Modify: `MIGRATION_REPORT.md` with the completed notification slice and next migration targets.

- [x] **Step 1: Run focused tests**

Run: `./gradlew :core:model:test :core:data:test :feature:notification:testDebugUnitTest --console=plain`

Expected: exit code 0 with no failed tests.

- [x] **Step 2: Run the complete unit test suite**

Run: `./gradlew :app:testDebugUnitTest --console=plain`

Expected: existing app tests and migrated feature tests pass.

- [x] **Step 3: Build the debug APK**

Run: `./gradlew :app:assembleDebug --no-daemon --console=plain`

Expected: exit code 0 and a debug APK is produced.

- [x] **Step 4: Verify the diff and architecture constraints**

Run: `git diff --check`; `rg -n "com\.arkdo\.android\.(services|ui|data)" feature/notification core/data core/model core/network core/designsystem core/common`; `git status --short`.

Expected: no whitespace errors; feature/core modules contain no imports from the old app-only packages; existing unrelated modifications remain present.

- [ ] **Step 5: Commit only migration files**

Stage the new modules, migrated notification files, docs, and Gradle changes. Do not stage pre-existing modified files unless they are explicitly part of the migration diff. Commit with:

```bash
git add settings.gradle.kts build.gradle.kts gradle/libs.versions.toml core feature app/build.gradle.kts app/src/main/kotlin/com/arkdo/android/di app/src/main/kotlin/com/arkdo/android/ui/notification ARCHITECTURE.md MIGRATION_REPORT.md docs/superpowers
git commit -m "refactor: migrate notification to modular architecture"
```
