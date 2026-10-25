# 検証ガイド

## コンテナでビルド・テストする

JDK と Android SDK をホストに入れずに、`scripts/check.sh` と同じ検証を Docker コンテナで実行できます。
必要なのは Docker だけです。
rootless モードや `userns-remap` を使う Docker では生成物の所有者を保てないため、スクリプトは実行前に止まります。

```bash
scripts/container-check.sh
```

- リポジトリ直下の `Dockerfile` からイメージ `task-nudge-build:local` を作り、その中で `scripts/check.sh` を実行します。
  JDK 17、Android SDK Platform 36（リビジョン 2）、Build Tools 35.0.0 の版はこの `Dockerfile` で固定しています。
  Platform のリビジョンが変わった場合はイメージの作成が失敗するので、`Dockerfile` の `PLATFORM_REVISION` を更新して検証し直します。
- 引数を付けると、`scripts/check.sh` の代わりにそのコマンドを実行します。
  例：`scripts/container-check.sh ./gradlew --no-daemon :core:test`
- ホストのユーザーとして実行するので、`build/` などの生成物はホストのユーザーの所有になります。
  `local.properties` は作りません。
- Gradle のキャッシュと Robolectric がダウンロードする Android のライブラリは、Docker ボリューム `task-nudge-build-cache-<ユーザー ID>`（例：`task-nudge-build-cache-1000`）に保存します。ユーザー ID は `id -u` で分かります。
  初回はダウンロードを含めて5分ほどかかります。
  キャッシュを消すときは `docker volume rm task-nudge-build-cache-$(id -u)` を実行します。次の実行で作り直されます。
  キャッシュには実行時に読み込まれる Gradle のプラグインなどが入るため、スクリプトは毎回このボリュームをホストのユーザーだけが書き込める状態にしてから実行します。
- **コンテナで作った APK を普段使いの端末に上書きしないでください。**
  debug 署名の鍵はキャッシュのボリューム内で作られ、ホストの `~/.android/debug.keystore` とは別の鍵です。
  署名が違う APK は上書きできず、アンインストールすると記録が消えます。
  普段使いの端末に入れる APK はホストでビルドし、下の「手動で APK を入れる場合」の手順で署名を確かめます。

エミュレーターと実機での計装テスト（`scripts/device-smoke.sh`）、`adb` での接続とインストールはコンテナの外で行います。
USB 端末や KVM をコンテナに渡す構成にしていないためです。
ホストには、下の「ホスト環境」の JDK と SDK、および platform-tools が必要です。

## ホスト環境

- JDK 17、Android SDK Platform 36、Android SDK Build Tools 35、platform-tools。Gradle 8.13 は `gradlew` に固定されています。
- AGP 8.13.2、Kotlin 2.2.21、Room 2.8.4、WorkManager 2.10.5、Compose BOM 2025.10.01。依存バージョンは `gradle/libs.versions.toml` を参照します。
- リポジトリ直下で `scripts/check.sh` を実行します。内容は `:core:test :app:testDebugUnitTest :app:lintDebug :core:detekt :app:detekt :app:assembleDebug :app:assembleDebugAndroidTest`、`--no-daemon --max-workers=1` です。APK は `app/build/outputs/apk/debug/app-debug.apk` にできます。
- ローカルの SDK パスや署名情報をリポジトリへ保存しません。`ANDROID_HOME` と `JAVA_HOME` を環境に設定してください。

## 静的解析（detekt）

`scripts/check.sh` は detekt 1.23.8 で Kotlin のコードを検査し、新しい指摘が1件でもあれば失敗します。
検査するのは `core` と `app` の `src/main` と `src/test` です。端末テスト（`src/androidTest`）は対象外です。

- detekt だけを実行するときは `./gradlew :core:detekt :app:detekt` を実行します。
  指摘の一覧は `core/build/reports/detekt/detekt.html` と `app/build/reports/detekt/detekt.html` にできます。
- 規則は detekt の既定に、`config/detekt.yml` の変更を重ねたものです。
  変更した規則と理由は、このファイルのコメントに書いてあります。
  例：Compose の関数名が大文字で始まることは `FunctionNaming` の対象外にしています。
- 導入時点（commit `403cc55`）の指摘 194件は、モジュールごとのベースライン（`core/detekt-baseline.xml`、`app/detekt-baseline.xml`）に記録しています。
  ベースラインにある指摘では失敗しません。
  直すかどうかの判断は Issue #42 に記録しています。

指摘が出たときは、次の順に対応します。

1. まずコードを直します。
2. 規則がこのリポジトリの書き方に合わないと判断したときは、`config/detekt.yml` で規則を調整し、理由をコメントに書きます。
3. 直さずに残すときだけ、ベースラインに追加します。残す理由は PR の本文に書きます。
   `./gradlew :core:detektBaseline :app:detektBaseline` はベースラインを全体で作り直すので、差分が残すと決めた指摘の追加だけであることを確かめます。

ベースラインは、指摘のあった関数や行の内容で指摘を識別します。
記録済みの行を書き換えると新しい指摘として扱われるので、たとえば120文字を超える既存の行を変えるときは、その行を折り返します。
直した指摘の記録はベースラインに残っていても失敗の原因にはなりませんが、ベースラインを作り直すと消えます。

detekt の版：1.23.8 は Kotlin 2.0.21 向けに作られた安定版ですが、このリポジトリの Kotlin 2.2.21 のコードを解析できることを確認しました（型解決なしの `detekt` タスク）。
2.0.0 は alpha 版で、最新の alpha.6 は Kotlin 2.4・AGP 9 向けのため採用していません。
Kotlin を上げて detekt が解析に失敗したら、その時点の detekt の版と[互換表](https://detekt.dev/docs/introduction/compatibility/)を確かめます。

## 端末・エミュレーター

Android 8.0（API 26）以上の**専用テスト端末またはエミュレーター**を 1 台 `adb devices` で `device` と表示させ、`scripts/device-smoke.sh` を実行します。このスクリプトは接続台数を検査し、`connectedDebugAndroidTest` で debug APK とテスト APK をインストールして全 instrumentation テストを走らせます。このテスト構成は終了後に対象アプリとテスト APK をアンインストールするため、既存の記録がある端末では実行しないでください。画面画像は `build/device-screenshots/` に取得します。テスト結果は `app/build/outputs/androidTest-results/connected/` と `app/build/reports/androidTests/connected/` を確認します。

手動で APK を入れる場合、既存アプリと同じ署名なら `adb install -r app/build/outputs/apk/debug/app-debug.apk` で上書きできます。CI の debug 署名は実行間で固定管理されず、別 run の APK は上書きできる保証がありません。署名が違ってアンインストールすると記録は消えます。テスト APK は `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` です。個別の原因調査では、両 APK を入れた後に `adb shell am instrument -w dev.tasknudge.test/dev.tasknudge.DeviceTestRunner` を実行できます。`adb logcat` で `ReminderEngine` と `TaskNudge` の記録を確認します。テストは専用のインメモリ Room DB を使い、テスト中の実通知を最後に消します。

GitHub Actions は pull request と main への push で起動し、ホスト検証と API 35 Google APIs x86_64 エミュレーターの端末テストを別 runner で並行実行します。PR 作成前の検証にはローカルの `scripts/check.sh` を使います。アップロードに成功した run では、`task-nudge-debug` に APK、`host-test-results` に JVM/lint/detekt、`android-test-results` に instrumentation 結果と実画面画像が3日間保存されます。アップロード失敗はジョブを失敗にしないため、必要な成果物は run の **Artifacts** 一覧で存在を確認してください。ローカルで `/dev/kvm` が使えない場合は、CI の emulator job の成功を確認するまで端末検証を完了と扱いません。

README の3画像は [成功したCI 36952478562](https://github.com/yutotamura55/task-nudge/actions/runs/36952478562) のAPI35実画面です。最終source `6d4db8f` の [PR CI 36963062421](https://github.com/yutotamura55/task-nudge/actions/runs/36963062421) と [push CI 36963059186](https://github.com/yutotamura55/task-nudge/actions/runs/36963059186) は単体101件・端末24件がすべて成功。両runの画像も掲載画像とSHA-256一致を確認しました。現在のchecksは [PR #2](https://github.com/yutotamura55/task-nudge/pull/2) を参照してください。

## 手動確認（機種ごとの差を見る）

1. 通知を許可・拒否した状態でタスクを追加し、拒否中も一覧・詳細・手動完了・履歴が使えることを確認します。再許可後はアプリを開き、通知が再開することを確認します。
2. 通知の「完了」「保留」と通知タップを試し、連打しても同日の履歴や新しい通知が壊れないことを確認します。
3. 端末を再起動してアプリを開き、通知状態と履歴が維持されることを確認します。アプリ強制停止後は手動起動まで通知が再開しないことを確認します。
4. 日付またぎとタイムゾーン変更後、前日の通知が残らず、過去の履歴の日付が書き換わらないことを確認します。
5. 文字サイズを大きくし、画面を回転して、入力中の下書きと保存ボタンが使えることを確認します。通知時間帯、通知チャンネル、省電力設定も機種ごとに確認します。
6. Issue #9 の通知間隔を確認する専用端末では、通知時間を3時間以上含むように設定し、間隔を15分にします。異なるタスクを2件登録して最初の通知を確認した後、画面を消して3時間放置します。その間は通知を開いたり消したりせず、終了後に通知欄の件数と各通知の時刻、端末の充電・省電力状態を記録します。2件目はまだ通知されていないタスク、以降は通知が古いタスクが選ばれることを確認します。3時間の件数は端末・OSの制御を含む実測値として記録し、全端末への保証と混同しません。
7. Issue #28 の短い間隔は、専用端末で通知時間を0〜24時にし、未完了のタスクを1件登録して5分を選びます。保存後に設定を開き直して5分が選択されていることを確認し、最初の通知後に画面を消して30分間の通知投稿時刻を記録します。10分でも同じ手順を繰り返します。「アラームとリマインダー」の許可、省電力設定、充電状態も記録し、設定値と実際の間隔を分けて報告します。端末やOSによる遅延を含む実測値であり、指定した分数での到着は保証しません。

OEM の省電力制御、再起動、強制停止後の挙動は API 35 エミュレーターだけでは確認できません。実機での確認結果がない場合は、検証済みと記載しません。

開発環境、通知権限、データ境界、端末テストで今回確認した事実は [Android 開発記録](android-development-notes.md) にまとめています。
