# Android 17の互換性記録

- 対象候補: Android 17 / API 37、SM-S948Q、ファームウェアS948QOPU1BZI9。
- 状態: バージョン分離の準備済み。ROM・実機のフック契約は解析中です。
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
