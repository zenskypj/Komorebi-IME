# zenz v3.2（実験的）

「変換エンジン・辞書」の zenz 欄から約 70 MiB のモデルをダウンロードし、
SHA-256 を検証して端末内へ保存する。インストール後に変換を有効にできる。
変換処理と入力内容は端末内に留まる。パスワードなどの機密欄では使用しない。
現在、配布するランタイムは arm64-v8a 用。他の CPU ではボタンを無効にする。
zenz v3.2 は推論のみであり、zenzai-plus 用 TTT 学習は実行しない。
既存の任意ビルド版 zenzai-plus も維持する。両方がある場合は v3.2 を優先する。

モデル: Miwa-Keita/zenz-v3.2-small-gguf、Apache-2.0、
`ggml-model-Q5_K_M.gguf`、SHA-256 `29c223d4c23327b80fd13ebb5ab2555057a46317997d5da391584ffbef0db673`。
ランタイム: azooKey/llama.cpp `66afb885a8ddcc0511798aec9fb5a01017941018`、MIT。
入力形式は公開実装 skk-zenz の v3.2 対応形式を参考にした。

ランタイムの再ビルドには Android NDK、CMake、Ninja が必要。
`ANDROID_NDK_HOME=/path/to/ndk scripts/build_zenz32.sh`。
追加 CPU 向けには `ZENZ_ABIS="arm64-v8a x86_64"` のように指定する。
モデルファイルは APK に含めない。

## 検証状況

ホスト側で指定 GGUF を実際に読み込み、「きょうは」→「今日は」、
「きょうはいいてんきです」→「今日はいい天気です」、
左文脈「試験の」＋「かいとう」→「回答」を確認した。
単体テスト 146 件、debug／release Lint、APK と AAB のビルドが成功した。
Android 実機でのダウンロード、推論速度、IME 操作は未確認。
