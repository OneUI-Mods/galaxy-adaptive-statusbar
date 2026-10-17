# 配布用ビルド

GitHub Releaseへ添付するAPKはrelease variantのみを使います。debug APKは配布しません。

```sh
python3 tools/package_release.py --source-ref v0.4.4-experimental
```

この手順はassembleRelease、debuggable属性と検証用Activityの混入検査、zipalign、署名と署名検証、ソースZIPとSHA-256の作成を行います。指定したソースと現在のアプリコードが異なる場合は停止します。配布対象は`dist/*-release.apk`、対応するソースZIP、SHA256SUMSです。

署名ファイルはGit管理外の`.signing/`に保存します。

- `release.keystore`: 配布用PKCS12鍵、aliasは`galaxybar-release`
- `password`: 鍵のパスワードを1行で保存
- `lineage`: 既存debug署名から配布署名への更新を許可する証明

鍵とパスワードは配布物・ソースZIP・GitHubへ含めません。今後の更新に同じ署名鍵が必要なので、アクセスを制限した場所へ別途バックアップしてください。対応端末で既存アプリを削除せず更新できることを確認してから公開します。新しい鍵を毎回生成しません。

CIは署名鍵を保持せず、署名前のrelease APKを`galaxy-adaptive-bar-release-unsigned`という開発用artifactへ保存します。このAPKはそのままインストール・配布するものではありません。GitHub Releaseには上記手順で検証した署名済みAPKを添付します。

リリース文は利用者向けに、改善点・対応環境・必要な更新手順・既知の制限を記載します。個人設定や内部実装、テストの途中経過は含めません。

ライセンス一覧は`app/src/main/assets/legal/libraries.json`で管理します。依存関係を更新した場合は、解決済みのruntime componentsと照合して一覧とバージョン・本文・通知を更新してください。配布スクリプトは一覧との一致と参照ファイルの存在も検査します。

現在の配布対象はlibxposed API 102。配布スクリプトはmodern metadata、entryとscope、旧API参照とlegacy entryの混入も検査します。APIはcompileOnlyでAPKへ同梱しません。
