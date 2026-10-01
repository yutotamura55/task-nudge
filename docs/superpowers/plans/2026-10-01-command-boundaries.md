# Command Boundaries Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. This is one bounded correction step before Task 4 of the MVP plan; retain the existing worktree and parent ledger.

**Goal:** Make committed writes, data observation, and screen-owned operation results independent and explicit.

**Architecture:** Repository commands report database commit outcomes. A small screen-session operation model owns success/failure, while an independent retryable observation state supplies display data. Keep the engine mutex and existing notification recovery protocol.

**Tech Stack:** Existing Kotlin, Compose, Room, WorkManager; no new dependencies.

**Spec:** `docs/superpowers/specs/2026-10-01-command-boundaries.md`

## Global Constraints

- Preserve the original MVP specification, SDK/toolchain versions and Japanese UI.
- All app mutations and OS notification side effects go through the shared engine mutex; Room transactions protect related DB writes. No destructive migrations.
- Keep `TaskRepository` command signatures and the `TaskNudgeApp` entry point stable.
- No accounts, network permission, framework additions, schema changes, or new features.
- Worktree `task-nudge/.worktrees/mvp`, feature branch only; never merge or push main.

## Review Focus

1. A write commits while observation fails: report committed state and offer data reload, with no duplicate retry.
2. Old settings save or any command failure arrives after navigation: preserve the current screen and draft.
3. Read retry occurs more than once: keep exactly one observation job and recover from failure.
4. Notification maintenance fails after DB commit: return the committed ID, log locally, recover on reevaluation.
5. Rotation and delayed new-task observation: preserve draft/ownership and distinguish saved loading from an invalid deep link.

### Task 1: Clarify command outcomes and screen ownership

**Files:**
- Modify `app/src/main/java/dev/tasknudge/ui/TaskViewModel.kt`, `TaskNudgeApp.kt`, `TaskEditorScreen.kt`, `SettingsScreen.kt`, and other UI call sites only when the state contract requires it.
- Create `app/src/main/java/dev/tasknudge/ui/TaskUiState.kt` for explicit observation, session and operation types if separating those types improves readability.
- Modify `app/src/main/java/dev/tasknudge/reminders/ReminderEngine.kt` and `data/TaskRepository.kt` for the documented commit contract; notification diagnostics may touch `ReminderWorker.kt`.
- Test `app/src/test/java/dev/tasknudge/ui/TaskViewModelTest.kt`, `reminders/ReminderEngineTest.kt`, and `app/src/androidTest/java/dev/tasknudge/ui/TaskScreensTest.kt`.

**Interfaces:** Keep repository and top-level composable signatures. Replace implicit feedback flags with explicit session-owned state. A concrete implementation shape is:

```kotlin
sealed interface OperationState {
    data object Idle : OperationState
    data object Saving : OperationState
    data class Committed(val result: OperationResult) : OperationState
    data class Failed(val message: String) : OperationState
}
sealed interface OperationResult {
    data class TaskSaved(val id: Long) : OperationResult
    data object SettingsSaved : OperationResult
    data object Updated : OperationResult
}
data class ScreenOperation(val sessionId: Long, val state: OperationState)
```

Equivalent small types are acceptable. Do not build a general event dispatcher. Observation errors and field errors must not share `OperationState.Failed` unless they are actual command failures. Explicit `beginScreenSession`, `consumeOperation`, and `retryObservation` operations replace navigation's overloaded `clearFeedback` role. Preserve immutable flow state, coroutine cancellation, and saved draft restoration.

- [ ] **Step 1: Pin user outcomes with failing tests.** Extend the existing fake repository with controllable observation failure/restart and separately suspended settings/task writes. Replace field-equality acknowledgment tests. Add tests for each Review Focus case; engine fakes throw after the commit point and verify persisted rows and returned ID. For example, the delayed-publish fixture should exercise:

```kotlin
val repo = FakeRepository().apply { publishSaves = false }
val vm = TaskViewModel(repo)
vm.saveTask(null, "首", "1")
runCurrent()
vm.saveTask(null, "首", "1")
runCurrent()
assertEquals(1, repo.saved.size)
// Also assert a committed result, not an in-flight save or retryable failure.
```

- [ ] **Step 2: Separate engine commit from follow-up maintenance.** Preserve pre-commit reconciliation and the database transaction. After the transaction returns, run notification reconciliation/cancellation and scheduling as explicit recoverable maintenance, preserving cancellation. Log exceptions without task text; individual failure must not prevent scheduling recovery where possible. Never wrap the whole mutation with a success-producing catch. Document the contract on repository methods. Keep existing token/cadence invariants.
- [ ] **Step 3: Implement the state model once for all UI mutations.** Observation owns its job and retry; screen-owned operations capture session identity at start and publish only to that owner. Task/settings commits prevent same-editor resubmission. Immediate-update operations consume their result to allow subsequent operations. Keep routing and draft state coherent on rotation. Render committed-but-not-yet-observed data explicitly; no matching-title/recurrence wait, timeout-to-save-failure, or global saving lock while waiting for observations.
- [ ] **Step 4: Verify the boundary correction.** Run focused ViewModel and engine tests until their named regressions pass. Then run one integration gate after source changes are complete:

```bash
/tmp/task-nudge-toolchain/run-gradle :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --max-workers=1
```

Runtime UI execution belongs to the existing Task 4 CI step. Record exactly what was executed. Inspect changes once for duplicated state/condition growth and unused replaced fields.
- [ ] **Step 5: Commit and report.** Commit `refactor: separate persistence outcomes and screen operations`. Write the parent SDD workspace's `command-boundaries-report.md` with evidence and state-transition explanation. Controller requests one independent combined correctness/design review before Task 4 proceeds.

## Plan self-review

The three blockers map to steps 2 and 3; all five Review Focus conditions map to step 1 and the integration gate. Repository public signatures stay stable for subsequent device tests. UI state types and tests change together. No notification state-machine rewrite or database migration is required. The original parent plan remains authoritative for the remaining CI, independent final review and PR work.
