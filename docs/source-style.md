# ソースの書式

自作ソース、テスト、検証ツール、ビルド設定を同じ規則で整えています。エディターの基本設定はルートの`.editorconfig`を参照してください。

| 対象 | 書式 |
| --- | --- |
| Java | Google Java Style、スペース2個。google-java-format 1.24.0を使用 |
| Python | スペース4個。Black 25.1.0を使用 |
| Gradle | スペース4個。設定や依存関係を1行に1項目ずつ記述 |
| XML | スペース2個。Manifestや画像定義は属性ごとに改行 |
| 翻訳・色・styleの値 | 値を保持して1項目ずつ記述。翻訳文の途中へ整形用の改行を加えない |
| シェル・HTML・JSON・YAML | スペース2個。Prettier 3.6.2を使用 |

Javaの整形では`--skip-sorting-imports --skip-removing-unused-imports --skip-reflowing-long-strings`を指定しています。リフレクション先の名前、設定キー、文字列リテラルは書式変更の対象に含めません。

XMLには`@prettier/plugin-xml` 3.4.2の`xmlWhitespaceSensitivity=preserve`を使用しています。Manifestやdrawableでは属性を改行し、valuesの各項目は本文を保持して読みやすく並べます。シェルには`prettier-plugin-sh` 0.18.0を使用しています。

生成されたGradle Wrapper、バイナリ、署名ファイル、ライセンス本文はそのまま保ちます。ビルド出力や実機から取得した調査資料をソースとして整形しません。

## 変更時の確認

- Javaの構造や制御フローを変更する場合は、対象の既存テストとLintを実行します。
- 翻訳・XMLは文字列と属性値、Gradleは設定値と依存関係が意図せず変わっていないか確認します。
- 整形だけの変更と、機能の変更を分けてレビューできるようにします。
