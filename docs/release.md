# リリースの作り方

GitHub の Releases に、リリース用の鍵で署名した APK を置く手順です（Issue #61）。
タグ（例：`v0.2.0`）を push すると、`.github/workflows/release.yml` が APK を作って署名し、そのタグの Release に添付します。

Releases の APK は、新しくアプリを入れる人向けです。
作者の普段使いの端末に入っているアプリは手元の開発用の鍵で署名しているので、Releases の APK では上書きできません（入れ直すと記録が消えます）。
その端末は、これまでどおり手元でビルドした debug APK を USB で上書きします。

## 最初に一度だけ行うこと

### 1. リリース用の鍵を作る

リポジトリの外の場所で、鍵（keystore）を作ります。次の例では、ホームの `secure` フォルダに置きます。

```bash
mkdir -p ~/secure
keytool -genkeypair -keystore ~/secure/task-nudge-release.jks -alias tasknudge \
  -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Task Nudge"
```

`keytool` は JDK に含まれます。パスが通っていなければ、JDK の `bin/keytool` を指定します。
パスワードを2回聞かれます。鍵ファイルのパスワードと鍵のパスワードは、同じものになります（PKCS12 形式のため）。

- 鍵ファイルとパスワードは、パスワード管理ソフトなど、端末が壊れても残る場所に控えます。
  **鍵をなくすと、配った APK を更新できなくなります。** 同じアプリを別の鍵で署名した APK では上書きできず、入れ直すと記録が消えます。
- 鍵ファイルとパスワードは、リポジトリ、Issue、PR、チャットに書きません。リポジトリの `.gitignore` は `*.jks` と `*.keystore` を除外しています。

### 2. 証明書の SHA-256 を控える

workflow は、APK がこの鍵で署名されたことを証明書の SHA-256 で確かめます。次のコマンドで値を出します。

```bash
keytool -exportcert -keystore ~/secure/task-nudge-release.jks -alias tasknudge | sha256sum | cut -d' ' -f1
```

64文字の16進数（`0`〜`9` と `a`〜`f`）が1行出ます。この値は秘密ではありません。

### 3. GitHub に登録する

自分のターミナルで、リポジトリのフォルダに移ってから実行します。
パスワードを入力する2つのコマンドは、入力を求められたら貼り付けます。値はコマンドの履歴に残りません。

```bash
base64 -w0 ~/secure/task-nudge-release.jks | gh secret set TASK_NUDGE_KEYSTORE_BASE64 -R yutotamura55/task-nudge
gh secret set TASK_NUDGE_KEYSTORE_PASSWORD -R yutotamura55/task-nudge
gh secret set TASK_NUDGE_KEY_PASSWORD -R yutotamura55/task-nudge
gh secret set TASK_NUDGE_KEY_ALIAS -R yutotamura55/task-nudge --body tasknudge
gh api -X POST repos/yutotamura55/task-nudge/actions/variables -f name=TASK_NUDGE_RELEASE_CERT_SHA256 -f value=<2で控えた値>
```

`<2で控えた値>` は置き換えます。それ以外は、そのまま実行できます。
変数 `TASK_NUDGE_RELEASE_CERT_SHA256` がすでにある場合（鍵を作り直したときなど）は、最後のコマンドが失敗します。そのときは、次の形で値を更新します。

```bash
gh api -X PATCH repos/yutotamura55/task-nudge/actions/variables/TASK_NUDGE_RELEASE_CERT_SHA256 -f name=TASK_NUDGE_RELEASE_CERT_SHA256 -f value=<新しい値>
```
`TASK_NUDGE_KEY_PASSWORD` には、`TASK_NUDGE_KEYSTORE_PASSWORD` と同じパスワードを入れます。

登録したものは、GitHub のリポジトリのページの「Settings」→「Secrets and variables」→「Actions」で確かめられます。secrets は名前だけが表示され、値は表示されません。変数（`TASK_NUDGE_RELEASE_CERT_SHA256`）は値も表示されます（秘密ではない値です）。

### 4. 試しに動かす

Actions の「Release」→「Run workflow」で、main を選んで実行します。
手動実行では Release を作らず、署名した APK を成果物 `task-nudge-release` に置くだけです（3日で消えます）。
最後まで成功すれば、鍵と登録が正しく動いています。失敗した場合は、ログの最後のエラーに、足りない secrets か、証明書が一致しないことが表示されます。

## リリースごとに行うこと

1. PR で `app/build.gradle.kts` の `versionCode` を1増やし、`versionName` を新しい版（例：`0.2.0`）にします。`versionCode` が前の版より大きくないと、端末で上書きできません。
2. PR をマージした後、main の最新のコミットにタグを付けて push します。次の `v0.2.0` は例なので、`v` と新しい `versionName` をつなげたものに置き換えます。

   ```bash
   git switch main
   git pull --ff-only
   git tag v0.2.0
   git push origin v0.2.0
   ```

   workflow は、次のどれかに当たると、署名も公開もせずに止まります。
   - タグの名前が `v` と `versionName` をつなげたものと違う。
   - タグを付けたコミットが main に含まれていない（レビューしていないコードを署名しないため）。
   - `versionCode` が、前のタグの版より大きくない。
3. Actions の「Release」が成功すると、Releases にそのタグの版ができます。公開の途中で失敗したときは、その実行をやり直せば、同じ版の Release に APK を添付し直します。`task-nudge-v0.2.0.apk` と、その SHA-256 を書いた `task-nudge-v0.2.0.apk.sha256` が添付されます。

## 確かめたこと

2026-10-27 に、使い捨ての試験用の鍵で次を確かめました（本物の鍵は使っていません）。

- 鍵の環境変数がないときは、これまでどおり署名なしの release APK になる。
- 鍵を渡すと、その鍵で署名された APK になる（`apksigner` で証明書を確認）。
- API 35 エミュレーターで、`versionCode` 1 の APK を入れてから、同じ鍵で作った `versionCode` 2 の APK で上書きできる。データベースに入れた行は残り、最初のインストール日時も変わらない。
- 鍵の場所だけを渡してパスワードを渡さないと、開発用のビルドはそのまま成功し、release の署名の段階で失敗する。

workflow は、APK を作って署名するジョブと、Release を作るジョブに分けています。前者は鍵を持ちますがリポジトリを読むことしかできず、後者はリポジトリに書き込めますが鍵を持ちません。鍵を受け取るビルドでは、Gradle のキャッシュ用の action を使いません。

## 最初のリリース

2026-10-27 に `v0.1.0` を公開しました（`versionCode` 1、main の `d51349d`）。公開した APK は GitHub にログインせずにダウンロードでき、証明書の SHA-256 が登録した値と一致することを確かめました。あわせて、README のインストールの説明を、Releases から入れる手順に書き直しました。
