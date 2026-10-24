# Issue #35 リファクタリング

開発中に重複・複雑化した判定と処理をまとめた。
アプリの動作は変えていない。
画面遷移の整理は #36 で扱う。

## 規則の置き場所

| 規則 | 置き場所 | 使う側 |
| --- | --- | --- |
| 完了できるか（日ごとは同じ日付に1回、時間ごとは最後の完了から指定時間後） | `core` の `ReminderTask.canComplete` | 通知の判定、手動の完了、一覧と詳細の表示 |
| 通知の対象か（停止中・保留中を除き、繰り返しの期日が来ている） | `core` の `ReminderTask.isDue` | `ReminderPolicy.select`、表示中の通知が有効かの確認 |
| 入力の範囲と通知間隔の選択肢 | `core` の `TaskInput.kt` | 入力検証、エラーの文言、画面の補足文 |
| 入力エラーの文言 | `TaskViewModel.kt` の `InputError.message()` | 各入力画面 |

画面のタスク（`TaskSnapshot`）は `reminderTask(today)` で `ReminderTask` に変換し、通知と同じ規則で状態を表示する。
規則を変えるときは `ReminderTask` だけを直す。

## ReminderEngine の構成

- `evaluate` は、通知を一件投稿する `postNextReminderLocked` と、次の予約を入れる `scheduleNextEvaluationLocked` に分けた。
  予約は投稿の成否にかかわらず `finally` で行う。
- `reconcileLocked` は、保存した通知ごとの扱いを `storedReminderFate` で決め、その結果を実行する。
  扱いは「残す」「表示済みとして確定」「破棄」「スワイプで消されたとみなして保留」の4種類。
- 保留、`lastNotifiedAt` の更新、表示中の通知だけを取り消す処理は、それぞれ一つの関数にまとめた。
- mutex、トランザクションの範囲、コミット後の処理、`NotificationGateway` を呼ぶ回数と順序は変えていない。

## 確認したこと

- 変更の前に、境界の動作を固定するテストを追加し、変更前のコードで成功することを確認した。
  対象は、日付をまたぐとき、時間ごとのタスクが次の対象時刻ちょうどになったとき、保留が切れたとき、通知の猶予（5分）の境界、一つのタスクの通知をまとめて取り消すとき、通知の許可がなくなったとき、入力エラーの文言の全種類。
- Lizard 1.24.3 で製品コードのメソッドごとの循環的複雑度を変更前後で測った（閾値6以上を表示）。

| メソッド | 変更前 | 変更後 |
| --- | ---: | ---: |
| `ReminderEngine.reconcileLocked` | 16 | 9 |
| `TaskViewModel.saveTask` | 11 | 6未満 |
| `ReminderPolicy.select` | 10 | 6未満 |
| `taskStatus` | 9 | 6未満 |
| `MainActivity.onCreate` | 9 | 6未満 |
| `ReminderEngine.evaluate` | 8 | 6未満 |

`evaluate` から分けた `postNextReminderLocked` は7、`reconcileLocked` から分けた `storedReminderFate` は7。
数値は候補を探す手がかりとして使い、読みやすさはソースを読んで判断した。
