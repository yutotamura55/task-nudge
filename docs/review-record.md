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
