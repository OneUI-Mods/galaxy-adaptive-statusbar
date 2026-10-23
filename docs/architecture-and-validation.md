# 実装・検証の補足

READMEから分離した開発用の説明です。過去の測定値は対応する当時の環境の記録であり、現在の動作保証値ではありません。対応環境はREADMEと互換性・リリース文を確認してください。

## ビルドと導入

JDK 21とAndroid SDK 37を使用します。

```sh
export ANDROID_HOME="$HOME/Library/Android/sdk"  # 環境に合わせる
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
python3 -m unittest discover -s tools -p 'test_*.py'
```

配布APKは署名済みの**releaseビルド**です。GitHub Releaseから`*-release.apk`をダウンロードしてください。検証用画面を含むdebug APKは配布しません。

配布物は`python3 tools/package_release.py --source-ref <リリースのタグ>`で生成します。署名・検証・ソースZIP・SHA-256の手順は[配布用ビルド](docs/releasing.md)を参照してください。GitHub Actionsは単体テスト、Lint、各ビルドを実行し、署名前のrelease APKを開発用artifactとして保存します。

```sh
adb connect HOST:PORT
python3 tools/adb_target.py --remember HOST:PORT
bash tools/install.sh
bash tools/adb.sh shell 'su -c "kill -TERM $(pidof com.sec.android.app.launcher) $(pidof com.android.systemui)"'
```

`install.sh`は生成済みの署名付きrelease APKを導入し、既存Vector CLIによる本モジュールの有効化、SystemUIとOne UIホームのscope登録を行います。CLIがない環境ではVectorマネージャーで両方を対象に設定してください。初回読込み・APKの更新時には両プロセスの再読み込み、または端末の再起動が必要で、ロック画面に戻る場合があります。既存版はアンインストールせず上書きすると設定を保持できます。

新規インストール時は処理OFFです。解除後に設定アプリでONにすると反映します。設定変更はProviderの変更通知で反映し、SystemUI再起動は不要です。通知を取り逃した場合に備えた定期確認も残しています。既存OneUiLiveColors・OneUIX等の設定は変更しません。ほかの配色モジュールが同じ対象を制御する場合の競合試験は未完了です。

「アプリの開閉に追従」をONにすると、対応するOne UIホームでバーがウィンドウへ追従します。画像に固定せず自動配色を継続します。OFFにすると通常位置のバーへ戻ります。すべての履歴カードへの表示、分割画面、横画面、DeX、3Dカード効果、Home Upの全設定は対応未確認です。iOSと完全に同じ動作を保証する機能ではありません。検証範囲と実装の記録は[アニメーション追従の開発状況](docs/motion-development.md)を参照してください。

## 実装

- Samsungの`QSClockIndicatorView`からStatusBarWindowを検出。文字位置は`Layout.getPrimaryHorizontal`、baseline、Paintの文字境界、Unicode文字境界から取得し、独自ForegroundColorSpanで配色します。TextViewの等分はしません。
- 通知・通常の状態アイコンはImageViewごとに処理し、最新の標準フィルターを追跡して停止時に復元します。
- `ModernStatusBarView`のnative binding APIでWi-FiとComposeのstacked-mobile表示を配色します。デュアルSIMは一つの複合View単位であり、内部の各SIM図形の別配色は未実装です。
- `SamsungBatteryMeterDrawable`はsetColorFilterを無視するため、native描画中の対象Paintだけを差し替えます。本体と残量文字を別判定し、文字には電池の塗りつぶしも合成した背景を使います。標準の形状・描画条件を保持します。起動時の合成描画テストに失敗すると電池対応を停止します。
- WMS `mirrorDisplay` + Samsung `ScreenCaptureInternal.captureLayers`で、SystemUI自身のViewRoot Surfaceを除外します。mirror生成直後にworkerで50ms待ち、一時的な取得失敗時もmirrorを保持することで実機の準備待ち問題を修正しました。
- キャプチャ要求はステータスバー領域・1/2スケール。サイズと不透明画素を検証します。解析は線形sRGB、平均／中央値／投票／低コントラスト領域優先、ヒステリシスです。
- 白黒の切替は設定できる色フェード（0〜500ms、10ms刻み、初期80ms）。0msで即時切替になり、設定変更はSystemUI再起動なしで反映します。同じ判定では延長せず、逆方向への変更は現在の表示色からつなぎます。初回表示・標準色への復帰・AndroidのアニメーションOFF時は即時反映します。フェード中だけ画面更新を行い、追加キャプチャは行いません。
- OFF通知でフェードcallback・補間状態・旧セッションの結果を破棄し、標準色へ即時復元して色変更用の11フックを解除します。再ON時は登録し直し、通信表示の標準色もnative dispatcherから取得し直します。OFF中は再有効化に必要なView検出と設定監視だけを残します。
- キャプチャと背景解析は単一worker。メインスレッドは座標取得と色反映を担当し、同色の再描画を抑えます。
- 設定は5/10/15/30Hz、初期10Hz。30Hzは実験設定で、10Hzより負荷が増えます。静止背景を20回続けて検出したときと省電力時は最大5Hzに下げます。変化検出後は設定値に戻ります。
- **消灯・ロック中はキャプチャしません。** 消灯通知で即座に色を復元し、workerでmirrorと画素バッファを解放します。画面点灯・ロック解除後に再開します。停止中の設定確認は5秒間隔です。消灯直前に既に開始したnative取得がある場合は完了後に破棄します。
- ステータスバー非表示・外部ディスプレイ・未検証の回転では標準表示。通常の取得エラーでも標準配色へ戻し、5回連続失敗で配色と位置追従を停止します。OFF→ONで再試行できます。
- FLAG_SECURE/DRMはthrowポリシーで取得を拒否します。保護制限を回避しません。保護されたフレームの拒否は配色と位置追従を一時停止し、標準表示へ戻します。通常の背景を再び取得できると、ON/OFF操作なしで再開します。FLAG_SECUREの実機試験で拒否中の標準表示と自動復帰を確認。DRM動画自体は未検証です。

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
|保護画面|FLAG_SECUREで画素の利用を拒否、配色と位置追従を一時停止。保護を外すとON/OFFなしで自動復帰|
|安全な停止|フェード中のOFFと15ms間隔8回の切替。色変更フック・callback・対象が0になり取得数が停止、再ONでフェード再開を実機確認|
|UI|5言語×3画面の起動、Material設定・ToolbarのInsetsとフェード設定保存の実機UIテスト4件と停止・再開テスト2件成功|
|自動テスト|輝度・合成判定6件、フェード8件、クラッシュ保護11件、ADB再発見4件、Lint、debug/releaseビルド|

動く縞で各約13秒の測定では10Hz設定で8.8〜9.0Hz、30Hz設定で28.4Hz。10HzでSystemUI CPUが1コア基準で約10ポイント増加しました。取得＋解析は約5〜23msの例がありますが、負荷・遅延の保証値ではありません。画面生成から反映までの遅延、GPU負荷、バッテリー消費、連続稼働時のメモリ増加は未評価です。SurfaceViewと同梱の自作動画、Chromeのローカルページを検証しました。ホームの既存MultiplePictureWallpaperで左右のページ移動に伴う背景変化と復帰も確認しました。すべてのライブ壁紙・動画アプリ・ゲームを検証したわけではありません。

デバッグAPKには、画面外の描画とSurfaceView/動画/保護画面を検証する非公開Activityを含みます。releaseにはこのfixtureを含みません。詳しくは[診断・残りの検証](docs/diagnostics.md)を参照してください。

## LAN切断時の再発見

`tools/adb.sh`は、既存ADB経路とLANのmDNS接続サービスから対象を探し、serial/device/fingerprintの一致を確認してから操作します。実機のLANポート変更後にも再接続できました。識別情報はGit対象外の`evidence/device/adb-target.json`へ保存します。ネットワーク設定・無線デバッグ・認証キーは変更しません。

設定画面は「配色」「調整」「状態」「情報」のタブから操作できます。ライセンスは検索可能な一覧から、ライブラリごとの本文・著作権表示を確認できます。

ソースを編集する際の書式は[ソースの書式](docs/source-style.md)を参照してください。
