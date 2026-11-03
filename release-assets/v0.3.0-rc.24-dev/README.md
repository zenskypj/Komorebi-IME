# 0.3.0-rc.24-dev テスト用 APK

ユニバーサル debug 署名版（`me.zssu.ime.debug`、versionCode 48、versionName `0.3.0-rc.24-dev`）。
**正式署名版ではありません。** 実機での操作確認は未実施です。ネイティブライブラリは
GitHub のファイルサイズ上限に収めるため圧縮しています（`-PcompressNativeForTest=true`）。

## rc.23-dev からの変更

- **Qwen の thinking を確実に無効化**: `/no_think`（ソフトスイッチ）だけでは thinking が残ることが
  あるため、assistant ターンに空の thinking ブロックを前置する（Qwen3 のチャットテンプレートが
  `enable_thinking=false` のときと同じ）。最初の生成トークンから本文になる。
- **システムプロンプトを短縮**: 長い番号付きルールをやめ、要点（本文だけ出力／説明・マークダウン
  禁止／意味・数値・改行を変えない・翻訳しない／不要箇所はそのまま）に絞った短い指示にした。
  小さいモデルでの指示追従と、`n_ctx=2048` の prefill 負担の両方に効く。
- 出力側では従来どおり thinking 終了マーカー（Qwen の `</think>`、Gemma の `<channel|>`）以降だけを
  取り出すため、万一 thinking が混ざっても結果には残らない。
- `ProofreadPrompt.Format` を QWEN / GEMMA に整理（CHATML を QWEN に改名）。

## 実機での未確認項目

- Qwen で thinking が出力されず本文だけが返ること（速度・品質）
- 各モデル（Qwen3.5 0.8B / Qwen3 1.7B / Gemma 4 E2B）の校正品質

## インストール

```
adb install -r Komorebi-IME-0.3.0-rc.24-dev-universal-debug.apk
```

applicationId は `me.zssu.ime.debug` です。正式版 `me.zssu.ime` とは別枠なので共存できます。
