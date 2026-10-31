# 開発者向けガイド

利用者向けの説明は[README](../README.md)にあります。公開先は[galaxy-adaptive-statusbar](https://github.com/OneUI-Mods/galaxy-adaptive-statusbar)です。

## ソースとビルド

Android SDKのコンパイル対象は37です。JDK・Gradle・依存関係の版は、リポジトリ内のGradle設定とwrapperを基準にしてください。

```sh
./gradlew :app:assembleRelease
```

配布にはrelease variantを使い、配布鍵による署名とAPKの構造を検証します。debug APKやテスト専用画面を含むAPKを配布しません。署名鍵・パスワード・端末ログ・外部アプリのバイナリをGitへ追加しないでください。

## 設計・検証

未確認のOS・端末・対象アプリの版を対応済みと記載しないでください。実測・実機確認と、コード上で準備した互換処理を区別して記録します。READMEは機能、導入、更新、制約を説明し、内部API・フック・測定値はこのディレクトリに記録します。

## ドキュメント一覧

- [実装と過去の検証の補足](architecture-and-validation.md)
- [署名・配布手順](releasing.md)
- [ソースの書式](source-style.md)
- [Android 16の互換性](compatibility/android16.md)
- [Android 17の互換性](compatibility/android17.md)

- [Kotlin移行の設計と検証範囲](kotlin-migration.md)
