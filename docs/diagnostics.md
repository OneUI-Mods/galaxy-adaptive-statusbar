# 診断と検証状態

## 端末を照合して操作

```sh
python3 tools/adb_target.py --remember HOST:PORT  # 許可済み端末の初回だけ
bash tools/adb.sh shell getprop ro.build.display.id
bash tools/adb.sh shell 'su -c "id; cat /proc/self/attr/current"'
bash tools/adb.sh shell 'su -c "/data/adb/lspd/cli status"'
bash tools/adb.sh shell 'su -c "/data/adb/lspd/cli scope ls dev.ryuya.galaxybar"'
bash tools/adb.sh shell 'run-as dev.ryuya.galaxybar cat shared_prefs/status.xml'
bash tools/adb.sh shell 'su -c "grep GalaxyBar /data/adb/lspd/log/modules* | tail -40"'
```

LANポートが38489から35655へ変わった際、mDNSで再発見してserial/device/fingerprintを照合しました。同一モデルの別端末、identity欠落、pairingサービス、offline/unauthorizedを避ける4件の自動テストがあります。記録ビルドが変わった場合は自動で許可せず、互換性を再調査します。

`evidence/device/`のAPK、framework、window/layer dump、画面画像と識別情報は非公開の端末調査資料であり、Git対象外です。

## 取得方式の根拠

AOSPとSamsung実機の動作は区別します。

- [Android 16 QPR2 ScreenCapture](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-qpr2-release/core/java/android/window/ScreenCapture.java): secure/protected content policyの参照。
- [SurfaceFlinger Android 16 QPR2](https://android.googlesource.com/platform/frameworks/native/+/refs/heads/android16-qpr2-release/services/surfaceflinger/SurfaceFlinger.cpp): captureLayers / mirror処理の参照。
- [Vector](https://github.com/JingMatrix/Vector): legacy Xposed互換機構。

Samsung APK / frameworkの調査で確認:

- 実際の時計は`QSClockIndicatorView`。AOSPの`Clock`を登録できただけでは、時計に適用できたことになりません。
- 既存レイヤー取得APIは`android.window.ScreenCaptureInternal`。公開ラッパーの`ScreenCapture`とは別です。
- LayerCaptureArgs.BuilderにsourceCrop/frameScale/excludeLayers/childrenOnly/secure・protected policyが存在します。
- StatusBarは独立したViewRoot Surface、縦画面1440×140pxです。
- Wi-FiとCompose stacked-mobileは`ModernStatusBarView`のnative bindingへ配色を渡せます。
- `SamsungBatteryMeterDrawable`は内部Bitmap/Canvasへ本体・文字・充電図形を描き、setColorFilterは空実装です。Canvas呼出しが事前コンパイルで最適化されていたため、描画callerのdeoptimizeを追加しました。自身で生成した画面外Drawableに識別色を描くself-testで、本体と文字のフックが実際に反映されることを確認します。

## 取得の切り分け

`bash tools/probe.sh`はroot app_processでcaptureDisplayを実行します。`bash tools/probe.sh layers`はmirror captureです。独立プローブは比較用にステータスバー自身を含みます。

|方式|実機結果|
|---|---|
|captureDisplay|crop指定にもかかわらず1440×3120。初回取得＋CPU読出し約203ms|
|mirrorDisplay + captureLayers / childrenOnly=false|720×70。rootプローブ初回約80ms|
|UID10049に変更したrootプローブ|720×70、約60msの一例。ただしSELinuxはksuのまま|
|実際のSystemUI worker|720×70の継続取得に成功|

SystemUIのSELinux contextは`u:r:platform_app:s0:c512,c768`、rootプローブは`u:r:ksu:s0`です。SystemUIのUIDで成功しただけでは、SystemUIドメインでの成功を証明できません。

初期のSystemUI呼出しはnative error -2で停止しました。mirror作成からSurfaceFlinger snapshotへ反映される準備待ちをworkerで50ms追加し、取得不可のたびmirrorを捨てる処理を修正した後、実際のSystemUIドメインから継続取得に成功しました。

## 実画面の確認

2026-10-16の実機テスト:

- 白・黒・左右分割・40px縞・動く縞で、表示スクリーンショットと除外後の背景PNGを比較。
- 白PNGは全画素255、黒PNGは全画素0。分割と静止縞は2色のみで、時計・アイコンが残らないことを確認。
- 時計の各文字で異なる輝度・色の判定ログと実画面を確認。
- 通知・状態アイコン、Wi-Fi、ComposeデュアルSIM表示が背景で白黒に切り替わることを確認。デュアルSIMの複合View内部は一括配色。
- OFFによる標準表示の復元を確認。
- 電池は初期版で判定と実表示が不一致。deoptimize修正後はSystemUI内のnative合成描画self-testが成功。追加の解除後、白背景の黒い電池本体＋白文字、黒背景の白い本体＋黒文字を実画面で確認。充電変化・残量境界は未実行。
- 最新版はロック中Frame 0。消灯後、約31秒の2回の状態取得でもFrame 0を維持。workerは同期間にCPU tickが1だけ増加。SystemUI全体・GPU・バッテリー消費の値ではありません。
- 5言語×3画面起動、Material設定画面とToolbarのInsets検査の実機instrumentation 2件成功。最初に見つかったドロップダウンのLayoutParams型例外は修正済み。
- 輝度・電池合成6件、再発見4件の単体テスト、Lint、APKビルドに成功。

端末がロックされ、ユーザーから追加の解除をしないとの指示があったため、いったん消灯状態の停止確認と画面外の合成描画、ビルド・資料整備へ切り替えました。その後ユーザーが追加で解除し、電池・SurfaceView・自作動画・保護画面・Chrome・既存ライブ壁紙を優先して実機確認しました。最新版へ更新するSystemUI再読込みで再びロックへ戻ったため、横画面のgateは有効なままです。

## デバッグ情報

デバッグON時だけSystemUI cacheの`galaxybar-background.png`を約3秒ごとに上書きし、各要素の輝度と色をログへ出します。画像は個人情報を含み得るため、テスト背景で使い、終了後はデバッグOFFにしてください。

```sh
bash tools/adb.sh exec-out su -c 'cat /data/user_de/0/com.android.systemui/cache/galaxybar-background.png' > background.png
bash tools/adb.sh shell 'su -c "rm -f /data/user_de/0/com.android.systemui/cache/galaxybar-background.png"'
```

設定画面のcapture+analysisはworker内の時間です。session rateはOFF/ロック時間も含む平均なので、動作中レートやend-to-end遅延の測定には別の時間窓を使います。Last errorは直近の履歴であり、現在のstateがCapture OKなら復帰後も残る場合があります。

## 残りの実機確認

必須環境のすべてを検証済みとは扱いません。

- 電池の背景なし設定、低残量、充電／保護図形、残量桁数の変化。
- 別のライブ壁紙、静止壁紙、実際の動画アプリ、ゲーム、マルチウィンドウ。SurfaceViewとMediaPlayerの自作動画、Chromeのローカルページのスクロールは確認済み。既存MultiplePictureWallpaperのホーム左右移動では背景PNGが変わり、逆方向へ戻ると元と同一のhashになった。壁紙設定は変更していない。
- 通知増減・Wi-Fi／通信の状態変化・overflow dotの復元。
- 横画面の座標とmirrorの対応。現状は回転0以外を明示的に停止。
- DRM動画。FLAG_SECUREのfixtureではSecure layersのSecurityException、標準色復元、5回で停止を確認。保護画面の画像は保存していない。
- 消灯直前から解除後までの連続ライフサイクル、再起動、一時root再取得。
- 長時間動作、CPU/GPU、メモリ、フレーム時間、背景生成から色反映までの遅延。
- One UI 9 / Android 17、別ファームウェア、他の配色モジュールとの競合。

debug APKのみ、非公開の`CaptureFixtureActivity`にSurfaceViewで動く白黒縞と、同梱の自作動画があります。rootからの起動例（解除済みで行う）:

```sh
bash tools/adb.sh shell 'su -c "am start --user 0 -n dev.ryuya.galaxybar/.CaptureFixtureActivity"'
bash tools/adb.sh shell 'su -c "am start --user 0 -n dev.ryuya.galaxybar/.CaptureFixtureActivity --ez video true"'
# 保護画面ではスクリーンショットを取らず、取得拒否・標準色復帰の状態を確認する
bash tools/adb.sh shell 'su -c "am start --user 0 -n dev.ryuya.galaxybar/.CaptureFixtureActivity --ez secure true"'
```

## 負荷の測定例

同じ背景・輝度・更新頻度でOFF/ONを比較します。ADBの観測負荷も考慮します。

```sh
bash tools/adb.sh shell dumpsys cpuinfo com.android.systemui
bash tools/adb.sh shell dumpsys meminfo com.android.systemui
bash tools/adb.sh shell dumpsys gfxinfo com.android.systemui framestats
bash tools/adb.sh shell 'top -b -n 1 -p $(pidof com.android.systemui)'
```

cropが小さくても、内部合成負荷まで小さいとは断定できません。実機ログの約5〜23msは取得＋解析の一例で、継続負荷やバッテリー消費を保証する値ではありません。

## 短時間の負荷比較

1440×3120、debug画像保存OFF、30fpsのSurfaceView縞背景、各約13秒の窓で`/proc`のCPU tick差を取得しました（100 tick/s、1コア=100%）。同じSystemUI PIDでOFF/10Hzを交互に2回、最後に30Hzを1回測定しています。

|設定|実測取得レート|SystemUI CPU（1コア基準）|capture worker CPU（同）|
|---|---:|---:|---:|
|OFF、1回目|0|10.6%|0.15%|
|10Hz、1回目|9.0Hz|20.7%|3.80%|
|OFF、2回目|0|10.2%|0.00%|
|10Hz、2回目|8.8Hz|20.7%|3.86%|
|30Hz|28.4Hz|34.7%|10.40%|

10HzでSystemUI CPUが約10ポイント増えています。SystemUI全体には他モジュールやアニメーションも含まれます。SurfaceFlinger・GPUの負荷や端末全体の電力はこの表に含みません。短いテストのため長時間安定性やバッテリー消費を保証せず、30Hzは負荷が増える実験設定として扱います。

## 0.4.1の短い色フェード

白黒の判定自体は従来と同じで、反映時に80msのease-out補間を追加しました。Choreographerの一つのcallbackで時計の各文字・通知・通信・電池本体と残量文字を更新し、変化がなくなればcallbackを停止します。追加の背景キャプチャは行いません。

同じ判定の再受信では開始時刻を更新しません。反対色へ再変更された場合は現在の補間色から開始し、表示色の飛びを防ぎます。通信表示のinverse色も中間色の補色へ対応しました。初回表示、AndroidのアニメーションOFF、無効化・消灯・ロック・取得失敗での標準復帰は即時です。システムのduration scaleで80msより長くはしません。

単体テスト4件で、80msでの完了、30Hz相当の連続受信でも延長しないこと、途中反転時の連続性、初回表示と即時確定を確認しました。フェードは色反映を追加するため、0.4.0のCPU測定値にこの描画負荷は含みません。

実機の白→黒→白テストを4回切り替えて画面収録し、時計の画素値が中間色を経て最終色へ到達することを確認しました。収録では約5フレーム（60Hz相当）にわたる補間が見られ、Capture OKとエラーなしを維持しています。背景変化から判定開始までのキャプチャ待ちは、80msのフェード時間とは別です。検証後も画像保存設定はOFFです。

## 0.4.2のフェード時間設定

設定の「フェード時間」で0〜500msを10ms刻みに変更できます。`fade_ms`をSharedPreferencesへ保存し、Provider経由でSystemUIへ反映します。初期値は80ms、0msは即時切替です。異常な値は範囲と刻みに補正します。設定取得は従来どおり約1秒以内、設定変更ごとのSystemUI再読込みは不要です。長くすると色の追従はゆっくりになります。AndroidのアニメーションOFF設定は引き続き優先します。

時間変更がアニメーション途中で反映された場合は、その時点の表示色から新しい時間でつなぎます。同じ設定を毎回受信しても開始時刻を更新しません。フェードの単体テストは8件へ増やし、0ms・500ms・途中変更・補正を追加しました。

実機instrumentationは3件成功（5言語×3画面、Insets、フェード設定のキー操作90ms→0ms・保存・再作成・Provider連携）。さらに実際のSystemUIの状態通知で300msと0msへの変更を確認し、同じSystemUIプロセスのまま反映することを検証しました。最後は元の80msに戻しています。

## 0.4.3のSystemUIクラッシュ保護

`Application.attach`後、配色フックを登録する前に監視状態を読み込みます。SystemUI自身の`ActivityManager.getHistoricalProcessExitReasons`と、フックを登録したプロセスのPID・開始時刻を照合します。[AndroidのAPI仕様](https://developer.android.com/reference/android/app/ActivityManager#getHistoricalProcessExitReasons(java.lang.String,int,int))では終了履歴は有限のリングバッファです。[ApplicationExitInfo](https://developer.android.com/reference/android/app/ApplicationExitInfo)の終了理由・signalを使い、Java/nativeクラッシュ、ANR、初期化失敗、過剰リソース、SIGABRT/SIGSEGVを判定します。

2分以内に3回で停止をラッチし、次回起動には配色フックを登録しません。0.6.1までの実装では、終了理由が不明なまま30秒以内に3回再起動した場合も停止していました。0.6.2以降は、終了理由が不明・未取得というだけでは停止しません。手動のSIGTERM、SIGKILL、低メモリ・更新等の通常終了は数えません。監視したプロセスに対応しない履歴や、前回の履歴の重複も数えません。

状態ファイルはSystemUIのdevice-protectedな`files/galaxybar-crash-guard.properties`です。AtomicFileで保存してからフックを開始します。停止ラッチは再起動やAPK更新で消さず、設定アプリが生成する新しい解除nonceだけで解除します。アプリの設定値は自動でOFFにし、利用者が準備できてから再びONにする動作です。フック初期化の失敗時には登録済みの本モジュールのフックだけを解除します。

プロセス終了で失われるメモリ上のカウンターに依存せず、起動時だけ確認します。常時の監視ポーリング、画面キャプチャ、uncaught exception handlerの置換はしません。起動時にcompanionのcredential-encryptedデータが利用できない場合は、解除要求の読出しを保留し、解除を勝手に推測しません。状態表示はユーザーストレージが利用可能になってから通知します。

実行済み:

- 単体テスト11件で3回の判定・永続化・手動再読込みの除外・履歴重複・長時間後の単発クラッシュ・再起動をまたぐ停止・明示reset・PID再利用・不明な再起動・時刻逆行・壊れた状態の拒否を検証。
- 実機instrumentation4件成功。5言語、Insets、フェード設定に加え、保護中のONスイッチ無効化・解除ボタン・一般アプリUIDからguard_statusの書込みを拒否することを確認。
- 実際のSystemUI内で監視状態の保存と通常のフック登録を確認。
- 状態ファイルへ検証用の3回分の異常終了時刻を入れて再読み込みし、停止ラッチ、ON設定の自動OFF、新しいPIDで時計フック・Engineが登録されないことを確認。SystemUIは正常起動。
- 新しい解除nonceと再読み込みで監視を再開し、正常に時計フック・Engineを登録することを確認。検証前のON設定を復元。

実機にJava/nativeクラッシュやANRを故意に起こす試験はしていません。OSが実際に採取したクラッシュ情報からの連続復旧は未実行で、終了理由の分類は単体テスト、停止経路は疑似記録で検証しています。他モジュールやOSの不具合を修復するものではありません。

診断:

```sh
bash tools/adb.sh shell 'su -M -c "cat /data/user_de/0/com.android.systemui/files/galaxybar-crash-guard.properties"'
bash tools/adb.sh shell 'dumpsys activity exit-info com.android.systemui'
```

Tail ADBの独立したmount名前空間ではprivate dataが見えず、run-asが失敗する場合があります。その場合の自分の診断ファイルの読出しは`su -M`を使います。所有者・権限を変更して対処しないでください。

解除後に追加の手動SIGTERM再読み込みも実行し、異常終了数0のまま保護監視とフック登録が続くことを実機で確認しました。利用者が選択したフェード時間は維持しています。


## 0.4.4のOFF時のフック解除

従来はOFFでもImageView・ModernStatusBarView・電池描画のフックが登録されたままで、設定の読出しにも最大約1秒の遅れがありました。0.4.4はProviderのSharedPreferences変更通知をSystemUIのContentObserverへ送り、メインスレッドで描画世代を進めます。enabledの変更通知では設定を取得する前にフェード予約・補間・対象を捨て、標準色へ復元して色変更フック11件を解除します。処理途中のキャプチャは完了しても旧世代として描画を拒否します。mirrorの解放は取得worker上で行います。

OFF→ONの間隔がProviderへの問い合わせより短い場合も、一度停止してから新しい設定を読みます。変更前に要求した設定の応答は採用しません。再ONは同一SystemUIプロセス内でフックを再登録し、電池合成テストとnative dispatcherからの通信表示の標準色取得をやり直します。OFF中のアプリ・テーマ変更前の色を復元用に使い回しません。

OFFでも再ONを検出するための設定監視と時計Viewのライフサイクルフック2件は残します。配色やフェードは行いません。電池描画のART deoptimizationはSystemUIの再起動まで残ることがありますが、OFF中のCanvas差替えフックは解除します。クラッシュ保護による起動停止は従来どおりこの監視フックより前で判定します。

診断の`Color hooks`、`fade callbacks`、`fade targets`、`applied views`で停止状態を確認できます。OFFは全て0です。

実機ではロック中のMaterial Switch操作と15ms間隔8回のON/OFF、解除後の動く縞背景・500msフェード中のOFFと同じ連続切替を検証しました。OFFの2回連続の診断でフック・callback・フェード対象・適用Viewが全て0、取得フレーム数が増えないことを確認し、再ONで同一SystemUIプロセスのまま11フックと電池合成テスト、実際のフェードcallbackが再開しています。エラーなし、元のON・10Hz・110msへ復元しています。追加instrumentation2件は成功です。

既存の起動UI4件も解除後は成功しました。途中、ロック中のUI試験2件は初期表示・Activity再作成待ちで失敗し、動く背景テストも設定画面の起動で背景を閉じてしまい再開時のフェード確認に失敗しました。後者は背景を維持したまま設定値を変更するテストへ修正し、画面が必要な試験は解除後に再実行しました。

```sh
bash tools/adb.sh install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
# 対応端末で本モジュールを読み込み、画面を解除して実行
bash tools/adb.sh shell am instrument -w -e class dev.ryuya.galaxybar.HookToggleTest dev.ryuya.galaxybar.test/androidx.test.runner.AndroidJUnitRunner
```

## クラッシュ保護の誤作動調査

[調査記録](crash-protection-audit.md)に、取得できた終了履歴・確認できた不具合・再現試験・実機未反映の範囲を記載しています。
