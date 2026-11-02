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
- Merge pull requests only as "Merging pull requests" below allows.
- Original idea is https://github.com/yutotamura55/my-software-ideas/issues/11; implementation tracking is https://github.com/yutotamura55/task-nudge/issues/1. Follow the shared my-software-ideas-issue-workflow: link the original idea without a closing keyword. Use `Closes #1` only when the development Issue is fully addressed; otherwise link it and state remaining work.

## Merging pull requests

The assistant may merge a pull request into the repository's default branch (check it with `gh repo view --json defaultBranchRef`) itself when all of these hold:

1. Every required CI check passed. When CI cannot run, do not merge.
2. The self-review and an independent review by a model of a different family are done, their Important findings are fixed, and the fixes were reviewed again.
3. The pull request targets the default branch. A pull request built on another pull request's branch waits until that one is merged and its base is changed to the default branch.
4. The change is not "very high risk" (below).
5. Items 1, 2, and 4 apply to the pull request's current head commit. After any new commit, redo them. Merge with `gh pr merge --match-head-commit <that commit>` (on older gh, `gh api -X PUT repos/<owner>/<repo>/pulls/<number>/merge -f sha=<that commit>`) so that only the judged revision is merged.

Choices listed under "確認してほしいこと" in the pull request body are merged as the assistant chose them; the user reviews them after the merge and changes them in a new pull request. A choice that a revert cannot undo makes the change very high risk.

A change is **very high risk** when it cannot be undone, harms people or money outside the repository, could break without any test noticing, or changes the safeguards themselves. In particular:

- payments, billing, or anything that costs money;
- authentication, authorization, sessions, rate limits, or cryptography;
- destructive changes to persistent data or its formats (dropping, renaming, or retyping columns or tables, bulk updates or deletes, backup and restore formats); adding a column or table only is not;
- production infrastructure, deployment, or secret and environment settings;
- publishing a release or changing signing;
- safeguards: CI workflows, branch protection, `AGENTS.md` and other agent rules, agent permissions;
- handling of secrets or personal data, and history rewriting;
- irreversible external operations (sending messages to other people, deleting external resources, changing repository visibility);
- license or other legal texts;
- a new dependency, unless all of these hold: it exists in the official registry under exactly that name; it is established and maintained; its license is compatible; it runs no install script, or its script was read and found safe; its version and hash are pinned in the lockfile; and it passes the dependency audit. Lockfile-only changes count as dependency changes;
- a major dependency update, unless every breaking change in its changelog was traced to the code that uses it and verified by tests or by running it. When that investigation is large, do the update in a separate Issue.

Judge the risk as the highest of three judgments, none of which can lower another: the path and content rules (`pr_risk.py` in the maintainer's development rules), the implementing agent, and the different-family reviewer, which must give file, line, and reason. When the reviewer cannot run, treat the change as very high risk. Write the three judgments in the pull request body. When in doubt, treat the change as very high risk.

Leave a very high risk pull request for the user to merge, and say why at the top of its body.

Never push directly to the default branch, enable auto-merge, enqueue a pull request, or bypass branch protection (for example with `gh pr merge --admin`). Use the merge method this repository already uses. After merging, confirm that CI on the default branch passed and, where a merge deploys to production, that the deployment succeeded; if either fails, open a revert pull request and merge it under the same rules. Report every merged pull request to the user with its URL, its open choices, and the risk judgments.

## Verification

Run `scripts/check.sh` for core tests, app unit tests, lint, detekt and debug/test APK builds.

Fix new detekt findings first. Adjust a rule in `config/detekt.yml` only with a comment explaining why it does not fit this codebase, and add a finding to a module's `detekt-baseline.xml` only when the PR body records why it stays. See `docs/testing.md`.

Use `scripts/device-smoke.sh` for connected Android instrumentation tests. GitHub Actions runs API35 emulator verification and exports test reports, screenshots and a debug APK. Inspect CI results and fix failures within scope before reporting completion. State any unexecuted device/OEM checks honestly.

Without a host JDK17 and Android SDK, run `scripts/container-check.sh` (Docker) for the same checks; see `docs/testing.md`. Device and emulator tests, adb, and installs to an everyday device run on the host, because the container's debug signing key differs from the host key. Other machines may use Android Studio/SDK plus JDK17 and the checked-in Gradle wrapper. Local paths are not project configuration.
