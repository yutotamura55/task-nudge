# Task Nudge development

Read the workspace AGENTS.md and the design/plan in docs/superpowers before implementing.

- Native Android Kotlin app: Compose Material 3, Room/SQLite, WorkManager, pure Kotlin core. Japanese UI; minSdk26/compileSdk36/targetSdk36; JDK17.
- Keep the app offline. Do not add INTERNET permission, accounts, analytics, a server, or foreground services. Issue #24 added `SCHEDULE_EXACT_ALARM` only for timely reminders during device sleep; keep the inexact fallback when the user does not grant it, as described in `docs/issues/24-sleep-notifications.md`.
- Follow `docs/superpowers/specs/2026-09-30-task-nudge-design.md` for date, snooze, permission, and notification generation semantics. All writes and notification side effects use the shared engine mutation boundary.
- Issue #9 replaced the original single-notification limit and WorkManager-only timing with accumulated notifications and a while-idle OS schedule. See `docs/issues/9-notification-cadence.md` for notification cadence and `docs/issues/24-sleep-notifications.md` for exact and inexact scheduling.
- Keep Room schemas in git; never use destructive migration. Never commit local.properties, SDKs, Gradle caches, signing keys, or credentials.
- Follow `docs/superpowers/specs/2026-10-01-command-boundaries.md`: distinguish committed writes, retryable observation, and screen-owned operation results. Repeated fixes at one boundary require reassessing its state ownership and changeability; passing tests alone does not establish design quality.
- Fetch the remote default branch before new independent work; use dedicated feat/fix/chore branches.
- The user selected subagent-driven-development for this MVP. Review each task and the complete diff independently. Prefer a read-only CLI review with a confirmed non-GPT model; document fallback if unavailable.
- Only the user merges. Do not push directly to main, merge PRs, enable auto-merge, or enqueue a PR.
- Original idea is https://github.com/yutotamura55/my-software-ideas/issues/11; implementation tracking is https://github.com/yutotamura55/task-nudge/issues/1. Follow the shared my-software-ideas-issue-workflow: link the original idea without a closing keyword. Use `Closes #1` only when the development Issue is fully addressed; otherwise link it and state remaining work.

## Verification

After bootstrap, run `scripts/check.sh` for core tests, app unit tests, lint and debug/test APK builds. Until the script exists, run:

```bash
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

Use `scripts/device-smoke.sh` for connected Android instrumentation tests. GitHub Actions runs API35 emulator verification and exports test reports, screenshots and a debug APK. Inspect CI results and fix failures within scope before reporting completion. State any unexecuted device/OEM checks honestly.

Local provisioned toolchain, when present: `source /tmp/task-nudge-toolchain/environment.sh`. Other machines may use Android Studio/SDK plus JDK17 and the checked-in Gradle wrapper. Local paths are not project configuration.
