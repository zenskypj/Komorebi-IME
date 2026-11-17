# Komorebi Desktop for Windows

Windows 10/11 x64 向けの **独自日本語 IME の開発プレビュー**です。Windows の Text Services Framework (TSF) を使い、ローマ字処理と辞書候補は Komorebi 自身が処理します。Microsoft IME、ネット接続、入力学習は不要です。

1. ZIP を展開します。展開先の `Setup.exe` または `Install.cmd` をダブルクリックし、管理者権限の確認画面に応答します。成功・失敗はダイアログに表示されます。
2. Windows の「時刻と言語」→「言語と地域」→「日本語」→「言語のオプション」で、日本語を追加します（未追加の場合）。インストーラーは Komorebi Desktop のプロファイルを登録して有効にします。一覧が更新されない場合は、サインアウトしてサインインし直してください。
3. `Win+Space` で Komorebi Desktop に切り替えます。`Ctrl+J` または「半角／全角」で A／あを切り替えます。ローマ字で入力し、`Space` で候補一覧を開きます。`Space`・上下キーで候補を選択、`Enter` または一覧の番号キーで確定します。`Backspace` は候補選択からかなに戻るか、ローマ字を1字戻します。`Esc` は未確定入力を取り消します。
4. 取り外す場合は他の IME に切り替え、開いている入力アプリを閉じてから、展開先の `Uninstall.cmd` を実行します。

スタートメニューの「Komorebi Desktop」または ZIP 内の `KomorebiSettings.exe` で、開始時の A／あ と候補ウィンドウの表示を設定できます。保存後は IME を切り替え直してください。

例: `nihongo` → にほんご → 日本語、`toukyounotenki` → とうきょうのてんき → 東京の天気。ひらがな・カタカナも選べます。公開 Mozc 辞書からコスト順に抜き出した最大6万語と基本辞書を使い、独自の文節探索で候補を作ります。Android 版の Mozc エンジンそのものやその文脈学習・AI は未搭載で、候補順位は Android 版と異なります。

配布 DLL は Authenticode 未署名です。保護機能がブロックする環境で、その制限を解除する必要はありません。Windows 実機での登録・アプリ横断の入力、ARM64 および x86 アプリは未確認です。

## Build

Linux と LLVM-MinGW (x86_64 UCRT)、Python 3 が必要です。

```sh
MOZC_DICTIONARY_DIR=/absolute/path/to/mozc/src/data/dictionary_oss \
  CXX=x86_64-w64-mingw32-clang++ windows/build.sh /tmp/komorebi-windows
(cd /tmp/komorebi-windows && wine ./Smoke.exe)
```

`Smoke.exe` は変換エンジン、DLL 読み込み、COM クラス生成を確認します。システムに IME を登録しません。ローマ字表は既存のアプリ資産、固有名詞は CC0 の基本辞書を使用しています。

配布用辞書は [Mozc 3f235b4](https://github.com/google/mozc/tree/3f235b4eb6fcff7d14ef5f0fb8ee56de7ee4c732/src/data/dictionary_oss) の公開データを使います。
