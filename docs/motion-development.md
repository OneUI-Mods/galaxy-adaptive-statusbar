# One UIホーム追従の開発状況

0.5.0の実装をローカルで開発中です。署名済みrelease版を対応端末に導入しましたが、ロック解除待ちのため、開閉中の描画をまだ実機確認できていません。GitHub Releaseは更新していません。

## 対応する経路

- One UIホーム17.5.03.27 / Android 16 / 指定のOne UI 8.5ビルドに限定。
- One UIホームの`PlayerImpl.makeUpdateInfo`から、通常・Home Upの計算後の実際の位置、crop、拡大率、透明度、角丸を取得。通常のValueAnimPlayerとHome UpのSpringAnimPlayerを共通の出力で扱います。
- 開始・終了・中断は同じPlayerとAnimatorSetから取得。通常の開く動作はnativeのwindow-position durationとAndroidのduration scaleを使い、原則として最後の250msで表示を受け渡します。ばねには固定の完了時刻を当てはめず、実際の幅・速度と進み具合を使います。
- SystemUIは自分のステータスバーViewをRenderNodeへ記録し、タッチを受け取らない専用WindowContextの透明ウィンドウへ描画します。他アプリの画像はコピーしません。
- 接続先の登録はSystemUI、取得とフレーム送信はOne UIホームのUIDに限定。転送するのは座標のみです。古いフレーム、不正な値、別セッションの遅延結果を拒否します。
- アニメーション中は背景キャプチャを止め、既存の配色・色フェードの結果を無効化します。OFF、消灯、ロック、回転、中断、220msの更新途絶で元のバーを復元します。

AOSPも[ランチャーのアプリ遷移とステータスバー表示のタイミングを連携](https://android.googlesource.com/platform/packages/apps/Launcher3/+/refs/tags/android-14.0.0_r11/quickstep/src/com/android/launcher3/QuickstepTransitionManager.java)しています。本実装の対象は上記Samsung製ランチャーの実機バージョンです。

## 現在までの確認

- 単体テスト34件成功。新規9件は受け渡し時刻、短い動作、ばねとovershoot、ホーム戻り、不正・遅延フレームを検証します。
- releaseビルド、署名、zipalign、検証用Activityの除外、Lintに成功。
- 署名済みの実機テストAPKでIPC権限とParcelの検査2件に成功。
- 実機の既存OFF/ON試験1件に成功。ロック中に取得数が増えず、停止状態と再有効化を確認。
- Vectorの対象へOne UIホームを追加。両プロセスでフックとEngineの初期化に成功。クラッシュ保護の検出回数は0。
- Home Upの設定2ファイルを退避し、全設定が変わっていないことを比較確認。自動消灯設定も元の値へ復元。

## 解除後の必須確認

1. 現在のHome Up設定で、ホームのアイコンから実際に開閉して画面収録する。移動中のバー、固定側の消え方、最後の復帰、二重表示・ちらつき・位置ずれをフレームで確認する。
2. One UI側の子RenderNodeのalphaやtintがコピー側へ与える影響を確認する。必要なら独立したhardware bitmapへ固定する。
3. Home Upの開く動作とジェスチャー動作の調整を一時的にOFFにし、通常設定でも同じ試験を行う。調整値を初期化せず、元の設定へ戻して比較する。
4. 途中反転・連続開閉・設定OFF・消灯を試し、元のバーが必ず戻り、透明ウィンドウが残らないことを確認する。
5. 新しい設定Switch、5言語、既存の色フェード・保護UIの回帰を確認する。

確認後にREADME・CHANGELOG・利用者向けのリリース文を仕上げ、署名済みrelease APKを改めてパッケージ化します。既存タグを移動せず、新しいタグで配布します。
