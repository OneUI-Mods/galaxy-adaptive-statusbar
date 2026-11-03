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

- [探索・保存・更新時の互換性](dexkit-compatibility.md)

## 通知のオーバーフロードット

`StatusBarIconView`の通知アイコンはImageViewのフィルターで描画色を変更できますが、オーバーフロードットは`onDraw(Canvas)`内で独立した`mDotPaint`を使います。Android 16・17の保存済みSystemUI APKで、`setDecorColor(int)`が`mDecorColor`を保存し、`updateDecorColor()`がドットのPaintを更新する経路をそれぞれ確認しています。

`NotificationIconTintAdapter`はこの設定メソッドを利用し、ImageViewの配色と同じフェード値を渡します。純正の表示状態・半径・出現アニメーション・Doze補間は変更しません。通知設定のOFF、画面の消灯、対象の消失、エンジン停止時に最新の純正色へ戻します。配色中の純正テーマ更新も記録し、次のキャプチャまで純正色が一瞬見えることを防ぎます。純正の色に含まれる透明度は保持します。

対象クラスとメソッド・フィールドは独立したAndroid 16・17のDexKitプロファイルに追加しています。起動時の型・署名確認が失敗した場合はこの追加アダプターのみ停止します。JVMの回帰試験ではテーマ変更中の色保持、OFF時の最新色の復元、連続フェードで透明度が累積しないことを確認します。実機の描画確認はビルド試験とは別に記録します。
