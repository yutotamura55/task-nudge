# Task Nudge Android MVP 設計

## 目的と合意範囲

首・腕・腰のストレッチなど、忘れがちな小さな行動を思い出し、通知から完了・保留できる個人用 Android アプリ。毎日の未完了項目を積み上げず、最後に実行してから最も時間が経ったものを一つずつ知らせる。元のアイデアは [my-software-ideas #11](https://github.com/yutotamura55/my-software-ideas/issues/11)。PR 作成時に合意した Issue の範囲を満たすか評価し、完了していれば cross-repository closing keyword を記載する。部分対応なら通常リンクと残作業を記載する。

ユーザーは設計・実装・独立レビュー・PR 作成と subagent-driven-development を承認済み。以下の時刻・入力上限・技術構成は、未指定部分を具体化した初期値である。

## 共通制約

- Android minSdk 26、compileSdk 36、targetSdk 36。JDK 17、Gradle 8.13、AGP 8.13.2、Kotlin 2.2.21。
- Kotlin、Jetpack Compose、Material 3、Room/SQLite、WorkManager。単一アプリプロセスと純 Kotlin `:core` モジュール。
- 日本語 UI。アカウント・サーバー・分析 SDK・広告・INTERNET 権限は追加しない。
- 初期通知時間はローカル時刻 09:00 以上 21:00 未満。設定可能範囲は開始 0..23 時、終了 1..24 時、開始 < 終了。
- 通知間隔は初期 30 分、選択肢 30/60/120 分。保留は 1 時間固定。通知は同時に最大 1 件。
- タイトルは trim 後 1..100 文字。繰り返しは初期 1 日、整数 1..365 日。タイトルのみの入力で追加できる。
- 完了は同一タスク・同一保存ローカル日で最大 1 件。過去の未完了インスタンスは作らない。
- バックアップは無効。端末移行・クラウド保存・エクスポート・完全削除・完了取消は MVP 対象外。
- main への直接 push、merge、auto-merge、merge queue 投入は行わない。マージはユーザーが行う。

## 選択肢と採用理由

| 方針 | 要件・モデル | 費用・性能 | 整合性・耐障害性 |
| --- | --- | --- | --- |
| **採用: ネイティブ + Room + WorkManager** | 通知アクションとローカル履歴に自然。OS の通知権限をそのまま説明できる | サーバー費用なし。15 分以上の低頻度チェック | 一意制約、トランザクション、単一エンジンで保護。OS 遅延を許容 |
| Web/PWA | Android の背景処理・通知アクションに追加制約 | 配布は簡単だが要件の回避策が増える | 永続性・背景実行の挙動差が増える |
| サーバー + push | 複数端末には有用だが今回の範囲を超える | 認証・運用・ネットワーク費用が増える | 同期競合と個人データ管理が追加される |

正確な時刻を保証するアラーム・常駐サービスは不要。WorkManager の periodic work は最短 15 分であり、省電力や OS の都合で遅れるため、画面には「通知は端末の状態により遅れることがあります」と表示する。[公式 WorkManager 文書](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)

## 日付と候補選択

現在時刻は注入可能な `Clock` と現在の `ZoneId` から取得する。完了イベントには UTC epoch milliseconds と、**完了操作時**のローカル日 `YYYY-MM-DD` を両方保存する。履歴の日付はタイムゾーンを変えても書き換えない。履歴は日付と回数を示し、日時降順で表示する。

当日の保存日付と一致する完了があれば通知候補から除外する。最後の保存完了日から現在ローカル日までの暦日数が `recurrenceDays` 以上なら再度候補となる。タイムゾーン・時計変更で現在日が最後の保存日より前なら、その日を追い越すまで待つ。未完了タスクは作成直後から候補。日数分の backlog は生成しない。手動完了は通知周期前でも可能であり、その日から周期が始まる。

候補の決定順:

1. 停止中、今日完了、周期未到達、保留期限前のタスクを除外。
2. 保留期限に到達したタスクを通常候補より優先。
3. 同じ優先区分では最後の実完了 timestamp が古い順。未完了なら作成 timestamp を使用。
4. 同時刻なら作成 timestamp、最後に ID 昇順で安定化。

保留期限の到来は「通知可能になる」時点であり、正確な通知時刻ではない。すべての新規通知は通知時間と前回の実通知からの全体間隔を守る。保留中も他のタスクは次の通知機会に選べる。

## 通知と状態遷移

通知本文はタスク名、アクションは `完了` / `保留`。lock screen は PRIVATE。通知タップでアプリを開き、そのタスクを表示する。タップだけでは完了にせず、通知を維持する。通知 ID は固定し、各世代にランダムな token を付ける。通知、アクション PendingIntent、deleteIntent は task ID・token を持ち、Intent の data URI にも token とアクションを含める。PendingIntent は明示的・immutable、受信側は非 exported。

| 操作・事象 | 結果 |
| --- | --- |
| 完了 | 保存日付き履歴を INSERT OR IGNORE、保留解除、該当 active を解除して通知 cancel。次は通常間隔後 |
| 保留 / スワイプ削除 | そのタスクを操作時刻 +1 時間まで保留、該当 active を解除。他タスクは全体間隔後から通知可能 |
| 通知を無視 | 同日・通知時間内はその 1 件を維持し、新規通知を重ねない |
| 古い token の callback | 何も変更しない。特に新世代を cancel しない |
| 日付変更 / 時間外 / 停止 / 完了済み | active の無効化と cancel。昨日の通知は新しい日を塞がない |
| タイトル変更 | 該当通知を無効化し、次の評価で最新タイトルを使用。履歴は同じ task ID に残す |
| 権限または channel 無効 | 通知成功として記録しない。画面に通知設定への導線。自動で許可ダイアログを繰り返さない |

通知 permission を除くとアプリを使用可能。初回は説明と「通知を有効にする」ボタンを表示し、API 33 以上ではその明示操作で POST_NOTIFICATIONS を要求する。拒否後は設定へのボタンを表示。API 26..32 の全体/チャンネル無効も同じ状態として案内する。

### 競合とプロセス停止

UI、Worker、Receiver の DB 更新・通知 post/cancel はアプリ共有の `ReminderEngine` を通し、単一 `Mutex` 内で直列化する。DB の関連更新は Room transaction を使用。OS 通知と DB の原子的なコミットは不可能なので、active 行には token、taskId、localDate、preparedAt、deliveredAt(nullable) を永続化する。

post 前に active 予約を書き、通知成功後に deliveredAt と lastNotifiedAt を書く。権限確認前には予約しない。明示的 post 失敗では予約を解除し、配信時刻を進めない。プロセス再起動後は `activeNotifications` と token を照合する。

- DB と OS が一致: 配信済みとして続行。未確定なら preparedAt を配信時刻として確定。
- DB active があるが OS にない: 配信済みなら削除扱いで 1 時間保留。未確定予約なら配信時刻を進めず予約解除して再評価。
- OS のみ、または token 不一致: 古い OS 通知を cancel して DB を再評価。
- 日付変更・停止・完了・時間外の無効化判定は「OS にない→保留」より先に行う。

post 直後・確定前に停止し、その間にユーザーが消した場合の厳密な配信判定はできない。未確定予約として再評価するが、OS 上は常に固定 ID の 1 件だけ。通常経路の deleteIntent は token に基づいて保留を記録する。テストでこの限界と回復方針を固定する。

`BroadcastReceiver.goAsync()` の短い coroutine でエンジン処理し、finally で finish。長い作業はしない。唯一の periodic work を 15 分間隔で KEEP 登録し、起動・追加編集・完了保留・設定変更では評価と必要な unique one-time work を依頼する。WorkManager が通常の再起動復元を担当し、起動時にも登録と整合を確認する。force-stop 後は手動起動まで動作しない。

## 永続モデルと境界

`:core` は Android 非依存の候補選択・入力検証・日付判定を担当。`:app` は Room、通知、WorkManager、Compose を担当。Application 内の小さな `AppContainer` で DB/engine/repository を一度作り、Hilt や汎用 DI フレームワークを追加しない。

- `TaskEntity`: id(Long), title, recurrenceDays, createdAt(epoch millis), archived(Boolean), snoozedUntil(nullable epoch millis)。UI の「停止」は archived に対応し、履歴を維持する。
- `CompletionEntity`: id(Long), taskId(FK), completedAt(epoch millis), localDate(String)。unique(taskId, localDate)、taskId index。
- `ReminderStateEntity`: singleton id=1, activeTaskId?, activeToken?, activeLocalDate?, preparedAt?, deliveredAt?, lastNotifiedAt?。
- `ReminderSettingsEntity`: singleton id=1, startHour=9, endHour=21, intervalMinutes=30, permissionRequested=false。

last completion は履歴の MAX(completedAt) とその localDate を取得。today completion は日付一致で調べる。件数は COUNT。Room schema を version control し、destructive migration を使わない。バックアップ設定 `allowBackup=false` に加え旧版 fullBackupContent と API31+ dataExtractionRules で cloud/device-transfer から全データを除外する。端末メーカーによるバックアップ挙動差は README に記す。[公式バックアップ文書](https://developer.android.com/identity/data/autobackup)

## 画面

Material 3、Android 12+ の dynamic color、旧版の落ち着いた青緑テーマ、dark mode。日本語システムフォント、タップ領域 48dp 以上、状態を色だけで伝えない。edge-to-edge の system/IME inset、文字拡大、TalkBack を考慮。幅が広い場合も本文を最大 720dp に収める。

- **ホーム「タスク」**: 今日の完了数/有効タスク数、通知停止理由、タスク一覧、追加 FAB、設定ボタン、停止中表示切替。行にはタイトル、今日完了/次回対象日/保留時刻、完了回数、最終完了日、手動「完了」。行選択で詳細へ。0 件なら「タスクを追加して、少しずつ続けましょう」。ロード/DB エラーを空一覧と区別する。
- **追加/編集**: タイトル欄が最初にあり必須。任意の「繰り返し」欄は初期毎日。保存/キャンセル。空白・長すぎ・非整数・範囲外は欄の日本語エラーにし、保存しない。入力は回転時維持。停止/再開は詳細画面から可能。
- **詳細/履歴**: タイトル、編集、停止/再開、累計完了回数、日付降順の完了一覧。「まだ完了の記録がありません」。停止しても履歴は閲覧可。連打の完了は 1 件。
- **設定**: 通知可能時間、全体間隔、通知許可状態と設定導線、OS 遅延の説明、端末内保存とアンインストールで履歴が消える旨。開始 >= 終了は保存不可。24 時は翌日 00:00 の境界として表示。

## 検証と完了条件

1. JVM の固定 Clock/ZoneId テストで候補順、保留、周期、深夜、DST、日付変更を検証。
2. Room/Robolectric で一意制約、同時処理、権限拒否、古い token、プロセス再生成、post 失敗、OS/DB 不一致を検証。
3. Compose UI テストでタイトルだけの追加、入力エラー、完了/履歴、停止/再開、設定、permission 説明を検証。
4. CI で core tests、app unit tests、lint、debug APK を作り artifact 化。利用可能なら emulator/device で Compose tests と通知アクションを smoke。使用できない場合は実行済みと未実行を明記し、代替のホストテストと手動手順を提供。
5. 全差分の独立 read-only review を実施し、指摘修正・再検証後 PR を作る。main への統合はユーザーが行う。

AGP/JDK/Gradle の組み合わせは [AGP 8.13 公式互換表](https://developer.android.com/build/releases/agp-8-13-0-release-notes) に基づく。依存ライブラリは Task 1 で公式リポジトリのメタデータを確認して固定する。
