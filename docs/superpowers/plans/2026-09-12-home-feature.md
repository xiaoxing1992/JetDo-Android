# Home Feature 模块化实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将首页核心话题列表迁移到 `feature:home`，覆盖最新、热门、未读、刷新和分页。

**Architecture:** `feature:home` 只依赖 `core:data`、`core:model` 和 `core:designsystem`。app 通过适配器把现有 `TopicService` 映射为 `HomeRepository`，RootTab 保留导航回调。

**Tech Stack:** Kotlin、Jetpack Compose、Hilt、StateFlow、Coroutines、JUnit。

**Spec:** `docs/superpowers/specs/2026-09-12-home-feature-design.md`

## Global Constraints

- 不改变现有 `/latest.json`、`/hot.json`、`/unread.json` 请求和分页语义。
- feature 模块不得引用 `com.jetdo.android.services`、`ui`、`data.models` 或 `app` 包。
- 本轮不迁移分类抽屉、实时新话题和 TopicDetail。
- 新行为先写失败测试，再写生产实现。

### Task 1: 提取首页稳定模型

**Files:**
- Create: `core/model/src/main/kotlin/com/jetdo/android/core/model/HomeModels.kt`
- Create: `core/model/src/test/kotlin/com/jetdo/android/core/model/HomeModelsTest.kt`

- [x] Write tests for query feed values, `copy`, and topic page model.
- [x] Run `./gradlew :core:model:testDebugUnitTest` and verify the new type failure.
- [x] Add `HomeFeed`, `HomeQuery`, `HomeTopic`, `HomePage`, `HomePageResult` models.
- [x] Run the model tests again.

### Task 2: Add Repository contract and app adapter

**Files:**
- Create: `core/data/src/main/kotlin/com/jetdo/android/core/data/home/HomeRepository.kt`
- Create: `app/src/main/kotlin/com/jetdo/android/di/HomeModule.kt`

- [x] Write repository contract tests with a fake client boundary.
- [x] Run the focused data test and verify failure before implementation.
- [x] Implement the contract directly over `core:network.JsonNetworkClient`.
- [x] Preserve query-to-path mapping, result errors, category/tag mapping and topic deduplication inputs.
- [x] Add the Hilt binding and run data/app compilation.

### Task 3: Add feature ViewModel and screen

**Files:**
- Create: `feature/home/build.gradle.kts`
- Create: `feature/home/src/main/kotlin/com/jetdo/android/feature/home/HomeViewModel.kt`
- Create: `feature/home/src/main/kotlin/com/jetdo/android/feature/home/HomeRoute.kt`
- Create: `feature/home/src/main/kotlin/com/jetdo/android/feature/home/HomeScreen.kt`
- Create: `feature/home/src/test/kotlin/com/jetdo/android/feature/home/HomeViewModelTest.kt`
- Modify: `settings.gradle.kts`, `app/build.gradle.kts`

- [x] Write failing ViewModel tests for load, refresh, append and deduplication.
- [x] Run the focused feature test and verify failure.
- [x] Implement StateFlow state, single in-flight guard and retry behavior.
- [x] Implement Compose tabs, pull-to-refresh, list rows, empty/error/loading states and callbacks.
- [x] Run feature tests and compile.

### Task 4: Wire RootTab compatibility entry

**Files:**
- Create: `app/src/main/kotlin/com/jetdo/android/ui/home/HomeFeatureScreen.kt`
- Modify: `app/src/main/kotlin/com/jetdo/android/ui/screens/RootTabScreen.kt`

- [x] Keep app navigation callbacks and login/profile actions in the compatibility entry.
- [ ] Route the core home list through `HomeRoute` after visual parity verification.
- [x] Leave the old drawer host and advanced HomeViewModel as the active UI implementation for now.
- [ ] Run app unit tests and Debug APK build.

### Task 5: Documentation and verification

**Files:**
- Modify: `ARCHITECTURE.md`
- Modify: `MIGRATION_REPORT.md`
- Modify: this plan

- [x] Document `feature:home` and the intentionally deferred home capabilities.
- [ ] Run `git diff --check` and the module boundary search.
- [ ] Run the focused tests, app tests and `:app:assembleDebug`.
