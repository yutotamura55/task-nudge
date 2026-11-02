# Issue #67: テストカバレッジの計測

## 目的

単体テストがアプリのコードのどこを動かしているかを数値で確かめ、テストが薄い箇所を見つける。
この Issue では計測の仕組みを入れて結果を記録するまでを行う。
テストの追加と、CI で割合を検査するかどうかは、別の Issue で決める（2026-11-03、ユーザーが範囲を選んだ）。

## 計測の方法

Kover 0.9.11 を `core` と `app` に入れた。手順は [検証ガイド](../testing.md) の「単体テストで動いた行の割合（カバレッジ）を測る」にある。
Kover は既定ではすべてのテストの実行に計測の仕組みを付けるので、`-Pcoverage` を付けたときだけ測るようにした。`scripts/check.sh` と CI のテストは導入前と同じ条件で動く。
Kover を選んだのは、Kotlin と Android のビルドバリアントにそのまま対応し、AGP の設定を変えずに入れられるためである。
AGP に組み込まれた JaCoCo は端末テストも測れるが、エミュレーターか実機が要り、この PR の範囲（単体テストの計測）では使わない。

## 結果（2026-11-03、main の `fac5b6e` に計測の設定だけを加えた状態）

| モジュール | 行 | 分岐 |
| --- | --- | --- |
| `core` | 98.0%（98/100 行） | 95.5%（128/134） |
| `app`（Room の生成コードを除く） | 52.3%（729/1394 行） | 40.4%（416/1030） |

`app` の数値は、コードの種類によって大きく違う。

| `app` のコードの種類 | 行 | 対象のファイル |
| --- | --- | --- |
| ロジック（通知の判断、保存、書き出し・読み込み、画面の状態） | 96.1%（717/746 行） | `ReminderEngine`、`TaskViewModel`、`BackupCodec`、`TaskSnapshot`、`Route` など |
| 画面（Compose） | 0.6%（3/528 行） | `TaskNudgeApp`、`TaskListScreen`、`SettingsScreen` など各画面と `Theme` |
| Android の入口と OS への依頼 | 7.5%（9/120 行） | `MainActivity`、各 `Receiver`、`ReminderWorker`、`AndroidReminderScheduler`、`ContentBackupDocument` など |

画面の低い数値は、テストがないことを意味しない。
画面は端末テスト（`TaskScreensTest` 37件、`ScreenshotTest` 7件）で操作しているが、Kover はその分を測らない。
「Android の入口」のうち、通知の「完了」「保留」を受け取る `ReminderActionReceiver` も、端末テスト（`ReminderSmokeTest` 4件）が通知のボタンを押して動かしている。
Room の生成コード（`TaskDao_Impl.java` と `TaskDatabase_Impl.java`、557 行）を含めると、`app` の行は 63.9% になる。手で書いたコードではないので除いた。

## テストが薄い箇所

単体テストで一度も動かなかった行のうち、壊れると利用者に影響するものを挙げる。
端末テストで動いているかは、テストのコードを読んで確かめた（端末テストの分のカバレッジは測っていない）。

| 箇所 | 動かなかった処理 | 端末テスト | 影響 |
| --- | --- | --- | --- |
| `ReminderEngine.kt` 147〜150 行 | 表示中の通知が 40 件に達したとき、最も古い通知を消してから新しい通知を出す処理 | なし | 長く通知を放置した端末で、通知が出なくなる・消すべきでない通知を消す、といった不具合を検出できない |
| `ReminderAlarmReceiver`、`ReminderBootReceiver`、`ReminderWorker` | スリープ中のアラームと定期実行から通知の判断を呼ぶ処理、失敗時に予約し直す・再試行する処理、再起動や時計の変更の後に定期実行を登録し直す処理 | なし | 判断の中身（`ReminderEngine`）は試しているが、OS から呼ばれる入口は手動確認だけ |
| `AndroidReminderScheduler.kt` 18〜27 行 | WorkManager に定期実行と1回の実行を登録する処理 | なし（`scheduleWhileIdle` だけ単体テストあり） | 登録の名前や重複時の方針（`KEEP`）を変えても検出できない |
| `ContentBackupDocument`（`BackupDocument.kt`） | 書き出し先を `"wt"` で開けない保存先で `"w"` に切り替える処理、読み込み・削除 | なし（テストは偽の実装を使う） | 一部の保存先での書き出しの失敗を検出できない |
| `TaskViewModel.kt` 227〜236 行 | 読み込みの失敗理由（10 種のうち 8 種）を画面の文に変える処理 | なし | 理由の判定（`BackupCodec`）は試しているが、表示する文の誤りは検出できない |

表は 2026-11-03 の計測時点のものである。`ReminderEngine.kt` 147〜150 行には、その後 Issue #69 で単体テストを足した（`ReminderEngineTest` の `reminderAtTheVisibleLimitReplacesOnlyTheOldest` など）。

次の行も動いていないが、影響が小さいので挙げない。

- `CancellationException` を投げ直すだけの `catch`（`TaskViewModel.kt` 44・140・173・212 行）。
- `BackupCodec.kt` 184 行：読み込み中の例外のうち、分類しないもの（`IOException`・日時の書式・数値の書式などの以外）をそのまま返す処理。そのまま返った例外は `TaskViewModel` の `catch` が「ファイルを開けませんでした」と表示する。`IOException` などを「形式が壊れている」に変える 182〜183 行は、既存のテスト（`BackupCodecTest` の `rejectsInvalidUtf8` など）で動いている。
- `BackupCodec` の上限付きストリームのうち、1 バイトずつの読み書きと `skip`（192・195・206・207 行）。今の読み込みと書き出しはこの経路を使わない。
- `core` の 2 行（`TaskInput.kt` 32・46 行）。32 行は `bulkTitlesError` に正しい入力を渡したときの処理で、`core` のテストは誤った入力だけを試しているが、`app` の `TaskViewModel` のテストからは動いている。46 行は `validateBulkTaskInput` の引数 `recurrenceDays` を省略した呼び出しで、テストにもアプリにもこの呼び出しはない。

## 次に決めること

- 上の表の箇所にテストを足すか、どれから足すか。
- `scripts/check.sh` や CI でカバレッジを測り、レポートを成果物として残すか。割合に下限を設けて失敗させるか。
- 端末テストの分も測るか（AGP の JaCoCo を使い、エミュレーターで実行する）。
