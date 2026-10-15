# 診断と検証状態

## 取得コマンド

```sh
adb -s 192.168.3.20:38489 shell 'id; getenforce; getprop ro.product.model; getprop ro.product.device; getprop ro.build.version.release; getprop ro.build.version.sdk; getprop ro.build.version.oneui; getprop ro.build.display.id'
adb -s 192.168.3.20:38489 shell 'su -c "id; cat /proc/self/attr/current"'
adb -s 192.168.3.20:38489 shell 'su -c "/data/adb/lspd/cli status"'
adb -s 192.168.3.20:38489 shell 'su -c "/data/adb/lspd/cli modules ls"'
adb -s 192.168.3.20:38489 shell 'su -c "/data/adb/lspd/cli scope ls dev.ryuya.galaxybar"'
adb -s 192.168.3.20:38489 shell dumpsys SurfaceFlinger --list
adb -s 192.168.3.20:38489 shell dumpsys window windows
adb -s 192.168.3.20:38489 shell 'su -c "grep GalaxyBar /data/adb/lspd/log/modules* | tail -60"'
adb -s 192.168.3.20:38489 shell 'run-as dev.ryuya.galaxybar cat shared_prefs/status.xml'
```

デバッグON時だけ、SystemUIのcacheに `galaxybar-background.png` を上書き保存します。取得にはrootが必要です。個人情報を含み得るためGitや公開資料に入れないでください。テスト背景でのみ有効化を推奨します。OFFにすると追記を停止しますが、最後のPNGの削除は別途必要です。

```sh
adb shell 'su -c "find /data/user_de/0/com.android.systemui /data/user/0/com.android.systemui -name galaxybar-background.png"'
```

`evidence/device/` のAPK、framework、window/layer dump、画面画像は端末固有・非公開の調査資料です。Git対象外です。

## AOSPの仕様（Samsungでの動作とは別）

- [Android 16 QPR2 ScreenCapture](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-qpr2-release/core/java/android/window/ScreenCapture.java): secure/protected content policyの定義。
- [SurfaceFlinger Android 16 QPR2](https://android.googlesource.com/platform/frameworks/native/+/refs/heads/android16-qpr2-release/services/surfaceflinger/SurfaceFlinger.cpp): captureLayers / mirrorとcapture処理の参照。
- [AOSP ScreenCapture API](https://android.googlesource.com/platform/frameworks/base.git/+/main/core/java/android/window/ScreenCapture.java): 旧系統のsourceCrop、excludeLayers等の参照。ブランチ差分があり、現端末のクラス名として使用しない。
- [Vector](https://github.com/JingMatrix/Vector): legacy Xposed互換機構の参照。

## Samsung APK / DEXで確認した事項

- `com.android.systemui.statusbar.policy.Clock` はTextView。`updateClock()`が`getSmallTime()`の文字列を更新し、`onDarkChanged()`が`setTextColor()`を呼ぶ。
- 実際のSamsung時計は `com.android.systemui.statusbar.policy.QSClockIndicatorView`。QSClockを継承し、notifyTimeChangedでテキストを更新、onDarkChangedで色を変更する。0.2.0ではこちらのonAttachedToWindow/onLayoutを対象に修正した。旧Clock hook登録ログだけでは実際の時計へのフック成功を意味しない。
- `android.window.ScreenCapture`は新しい公開系統のラッパー。実際の既存captureLayers APIはこの端末では `android.window.ScreenCaptureInternal`。
- 同クラスのLayerCaptureArgs.BuilderにsourceCrop/frameScale/excludeLayers/childrenOnly、secure/protected policyが存在。
- SamsungBatteryMeterDrawableは内部Bitmap/Canvasに残量文字と図形を描く。`setColorFilter()`は空実装。
- SystemUIにREAD_FRAME_BUFFER権限が付与されている。ただしWMS mirror/SurfaceFlingerアクセスが実際に成功するかは、SystemUI SELinuxドメインで別途実行確認が必要。

## 実行済み

- ADB・root・SELinux・Vector情報の取得。
- StatusBarの独立Surfaceと140px Windowを取得。
- rootプローブからdisplay captureとmirror layer captureを実行、画像を視認。
- APKのビルド、5件の輝度判定単体テスト、Lint（警告あり・エラー0）。
- 実機へインストール、Vector scope登録・有効化。
- SystemUI再起動後の `GalaxyBar: Clock hook installed` ログ。

## 未実行

ロック解除後のengine起動、SystemUIドメインでのcapture、除外画像の比較、文字/アイコンの色反映、各種動的背景、保護画面のfallback、復元、性能と長時間テスト。これらはログ上のhook登録だけでは成功扱いにしません。

## 負荷測定の例

同じ背景、画面輝度、更新頻度でOFF/ONを比較します。root/ADBの観測負荷自体も考慮してください。

```sh
adb shell dumpsys cpuinfo com.android.systemui
adb shell dumpsys meminfo com.android.systemui
adb shell dumpsys gfxinfo com.android.systemui framestats
adb shell 'top -b -n 1 -p $(pidof com.android.systemui)'
```

設定画面のcapture+analysis時間はworker処理時間で、背景フレーム生成から表示反映までのend-to-end遅延ではありません。session rateはOFF時間も含む平均です。動画への追従遅延はフレーム時刻との突合が別途必要です。
