# Komorebi Desktop for Windows

Windows 10/11 x64 向けの **独自日本語 IME の開発プレビュー**です。Windows の Text Services Framework (TSF) を使い、ローマ字処理と辞書候補は Komorebi 自身が処理します。Microsoft IME、ネット接続、入力学習は不要です。

1. ZIP を展開します。展開先の `Setup.exe` または `Install.cmd` をダブルクリックし、管理者権限の確認画面に応答します。成功・失敗はダイアログに表示されます。
2. Windows の「時刻と言語」→「言語と地域」→「日本語」→「言語のオプション」で、日本語を追加します（未追加の場合）。インストーラーは Komorebi Desktop のプロファイルを登録して有効にします。一覧が更新されない場合は、サインアウトしてサインインし直してください。
3. `Win+Space` で Komorebi Desktop に切り替えます。ローマ字で入力し、`Space` で候補を順に選び、`Enter` で確定します。`Backspace` は候補選択からかなに戻るか、ローマ字を1字戻します。`Esc` は未確定入力を取り消します。`Ctrl+J` は未確定文字がないときに、その入力スレッドのかな入力を切り替えます。
4. 取り外す場合は他の IME に切り替え、開いている入力アプリを閉じてから、展開先の `Uninstall.cmd` を実行します。

例: `nihongo` → にほんご → 日本語、`hashi` → はし → 橋 / 箸 / 端。かな・カタカナも選べます。辞書は全文一致の小規模な同梱辞書です。Android 版の Mozc、文章の文節分割、学習、AI、候補一覧ウィンドウは未搭載です。

配布 DLL は Authenticode 未署名です。保護機能がブロックする環境で、その制限を解除する必要はありません。Windows 実機での登録・アプリ横断の入力、ARM64 および x86 アプリは未確認です。

## Build

Linux と LLVM-MinGW (x86_64 UCRT)、Python 3 が必要です。

```sh
CXX=x86_64-w64-mingw32-clang++ windows/build.sh /tmp/komorebi-windows
(cd /tmp/komorebi-windows && wine ./Smoke.exe)
```

`Smoke.exe` は変換エンジン、DLL 読み込み、COM クラス生成を確認します。システムに IME を登録しません。ローマ字表は既存のアプリ資産、固有名詞は CC0 の基本辞書を使用しています。
