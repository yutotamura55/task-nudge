# Task Nudge 開発引き継ぎ

この文書から再開する。完了済みの実装・設計・toolchain を最初からやり直さない。

## 依頼と権限

- ユーザーの依頼は、Android MVP の設計・実装・独立レビュー・PR 作成まで。subagent-driven-development 指定。
- Repository: **private** `yutotamura55/task-nudge`。
- Source Issue: https://github.com/yutotamura55/my-software-ideas/issues/11 （OPEN）。
- Branch: `feat/11-task-reminders`。Base: `origin/main` の `7b401f0`。
- **main へ push・merge・auto-merge・merge queue を行わない。マージはユーザーのみ。**
- 合意した Issue 要件を全部満たせば PR に `Closes yutotamura55/my-software-ideas#11`。部分完了なら通常リンクと残作業を記す。
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
- **現在実行中:** `docs/superpowers/plans/2026-10-01-command-boundaries.md`

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

## Task 4 実行中（CI checkpoint）

- 担当: `/root/task4_implementation`。作業場所はこの worktree。開始点 `910a682`。元 Task4 brief は `.superpowers/sdd/2026-09-30-task-nudge/task-4-brief.md`、進捗は同ディレクトリの `task-4-report.md`。
- 追加済み: 実 Room + Android 通知 + Receiver の instrumented tests、実 Activity/Compose の操作・通知タップ・回転・文字拡大・権限拒否・スクリーンショット tests、`scripts/check.sh` / `scripts/device-smoke.sh`、SHA 固定の GitHub Actions workflow。
- ホスト gate は provisioned runner で `:core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --max-workers=1` が exit 0。**端末実行は未確認**。次は feature branch push 後の API35 CI emulator 結果を調べ、失敗を修正する。
- CI run [36874951511](https://github.com/yutotamura55/task-nudge/actions/runs/36874951511) は setup-android が削除済み `tools` package を要求して project task 前に失敗。両 job に `packages: platform-tools` を明示した `319875f` を push。次の [36875126874](https://github.com/yutotamura55/task-nudge/actions/runs/36875126874) は verify 成功（core34/app67、lint0 errors・2 warnings）だが device job で 23 件開始、5 件完了中 3 件失敗後に instrumentation process が終了。
- device 報告と logcat の原因: 回転後 landscape + keyboard で保存ボタンはスクロール前の画面外、失敗時に portrait へ戻らず次の screenshot test に影響、許可 revoke は instrumented process の UID を kill。修正はスクロール可能性の確認、必ず portrait 復元、データ観測後 screenshot、拒否 UI にはテスト用の通知無効 gateway 注入。実通知/Receiver テストは引き続き実 Android gateway を使用。次の CI run で全 23 件を実行し直す。
- README / `docs/testing.md` はローカル commit `31e210d` に追加。実スクリーンショットはまだなく、CI の実画像を確認する前に完成を主張しない。setup-android の Node20 deprecation annotation は SDK setup 成功後も残る既知 warning。

## 残る工程

1. Task4 の CI emulator 実行結果を確認して失敗を修正し、実スクリーンショットを検証・保存する。
2. README と `docs/testing.md` を完成し、host/device gate を確証する。
3. 全差分を main base `7b401f0` と比較して独立レビュー。下記の確認済み非GPT CLIを優先。
4. material findingsを修正・必要な再検証・scoped review後、`gh pr create`。PR URL、検証、reviewer/model/findings を報告。PRはまだ未作成。

端末テストは API35 emulator で一度起動したが全件成功には至っていない。local KVMは使用不可なので CI の再実行が必要。OEM/reboot/force-stopの実機確認は未実行と明記する。

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

> このrepoの `docs/development-handoff.md` と AGENTS.md を読み、`feat/11-task-reminders` の保存境界再設計の途中から続けてください。Task1–3 と toolchain はやり直さず、現在diffを維持してください。subagent-driven-developmentで、再設計の完成・独立レビュー・Task4 CI/device/docs・全体非GPTレビュー・PR作成まで進めてください。mainのpush/mergeはしないでください。
