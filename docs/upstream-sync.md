# 上流更新の統合状況

対象: soichi11208/Zinna-IME main `81e04e6155d81d39f79864303b44ddb6e3682203`。
共通祖先: `c6fea72fe9f8c4ca69cfd42ef2e880e239e5f220`。
ユーザー指定により jawiki と F-Droid 用設定は対象外。

## 統合した内容

- zenzai-plus の推論、TTT、辞書学習、暗号化チェックポイント、単独かな入力、
  Mozc併用、設定画面、任意ビルド・準備スクリプトとモデルカードの収録。
  KomorebiのKarukanは維持。有効時はzenzaiを優先し、機密欄ではMozcを使用。
- パスフレーズで暗号化する学習・辞書・設定バックアップと復元画面。
  モデルおよびzenzaiの学習チェックポイントは対象外。Mozc起動前に復元し、
  個人辞書の同期でエンジンを再帰初期化しないよう調整。
  設定リポジトリが初期化される前にApplicationで復元を適用。
  最優先候補は移行先のKeystoreで暗号化し直す。
- 文脈に応じた助詞順位の修正と、入力ごとの preceding_text 連携。
- カタカナ英語辞書 20,953件のシステム辞書組み込み。従来の個人辞書側の同梱コピーは
  空の辞書へ置換。固有名詞・AI辞書の個別トグルは維持。
- 数字面のMozcスペース幅、コピー変更イベント検出、再現可能なMozcビルド。
- 上流の Unlicense OR MIT-0 原文、アプリ内表示、従来のApache・第三者表示の維持。

## 重複・競合時の採用方針

Backspace・選択同期・文節カーソル・記号2面・フリックガイド・候補順位の独自実装は
既存テストを保持し、上流の同目的の変更による単純置換はしない。
ブランド・application ID・SDK36.1・ABI分割の明示指定はKomorebiの設定を維持する。
Karukan削除、上流への問い合わせ先への変更、クリップボードの常時250msポーリングは採用しない。
大容量jawikiのための暗号化ストレージ上限512MBへの拡大は不要のため64MBのまま。
出典条件が不明確なスクレイピング由来辞書は既存の配布方針により追加しない。

## 未検証の外部条件

zenzai-plus互換TTTランタイムと指定モデルが未入手。通常ビルドへコードは統合済みだが、
モデル同梱ビルド、実推論、実TTTは検証できていない。
実機でのIME操作、バックアップ復元、各エンジン切り替えも未検証。

## 検証結果（2026-11-02）

- JVM: app 136件、mozc 5件、karukan 2件。失敗・エラー・スキップなし。
- Mozc `//converter:history_reconstructor_test`: 3ケース成功。
  漢字・カタカナ文脈を名詞として復元し、NO_LEARNINGを保持する回帰ケースを含む。
- `:app:lintDebug`、`:app:assembleDebug`、`:app:assembleRelease`: 成功。
- debug APK の署名検証: 成功。release APK は未署名。
- 両APKの16KB ZIP整列、全Mozc ABIとKarukan/libc++のELF LOAD整列を確認。
- 両APKに新しいMozc生成物・システム辞書マーカー・同期したライセンス表示を確認。
- R8 mappingでMozcJNI、KarukanNative、ZenzaiNativeのクラス名保持を確認。
- zenzaiモデル未準備時の `:app:validateZenzai -Pzenzai=true` は期待する事前検査で停止。
- 辞書生成は20,953件、5列・重複なし・コスト8000を確認。
- 実機操作・端末間復元・モデル付きzenzai推論/TTTは未検証。
