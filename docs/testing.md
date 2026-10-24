# 検証ガイド

## ホスト環境

- JDK 17、Android SDK Platform 36、Android SDK Build Tools 35、platform-tools。Gradle 8.13 は `gradlew` に固定されています。
- AGP 8.13.2、Kotlin 2.2.21、Room 2.8.4、WorkManager 2.10.5、Compose BOM 2025.10.01。依存バージョンは `gradle/libs.versions.toml` を参照します。
- リポジトリ直下で `scripts/check.sh` を実行します。内容は `:core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`、`--no-daemon --max-workers=1` です。APK は `app/build/outputs/apk/debug/app-debug.apk` にできます。
- ローカルの SDK パスや署名情報をリポジトリへ保存しません。`ANDROID_HOME` と `JAVA_HOME` を環境に設定してください。

## 端末・エミュレーター

Android 8.0（API 26）以上の**専用テスト端末またはエミュレーター**を 1 台 `adb devices` で `device` と表示させ、`scripts/device-smoke.sh` を実行します。このスクリプトは接続台数を検査し、`connectedDebugAndroidTest` で debug APK とテスト APK をインストールして全 instrumentation テストを走らせます。このテスト構成は終了後に対象アプリとテスト APK をアンインストールするため、既存の記録がある端末では実行しないでください。画面画像は `build/device-screenshots/` に取得します。テスト結果は `app/build/outputs/androidTest-results/connected/` と `app/build/reports/androidTests/connected/` を確認します。

手動で APK を入れる場合、既存アプリと同じ署名なら `adb install -r app/build/outputs/apk/debug/app-debug.apk` で上書きできます。CI の debug 署名は実行間で固定管理されず、別 run の APK は上書きできる保証がありません。署名が違ってアンインストールすると記録は消えます。テスト APK は `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` です。個別の原因調査では、両 APK を入れた後に `adb shell am instrument -w dev.tasknudge.test/dev.tasknudge.DeviceTestRunner` を実行できます。`adb logcat` で `ReminderEngine` と `TaskNudge` の記録を確認します。テストは専用のインメモリ Room DB を使い、テスト中の実通知を最後に消します。

GitHub Actions は pull request と main への push で起動し、ホスト検証と API 35 Google APIs x86_64 エミュレーターの端末テストを別 runner で並行実行します。PR 作成前の検証にはローカルの `scripts/check.sh` を使います。アップロードに成功した run では、`task-nudge-debug` に APK、`host-test-results` に JVM/lint、`android-test-results` に instrumentation 結果と実画面画像が3日間保存されます。アップロード失敗はジョブを失敗にしないため、必要な成果物は run の **Artifacts** 一覧で存在を確認してください。ローカルで `/dev/kvm` が使えない場合は、CI の emulator job の成功を確認するまで端末検証を完了と扱いません。

README の3画像は [成功したCI 36952478562](https://github.com/yutotamura55/task-nudge/actions/runs/36952478562) のAPI35実画面です。最終source `6d4db8f` の [PR CI 36963062421](https://github.com/yutotamura55/task-nudge/actions/runs/36963062421) と [push CI 36963059186](https://github.com/yutotamura55/task-nudge/actions/runs/36963059186) は単体101件・端末24件がすべて成功。両runの画像も掲載画像とSHA-256一致を確認しました。現在のchecksは [PR #2](https://github.com/yutotamura55/task-nudge/pull/2) を参照してください。

## 手動確認（機種ごとの差を見る）

1. 通知を許可・拒否した状態でタスクを追加し、拒否中も一覧・詳細・手動完了・履歴が使えることを確認します。再許可後はアプリを開き、通知が再開することを確認します。
2. 通知の「完了」「保留」と通知タップを試し、連打しても同日の履歴や新しい通知が壊れないことを確認します。
3. 端末を再起動してアプリを開き、通知状態と履歴が維持されることを確認します。アプリ強制停止後は手動起動まで通知が再開しないことを確認します。
4. 日付またぎとタイムゾーン変更後、前日の通知が残らず、過去の履歴の日付が書き換わらないことを確認します。
5. 文字サイズを大きくし、画面を回転して、入力中の下書きと保存ボタンが使えることを確認します。通知時間帯、通知チャンネル、省電力設定も機種ごとに確認します。
6. Issue #9 の通知間隔を確認する専用端末では、通知時間を3時間以上含むように設定し、間隔を15分にします。異なるタスクを2件登録して最初の通知を確認した後、画面を消して3時間放置します。その間は通知を開いたり消したりせず、終了後に通知欄の件数と各通知の時刻、端末の充電・省電力状態を記録します。2件目はまだ通知されていないタスク、以降は通知が古いタスクが選ばれることを確認します。3時間の件数は端末・OSの制御を含む実測値として記録し、全端末への保証と混同しません。

OEM の省電力制御、再起動、強制停止後の挙動は API 35 エミュレーターだけでは確認できません。実機での確認結果がない場合は、検証済みと記載しません。

開発環境、通知権限、データ境界、端末テストで今回確認した事実は [Android 開発記録](android-development-notes.md) にまとめています。
