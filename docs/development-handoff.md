# Task Nudge 開発引き継ぎ

この文書から再開する。完了済みの実装・設計・toolchain を最初からやり直さない。

## 依頼と権限

- ユーザーの依頼は、Android MVP の設計・実装・独立レビュー・PR 作成まで。subagent-driven-development 指定。
- Repository: **private** `yutotamura55/task-nudge`。
- Source Issue: https://github.com/yutotamura55/my-software-ideas/issues/11 （OPEN）。
- Branch: `feat/11-task-reminders`。Base: `origin/main` の `7b401f0`。
- **main へ push・merge・auto-merge・merge queue を行わない。マージはユーザーのみ。**
- 新しい共有 workflow に従い、元アイデア #11 は通常 URL で参照。開発先 [task-nudge #1](https://github.com/yutotamura55/task-nudge/issues/1) の受け入れ条件を全部満たせば PR に `Closes #1`。部分完了なら通常リンクと残作業を記す。
- ユーザーの追加指示: 修正を繰り返してテストを通すだけではなく、設計の変更容易性・保守性を評価する。トークン制限後や他 AI への引き継ぎでも再開可能にする。

## 再開場所

同じ環境では `/home/y-tamura/workspace/task-nudge/.worktrees/mvp` を使う。親 checkout は `/home/y-tamura/workspace/task-nudge`。

```bash
git status --short
git log -8 --oneline
```

未コミット変更は中断された実装である。消さず、最新の記録と比較して続行する。別環境では private repo を `gh repo clone` し、`feat/11-task-reminders` を checkout する。リポジトリ・workspace の AGENTS.md を最初に読む。

詳細なローカル SDD 記録は `.superpowers/sdd/2026-09-30-task-nudge/`（gitignored）の `progress.md`、各 `*-report.md`、brief、review diff。この文書は別環境でも読める要約である。

## 合意したアプリ

タスク名だけで登録すると通知が出る。一日一回で十分。過去の未完了インスタンスは作らず、前回完了が古いタスクと、期限が来た保留タスクを優先する。通知は最大一件、完了・一時間保留。初期は09–21時・30分間隔。追加・編集・履歴・停止/再開・設定を日本語で提供する。Room/SQLite、Compose Material3、WorkManager、オフライン。通知権限を拒否しても管理画面は使える。バックアップ除外。

仕様・計画:

- `docs/superpowers/specs/2026-09-30-task-nudge-design.md`
- `docs/superpowers/plans/2026-09-30-task-nudge.md`
- **最新の境界再設計:** `docs/superpowers/specs/2026-10-01-command-boundaries.md`
- **完了した追加計画:** `docs/superpowers/plans/2026-10-01-command-boundaries.md`

## 完了済みの単位

| 単位 | Commit | 検証・独立レビュー |
|---|---|---|
| 初期設計・計画 | `3c57634` | Spec/plan/AGENTS 保存済み |
| Task1 build/pure policy | `d58ecf4` | core34件、lint/APKs成功。gpt-6.1-sol high: blocking findingなし |
| Task2 Room/notification | `9ef767e` | core34/app34件、lint/APKs成功。gpt-6-astra high: blocking findingなし |
| Task3 Japanese UI | `2eca5b9` + `2d617b2` | core34/app52件、lint0 errors/1kapt warning、両APK成功。保存連打/画面所有者の2指摘を修正し scoped review成功 |
| 再設計文書 | `0832100` | 下記の architecture review に基づく |

Task1–3 の実装・修正担当は **gpt-6.1-sol high**。architecture review は **gpt-6-astra high**。実装担当の自己レビューを独立レビューの代用にしない。

## 保存境界の再設計：完了

独立設計レビューが3つの構造的問題を指摘した:

1. 保存成功を snapshot の入力値一致で待つと、観測停止で全操作が止まるか、確定済み保存を再試行させる。
2. global feedback flags と画面の所有者が混在し、設定保存や古いエラーが別画面へ届く。
3. DB commit 後の通知処理/予約失敗を保存失敗として返すと、再試行が重複追加になる。

決定は、コマンド確定・データ観測・画面所有者の境界を限定して再設計すること。通知の prepared/delivered recovery、mutex、Room は維持する。判断が不十分なら追加の境界修正が必要になる。

実装担当は `/root/command_boundaries_resume`。保存された WIP `b913463` から再開し、次を確認・修正した:

- Engine の commit 後 cancel/reconcile/schedule を回復可能な処理に分け、境界と例外クラスだけローカル記録。commit 前処理と mutex は維持。
- TaskViewModel の session/operation state と再試行可能な観測を確認。ViewModel 24件・ReminderEngine 28件を含む app 67件、core 34件が成功した。
- instrumented UI の旧「保存中」期待を「保存済み・データ表示待ち」へ修正。保存結果を表示する前に戻れる結果も追加し、AndroidTest APK まで compile/assemble 成功。**端末上での実行は Task 4。**
- 指定 gate `:core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --max-workers=1` は exit 0。lint 0 errors / 既知の kapt warning 1件。
- ローカル記録 `.superpowers/sdd/2026-09-30-task-nudge/command-boundaries-report.md` に状態遷移と検証を保存。独立した combined correctness/design review は、旧画面 callback が実行時の新 session/task ID を読む Important 指摘を出した。各 screen callback に生成時の owner/task ID を束縛し、旧 action を保持して新 draft で呼ぶ 4 件の instrumented regression を追加。修正差分の scoped re-review は全指摘解消で完了し、commit `910a682` まで保存済み。

## 最終検証と PR

- 実装・保存境界再設計・時計共有・独立レビュー・Task4は完了。検証したsource commitは `6d4db8f`。
- [PR #2](https://github.com/yutotamura55/task-nudge/pull/2) は `main` 向け。マージはユーザーが行う。現在のdraft/ready・checksはPRを参照する。
- [PR CI 36963062421](https://github.com/yutotamura55/task-nudge/actions/runs/36963062421) と [push CI 36963059186](https://github.com/yutotamura55/task-nudge/actions/runs/36963059186) はともにSUCCESS。各XMLは単体101件、API35端末24件、失敗・error・skipいずれも0件。lint・debug/test APKビルドも成功。
- 一覧・詳細履歴・設定の3PNGを原寸確認。両runのartifactと掲載画像のSHA-256はすべて一致。「今日の完了1/2」「今日完了済み」無効、履歴1件、時間9–21/30分を確認。README、testing、review-recordに証跡を保存。
- 独立reviewerはGemini `gemini-3.1-pro-high` とCopilot CLI `claude-sonnet-5`（実ログ確認）。重大な欠陥は修正・再レビュー済み。保存前OS照合のP2可用性制約は設計上の判断として残す。
- 最終source後の変更は文書のみ。新しいCI状態はPRのchecksを参照し、過去の検証済みsourceを誤って再実装しない。

## 今後の作業

依頼されたMVP開発は完了。PRのレビューへの対応とユーザーによるマージを待つ。新しい指摘がある場合のみ、該当範囲を調査・修正・独立レビュー・再検証する。完了済みTask1–4やtoolchainを再実行する必要はない。

OEMの省電力・再起動・force-stop・転送、実OSの通知拒否/再許可は機種別手動確認が未実行。CI debug APKは試用で、run間の署名互換を保証しない。これらは合意MVPの確認範囲と制約としてREADME/testingに明記済み。

## Toolchain と実行

`/tmp/task-nudge-toolchain` に JDK17、Gradle8.13、SDK36/build-tools35、license、cache を準備済み。再導入不要。同環境の runner:

```bash
/tmp/task-nudge-toolchain/run-gradle :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --max-workers=1
```

Gradle は network/socket sandbox のため approved prefix `/tmp/task-nudge-toolchain/run-gradle` で escalation を使う。**編集は通常権限の別コマンドで行う。編集scriptとescalated Gradleをまとめると承認待ちで停止した。** AGP lint FIR の内部エラーは `--max-workers=1` で回避できた。kapt推薦/processor-options警告は既知。

別環境はJDK17/Android SDKとchecked-in wrapperを使う。SDK/cache/local.properties/keysはcommitしない。公式依存確認とchecksum証拠は `/tmp/task-nudge-dependencies.json`、`/tmp/task-nudge-toolchain/report.md`。

## 最終 CLI レビュー（transport は検証済み）

`agy` v1.2.12、実モデル **gemini-3.1-pro-high**。probe の init.model と SUCCESS を確認済み。`--mode plan --sandbox`、read-only、全文diffをstdinで渡す。`--disable-slash-commands` はplan modeを無効化するため付けない。

NDJSON input は `{"event":"user","message":{"content":"requirements and full diff"}}`。`type:user` は誤り。

```bash
agy --model gemini-3.1-pro-high --mode plan --sandbox \
  --input-format stream-json --output-format stream-json \
  --print-timeout 240s --log-file /tmp/task-nudge-review/final.log \
  --print '' < /tmp/task-nudge-review/final-request.jsonl \
  > /tmp/task-nudge-review/final-output.jsonl
```

requestはPython json.dumps等で作り、requirementsとdiffを含める。実装の正当化は渡さない。tools/helpers/mutations禁止。result/actual modelを確認し、review前後にbranch/treeが変わっていないことを確認する。CLIが利用不可/完了不能なら理由を記録して別context subagentへfallbackする。

## 記録しておく非blocking項目

- routine evaluationが全履歴を取得する。件数増加時の集計queryは改善候補。paging/frameworkを先行追加しない。
- nullable active generation fields、unused ActiveReminder、snooze/validation constantsの整理候補。
- notification post/worker errorsの診断不足。今回の保存後処理にはログを入れている。
- launcher iconとbackup rulesの旧minorは実装済み。kapt/toolchain warningは引き続き正確に報告する。

## 別 AI に渡す開始指示

> AGENTS.md、development-handoff.md、review-record.mdとPR #2の最新checks/レビューを確認してください。MVP実装・独立レビュー・単体101件/端末24件・実画像3枚は完了済みです。新しい指摘があればその範囲だけ対応し、完了済み工程を再開しないでください。mainへpush/merge/auto-mergeは行わず、worktreeを保持してください。
