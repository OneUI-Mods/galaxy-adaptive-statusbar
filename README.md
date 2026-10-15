# Galaxy Adaptive Status Bar — experimental

Galaxy One UIの時計を文字ごとに、通知・通信表示をアイコンごとに、実際の背景とのコントラストで黒／白へ切り替えるlegacy Xposed / VectorモジュールとMaterial 3設定アプリです。

**0.4.3は実験版です。** SM-S948Q / Android 16 / One UI 8.5の縦画面で、SystemUI自身を除外した背景取得、時計・通知・Wi-Fi・デュアルSIM表示の配色を実機確認しました。電池本体と残量文字、SurfaceViewアニメーションと自作動画への追従、保護画面での取得拒否も確認しました。現在のライブ壁紙のページ切替とChromeスクロールでも継続取得を確認しました。横画面、長時間負荷、One UI 9は未検証です。

GitHub: https://github.com/ryuya0124/galaxy-adaptive-statusbar （非公開）

## 対応環境

|項目|実機確認結果|
|---|---|
|モデル / device|SM-S948Q / m3q|
|Android / SDK|16 / 36|
|One UIプロパティ|80500 (8.5)|
|Build.DISPLAY|BP4A.251205.006.S948QOPS1AZHL|
|解像度 / density|1440×3120 / 468 override|
|root / SELinux|一時root / Enforcing|
|Vector|2.2 / code 3080 / API 102|
|StatusBar|独立Surface、縦画面1440×140px|

このビルドだけを明示的に許可します。他OS・One UI 8の別ビルド・One UI 9は、内部クラス調査と実機確認が必要です。boot・システムパーティション・SystemUI.apk・SELinux設定は変更しません。

## ビルドと導入

JDK 17以上とAndroid SDK 36を使用します。

```sh
export ANDROID_HOME="$HOME/Library/Android/sdk"  # 環境に合わせる
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
python3 -m unittest discover -s tools -p 'test_*.py'
```

APK: `app/build/outputs/apk/debug/app-debug.apk`。GitHub Actionsも単体テスト、Lint、APKと実機UIテストAPKのビルドを実行します。CIのdebug署名はローカルdebug署名と異なる場合があります。公開配布用の署名鍵は設定していません。

```sh
adb connect HOST:PORT
python3 tools/adb_target.py --remember HOST:PORT
bash tools/install.sh
bash tools/adb.sh shell 'su -c "kill -TERM $(pidof com.android.systemui)"'
```

`install.sh`はAPK導入、既存Vector CLIによる本モジュールの有効化、`com.android.systemui/0`のscope登録を行います。CLIがない環境ではVectorマネージャーで設定してください。初回読込み・APKのフック更新時にはSystemUI再起動が必要で、ロック画面に戻る場合があります。

新規インストール時は処理OFFです。解除後に設定アプリでONにすると反映します。通常の設定変更は約1秒で反映され、SystemUI再起動は不要です。既存OneUiLiveColors・OneUIX等の設定は変更しません。ほかの配色モジュールが同じ対象を制御する場合の競合試験は未完了です。

## 実装

- Samsungの`QSClockIndicatorView`からStatusBarWindowを検出。文字位置は`Layout.getPrimaryHorizontal`、baseline、Paintの文字境界、Unicode文字境界から取得し、独自ForegroundColorSpanで配色します。TextViewの等分はしません。
- 通知・通常の状態アイコンはImageViewごとに処理し、最新の標準フィルターを追跡して停止時に復元します。
- `ModernStatusBarView`のnative binding APIでWi-FiとComposeのstacked-mobile表示を配色します。デュアルSIMは一つの複合View単位であり、内部の各SIM図形の別配色は未実装です。
- `SamsungBatteryMeterDrawable`はsetColorFilterを無視するため、native描画中の対象Paintだけを差し替えます。本体と残量文字を別判定し、文字には電池の塗りつぶしも合成した背景を使います。標準の形状・描画条件を保持します。起動時の合成描画テストに失敗すると電池対応を停止します。
- WMS `mirrorDisplay` + Samsung `ScreenCaptureInternal.captureLayers`で、SystemUI自身のViewRoot Surfaceを除外します。mirror生成直後にworkerで50ms待ち、一時的な取得失敗時もmirrorを保持することで実機の準備待ち問題を修正しました。
- キャプチャ要求はステータスバー領域・1/2スケール。サイズと不透明画素を検証します。解析は線形sRGB、平均／中央値／投票／低コントラスト領域優先、ヒステリシスです。
- 白黒の切替は設定できる色フェード（0〜500ms、10ms刻み、初期80ms）。0msで即時切替になり、設定変更はSystemUI再起動なしで反映します。同じ判定では延長せず、逆方向への変更は現在の表示色からつなぎます。初回表示・標準色への復帰・AndroidのアニメーションOFF時は即時反映します。フェード中だけ画面更新を行い、追加キャプチャは行いません。
- キャプチャと背景解析は単一worker。メインスレッドは座標取得と色反映を担当し、同色の再描画を抑えます。
- 設定は5/10/15/30Hz、初期10Hz。30Hzは実験設定で、10Hzより負荷が増えます。静止背景を20回続けて検出したときと省電力時は最大5Hzに下げます。変化検出後は設定値に戻ります。
- **消灯・ロック中はキャプチャしません。** 消灯通知で即座に色を復元し、workerでmirrorと画素バッファを解放します。画面点灯・ロック解除後に再開します。停止中の設定確認は5秒間隔です。消灯直前に既に開始したnative取得がある場合は完了後に破棄します。
- ステータスバー非表示・外部ディスプレイ・未検証の回転では標準表示。失敗時も標準配色へ戻し、5回連続失敗で停止します。OFF→ONで再試行できます。
- FLAG_SECURE/DRMはthrowポリシーで取得を拒否します。保護制限を回避しません。FLAG_SECUREのテストActivityでは取得拒否と停止を確認しました。DRM動画自体は未検証です。

## 検証結果

|確認項目|結果|
|---|---|
|SystemUIドメインで背景取得|720×70、継続取得成功|
|自己描画の除外|白背景PNGは全画素白、黒背景PNGは全画素黒。時計・アイコンなし|
|白／黒／左右分割／細い縞／動く縞|実画面と背景PNGを比較。時計の各文字・通常アイコンの異なる配色を確認|
|Wi-Fi / ComposeデュアルSIM|実画面で白黒切替を確認|
|電池|SystemUI内のnative合成描画と、白／黒背景の実画面で本体・残量文字の切替を確認|
|消灯・ロック|最新モジュールで約31秒間、取得フレーム数0を維持|
|SurfaceView / 動画|動く縞とMediaPlayerの自作動画で継続取得・配色変化を確認|
|保護画面|FLAG_SECURE fixtureでSecurityException、標準表示復帰・連続5回で停止|
|安全な停止|設定OFFによる標準表示への復帰を実機確認|
|UI|5言語×3画面の起動、Material設定・ToolbarのInsetsとフェード設定保存の実機テスト4件成功|
|自動テスト|輝度・合成判定6件、フェード8件、クラッシュ保護11件、ADB再発見4件、Lint、debug/releaseビルド|

動く縞で各約13秒の測定では10Hz設定で8.8〜9.0Hz、30Hz設定で28.4Hz。10HzでSystemUI CPUが1コア基準で約10ポイント増加しました。取得＋解析は約5〜23msの例がありますが、負荷・遅延の保証値ではありません。画面生成から反映までの遅延、GPU負荷、バッテリー消費、連続稼働時のメモリ増加は未評価です。SurfaceViewと同梱の自作動画、Chromeのローカルページを検証しました。ホームの既存MultiplePictureWallpaperで左右のページ移動に伴う背景変化と復帰も確認しました。すべてのライブ壁紙・動画アプリ・ゲームを検証したわけではありません。

デバッグAPKには、画面外の描画とSurfaceView/動画/保護画面を検証する非公開Activityを含みます。releaseにはこのfixtureを含みません。詳しくは[診断・残りの検証](docs/diagnostics.md)を参照してください。

## UI・言語・ライセンス

- 操作UIはMaterial 3のToolbar、Card、Switch、露出ドロップダウン、Slider、Button、TextView、Dialogのみ。システムバーとカットアウトのInsetsを反映し、操作部の重なりを修正しました。テスト背景だけはバーの背後へ描きます。
- 日本語・英語・スペイン語・韓国語・簡体字中国語。アプリ内とAndroidのアプリ言語設定に対応します。
- システム連動／ライト／ダーク、Dynamic Color、独自Adaptive Iconとテーマ別モノクロアイコン。
- 「アプリについて・ライセンス」にプロジェクトMITと実行時依存57件のApache 2.0通知を同梱。
- ソースは[MIT License](LICENSE)。Xposed API 82はcompileOnlyでAPKに含めません。通知は`app/libs`とアプリ内に保存しています。

依存の変更後は`./gradlew :app:writeRuntimeDependencyList && python3 tools/update_notices.py`で通知を更新します。未知のライセンスは生成を停止します。

## SystemUIのクラッシュ保護

本モジュールのフックを登録する前に、SystemUI自身の[Android終了履歴](https://developer.android.com/reference/android/app/ActivityManager#getHistoricalProcessExitReasons(java.lang.String,int,int))と、このモジュールが監視したプロセスの記録を照合します。

- 2分以内に3回のJava/nativeクラッシュ・ANR・初期化失敗・過剰リソースによる終了を検出すると、次回起動で配色フックを登録しません。SIGABRT/SIGSEGVも対象です。
- 手動再読み込みのSIGTERM、SIGKILL、メモリ不足、パッケージ更新による終了は回数に含めません。終了理由が取得できないまま30秒以内に3回再起動した場合も、保守的に停止します。
- 停止状態はSystemUIのdevice-protectedデータへ原子的に保存し、通常の再起動やAPK更新では解除しません。フック初期化・監視状態の読出しや保存に失敗した場合も配色を開始しません。
- 保護中は自動配色をOFFにし、設定画面へ停止状態を表示します。「保護を解除してSystemUIを再読み込み」から解除できます。rootが必要で、ロック画面へ戻る場合があります。解除後の自動配色は利用者がONにします。
- 初期化途中のエラーでは、本モジュールが登録したフックを解除します。他モジュール・Vectorの設定は変更しません。起動時だけ確認し、常時の監視ポーリングや追加キャプチャは行いません。

終了理由は有限の履歴で、SystemUIの停止原因を本モジュールへ特定するものではありません。他のモジュールやOS側の原因を修復する仕組みではありません。解除しても繰り返す場合は、原因を調べるまで本モジュールを無効にしてください。

## 停止・復旧

```sh
bash tools/disable.sh             # 処理OFF。設定アプリを開く
bash tools/disable.sh --unhook    # 本モジュールのみVectorで無効化しSystemUI再起動
```

アプリが起動できない場合:

```sh
bash tools/adb.sh shell 'su -c "/data/adb/lspd/cli modules disable dev.ryuya.galaxybar"'
bash tools/adb.sh shell 'su -c "kill -TERM $(pidof com.android.systemui)"'
# または本モジュールだけ削除
bash tools/adb.sh uninstall dev.ryuya.galaxybar
```

一時root再取得後は`su -c id`、Vector status、scope、module statusを確認します。root取得やVector再導入は自動化しません。

## LAN切断時の再発見

`tools/adb.sh`は、既存ADB経路とLANのmDNS接続サービスから対象を探し、serial/device/fingerprintの一致を確認してから操作します。実機のLANポート変更後にも再接続できました。識別情報はGit対象外の`evidence/device/adb-target.json`へ保存します。ネットワーク設定・無線デバッグ・認証キーは変更しません。
