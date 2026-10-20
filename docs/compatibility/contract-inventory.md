# ROMのフック契約を照合する

`tools/HookContractInventory.java`は開発PC専用です。APK/JARから、指定したクラスの親・interface・フィールド・メソッドのメタデータだけをTSVへ出力します。アプリのビルドには含めません。ROMや第三者の実装コードをGitへ追加しないでください。

## 実行

JDK 21と、dexlib2 2.5.2・Guava 30.1-jreをクラスパスに指定します。`DEX_DEPS`はそれらのローカルJARをパス区切りで並べた値です。

```sh
mkdir -p /tmp/galaxy-hook-inventory
javac -cp "$DEX_DEPS" -d /tmp/galaxy-hook-inventory tools/HookContractInventory.java
java -Xmx768m -cp "/tmp/galaxy-hook-inventory:$DEX_DEPS" \
  HookContractInventory /path/to/launcher.apk /path/to/classes.txt /path/to/launcher.tsv
```

`classes.txt`は完全修飾クラス名を1行に1つ記載します。メタデータのほか、存在しない指定クラスを`.missing`、全クラス名を`.classes`へ出力します。1つのAPKに関係しないクラスも指定した場合、それらが`.missing`へ出ること自体は異常ではありません。

出力の種別は`C`（クラス）、`F`（フィールド）、`M`（メソッド）です。メソッドの引数型・戻り値型はDEX descriptorで記録されます。引数なしとboolean引数などを区別して比較してください。宣言から消えたフィールドは親クラスへ移動した可能性もあるため、単純な行差分でフック不能とは断定しません。

## DEX 041

今回の16/17の`services.jar`には、1つの物理DEXに複数の論理DEXを含む041形式がありました。先頭だけを読むとWindowManager関連クラスを見落とします。

ツールは各論理ヘッダーのサイズ・コンテナサイズ・ヘッダー位置を検証し、絶対オフセットを維持した一時的なメモリ上の039ビューでメタデータを読みます。各論理DEXのクラス数も照合します。入力ファイルは変更しません。このビューを端末で実行したり、署名・配布したりしてはいけません。仕様は[AOSP DEX container format](https://source.android.com/docs/core/runtime/dex-format#container-format)を参照してください。

今回の`services.jar`では16/17とも論理DEXが2つあり、指定したWindowManager関連15クラスを両方から取得しました。これにより、先頭だけの解析で「クラスがなくなった」と誤認する問題を避けています。

## 結果の扱い

- メタデータ一致は、引数の意味・呼び出しスレッド・ライフサイクル・描画結果の一致を保証しません。必要な箇所は元の実装の呼び出し関係も調べます。
- 解析に用いたAPK/JARの版・SHA-256と、実機から取得した値を別々に記録します。
- Android 16の基準は`android16-source.json`のcommitに残します。17へ合わせるために16の定義を上書きしません。
- 実機確認前の17用プロファイルは無効のままです。ビルド成功を対応完了として扱いません。
