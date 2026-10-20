# Android 17の互換性記録

- 対象候補: Android 17 / API 37、SM-S948Q、ファームウェアS948QOPU1BZI9。
- 状態: ROMと実機APKの一致を確認し、BZI9専用のフック入場条件を有効化。機能ごとの実機確認中です。
- `Android17Hooks`は独立したプロファイルです。実装と動作を確認するまではフックを許可しません。16の許可条件を広げて17へ流用しません。
- 今後はAPK／frameworkのハッシュ、クラス・メソッド・引数・フィールド・呼び出し関係、実機で確認した項目をこの記録へ追記します。
- ビルドの成功、フック登録の成功、画面上の機能確認は別々に記録します。

## 分離準備の検証

Android 16の許可条件の維持、17から16へフォールバックしないこと、未対応SDKを暗黙に有効化しないことを確認しました。既存分を含むJVMテスト91件、release Lint、releaseビルドが通過しています。この段階のAPKは端末へインストールしていません。17の機能確認済みという意味ではありません。

## BZI9 ROMとの照合（2026-10-20）

提供ROMからSystemUI、One UIホーム、frameworkを読み取り専用で抽出しました。ビルド表示は`CP2A.260605.016.S948QOPU1BZI9`、SDK 37、One UI 90000です。APKの版とSHA-256は[android17-rom.json](android17-rom.json)へ保存しています。systemパーティションのfingerprintを、実機の`Build.FINGERPRINT`として扱わないでください。

| 処理 | Android 16 | Android 17で確認した変更 |
| --- | --- | --- |
| ホームの版 | 17.5.03.27 / 1750305027 | 18.0.07.53 / 1800705053を別プロファイルへ記録 |
| 履歴カード | `com.honeyspace.ui.common.taskScene.TaskSceneView` | `com.honeyspace.common.taskscene.TaskSceneView`へ移動。`onDraw(Canvas)`、`getTasks()`、`hasTaskId(int)`は存在 |
| アニメーション終了 | `PlayerImpl.end()`と`finish()` | `end()`は存在せず、`finish()`を登録する分岐を準備 |

これらの差分はコードへ反映しています。時計・配色の主要クラスとBatteryAdapterが読むpaint・geometryのフィールドもDEX上で照合しました。クラスが存在することだけでは描画結果を保証しません。17のフック許可は引き続き無効です。

ROM照合後のJVMテストは94件すべて通過し、release Lintとreleaseビルドも通過しました。Android 16の入場条件、終了フック順序、既存設定は維持しています。16の元ソースは[android16-source.json](android16-source.json)のcommitとファイルハッシュから参照できます。

## 残る確認

- 実機のビルド・APKハッシュと、Vectorでの登録・解除・クラッシュ保護。
- 開閉・履歴・方向転換・Home Upでの非線形アニメーションと、文字ごとの配色の両立。
- 消灯時の停止と、設定OFF後に色・透明度・座標が元へ戻ること。

TailADBの接続先は接続拒否のため、この段階では実機確認・インストールを行えていません。TailADBの更新は利用者の指示で保留中です。端末・SystemUI・ホームの再起動も行っていません。

DEX 041を含む解析の再現方法は[contract-inventory.md](contract-inventory.md)を参照してください。

## 実機照合と読み込み準備（2026-10-20）

稼働中のビルドとAPKをTailADB経由で読み取り、SystemUI・One UIホーム・SamsungキャプチャがROMのSHA-256と一致することを確認しました。実機fingerprintはJSONの`runtime_fingerprint`に記録しています。更新保留は利用者が解除しています。

SM-S948Q / m3q / Samsung / SDK 37 / BZI9完全一致の入場条件を17用定義へ追加しました。16の条件は変更していません。個々の機能は引き続き必要なクラス・引数・フィールドを実行時に照合します。これは画面上の全機能を検証済みにする変更ではありません。

端末・SystemUI・ホームを再起動する前に、Samsungキャプチャなど単独プロセスで検証できるものを確認します。必要なソフトリブートは全体の最後にまとめます。

更新版のJVMテスト（Bar 95件／Capture 59件）とrelease Lintが通過しました。指定の配布手順でrelease APKを生成し、署名・検証用Activity非混入を確認して実機へ上書きしました。SystemUI・ホーム・frameworkの読み込み直しは最後にまとめます。

## 実機デバッグと設定UI（2026-10-20）

最後にzygoteをソフトリブートし、カーネルのboot IDが変わらないこと、SELinux EnforcingとTailADBの復帰を確認しました。解除後、Barは背景取得・文字ごとの配色・ライブ描画が動き、Home Tweaksはホームの場所別配色・時計・音量・壁紙プレビューがACTIVEになりました。Captureは録画対象一覧を取得でき、アプリ録画の縦開始→横→逆横→縦を1080×2340の固定枠で記録しました。逆横で180度を無視する設定も維持されています。

設定UIは機能ごとにまとまりを作り、M3の面・文字階層・操作の優先度を整理しています。プレビューは合成サンプルで、実際の通知や壁紙を読み取るものではありません。Samsung設定内のUIは純正コンテナを維持します。

## 設定UI・録画の追加確認（2026-10-20）

署名済みrelease版のBar 0.6.4、Capture 0.3.3、Home Tweaks 0.12.2を上書きし、3本の設定画面を実機で表示しました。単独アプリのUIはM3のカード、文字階層、合成プレビューを整理しています。Samsungへ差し込む画面の部品は変更していません。現在のJVM試験はBar 97件、Capture 59件、Home Tweaks 84件で、release Lintも通過しています。

Samsungのウィンドウ録画でBarのテスト背景を選び、同じアプリのMainActivityへ切り替えました。保存された動画は選択したPatternActivityの表示を維持し、切り替え先の設定画面を含みませんでした。対象アプリの内部音は880 Hzの合成音、メディア＋マイクはAACトラックとマイク入力で確認しました。スピーカー・本体メディア音量0の状態では純正の音量係数に従って内部音が無音になるため、録画専用音量100%を一時的に有効にして、メディア＋マイクで内部音も入ることを確認しました。専用音量のON/OFFは元へ戻し、確認用アプリは削除しました。生の音声・動画・端末設定は開発用の非公開証跡にのみ保存しています。

Android 16の実機再試験、Galaxyに存在しないAOSP標準録画画面、Home Tweaksのfocus変更を含む全設定の組み合わせを検証済みとは扱いません。16の定義と基準ソースは保持しています。

### 録画表示の背景欠落

Android 17のSamsung録画表示は`TouchInterceptFrameLayout` / `samsung_ongoing_cast_chip`です。従来のカプセル判定に一致せず、開閉中のライブ描画が子ImageViewだけを描いて背景を落としていました。このコンテナを背景・図柄を含む1要素として扱い、配色側も同じ判定で純正の色を保持します。16の既存カプセル条件は残しています。分類の回帰テスト2件を追加しました。ローカルのワイヤレスADBでSystemUIだけを再読み込みし、黒背景で録画開始後にゆっくり縮小するジェスチャーを実行しました。固定時と縮小途中の静止画で、赤い背景と白い図柄が同じ位置・スケールを保って描かれることを確認しました。描画の受け渡しと復元が完了し、Last errorは空です。system_server・ホームのPIDおよびカーネルboot IDは変わっていません。

### Android 17のComposeバッテリー

白背景でもカプセルが白いままになることを実機で再現しました。診断対象に旧ImageViewのバッテリーが含まれず、17の通常バーは`SamsungBatteryKt.SamsungBattery`からComposeのBatteryLayoutStateを描画しています。クイック設定・AOD向けには旧Drawableも残るため、旧Drawableのオフスクリーン試験成功だけでは通常バーを検証できません。

17専用アダプタはHomeStatusBarViewModelが明示された呼び出しだけを識別し、通常バー内のCompose semanticsにあるbattery要素から実際のウィンドウ座標を読み取ります。純正の色判定が持つRectは空のままで、位置取得には使えないことを確認しました。BatteryLayoutStateの26引数・フィールド、SnapshotStateの生成、描画関数をROMで照合しました。純正レイアウトを変更せずコピーし、カプセル・残量・充電図柄の配色だけを渡します。Composeの状態通知で背景更新とOFF時の復元を要求します。アニメーション中は選択矩形で純正ルートをクリップしてライブ描画し、画像化しません。16用BatteryAdapterは変更していません。状態スコープとレイアウト保持を含むJVM試験4件を追加し、合計102件とrelease Lintが通過しました。実機反映後の確認は追記します。

Samsungの`sysuiResTag`は`battery`を`com.android.systemui:id/battery`へ展開します。完全なタグ名で照合する回帰試験を追加しました。旧色判定関数のインライン化を避けるために試したComposableのdeoptimizeは、semantics方式では必要がないため取り除いています。バッテリー表示の切り替え不良・ちらつきの報告を受け、個別の配色だけを一時OFFにしてから再検証します。

### Compose配色の連続クラッシュと撤回（0.6.9）

0.6.8で実座標を取得して配色を開始した後、SystemUIが3回連続で異常終了し、クラッシュ保護が`crash_loop`で停止しました。例外はComposeの`produceState`内のClassCastExceptionで、`collectAsState`のコールバックをMutableStateとして読む時点で発生しています。バッテリーComposableへのSnapshotState読み取り追加と変更フラグの書き換えを含む経路で発生しました。個々の操作の因果を分離した再現は未実施であり、JVM試験の成功はComposeの実行時互換性を保証しません。保護は正しく作動しており、誤検出として解除しません。

0.6.9では追加した17用Composeアダプタと呼び出しを撤回し、Engine・HookEntry・StatusBarMotionを実機確認済み0.6.4の実装へ戻しました。録画表示の赤い背景保持と設定UIの改善は維持しています。16用バッテリー経路は変更していません。17のバッテリー配色および開閉時の同要素の追従は未解決です。今後はComposeの再構成・変更フラグを介入対象とせず、純正描画処理と描画の無効化を調べます。失敗した方式を有効のまま保護だけ解除して再試行することは避けます。

0.6.9はJVM試験97件、release Lint、releaseビルド、署名・配布内容の検査が通過しました。端末へ上書き後、失敗時の保護状態とログを非公開証跡へ保存し、SystemUI側のBar保護のみを解除して同プロセスをTERMで再読み込みしました。ホーム・system_server・カーネルは再起動していません。解除後に自動配色をONへ戻し、設定アプリの開閉を10回実行しました。SystemUIのPIDは維持され、新規FATAL EXCEPTION、配色処理のLast error、保護の再停止はありません。これは安定した処理への復帰確認であり、17バッテリー配色や開閉時のちらつきが直ったことを意味しません。

### 実機のView階層と描画値の観測（0.6.10）

`cmd window dump-visible-window-views`が返すViewHierarchyEncoder形式をデコードし、稼働中のStatusBar階層を確認しました。`system_icons`の直下に、GONEかつ0×0の旧BatteryMeterViewと、70×111の専用ComposeView／AndroidComposeViewが存在します。ComposeViewにリソースIDはありません。従来の葉収集はViewGroupの子へ再帰し、ViewGroupかつ実Viewの子がないAndroidComposeViewを描画対象から落としていました。17では親IDと旧バッテリーの兄弟を照合して専用ComposeViewを識別し、開閉時にビュー全体をライブ描画します。16の分類は変更していません。

観測フックはAndroidComposeView.dispatchDraw内で専用ビューを識別し、純正のバッテリー描画コールバックからレイアウト寸法・Paint色・描画Canvasの変換だけを読み取ります。Composable関数はフックせず、引数、SnapshotState、変更フラグ、純正Paintを書き換えません。観測の照合不一致は観測のみを停止します。この版は17のバッテリー配色を追加せず、まず実際の描画値と開閉追従を照合する段階です。
