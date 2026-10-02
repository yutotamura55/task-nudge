# Task Nudge Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. The user explicitly chose this execution method and authorized the design, implementation, independent review, and PR flow.

**Goal:** Build an offline Japanese Android app that reminds one eligible small task at a time and preserves completion history.

**Architecture:** Pure Kotlin `:core` owns deterministic reminder policy. Android `:app` owns a Room database, a serialized reminder engine, WorkManager and notification adapters, and Compose screens. One Application container shares the engine; no server or general DI framework.

**Tech Stack:** JDK 17, Gradle 8.13, AGP 8.13.2, Kotlin 2.2.21, Android 26+, Compose Material 3, Room, WorkManager, JUnit, Robolectric, Compose instrumentation tests.

**Spec:** `docs/superpowers/specs/2026-09-30-task-nudge-design.md` (read it with every task).

## Global Constraints

- Android minSdk 26、compileSdk 36、targetSdk 36。JDK 17、Gradle 8.13、AGP 8.13.2、Kotlin 2.2.21。
- Kotlin、Jetpack Compose、Material 3、Room/SQLite、WorkManager。単一アプリプロセスと純 Kotlin `:core` モジュール。
- 日本語 UI。アカウント・サーバー・分析 SDK・広告・INTERNET 権限は追加しない。
- 初期通知時間はローカル時刻 09:00 以上 21:00 未満。設定可能範囲は開始 0..23 時、終了 1..24 時、開始 < 終了。
- 通知間隔は初期 30 分、選択肢 30/60/120 分。保留は 1 時間固定。通知は同時に最大 1 件。
- タイトルは trim 後 1..100 文字。繰り返しは初期 1 日、整数 1..365 日。タイトルのみの入力で追加できる。
- 完了は同一タスク・同一保存ローカル日で最大 1 件。過去の未完了インスタンスは作らない。
- バックアップは無効。端末移行・クラウド保存・エクスポート・完全削除・完了取消は MVP 対象外。
- main への直接 push、merge、auto-merge、merge queue 投入は行わない。マージはユーザーが行う。
- All app mutations and OS notification side effects go through the shared engine mutex; Room transactions protect related DB writes. No destructive migrations.
- Execute tasks sequentially with independent SDD review gates; do not let several implementers change the same build files concurrently.
- Standard local environment: `source /tmp/task-nudge-toolchain/environment.sh`. Never commit local paths, local.properties, signing secrets, SDKs, or caches.

## Review Focus

- Midnight, daylight saving, and travel: history dates stay stable and yesterday's active notification never blocks today's work (Tasks 1 and 2).
- Rapid taps, stale PendingIntents, and simultaneous worker/receiver: one completion per day and no cancellation of a newer generation (Task 2).
- Process death around post/cancel and missing OS notifications: persisted state recovers without permanently suppressing reminders (Task 2).
- Permission denial, disabled channels, and failed posting: do not consume a delivery opportunity; in-app tracking remains usable (Tasks 2 and 3).
- Long Japanese titles, large text, rotation, empty/archived data: input is preserved, controls remain usable, and history remains accessible (Tasks 3 and 4).

---

### Task 1: Reproducible Android build and pure reminder policy

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`.
- Create: `core/build.gradle.kts`, `core/src/main/kotlin/dev/tasknudge/core/ReminderPolicy.kt`, `core/src/main/kotlin/dev/tasknudge/core/TaskInput.kt`.
- Test: `core/src/test/kotlin/dev/tasknudge/core/ReminderPolicyTest.kt`, `core/src/test/kotlin/dev/tasknudge/core/TaskInputTest.kt`.
- Create: `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/java/dev/tasknudge/MainActivity.kt`, `app/src/main/res/values/strings.xml`.
- Modify: `.gitignore` (Gradle/build/local files only).

**Interfaces:** Task 1 owns and freezes these Android-free declarations (all timestamps are `java.time.Instant`, all dates `java.time.LocalDate`):

```kotlin
data class ReminderTask(
    val id: Long,
    val createdAt: Instant,
    val recurrenceDays: Int = 1,
    val archived: Boolean = false,
    val lastCompletedAt: Instant? = null,
    val lastCompletedDate: LocalDate? = null,
    val completedToday: Boolean = false,
    val snoozedUntil: Instant? = null,
)
data class ReminderSettings(
    val startHour: Int = 9,
    val endHour: Int = 21,
    val intervalMinutes: Int = 30,
)
object ReminderPolicy {
    fun select(tasks: List<ReminderTask>, now: Instant, zone: ZoneId,
               settings: ReminderSettings, lastNotifiedAt: Instant?,
               hasActiveNotification: Boolean): Long?
    fun isWithinHours(now: Instant, zone: ZoneId, settings: ReminderSettings): Boolean
}
data class TaskInput(val title: String, val recurrenceDays: Int)
// Throws IllegalArgumentException for invalid input; UI maps it to Japanese field errors.
fun validateTaskInput(title: String, recurrenceDays: Int = 1): TaskInput
fun validateSettings(settings: ReminderSettings): ReminderSettings
```

- [ ] **Step 1: Verify and pin build dependencies, then create the smallest build.** Check official Maven metadata/AAR minCompileSdk and version compatibility. Start with Room 2.8.4, Compose BOM 2025.10.01, WorkManager 2.10.5, lifecycle 2.9.4, activity-compose 1.11.0, core-ktx 1.17.0, coroutines 1.10.2, JUnit 4.13.2, Robolectric 4.16, AndroidX test runner 1.7.0 and ext-junit 1.3.0. If a candidate is unavailable or incompatible, select a concrete stable compatible version from that artifact's official metadata, record the evidence in the commit description, and pin it in the catalog. No dynamic versions. Use Kotlin kapt 2.2.21 for Room to avoid a separate KSP compatibility axis. Apply Compose compiler plugin 2.2.21. Add Room schema output and exportSchema; Java/Kotlin bytecode target 17. Use `google()` and `mavenCentral()` only. Generate Gradle wrapper from provisioned Gradle and add verified `distributionSha256Sum`.

```kotlin
// settings.gradle.kts essentials
pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "task-nudge"
include(":core", ":app")
// app defaults: namespace/applicationId="dev.tasknudge"; minSdk=26; targetSdk=36
// core: kotlin("jvm"), jvmToolchain(17), JUnit4; app depends on project(":core")
```

Build a minimal Activity with Japanese app name `Task Nudge`; no permission prompt or business screen in this task. Run `./gradlew :core:test :app:assembleDebug` to confirm the skeleton before policy tests. Maven resolution failure is infrastructure, not the expected test failure.

- [ ] **Step 2: Write failing policy and input tests.** Use fixed Instants and IDs. Example:

```kotlin
@Test fun dueSnoozeWinsOverOlderNormalTask() {
    val now = Instant.parse("2026-09-30T03:00:00Z")
    val tasks = listOf(
        ReminderTask(1, now.minusSeconds(172800)),
        ReminderTask(2, now.minusSeconds(86400), snoozedUntil = now),
    )
    assertEquals(2L, ReminderPolicy.select(tasks, now, ZoneId.of("Asia/Tokyo"),
        ReminderSettings(), null, false))
}
@Test fun titleOnlyUsesDailyRecurrence() {
    assertEquals(TaskInput("首のストレッチ", 1), validateTaskInput(" 首のストレッチ "))
}
```

Add cases for oldest completed/never-completed ordering; equal timestamp ID tie; today excluded; archived excluded; future snooze excluded while other candidate selected; recurrence 1 and 365 boundaries; no backlog after long absence; 08:59/09:00/20:59/21:00; start=0/end=24; active prevents another; last delivery 29m59s/30m; persisted future local date after timezone travel; spring/fall DST with local-date recurrence; no candidates; blank/101-char title; 0/366 recurrence; invalid settings and cadence. Run `./gradlew :core:test`; confirm failures are missing policy behavior.

- [ ] **Step 3: Implement deterministic selection and validation.** Filter using local date and Instant separately; check hours, active, and global cadence before selecting. Sort due snoozes first, then `lastCompletedAt ?: createdAt`, then creation and ID. `endHour=24` means end of the current date, never pass 24 to `LocalTime.of`.

```kotlin
val today = now.atZone(zone).toLocalDate()
val due = task.lastCompletedDate == null ||
    ChronoUnit.DAYS.between(task.lastCompletedDate, today) >= task.recurrenceDays
val cooldownPassed = task.snoozedUntil == null || !now.isBefore(task.snoozedUntil)
```

- [ ] **Step 4: Verify deliverable and commit.** Run `./gradlew :core:test :app:assembleDebug` and `git diff --check`. Commit only Task 1 files with `feat: add Android build and reminder policy`. Review core API signatures against this plan before handoff.

### Task 2: Durable task storage and serialized notification lifecycle

**Files:**
- Create: `app/src/main/java/dev/tasknudge/TaskNudgeApplication.kt`, `AppContainer.kt`.
- Create under `app/src/main/java/dev/tasknudge/data/`: `TaskDatabase.kt`, `Entities.kt`, `TaskDao.kt`, `TaskRepository.kt`, `TaskSnapshot.kt`.
- Create under `app/src/main/java/dev/tasknudge/reminders/`: `ReminderEngine.kt`, `NotificationGateway.kt`, `AndroidNotificationGateway.kt`, `ReminderScheduler.kt`, `ReminderWorker.kt`, `ReminderActionReceiver.kt`.
- Create: `app/src/main/res/drawable/ic_notification.xml`, `app/src/main/res/xml/backup_rules.xml`, `app/src/main/res/xml/data_extraction_rules.xml`, `app/schemas/dev.tasknudge.data.TaskDatabase/1.json`.
- Modify: `app/src/main/AndroidManifest.xml`, `app/build.gradle.kts` only as required for Room/WorkManager/tests.
- Test: `app/src/test/java/dev/tasknudge/data/TaskDatabaseTest.kt`, `app/src/test/java/dev/tasknudge/reminders/ReminderEngineTest.kt`, `app/src/test/java/dev/tasknudge/reminders/AndroidNotificationGatewayTest.kt`.

**Interfaces:** Consumes all Task 1 core declarations. Produces these app APIs; implement DTOs in `TaskSnapshot.kt` and expose no Room entities to UI:

```kotlin
data class CompletionRecord(val id: Long, val completedAt: Instant, val localDate: LocalDate)
data class TaskSnapshot(val id: Long, val title: String, val recurrenceDays: Int,
    val archived: Boolean, val createdAt: Instant, val snoozedUntil: Instant?,
    val completions: List<CompletionRecord>)
data class AppSnapshot(val tasks: List<TaskSnapshot>, val settings: ReminderSettings,
    val permissionRequested: Boolean)
interface TaskRepository {
    val snapshots: Flow<AppSnapshot>
    suspend fun saveTask(id: Long?, title: String, recurrenceDays: Int = 1): Long
    suspend fun setArchived(id: Long, archived: Boolean)
    suspend fun completeTask(id: Long)
    suspend fun saveSettings(settings: ReminderSettings)
    suspend fun recordPermissionRequest()
}
enum class ReminderAction { COMPLETE, SNOOZE, DISMISS }
// Application singleton engine; zoneProvider reads ZoneId.systemDefault on each operation.
class ReminderEngine {
    suspend fun evaluate()
    suspend fun handleAction(taskId: Long, token: String, action: ReminderAction)
}
data class ActiveReminder(val taskId: Long, val token: String, val localDate: LocalDate,
    val preparedAt: Instant, val deliveredAt: Instant?)
interface NotificationGateway {
    fun canNotify(): Boolean
    fun activeToken(): String?
    fun post(taskId: Long, token: String, title: String)
    fun cancel()
}
interface ReminderScheduler {
    fun ensurePeriodicWork()
    fun requestEvaluation(delay: Duration = Duration.ZERO)
}
```

Constructor parameters may include DAOs/Clock/zoneProvider/gateway/scheduler, but public operations above remain stable. Repository methods use the engine's single mutation boundary; never introduce a second independent mutex. Prefer engine-owned internal mutation helper with repository facade over circular initialization. `AppContainer` exposes `repository`, `engine`, and `notificationGateway`. `TaskNudgeApplication.container` is an overridable property for test injection. `snapshots` emits DB-backed updates; its completions list supports MVP history and count without extra layers.

- [ ] **Step 1: Write failing database invariants.** Use Room in-memory DB with Robolectric API 35, inject Clock and ZoneId. Create the four tables specified in the design with singleton state/settings and FK/indexes. Test uniqueness, persistence and original local date across zone changes.

```kotlin
@Test fun duplicateSameDayCompletionIsIgnored() = runTest {
    val id = repository.saveTask(null, "首のストレッチ")
    repository.completeTask(id)
    repository.completeTask(id)
    assertEquals(1, repository.snapshots.first().tasks.single().completions.size)
}
```

Also verify archive preserves events, repeat interval edit preserves events, empty DB emits default settings, invalid data rejected before write. Run `./gradlew :app:testDebugUnitTest --tests '*TaskDatabaseTest'` and confirm failing assertions/absent behavior.

- [ ] **Step 2: Implement Room and repository writes.** Entity primitives map to core time types only at the boundary. Unique `(taskId, localDate)` plus INSERT IGNORE handles duplicate completion; related active/snooze updates use `withTransaction`. Read latest completion by actual timestamp; today's existence by stored localDate. Persist schema v1, no destructive fallback. All UI mutation methods acquire the engine mutex and reevaluate or schedule after the mutation without reentering the mutex.

```kotlin
mutex.withLock {
    database.withTransaction {
        // Insert completion IGNORE; clear snooze and the matching active state together.
        // OS effects occur while still holding mutex, outside this DB transaction.
    }
    // cancel only if this mutation invalidated the currently matching token
}
```

- [ ] **Step 3: Write failing lifecycle tests with a fake gateway/scheduler.** Add a test-only fixture constructing in-memory DB, fake Clock, mutable zone provider, gateway holding `visibleToken`, and engine. Test fixtures are private to the test suite, not new production abstractions.

```kotlin
@Test fun staleActionCannotCompleteOrCancelNewReminder() = runTest {
    val first = repository.saveTask(null, "首")
    engine.evaluate()
    val oldToken = gateway.activeToken()!!
    engine.handleAction(first, oldToken, ReminderAction.SNOOZE)
    // Advance fake Clock 30 minutes; a second normal task can now be posted.
    clock.advance(Duration.ofMinutes(30))
    repository.saveTask(null, "腕")
    engine.evaluate()
    val current = gateway.activeToken()
    engine.handleAction(first, oldToken, ReminderAction.COMPLETE)
    assertEquals(current, gateway.activeToken())
    assertTrue(repository.snapshots.first().tasks.first { it.id == first }.completions.isEmpty())
}
```

Pin these scenarios: concurrent `evaluate()` produces one post; concurrent complete produces one history event; deny permission/channel does not update lastNotifiedAt; gateway post throws and a later evaluation retries; swipe = 1h snooze; due snooze outranks normal; existing active blocks extras; local midnight invalidates old active; quiet hours invalidate active; edit/archive/manual complete invalidates matching active; unknown task/token no-op; process restart with DB/OS matching, prepared-only, DB-only delivered, OS-only and token mismatch; old deleteIntent after new post does not cancel it; persisted original completion day survives timezone switch. Run focused test class and confirm failures.

- [ ] **Step 4: Implement state reconciliation and adapters.** Follow the design's recovery table, validate stale action before mutation/cancel, and serialize all notification side effects. Reserve token in transaction, post, then set deliveredAt/lastNotifiedAt; catch SecurityException and explicit post errors to clear reservation without consuming cadence. Keep no more than one OS notification using a fixed ID; embed token in extras and unique intent data for each action.

```kotlin
val intent = Intent(context, ReminderActionReceiver::class.java)
    .setData(Uri.parse("tasknudge://reminder/$token/$action"))
    .putExtra("taskId", taskId).putExtra("token", token).putExtra("action", action)
val pending = PendingIntent.getBroadcast(context, 0, intent,
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
```

Use stable channel `task_reminders`, DEFAULT importance, PRIVATE visibility, Japanese action labels, setAutoCancel(false), and token-preserving deleteIntent. Gateway checks global enabled state and channel importance. `activeToken()` reads app `activeNotifications`. Receiver is non-exported, uses goAsync with finally finish and application-owned short coroutine scope. Worker calls the same singleton engine. Register unique 15-minute periodic KEEP work and unique one-time evaluation work; avoid a worker replacing/cancelling itself when scheduling its successor. Ensure opportunistic calls do not create an unbounded queue. Initialize scheduler at application startup. Notification tap uses MainActivity taskId extra.

- [ ] **Step 5: Configure privacy and verify.** Manifest only requests POST_NOTIFICATIONS explicitly; WorkManager may merge its normal boot/wake permissions, but final merged manifest must lack INTERNET and exact alarm/foreground service permissions. Disable backup and exclude root/file/database/sharedpref/external data from cloud and device transfer in the corresponding API rule formats. Run `./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`; fix manifest/lint and test failures. Commit Task 2 with `feat: persist tasks and deliver safe reminders`.

### Task 3: Japanese task, history, settings and permission screens

**Files:**
- Create under `app/src/main/java/dev/tasknudge/ui/`: `TaskNudgeApp.kt`, `TaskViewModel.kt`, `TaskListScreen.kt`, `TaskEditorScreen.kt`, `TaskDetailScreen.kt`, `SettingsScreen.kt`, `Theme.kt`.
- Modify: `app/src/main/java/dev/tasknudge/MainActivity.kt`, `app/src/main/res/values/strings.xml`.
- Test: `app/src/test/java/dev/tasknudge/ui/TaskViewModelTest.kt`, `app/src/androidTest/java/dev/tasknudge/ui/TaskScreensTest.kt`.
- Modify: `app/build.gradle.kts` for Compose UI testing dependencies and AndroidJUnitRunner.

**Interfaces:** Consumes `TaskRepository`, `AppSnapshot`, `TaskSnapshot`, `ReminderSettings`, `NotificationGateway.canNotify`, `ReminderEngine.evaluate` and core validation. Produces `@Composable fun TaskNudgeApp(repository: TaskRepository, notificationsEnabled: Boolean, onEnableNotifications: () -> Unit, initialTaskId: Long? = null)` and `TaskViewModel(repository: TaskRepository)` exposing `StateFlow` for loading/data/error and launching repository mutations. MainActivity owns runtime permission launcher/settings intents and rechecks availability on resume. Screens use callbacks and immutable view state; tests inject fake repository without real WorkManager.

- [ ] **Step 1: Write failing state/UI tests.** Cover required-only add, edit, errors, completion history, archive visibility, setting validation and explicit permission interaction. Define test-only fake repository with a MutableStateFlow and call records. Example:

```kotlin
@Test fun addWithTitleOnlySavesDailyTask() {
    composeRule.setContent { TaskNudgeApp(fakeRepository, true, {}) }
    composeRule.onNodeWithContentDescription("タスクを追加").performClick()
    composeRule.onNodeWithText("タイトル").performTextInput("首のストレッチ")
    composeRule.onNodeWithText("保存").performClick()
    composeRule.waitUntil { fakeRepository.savedInputs.size == 1 }
    assertEquals(TaskInput("首のストレッチ", 1), fakeRepository.savedInputs.single())
}
```

Unit tests verify exception/error state surfaces and loading differs from empty, no duplicate writes while saving, title trim and integer parsing errors, detail for missing task returns to list safely. Instrumented tests additionally verify blank/101-character title, bad recurrence, 0..24 hours, archive/reopen history, denied notification banner without automatic prompt, and state restoration during editor rotation. Run local `:app:testDebugUnitTest` for the unit red phase. Compile UI tests using `:app:assembleDebugAndroidTest`; run their red phase on the Task 4 CI emulator when local device is unavailable, clearly report this deferred execution.

- [ ] **Step 2: Implement task flows.** Use Scaffold/TopAppBar/FAB, LazyColumn and 48dp targets. Home shows today's completion count against active tasks only, rows show total count and most recent saved local day, and status labels come from the same date/recurrence semantics as core. Detail shows all completion dates including archived tasks; stopping preserves everything. Editor defaults recurrence=1 and hides optional complexity behind a clear label. Preserve draft with rememberSaveable; trim/validate again at repository boundary. Inline field errors in Japanese; disable only the save operation while saving.

```kotlin
var title by rememberSaveable(taskId) { mutableStateOf(initialTitle) }
OutlinedTextField(value = title, onValueChange = { title = it },
    label = { Text("タイトル") }, isError = titleError != null,
    supportingText = { titleError?.let { Text(it) } })
```

Use a small saved screen/destination state or Compose Navigation, choosing the smaller implementation; retain back behavior and initialTaskId notification routing. Read fonts/colors from MaterialTheme; dynamic colors API31+, fixed teal fallback and dark palette. Apply safeDrawing and ime insets, constrain content to 720dp. Do not add unrelated charts, streaks, onboarding or sync.

- [ ] **Step 3: Implement settings and permission flow.** UI accepts integer start/end hours and interval chips; start<end required, 24 interpreted only as end of day. Save validated settings then trigger reevaluation. In MainActivity, the enable button either launches API33+ permission on explicit first request or opens app notification settings after denial; record request before launching. On resume re-read channel/global state and evaluate. The app stays usable while permission is denied.

```kotlin
val launcher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission()
) { /* Refresh actual canNotify state; permission result alone ignores channel state. */ }
// On explicit request: persist requested=true before launcher.launch(POST_NOTIFICATIONS).
// Settings: ACTION_APP_NOTIFICATION_SETTINGS with EXTRA_APP_PACKAGE.
```

Display OS timing caveat and local-data policy. Add previews for light/dark and large text. Label icon buttons; hide decorative icons from accessibility.

- [ ] **Step 4: Verify and commit.** Run `./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`; inspect screen hierarchy/previews if available. Document deferred runtime UI checks for Task 4, then commit `feat: add Japanese task and history screens`.

### Task 4: CI APK delivery, emulator verification and user documentation

**Files:**
- Create: `.github/workflows/android.yml`, `README.md`, `scripts/check.sh`, `scripts/device-smoke.sh`, `docs/testing.md`.
- Create: `app/src/androidTest/java/dev/tasknudge/ReminderSmokeTest.kt`, `app/src/androidTest/java/dev/tasknudge/ScreenshotTest.kt`.
- Modify: UI tests/build/manifest only if integration exposes a concrete defect; record changes and rerun affected tests.
- Create: `docs/screenshots/tasks.png`, `docs/screenshots/history.png`, `docs/screenshots/settings.png` from actual emulator output after validation, not mockups.

**Interfaces:** Consumes completed app and public engine/repository APIs. Produces executable `scripts/check.sh` (local core/unit/lint/APK gates) and `scripts/device-smoke.sh` (requires one adb device, installs debug app and instrumentation APK, runs test runner); GitHub CI artifact `task-nudge-debug` containing APK and `android-test-results` with reports/screenshots. No production test endpoint or debug receiver.

- [ ] **Step 1: Add deterministic device checks.** Instrumentation uses test container/fixed clock for reminder logic; the real Android gateway posts to actual NotificationManager. Grant permission on API33+ with UiAutomation; configure hours=0..24 and no previous delivery in test DB. Use isolated DB and clean notifications in finally. Assert at most one actual notification, send its complete/snooze PendingIntent, await repository result, verify one history entry on duplicate send, and verify an old token cannot cancel the next generation. Keep one smoke path on real Room/gateway/receiver, not entirely faked dependencies.

```kotlin
@Test fun notificationCompletePersistsOnce() {
    // Arrange isolated DB, fixed Clock, actual gateway and receiver container.
    // Post via engine; get StatusBarNotification from NotificationManager.
    val action = notification.actions.first { it.title.toString() == "完了" }
    action.actionIntent.send()
    action.actionIntent.send()
    // Await one completion row and zero active notifications with bounded polling.
}
```

Add runtime UI checks for large-font layout, rotation-draft restoration, title-only add/detail/manual completion, and permission-disabled tracking. Capture screenshots using AndroidX test runner's screen capture API or UiAutomation; name files by screen, store in app external test files and pull as CI artifacts. Tests must not depend on wall-clock 09..21.

- [ ] **Step 2: Add local gate and device scripts.** Use strict shell mode, resolve repository root, check adb device availability and fail with an actionable message when absent. Build commands are identical locally and CI.

```bash
#!/usr/bin/env bash
set -euo pipefail
./gradlew --no-daemon :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

Device script runs `./gradlew :app:connectedDebugAndroidTest` after verifying a connected device; document adb install path and instrumentation command for manual troubleshooting. Run `bash -n scripts/check.sh scripts/device-smoke.sh`, then `scripts/check.sh`.

- [ ] **Step 3: Add GitHub Actions.** Trigger pull_request and push to feature branches/main, read-only contents permissions, cancel superseded runs. Pin trusted actions to commit SHAs after confirming upstream versions. A `verify` job uses setup-java 17 and Android setup, Gradle caching, then `scripts/check.sh`, uploads APK and XML/HTML reports even when tests fail. An `android-device-tests` job uses Ubuntu with KVM enabled and an API35 Google APIs x86_64 emulator via verified reactivecircus/android-emulator-runner action; run `scripts/device-smoke.sh`, pull screenshots and upload instrumentation reports. Use bounded job timeout (30 minutes verify, 40 emulator). No secrets/signing or publishing action.

```yaml
permissions:
  contents: read
concurrency:
  group: android-${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: true
# Emulator settings: api-level: 35, arch: x86_64, target: google_apis,
# disable-animations: true, script: scripts/device-smoke.sh
```

Run emulator job remotely after branch push. Inspect with `gh run list` / `gh run view --log-failed`; fix scope failures. Local `/dev/kvm` unavailable is expected in this environment; CI is the runtime verification path. If CI emulator is externally unavailable, record exact failure and leave runtime validation explicitly incomplete rather than claiming it passed.

- [ ] **Step 4: Write concise Japanese README and test guide.** Explain APK download/install, Android26 minimum, adding title-only tasks, daily completion semantics, recurrence, snooze and priority, quiet hours, max-one notification, permission/channel settings, OS delays/force-stop limitation, timezone date policy, local-only storage and backup exclusions, uninstall data loss. Include actual screenshots after CI capture. Document reproducible toolchain/dependency versions and commands, host/device tests, manual reboot/day rollover/permission recovery checklist and residual OEM behavior limitations. Link the original idea Issue; assess full completion using workspace policy when creating the PR.

- [ ] **Step 5: Verify integration, review, and hand off PR.** Run `scripts/check.sh` and `git diff --check`, confirm successful emulator CI and screenshot artifacts. Commit `ci: verify Android app and publish debug artifacts`. Controller requests read-only independent review of full diff against `origin/main`, provides requirements/diff without implementation rationale, prefers confirmed non-GPT CLI reviewer, falls back to separate-context subagent only when unavailable. Record reviewer/model/fallback/findings, fix material findings, rerun relevant checks and review fixes, then create PR against main using gh. PR links `https://github.com/yutotamura55/my-software-ideas/issues/11`; link the original idea without a closing keyword and include `Closes #1` for https://github.com/yutotamura55/task-nudge/issues/1 if the agreed development Issue scope is fully complete, or explain remaining work without a closing keyword for partial delivery. Report validation and review status. Never merge or enable auto-merge.

## Self-review record

Spec coverage maps build/policy to Task 1, persistence/notifications/privacy to Task 2, user flows to Task 3, and delivery/whole-app verification to Task 4. Timestamp/date responsibilities, fixed notification ID versus generation token, snooze versus global cadence, and single mutation ownership are consistent across tasks. Five Review Focus entries each have explicit tests in the owning task. Dependency candidate resolution is a concrete first execution step with pinned output, not an unbounded upgrade task. Runtime instrumentation is delegated to CI because local hardware acceleration is unavailable; this constraint does not turn an unexecuted test into a pass.
