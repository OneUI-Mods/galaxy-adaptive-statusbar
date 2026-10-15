# Galaxy Adaptive Status Bar — experimental

Galaxy One UIのステータスバーを、時計の文字・通知・通信アイコンごとに背景とのコントラストで白黒へ切り替えるためのAndroid Studio / legacy Xposedプロジェクトです。

**完成品ではありません。** 現在は独立キャプチャプローブの実機確認、モジュール実装、ビルド・単体テストまで。SystemUIからの背景除外・動的配色の実機検証は端末ロック解除待ちです。Samsung一体型バッテリー、横画面、DeX、Android 17 / One UI 9は未対応です。インストール時は処理OFFです。

## 実機で確認した環境

2026-10-16、ADB `192.168.3.20:38489` に接続して取得。

|項目|結果|
|---|---|
|モデル / device|SM-S948Q / m3q|
|Android / SDK|16 / 36|
|One UIプロパティ|80500 (8.5)|
|Build.DISPLAY|BP4A.251205.006.S948QOPS1AZHL|
|解像度 / density|1440×3120 / 468 (override)|
|root / SELinux|uid 0, u:r:ksu:s0 / Enforcing|
|Vector|2.2 / code 3080 / API 102|
|SystemUI|/system_ext/priv-app/SystemUI/SystemUI.apk|
|StatusBar window|独立Surface、縦画面の高さ140px|

対象ビルド以外ではフックを開始しない明示的なallowlistを使用します。別ビルドは対応アダプターと実機検証が必要です。

## ビルド

JDK 17以上、Android SDK 36が必要です。Android Studioでこのディレクトリを開くか、以下を実行します。

```sh
export ANDROID_HOME="$HOME/Library/Android/sdk"  # 環境に合わせる
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

Xposed API 82のJARはcompileOnlyとして同梱。APKにはXposed APIを含めません。取得元は `https://api.xposed.info/de/robv/android/xposed/api/82/api-82.jar`。Material Componentsとビルド依存はGoogle/Maven Centralから取得します。

GitHub管理先: https://github.com/ryuya0124/galaxy-adaptive-statusbar （非公開）。GitHub Actionsの定義は `.github/workflows/build.yml`。単体テスト・Lint・APKと実機UIテストAPKのビルドを実行します。CI生成のdebug APKは端末へ導入したローカルdebug APKと署名が異なる場合があります。

## 導入・有効化

```sh
adb connect 192.168.3.20:38489
bash tools/install.sh
```

このスクリプトはAPKを導入し、既存Vector CLIで本モジュールのみを有効化、scopeを `com.android.systemui/0` に追加します。Vector CLIがない環境では、VectorマネージャーでGalaxy Adaptive Barを有効化し、SystemUIのみを選択してください。

フックの初回読込みにはSystemUIの再起動が一度必要です。表示が一時的に消え、ロック画面へ戻る場合があります。

```sh
adb shell 'su -c "kill -TERM $(pidof com.android.systemui)"'
```

ロック解除後、Galaxy Adaptive Barを開いてモジュールをONにします。設定は約1秒で反映されます。設定変更ごとのSystemUI再起動は不要です。

既存の `net.ryuya_dev.oneuilivecolors`、OneUIX等は変更しません。既存モジュールとの同時動作は未検証です。

## 実装済み（画面上の効果は未検証）

- SamsungのQSClockIndicatorView attach/layoutからステータスバーWindowを検出（AOSPのClockとは別クラス）。
- `Layout.getPrimaryHorizontal`、行baseline、Paintの文字境界、Unicode文字境界を使う文字別領域。TextViewの等分はしません。
- 独自ForegroundColorSpanで文字別配色、既存spanを保持。
- ステータスバー配下のImageViewごとの色フィルター。通知/通信の祖先View IDで分類。
- WMS `mirrorDisplay` と `ScreenCaptureInternal.captureLayers`、自身のViewRoot Surfaceを `setExcludeLayers` に指定。
- 1/2スケールのステータスバー領域のみ要求。キャプチャ結果サイズ、透明/未定義画素を検査。
- 線形sRGB、黒白のコントラスト比、平均/中央値/投票/低コントラスト領域優先、ヒステリシス。
- 設定5/10/15/30Hz（初期10Hz）、同時キャプチャは1つ、画面消灯・非表示・省電力時の制御。
- キャプチャと解析は専用worker。メインスレッドにはView座標取得と色反映のみ。
- キャプチャ失敗時は標準配色へ戻し、5回連続失敗で停止。OFF→ONで再試行。
- 変更された標準アイコンTintを追跡し、OFF時に復元。停止時はmirrorを解放。
- 設定・テスト画面の操作部はMaterial 3のみ。Toolbar、Card、Switch、露出ドロップダウン、Slider、Button、TextView、Dialogを使用。標準Spinner/SeekBarは使用しない。
- 設定UIはsystemBarsとdisplayCutoutのInsetsを反映し、ステータスバー・ナビゲーションバーへの重なりを回避。テスト背景のピクセルだけはステータスバーの背後へ描画する。
- 処理状況、任意デバッグログ/背景PNG、白黒/分割/縞/動く縞のテスト画面。

## 実機キャプチャの検証結果

`bash tools/probe.sh` はrootのapp_processから既存APIを呼びます。`bash tools/probe.sh layers` はWMS mirrorのレイヤー取得を検証します。プローブは比較用に**ステータスバー自身を含みます**。

|方式|実機結果|
|---|---|
|captureDisplay|sourceCropとframeScaleの指定に対して1440×3120を返却。初回取得+CPU読出し約203ms|
|mirrorDisplay + captureLayers / childrenOnly=false|720×70の切り出し成功。初回取得+CPU読出し約80ms|
|childrenOnly=trueの初回試行|null。mirror生成直後の準備タイミングも未分離|
|SystemUI自身を除外した画像|未確認。モジュールは除外を指定するが、API成功だけで除外成功とは判断しない|

測定は各1回で、継続レートや遅延保証ではありません。小さい画像が返ることだけでは内部合成コストも小さいとは断定できません。10～30Hz、CPU/GPU負荷、バッテリー、長時間安定性は未測定です。

## 未対応・制約

- SamsungBatteryMeterDrawableはsetColorFilterを無視し、残量文字もDrawable内部で描画します。誤った全体Tintは適用せず、標準表示を維持。
- 独立したTextViewではないCompose/カスタム描画アイコンは、このViewアダプターでは未対応。
- 現時点では内蔵ディスプレイ・回転0のみ。横画面/DeXは標準表示。
- ロック画面の大時計やQSは対象外。rootを使ってロック解除を迂回しません。
- アプリ側がステータスバーの背後へ描画しない場合や、SystemUI自身のscrim/不透明背景を除外した場合、取得背景と表示背景の一致は未検証。
- FLAG_SECURE/DRMに対してはthrowポリシーを設定。保護画面の取得や制限回避は行わず、失敗時は標準表示。
- クロップ/レイヤー除外のAPIを実機で確認するまで、アプリ/ライブ壁紙/動画への追従成功を主張しない。
- SurfaceFlingerへの直接インジェクション、パーティション/boot変更、SELinux変更は行わない。ネイティブコンポーネントは現段階で不要。

## 緊急停止・復旧

```sh
bash tools/disable.sh             # 処理をOFF。root不要。設定アプリを開く
bash tools/disable.sh --unhook    # Vectorから本モジュールを無効化し、SystemUIを再起動
```

アプリが起動できない場合:

```sh
adb shell 'su -c "/data/adb/lspd/cli modules disable dev.ryuya.galaxybar"'
adb shell 'su -c "kill -TERM $(pidof com.android.systemui)"'
# またはモジュールAPKを削除（他モジュールは対象外）
adb uninstall dev.ryuya.galaxybar
```

一時root再取得後は `adb shell su -c id`、Vector status、scope、module statusを確認します。既存Vector自体が復旧していない場合は、その既存環境の復旧手順が別途必要です。本プロジェクトはroot取得やVector再導入を自動化しません。

## 次の実機確認

1. ロック解除し設定アプリでON。デバッグを必要時だけON。
2. `GalaxyBar: Capture OK` とcropサイズを確認。
3. デバッグPNGに時計・通知・通信アイコンが残っていないことを確認。黒/白の文字色振動がないことも確認。
4. テスト背景の白/黒/分割/縞/動く縞で実際の色を比較。固定色モードも確認。
5. Chromeスクロール、ライブ壁紙、動画、アイコン追加削除、画面消灯/非表示、保護画面、OFF復元を検証。
6. 再起動・回転・root再取得・長時間負荷測定を行い、未対応箇所を専用アダプターで追加。

実行コマンド・詳細の区別は [docs/diagnostics.md](docs/diagnostics.md) を参照。

## 表示・言語・ライセンス

- 独自のAdaptive Launcher IconとAndroidのテーマ別モノクロアイコンを設定。
- 日本語、英語、スペイン語、韓国語、簡体字中国語。言語は設定画面またはAndroidのアプリ言語設定から変更可能。
- システム連動／ライト／ダーク、Material 3 Dynamic Color。
- アプリ内「アプリについて・ライセンス」にプロジェクトMITと依存ライブラリ57件のApache 2.0通知を同梱。
- ソース全体は [MIT License](LICENSE)。Xposed APIはcompileOnlyで、APKには同梱しない。対応する通知はapp/libsとアプリ内に保存。

依存の更新後は `./gradlew :app:writeRuntimeDependencyList && python3 tools/update_notices.py` で通知を更新してください。未知のライセンスは自動承認せず生成を停止します。

## 切断時の端末再発見

```sh
python3 tools/adb_target.py --remember HOST:PORT  # 最初の一度、許可済み端末を記録
bash tools/adb.sh shell getprop ro.product.model
```

記録はGit対象外の `evidence/device/adb-target.json`。既存ADB経路とLANのmDNS接続サービスを探索し、端末serial/device/fingerprintの一致を確認してからコマンドを実行します。再発見時にポートが変わっていても追従します。ネットワーク設定、無線デバッグON/OFF、認証キーは変更しません。現在記録したビルドと異なる場合は照合を通さず、互換性調査を優先してください。
