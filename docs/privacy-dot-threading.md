# 緑の点の横伸びとスレッド所有権 — 0.6.23

## 実機で保存した原因

ユーザーが電池の右側に緑の塊が出ている状態で、再読み込み前にログ・画面・View階層を保存した。スクリーンショットには写らず、StatusBarの充電表示にも異常はなかった。`cmd window dump-visible-window-views`のScreenDecorOverlay側では、12×12pxのprivacy_dotがscaleX=10.832831、scaleY=1.487500、親のclipChildren/clipToPadding=falseのまま表示されていた。幅は約130pxになっていた。

旧Visualは純正のカプセル→丸い点への変形途中の倍率を元の倍率として保存し、その値に拡大係数を乗算して毎フレーム書き戻していた。さらにstatus-bar(main)とScreenDecorationsの両方へ同じobserverを登録し、別スレッドのViewを復元・invalidateしてCalledFromWrongThreadExceptionになっていた。復元できた場合でも、保存した倍率自体が変形途中のため丸い点へ戻せない。

## 修正

- View.scaleX/Yは読み取りだけにし、純正に所有させる。拡大は対象ImageViewのonDraw Canvasへsave→均一scale→restoreを適用する。
- 純正の縦横倍率が1付近の丸い状態に限り、従来の2dp相当・最大1.5倍の拡大を適用する。カプセルの途中の倍率を基準にしない。拡大係数もフレーム時間で補間する。
- ScreenDecorations側のobserver登録・描画更新・clip解除/復元はView.postで所有looperへ渡す。mainのstatus-bar rootへdot observerを付けない。
- ScreenDecorationsは不変の位置・alpha・丸さのsnapshotだけをmainへ渡し、右側アイコンの空き確保はmain側で行う。mainからnative cornerを読み書きしない。
- clipの所有権はUIスレッド別のregistryで参照カウントする。充電とプライバシーの同じmain階層では共有し、ScreenDecorationsとmain間でMapを競合させない。
- 非同期のmain callbackにも例外境界を設け、失敗時はこの表示調整だけを停止してnative側へ復帰する。カメラ・マイクの状態・色・可視性は変更しない。

## 検証範囲

JVM145件（実測の横伸び倍率・拡大の非累積性・有効/無効な丸さ・60/120Hz・異常値の回帰5件を含む）とrelease lintが成功した。独立実機プロセスでは、callback/configureを別looperから呼んでもViewTreeObserverの処理がView所有looperで行われることを2件、clipの併用・解放・多重closeを3件確認した。

SM-S948Q / Android17 BZI9の実機へ署名済みreleaseを反映し、Samsungカメラと上スワイプによるホーム復帰を確認した。点のView倍率は全cornerで1×1に戻り、対象の新PIDでスレッド違反とdot停止は記録されなかった。ユーザーも画面上の緑の塊が消えたことを確認した。カメラの全画面中は既存の可視性条件に従い拡大がinactiveだったため、この録画を拡大中の直接の視覚証跡とは扱わない。点がキャプチャから除外されるため、View階層と状態表示を分けて確認する。

Android16実機・全回転・全アプリでの全組み合わせは再試験していない。新しい実装も既存のAndroid17 firmware allowlist内に限定する。privateの画面・階層・ログ・純正解析資料・fixtureは配布へ含めない。

0.6.24ではcamera-onlyの既存fixtureで使用表示を持続させ、dot active=true・drawScale約1.4875とnative View倍率1×1を同時に確認した。通常アイコンの移動は録画で確認しているが、点自身はキャプチャ除外のためその録画で点の視覚形状を直接検査したとは扱わない。詳細はprivacy-icon-handoff.mdを参照。
