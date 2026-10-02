# MVP レビュー記録

作業 branch: `feat/11-task-reminders`。base: `7b401f0`。完了状態・検証した commit は [development-handoff.md](development-handoff.md) を参照。この記録の作成時点では端末検証・画面画像の回収が未完了。

## 全体レビュー

- reviewer: Antigravity CLI (`agy`)、実行ログで確認したモデル `gemini-3.1-pro-high`。
- 対象: `7b401f0..9cf7924` の全差分と要件。実装側の説明・報告を別途提示せず、読み取り専用、ツール・編集・委任禁止で依頼。CLI 結果 SUCCESS。
- 最初の指摘: 保存前の通知 OS 読み取り失敗で DB 保存も止まることを High と評価。通知 deep link による未保存 draft 破棄は Low の将来改善提案。

### 保存前の照合に関する再評価

別コンテキストの設計 reviewer (`gpt-6-astra`、high) がコード・仕様・テストを確認。失敗経路は実在するが、事前照合を一律 catch すると、表示済み prepared 世代の時刻/token を消し、通知間隔を飛ばす反例がある。

この具体例を Gemini に提示して技術的に再評価を依頼。実モデルと SUCCESS を再確認し、当該指摘は合意 MVP における非 blocking P2 可用性制約、残る material defect はないという結果になった。

**判断:** 現行の安全側動作を維持し、通知サービス状態を取得できない場合は保存前に失敗する制約を明記する。OS 障害中にも保存を保証する場合は、状態不明の観測と永続的な無効化世代を導入して回復に必要な情報を守る。単なる catch は採用しない。

## Task4 の独立レビュー

- reviewer: GitHub Copilot CLI 1.0.90、実呼び出し・応答で確認したモデル `claude-sonnet-5`。
- 対象: Task4 要件と `910a682..9cf7924` の全差分。読み取り専用、shell/write 禁止、利用可能ツールを view に限定し、ツール・委任禁止で依頼。
- 実際に要件と差分47,709文字が渡ったことを確認。exit 0、ファイル変更0、ツール実行なし。
- High: 端末テスト全件 pass と実画面画像が未提出。これは既知の未完了 gate と一致し、完了報告前に満たす必要がある。
- Medium: テスト Application の親初期化を省く意図、プロセス終了後の font scale 復元、可変 Clock の withZone、調査用 semantics log。
- Low: 無効 gateway の post assertion、fixture 差し替え時の通知 cleanup、action SHA の外部確認。action SHA は既に公式 upstream で確認済み。
- 修正 `7f38921`: 親初期化を省く意図を明記、外側スクリプトで font scale を復元、withZone で可変時刻を共有、調査ログ除去、旧通知 cleanup、表示を確認してからの操作と画像退避・回収を修正。
- 再レビュー: `9cf7924..7f38921` の追加全差分。実モデル `claude-sonnet-5`、exit 0、ファイル変更0。view ツールのみでテスト3ファイルと本体 Application を閲覧し、修正6点は妥当、残る重大な欠陥なしという結果。shell・編集・委任は実行なし。
- 端末全件 pass・実画像配置・CI artifact 成功は、引き続き完了前に必要なエビデンス待ち。

## 実行環境の記録

Copilot の `claude-opus-4.6` は利用不可と明示的に終了したため、そのモデルのレビューとは扱わない。利用可能な `claude-sonnet-5` の READY 応答と実モデルを確認して採用した。

Copilot の `-p` と標準入力の組合せでは、本文がレビュー会話に入らなかった。user.message に差分がないことを検出し、シェル展開を使わず subprocess の引数として本文を渡して再実行した。本文のない応答はレビュー実施として数えていない。

全体レビュー後の変更も、PR 作成前に追加で確認する。PR はユーザーがマージする。

## 端末検証3件の追加修正（2026-10-02）

CI `36886506402` はホスト成功、device23件中20件成功、3失敗。担当agentが使用上限で停止したため、確認済み Copilot CLI `claude-sonnet-5` へ androidTest2ファイルだけの修正を委任した。view/edit以外は不可、shell・本体変更・委任禁止。実際の変更は指定2ファイルのみ。

追加ボタンの bounded wait と表示確認を共通化し、invalid input の保存前にスクロール・表示確認、結果の bounded wait を追加。UiAutomationのcommand chainを単純コマンドに分け、画像退避先の実サイズを元ファイルと比較する。

親が `scripts/check.sh` を実行し exit0（94tasks、9executed、85up-to-date）を確認。別モデル Gemini (`gemini-3.1-pro-high`、実行ログ確認済み) の追加差分レビューは SUCCESS、重大な欠陥なし。対象は `7f38921` 以降のテスト修正と workflow 文書更新。ツール実行・編集・委任なし。端末での全件passと画像回収は引き続き未完。

新しい共有 idea workflow に合わせ、元アイデアはURL参照、開発先Issue [#1](https://github.com/yutotamura55/task-nudge/issues/1) を受け入れ条件・closingの単位とした。

## 画面描画の同期と最終1件の追加レビュー

- 対象: `0d5a990..9b91e99` の全差分と現在の ScreenshotTest。Copilot CLI の実モデル `claude-sonnet-5`、読み取り専用、shell/write禁止。exit0、ファイル変更0。
- 重大な欠陥の確定指摘なし。スクロール待機、署名キーの管理、画像の撮影範囲について確認を求める懸念が提示された。
- 親が確認: 詳細画面は単一 LazyColumn で、直前のヘルパーも同じスクロール対象を使う。その待機はノードの存在確認であり、描画安定待機ではない。Compose の操作同期に加え、今回の端末 CI で実動作を確認する。推測だけの追加修正は行わない。
- 固定 keystore、signingConfigs、CI の署名キー永続化設定はない。別 run の debug APK の署名互換を保証しないという説明は現構成に合う。
- captureToImage は実際の Android View の描画を同期して撮影する。システムバーを含まないアプリ画面画像であり、MVP の画面確認要件を満たす。画像内容そのものは CI artifact 回収後に確認する。
- CI `36950190490` はホスト101/101成功。端末23件と3画像の最終確認は実行中。

### 端末再実行による原因の訂正

CI `36950190490` はホスト101/101、端末22/23成功。3画像は担当と親が原寸確認し、一覧・詳細履歴・設定の正しい画面であることを確認した。残る失敗をスクロール問題とした説明は訂正する。

記録処理の注入 Clock は2026-10-01固定だが、TaskNudgeApp は直接 Instant.now() とシステム timezone を読んでいた。CI当日2026-10-02には、前日の完了を「今日完了済み」と表示しないため、テストが探す要素は存在しなかった。画像と XML（スクロール範囲0、該当要素なし）が一致する。

Clock/zone の所有者を AppContainer にそろえ、UI と engine へ同じ依存を渡す境界修正を行う。テストの文字変更や待機追加では対応しない。本番の時刻取得方式は維持し、固定日付を使う回帰確認・差分独立レビュー・端末再実行後に完了を判断する。

- 当該4ファイル差分の独立レビュー: Copilot CLI、実モデル `claude-sonnet-5`、exit0、編集0、viewのみ。要件・全差分・現在の4ファイルを提示し、下流の一覧/詳細/engineも閲覧。blocking defectなし、以前の動作に対する退行なし。
- zoneの再取得やpublic valは軽微なスタイル提案。zoneをrememberで固定すると実OSのtimezone変更を追従できなくなるため採用しない。既存依存を直接共有し、追加accessorは作らない。
- 既存CIの失敗が実際の挙動red証拠。追加した回帰テストは未定義パラメータでcompile redを確認し、修正後のfull host gateはexit0。端末では回帰1件を加えた24件を次runで検証する。
