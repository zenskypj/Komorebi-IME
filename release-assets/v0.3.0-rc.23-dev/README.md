# 0.3.0-rc.23-dev テスト用 APK

ユニバーサル debug 署名版（`me.zssu.ime.debug`、versionCode 47、versionName `0.3.0-rc.23-dev`）。
**正式署名版ではありません。** 実機での操作確認は未実施です。ネイティブライブラリは
GitHub のファイルサイズ上限に収めるため圧縮しています（`-PcompressNativeForTest=true`）。

## rc.22-dev からの変更

- **Gemma 4 E2B を追加**: 校正モデルに **Gemma 4 E2B**（`unsloth/gemma-4-E2B-it-GGUF`、Q4_K_M、約3.11 GB、
  Apache-2.0 / Google DeepMind）を追加。モデルは Qwen3.5 0.8B / Qwen3 1.7B / Gemma 4 E2B の3択。
- **プロンプトを family 対応に強化**: ネイティブ実行部はチャットテンプレートを適用しないため、
  Qwen は ChatML、Gemma は `<start_of_turn>` 形式を `ProofreadPrompt` で組み分けるようにした。
  どのモデルでも本文だけを返すよう、番号付きの明示的なルール（前置き・説明・マークダウン禁止、
  意味・改行を変えない、翻訳しない）を与える。出力からは思考ブロック（Qwen の `</think>`、
  Gemma の `<channel|>`）と残存する制御トークンを除去する。
- モデル管理画面に per-model のライセンス表示を追加。NOTICE / docs を更新。

## 実機での未確認項目

- 各モデルでの校正の品質・速度・発熱（特に Gemma 4 E2B は約3.11 GB）
- 校正小窓・モデル管理画面の操作
- 各アプリ（WebView を含む）の入力欄への反映

## インストール

```
adb install -r Komorebi-IME-0.3.0-rc.23-dev-universal-debug.apk
```

applicationId は `me.zssu.ime.debug` です。正式版 `me.zssu.ime` とは別枠なので共存できます。
