# Issue #30: GitHub Actions の所要時間

## 調査対象と観測

2026-10-17 の成功した [main の run 39016954323](https://github.com/yutotamura55/task-nudge/actions/runs/39016954323) と [PR の run 39018714616](https://github.com/yutotamura55/task-nudge/actions/runs/39018714616) のジョブ開始・終了時刻を GitHub Actions API で確認した。両 run とも `verify` の終了後に `android-device-tests` が始まっていた。

| run | `verify` | `android-device-tests` | ジョブ開始から終了まで |
| --- | ---: | ---: | ---: |
| main 39016954323 | 3分14秒 | 8分53秒 | 12分10秒 |
| PR 39018714616 | 3分16秒 | 8分06秒 | 11分24秒 |

PR run 39018714616 の端末ジョブログでは、エミュレーター action が 08:51:51〜08:59:16 の 7分25秒を占めた。その中で Android SDK・system image の準備後にエミュレーターが 08:53:45 に起動し、Gradle が 08:53:48 ごろから 08:59:15 まで動作した。41 件の端末テストは 08:57:43〜08:59:13 の約90秒だった。テスト中の一律の長い待機や、削除できる重複テストが主因と判断できる証拠はなかった。

同じコミット `94639c9` に対し、[feature push の run 39018694658](https://github.com/yutotamura55/task-nudge/actions/runs/39018694658) と上記 PR run が別々に起動している。各 run が端末ジョブまで実行するため、PR では検証の計算量も重複していた。

## 変更と確認点

- `verify` と `android-device-tests` の依存を外す。別 runner で両ジョブを同時に開始でき、以前は直列に加算されていた `verify` の約3〜4分が端末ジョブと重なる。両ジョブの検証内容と成果物は維持する。
- feature ブランチの `push` 起動を外す。PR は `pull_request`、main は `push` で引き続き検証する。PR 作成前の検証はローカルの `scripts/check.sh` を使う。

端末ジョブが `verify` の失敗を待たずに進むため、失敗した PR ではそのジョブの実行時間を使う。一方、PR の結果は両ジョブの成功で確認する。runner の割り当て待ちやエミュレーター起動時間には変動があるため、10分以内を常に保証する変更ではない。

PR の CI で、両ジョブが同時期に開始し、両方成功することと run 全体の時間を確認する。

## 成果物の保存

この workflow の debug APK、ホストテスト結果、端末テスト結果は、PR のレビューと端末確認に使う一時的な成果物として7日後に自動失効させる。既存の成果物の有効期限は変わらない。PR の確認や不具合調査が終わったら、必要な成果物を回収し、不要になったものを run の **Artifacts** から個別に削除する。未解決の問題の証拠や、端末確認にまだ必要な APK は用途が終わるまで残す。
