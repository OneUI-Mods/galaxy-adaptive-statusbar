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
