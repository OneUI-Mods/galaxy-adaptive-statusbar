# SystemUIの解除済みセッション引き継ぎ

Galaxy Reloadのroot helperとAdaptive Barの任意機能がprotocol 1で連携する。認証情報・lockscreen enabled・trust・強制認証フラグ・Compose stateは変更しない。通常のKeyguardLock APIはsecureなkeyguardを無効化できず、system callerもLockTaskTokenを要求するため使用しない。[AOSP KeyguardDisableHandler](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/wm/KeyguardDisableHandler.java)を照合した。

## 16の定義

AZHLのSystemUI APKを独立に調べた。KeyguardViewMediatorのonSystemReady()、doKeyguardLocked(Bundle,boolean,boolean):boolean、hideLocked()、onStartedGoingToSleep(int)と、mShowing / mPM / mSelectedUserInteractor / mUpdateMonitorを照合。SelectedUserInteractor.getSelectedUserId、KeyguardUpdateMonitor.isForcedLock / isUserInLockdown(int) / isSimPinSecureも確認した。16実機での再試験は未実施。

## 17の定義

BZI9の稼働APKで同じ契約を別に照合した。既存16定義をfallbackにせずSDKごとの入場条件を使う。登録失敗時はこの機能のハンドルだけを外し、capabilityを削除する。

## 要求と復旧

root helperはcurrent user 0、UserManagerのunlock状態、画面ON、KeyguardManagerのdevice/keyguard locked=falseを確認し、停止直前にも再確認する。Adaptive Barの配布署名と最低バージョン、既知の端末/ビルド、対象PIDのcmdlineとUIDも検証する。PIDはSystemUIだけを指定しTERMする。

要求はSystemUIのDE private files内へ原子的に保存し、ownerはPackageManagerで照合したSystemUI UID / mode0600にする。protocol、boot count、user、uptime、UUID nonceを持ち有効期間は12秒。moduleはregular file・owner・mode・サイズを確認し、成功/拒否を問わず先に要求を消費する。別boot、user、未来/期限切れ、未unlock、画面OFF、SIM PIN、lockdown、forced lock、cancel nonceでは受け付けない。

受理直後のstartup doKeyguardLockedだけを最大5秒間見送り、SamsungのhideLockedを呼ぶ。mShowing=falseを観測したら完了を記録し、直ちに通常のロック経路へ戻す。永続的なkeyguard無効化、enabled変更、起動時の自動解除は行わない。non-null bundleの明示的lock、消灯、強制認証では即中止する。失敗・タイムアウトはnative lockを表示し、root helperもcancel nonceを残して遅れた再開を抑止する。

CLIとGUIは同じAPKのReloadCommandを使う。wrapperはpm pathから現在のAPKを解決し、更新後も同じ入口を使う。root以外の実行を拒否し、固定コマンド・固定パッケージ・固定ファイル以外は操作しない。検証結果は実機確認後に追記する。


要求ファイルだけでは再開しない。root helperはabstract LocalServerSocketを保持し、SystemUIはnonceによる一度のhandshakeとkernel peer UID=0を確認する。helperもpeerはSystemUIの実UID・新SystemUIの正確なPID/cmdlineを確認し、再読み込みの隙間は画面OFFを50ms間隔で監視する。helperが死んだ後に残ったファイルや、別アプリが同じsocket名を取得した場合は認可できない。受理後はSystemUIのsleep/forced-auth経路が監視を引き継ぐ。
