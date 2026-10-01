# Task Nudge development

Read the workspace AGENTS.md and the design/plan in docs/superpowers before implementing.

- Native Android Kotlin app: Compose Material 3, Room/SQLite, WorkManager, pure Kotlin core. Japanese UI; minSdk26/compileSdk36/targetSdk36; JDK17.
- Keep the app offline. Do not add INTERNET permission, accounts, analytics, a server, exact alarm permissions, or foreground services.
- Follow `docs/superpowers/specs/2026-09-30-task-nudge-design.md` for date, snooze, permission, and notification generation semantics. All writes and notification side effects use the shared engine mutation boundary.
- Keep Room schemas in git; never use destructive migration. Never commit local.properties, SDKs, Gradle caches, signing keys, or credentials.
- Fetch the remote default branch before new independent work; use dedicated feat/fix/chore branches. Current MVP work uses feat/11-task-reminders.
- The user selected subagent-driven-development for this MVP. Review each task and the complete diff independently. Prefer a read-only CLI review with a confirmed non-GPT model; document fallback if unavailable.
- Only the user merges. Do not push directly to main, merge PRs, enable auto-merge, or enqueue a PR.
- Original idea is https://github.com/yutotamura55/my-software-ideas/issues/11; link it without an automatic closing keyword.

## Verification

After bootstrap, run `scripts/check.sh` for core tests, app unit tests, lint and debug/test APK builds. Until the script exists, run:

```bash
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

Use `scripts/device-smoke.sh` for connected Android instrumentation tests. GitHub Actions runs API35 emulator verification and exports test reports, screenshots and a debug APK. Inspect CI results and fix failures within scope before reporting completion. State any unexecuted device/OEM checks honestly.

Local provisioned toolchain, when present: `source /tmp/task-nudge-toolchain/environment.sh`. Other machines may use Android Studio/SDK plus JDK17 and the checked-in Gradle wrapper. Local paths are not project configuration.
