# 設定画面の開発記録

## 0.5.4: タブとライセンス一覧

配色・調整・状態・情報へ分類。独立したNestedScrollViewを持ち、非表示ページのスクロール復元は再表示後のレイアウトまで遅延する。画面再生成時の選択タブと各ページの位置を保存する。フックの動作は変更しない。

ライセンスは検索可能なコンポーネント一覧と、個別の本文・著作権表示へ分離。元の通知ファイルも保持する。解決済みruntime dependencies 57件の座標・バージョンと一覧を照合し、配布時にも検査する。

Android 16 arm64の独立したエミュレーターで画面テスト7件成功。タブ・非表示ページの位置復元、設定値維持、検索・本文・Back、既存画面の起動と多言語、システムバー余白を確認。日本語・ダークテーマの画面を目視確認した。実機のフック再試験は行っていない。


## 0.5.5: 自動復帰と見出しの整理

BOOT_COMPLETED／LOCKED_BOOT_COMPLETED／MY_PACKAGE_REPLACEDを非公開receiverで受ける。ロック中はCEへアクセスせず、解錠後に設定URIを通知して、SystemUIとOne UIホームが再取得する。Applicationのテーマ設定も解錠後へ限定。起動にsu・Activity・常駐サービスを使わず、設定値と保護状態を変更しない。

Engineの設定取得前にUserManagerを確認し、provider準備中の例外はキャプチャエラーの連続回数へ加えない。One UIホームはUSER_UNLOCKEDでも設定を再取得する。既存の保護停止を解除しない。

メイン画面のhero見出しと本文を全言語から削除。自動配色スイッチと各機能の説明は残す。独立したAndroid 16環境で、起動通知による設定維持を含む画面テスト8件成功。

rootなしのAndroid 16 Play Storeエミュレーター（ro.debuggable=0）の実際の再起動で、BootReceiverが自動起動することをActivityManagerログとpidで確認。


## libxposed API 102への移行

API 82のcompileOnly JAR、legacy manifest metadataとassets/xposed_initを除去。io.github.libxposed:api:102.0.0をcompileOnlyで使用し、XposedModuleのonModuleLoaded／onPackageReadyとframeworkのonSystemServerStartingで初期化する。META-INF/xposed/module.prop、java_init.list、scope.listを登録。min/target APIは102、autoHotReload=false。system_serverのscope名はmodern仕様のsystem。

既存のbefore/after処理は自前のHookCallbackでAPI 102のChainへ適応する。実登録はhook(Executable).setId(...).intercept(...)、解除は所有するHookHandleのみ。明示的な録画拒否・例外を握り潰さないようPASSTHROUGHを設定し、before/after自体の例外は原処理の結果を維持する。共有のReflectは通常のJava reflectionをキャッシュし、legacy APIへ一切依存しない。Batteryのdeoptimizeも公開APIへ変更した。

引数変更、戻り値、原処理の省略、意図した拒否、例外とcleanup、反射のオーバーロード・継承・静的呼び出し、登録失敗時の部分解除を回帰テストする。配布チェックはmodern metadataと旧APIの混入を検査。ライセンス一覧のcompile-only参照も102へ更新した。

API 102向けの単体テストはbefore/after adapter・反射・登録解除の15件を含む。rootなしのAndroid 16エミュレーターで起動・設定・ライセンス画面が起動し、UIテストBar8件／Capture4件成功。最初のBar実行はADBサーバー側の接続断で途中終了し、残り7件を再実行して成功。
