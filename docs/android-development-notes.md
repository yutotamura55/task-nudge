# Task Nudge の Android 開発記録

スマホアプリ全般・Android 全般の共有知見は workspace の `docs/mobile-app-development-notes.md` に整理した。この文書は Task Nudge での採用内容、障害の実例、検証証跡を残す。公式仕様・今回の観測・回避策を区別する。現在の検証結果は [testing.md](testing.md)、作業再開は [development-handoff.md](development-handoff.md) を参照。

## 次の開発で先に決めること

| 項目 | 準備すること | 理由 |
|---|---|---|
| 通知時刻 | 厳密な時刻が必要か、遅延を許容するか | バックグラウンド実行方式と許可が変わる |
| 通知許可 | 未要求・拒否・アプリ全体無効・channel 無効の導線 | 許可ダイアログの戻り値だけでは通知可能性を表せない |
| データ | 完了の一意性、日付の保存方法、バックアップ方針 | 連打・時差・端末移行で意味が変わる |
| 非同期 UI | 保存確定と表示更新、結果の所有者 | DB 更新の観測は保存呼び出しと同時に届かない |
| 端末検証 | emulator/KVM または実機、回転・文字拡大・IME | APK のコンパイルだけでは画面操作を検証できない |
| Toolchain | AGP・Gradle・JDK・SDK・依存バージョンの組合せ | 個別に最新版を選ぶと再現性が下がる |
| 引き継ぎ | branch、検証した commit、結果、次の操作 | agent/context の中断後に同じ実装をやり直さない |

## 1. 通知権限は画面の正常利用と独立させる

**知識:** Android 13/API33 以降の通知には実行時の `POST_NOTIFICATIONS` 許可が必要。新規インストール時の通常通知は初期状態で無効であり、target によってダイアログの表示制御も異なる。[公式の通知権限説明](https://developer.android.com/develop/ui/compose/notifications/notification-permission)

**今回の設計:** 明示的なボタンから要求し、要求済みを永続化。拒否後や channel 無効では OS 設定へ誘導する。復帰時は実際の権限・アプリ全体・channel 状態を再確認する。タスク追加・履歴は通知無効でも使用できる。

**事前準備:** 初回許可だけでなく、拒否・再起動・設定変更からの復帰を設計に含める。API33 未満の導線も用意する。

## 2. 実行中の権限取り消しはテストのプロセスにも影響する

**今回の観測:** API35 の [CI run 36875126874](https://github.com/yutotamura55/task-nudge/actions/runs/36875126874) で、`UiAutomation.revokeRuntimePermission` の後に instrumentation PID が signal 9 で終了した。XML の assertion だけを見ると原因が分からず、logcat と同時刻のプロセス終了を照合した。

**公式仕様との区別:** AOSP は `revokeSelfPermissionsOnKill()` による自己取り消しで同 UID のプロセスが終了すると説明している。これは今回使用した UiAutomation API と同一ではない。今回の取り消し後の終了は、上記 CI の観測として記録する。[AOSP runtime permissions](https://source.android.com/docs/core/permissions/runtime_perms)

**事前準備:** OS 権限を変えるテストにはプロセス終了を考慮する。拒否状態で画面が使えることは、通知無効 gateway を注入した実 Activity + Room テストで確認する。実通知・実 Receiver は別テストで維持する。実 OS の拒否・復帰は、外側からアプリを起動し直せる手動手順または別プロセスのテストで検証する。注入テストを実 OS の拒否操作の検証と呼ばない。

## 3. WorkManager は正確な通知タイマーではない

**知識:** periodic work の最小間隔は15分。実際の実行時刻は制約や OS の最適化に左右される。[公式 work request 説明](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)

**今回の設計:** 15分ごとの評価と、アプリの30/60/120分の通知間隔を分ける。実行されるたびに現在時刻・通知可能時間・前回通知・保留を判定し、保存した状態を基準にする。ボタン操作などでは unique one-time 評価も依頼する。

**事前準備:** 「一定時刻に必ず届く」という UX を約束する前に方式を選ぶ。省電力・強制停止・再起動の確認手順を準備し、許容する遅延を利用者に説明する。

## 4. PendingIntent は extras だけを変えても別物にならない

**知識:** PendingIntent の一致判定は Intent の action/data/component などを使い、extras の差だけでは区別しない。immutable でも作成者の `FLAG_UPDATE_CURRENT` による更新は可能。[PendingIntent 公式 API](https://developer.android.com/reference/android/app/PendingIntent)

**今回の設計:** 表示する通知 ID は固定し、各世代の token と操作を data URI に含める。明示的 component と immutable を使い、Receiver は task ID と token を DB の active 世代と照合する。

**事前準備:** 同じ通知を置き換えた後も古いボタンが届くケースを考える。表示 ID と操作世代を別の概念にし、古い完了・保留・削除操作が新しい通知を変更しないテストを最初から用意する。

## 5. DB と OS 通知を一つのトランザクションにはできない

**今回の問題:** 通知を表示してから DB の delivered 記録を確定する間、または UI 保存後の通知整理で中断・例外が起きる。保存後の例外を保存失敗として返すと、再試行で新規タスクを重複追加する。

**設計知見:** prepared/delivered を区別し、起動時・定期実行時に OS の実通知と照合する。DB commit をコマンド成功の境界にし、確定後の通知整理・再評価予約は復旧可能な処理としてログを残す。関連 DB 更新は transaction、OS 通知との直列化は共有 mutex。キャンセルを通常失敗に変換しない。

**現在の制約:** 保存前の OS 照合が失敗すると、DB 保存前にコマンドは失敗する。prepared 世代が実際には表示済みの場合に、配信時刻を確定しないまま世代情報を消して通知間隔を飛ばすことを防ぐため。通知サービス障害中にも保存を継続する保証は今回の範囲にない。完全な分離には、状態不明と永続的な無効化世代を扱う設計が必要であり、一律 catch では解決しない。

**確認:** 表示直後の中断、OS-only/DB-only、不一致 token、DB 確定後の gateway/scheduler 例外を検証する。[境界再設計の仕様](superpowers/specs/2026-10-01-command-boundaries.md)

## 6. 保存成功とデータ表示の更新を別の状態にする

**今回の問題:** 保存したタイトル・繰り返しが snapshot に現れるまで保存中にしていた。観測が停止すると待ち続け、観測エラーを保存失敗と扱うと確定済み追加を再試行させる。項目追加のたびに完了判定も変更する構造だった。

**設計知見:** 書き込みは `Idle / Saving / Committed / Failed`、読み込みは独立した状態と再試行。確定済み・未観測なら表示待ちとし、同じ編集からの再送信を防ぐ。読み込み失敗で保存結果を失敗に戻さない。

**事前準備:** テストでは「snapshot が遅れる」「読み込みだけ失敗する」「確定後に戻る」を別々に操作できるようにする。内部の待機方式を固定する assertion より、件数と画面・入力が正しいことを確かめる。

## 7. 非同期操作の所有者は callback 作成時に固定する

**今回の問題:** 画面 A の保存が遅れ、B を開いた後に A の結果が B の draft を閉じた。session 導入後も、callback が実行時の可変 session/task ID を読んでしまい、古い画面が新しい所有者として操作できた。

**設計知見:** 結果・エラー・遷移は開始元の session にのみ適用する。callback 作成時に immutable な owner/task ID を捕捉する。メッセージを消す操作と所有者の切り替えを兼用しない。

**確認:** 古い Compose click action を保持し、画面移動後に呼び出す。ViewModel に古い ID を直接渡す unit test だけでは、UI が新しい ID を渡す不具合を見つけられない。

## 8. 「一日」と「24時間」、回転とプロセス終了は別に検証する

**今回の設計:** 完了時の Instant と、その時点の LocalDate を保存する。日単位の繰り返しはカレンダー日で扱い、保存済みの日を端末の timezone 変更で書き換えない。固定 Clock/ZoneId で日付変更・DST・未来の日付を検証する。

**画面:** `rememberSaveable` で draft を回転時に保持し、ViewModel の操作結果を同じ session に対応させる。回転で ViewModel が残る場合と、プロセスが終了して作り直される場合は別のテスト。確定済みコマンドの receipt を永続化する設計は今回の範囲にない。

## 9. バックアップ無効は Android の版と転送方式を確認する

**知識:** `allowBackup=false` の端末間転送への適用は一部メーカーで異なる。Android11以前の XML と Android12以降の data extraction rules は別設定になる。[Auto Backup 公式説明](https://developer.android.com/identity/data/autobackup)

**今回の準備:** manifest に backup 無効と両 XML を指定し、cloud/device-transfer からデータを除外。Room schema を管理し、destructive migration を使わない。メーカー実機の移行動作は未検証として記録する。

**次回:** 端末内限定・移行可能・クラウド保存のどれを約束するか先に決め、対象 OS の最新の転送方式と設定を確認する。

## 10. 端末テストは viewport と非同期読み込みも操作する

**今回の観測:** landscape + keyboard では保存ボタンがスクロール前の画面外だった。失敗時に portrait 復元が走らず、次の画像テストへ姿勢が残った。seed 済みでも、Room の観測と画面描画が終わる前に assert/capture すると見えない。

**事前準備:** 回転・font scale・通知・DB・container を finally で戻す。存在と表示を分け、scroll 可能性を確認して操作する。DB の結果を bounded wait で確認してから画像を取得する。画面外のボタンを単に assertion 対象から外す対応はしない。

**検証層:** pure Kotlin/Room/Robolectric、Compose の APK compile、実 emulator 実行はそれぞれ記録する。compile 成功を端末 pass と報告しない。実 OS の通知と Receiver を少なくとも一つの smoke で動かす。

## 11. Toolchain と CI は小さな動く組合せを先に作る

**今回の準備:** JDK17・Gradle8.13・AGP8.13.2・Kotlin2.2.21・compile/target SDK36 の組合せを固定。公式配布物の checksum、依存の Maven metadata と AAR 条件を確認し、wrapper を保存した。SDK/cache/local.properties を commit しない。

**今回の観測:** setup-android action の既定 `tools` package は取得できず、アプリの task 前に CI が失敗した。`packages: platform-tools` の明示で進められた。action SHA の固定に加え、入力の既定値と runner の変化も確認が必要。[失敗と修正の CI](https://github.com/yutotamura55/task-nudge/actions/runs/36874951511)

**回避策の確度:** AGP lint の FIR 内部エラーは `--max-workers=1` で同じ差分の gate が成功したため、この repo の回避策として採用。全ての lint エラーへの一般的解決策ではない。resource rename 後の stale merge は対象 `mergeDebugResources --rerun-tasks` で再生成した。製品のエラーと toolchain の内部エラーをログで区別する。

## 12. Agent の中断に備えた保存も開発準備に含める

**今回の観測:** 使用上限で agent が停止し、最終 report 前の実装だけ残った。全体 gate が未完の WIP を明示した checkpoint commit と、branch/base/レビュー/次の操作を書いた文書で再開できた。

**実行環境の知見:** sandbox 内の編集と、escalation が必要な Gradle を一つの command にまとめると承認待ちで止まった。編集を通常権限、Gradle を承認済み prefix の別コマンドに分ける。これはこの agent 実行環境の知見であり、Android の制約ではない。

**準備:** 結果を最後まで report に残さない進め方を避け、節目で記録する。モデル、reviewer、検証した commit、未実行の確認を残す。テストが通っても、同じ境界で修正が続けば所有者・不変条件・変更の波及範囲を設計レビューする。

## 13. 端末テストの不安定さは CI に近い条件で再現してから直す

**今回の観測（Issue #45）:** CI の端末テストは、一つの run の中で複数のテストがまとめて失敗し、再実行すると通ることが多かった。起動済みのローカルエミュレーターでは11回すべて成功した。毎回データを消して起動し、`taskset -c 0-1` でエミュレーターと Gradle を CPU 2個に制限すると、6回中3回 CI と同じ失敗が出た。失敗の直前にはソフトキーボードの表示と非表示が繰り返されていた。

**原因:** 入力欄にフォーカスすると、システムがアプリにキーボードの領域（WindowInsets の IME）を渡し、`imePadding()` の画面が縮む。領域の更新はアプリの外で非同期に起き、Compose のテストは待たない。遅い環境では、タップ、スワイプ、表示の判定が、配置の動いている最中に当たる。既存の `dismissKeyboard()` は閉じ終わりを待たず、閉じた直後に前の表示要求が遅れて届き、領域が再び現れることもあった。

**キーボードの無効化は解決にならなかった:** すべてのキーボードを `adb shell ime disable` で無効にしても、設定画面のテストを繰り返すと 25〜150 回に 1〜3 回失敗した。失敗時、アプリはキーボードが表示中（下端から 221px）と受け取っており、システムのログは `serverVisible: true` だった。キーボードのアプリがなくても、表示要求に対して領域だけが渡されることがある。また、`connectedDebugAndroidTest` が APK をインストールすると、システムが既定のキーボードを有効に戻すため、テストの前に無効にしても実行中は有効だった。

**対応:** テストは文字を入力した後に `closeKeyboard()` でフォーカスを外し、領域が 0.5 秒続けて消えてから次の操作をする。一度だけ確かめる待ち方では、遅れて届いた表示要求で 150 回に 1 回失敗した。フォーカスが必要なスワイプのテストは `awaitKeyboardAreaSettled()` で領域の高さが落ち着いてから位置を測る。この形で、CI に近い条件の全体実行で 44 件が最後まで動いた回はすべて成功した。キーボードを表示した画面の確認（#47）と、キーボードの種類による違いの確認（#48）は別の Issue で扱う。

**準備:** 「再実行で通る」失敗は、run 単位の環境の状態を疑う。失敗時の画面とキーボードの状態をログに残し（`FailureDiagnostics`）、CI に近い遅さで繰り返してから修正する。`ActivityScenario.use {}` は、ルールが失敗を受け取る前に Activity を閉じるので、記録はテスト本体の中で取る。
