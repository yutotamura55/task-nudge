# Issue #36 画面遷移の型と画面の引数

表示中の画面を文字列ではなく `Route`（`ui/Route.kt`）で表す。
アプリの動作は変えていない。

## Route

| 画面 | Route | 戻る先 |
| --- | --- | --- |
| 一覧 | `Route.TaskList` | （なし） |
| 詳細 | `Route.Detail(taskId)` | 一覧 |
| 追加 | `Route.Editor(null)` | 一覧 |
| 編集 | `Route.Editor(taskId)` | そのタスクの詳細 |
| まとめて追加 | `Route.Bulk` | 一覧 |
| 通知設定 | `Route.Settings` | 一覧 |

- 画面のタイトル、戻る先、表示に必要なタスク（`requiredTaskId`）は `Route` が持つ。
  必要なタスクが削除されたときは一覧に戻る。
- タスク ID は、それを使う画面の `Route` にだけ含める。
  以前は一覧や設定画面でも選択中のタスク ID を保持していたが、どの画面でも使っていなかった。
- 回転やプロセスの再生成では `Route.saver` で画面を復元する。
  新しい画面を追加したら、`Route.saver` の保存と復元にも追加し、`RouteTest` で往復を確認する。

## 古い画面の操作を無視する仕組み

画面を移るたびに `TaskViewModel.beginScreenSession()` が新しい session ID を発行する。
`TaskNudgeApp` の各コールバックは、作られたときの session ID（`owner`）を持つ。

- 画面内だけで完結する操作（画面の移動、停止中の表示切り替え、検索語、通知設定を開く）は `fromOwner` で確認し、現在の session と違えば何もしない。
- 保存や完了などの書き込みは、`owner` を ViewModel に渡し、ViewModel が同じ確認をする。

## 画面の引数

一覧・詳細・設定画面は、表示用データと操作のコールバックを分けて受け取る。

- 一覧：`TaskListContent` と `TaskListActions`
- 設定：`SettingsContent` と `SettingsActions`。入力エラーや保存中の表示は、他の入力画面と同じく `TaskUiState` で受け取る。
- 詳細：表示用の引数は型が異なる4つなのでそのまま渡し、操作だけを `TaskDetailActions` にまとめた。

`TaskListContent` のように同じ型の値が並ぶものは、呼び出し側で名前付き引数を使う。
