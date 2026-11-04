# 正式版リリース受け入れ基準

`0.3.0` 系は正式版に向けたテスト段階です。現在の公開版は README と GitHub Releases を参照してください。自動テストだけで「全Android端末で動作する」とは判断せず、
以下を満たしてから正式版タグを作成します。

## 自動ゲート

- `:app:testDebugUnitTest` と `:karukan:testDebugUnitTest` が全件成功する
- `:app:lintDebug` と `:app:lintRelease` のエラーが0件である
- `:app:assembleDebug`、`:app:assembleRelease`、`:app:bundleRelease` が成功する
- CPU別APKはAABと分け、`-PenableAbiSplits=true :app:assembleRelease` で生成する
- 全ABI APKの `versionCode` / `versionName` が一致する
- APK内のネイティブライブラリが16 KBページ向けに配置されている
- R8後のRelease APKにMozcランタイム、プライバシー文書、OSSライセンスが残っている
- `PRIVACY.md` とアプリ内 `assets/legal/PRIVACY.txt` が一致する

## Android・端末マトリクス

| 系統 | 必須確認 | 主な差分 |
| --- | --- | --- |
| Android 7 / API 24 | 実機またはAOSPエミュレータ | minSdk、旧InputConnection、3ボタンナビ |
| Android 10 / API 29 | 実機またはAOSPエミュレータ | ジェスチャーナビ、WindowInsets互換層 |
| Android 14 / API 34 | 実機 | edge-to-edge強制前、メモリ通知変更 |
| Android 15 / API 35 | 実機 | edge-to-edge強制、16 KBページ端末 |
| Android 16 / API 36 | 実機 | edge-to-edge opt-out廃止、予測戻る |

各世代で3ボタン／ジェスチャー（提供される場合）、縦／横、分割画面、設定画面からの復帰を
確認します。背景画像・濃さ・キー高さを変えた直後に、最下段のキーがナビゲーション領域へ
重ならず、旧世代で空の二重余白も生じないことを重点確認します。

## 入力互換性

- AOSP EditText、Chrome/WebView、主要SNS、メール、パスワードマネージャー、SSH端末
- 通常、複数行、検索、送信、URL、メール、電話、数値、パスワード、`TYPE_NULL`
- 入力中の回転、アプリ切替、選択変更、候補確定、最後の1文字のBackspace
- Mozc単独、Karukan成功・失敗・モデルなし・低RAM端末・推論中の追加入力

## 配布・法務・プライバシー

- Play App Signing用の鍵はリポジトリ外で管理し、正式版AABへ署名する
- Play ConsoleのData safetyと公開プライバシーポリシーを同じ内容にする
- Mozc、Karukan、llama.cpp、tokenizers、辞書、NDKランタイムの表示を実APKで開いて確認する
- Karukanモデル取得先、SHA-256、サイズ、削除方法がREADMEとアプリ表示で一致する
- デバッグAPKを正式版として配布しない

## 現時点で人手確認が必要な項目

- Android Keystore暗号化データのアップグレード後・再起動後の復号
- OEM独自のキーボード切替領域とフローティング／分割キーボード挙動
- arm64-v8a実機でのKarukan品質、発熱、ピークメモリ、推論キャンセル
- Play Consoleのpre-launch report、アクセシビリティ、クラッシュ／ANR

## rc.33-dev の作成条件

2026年11月4日の利用者の指定「精製したら検証は省いてapkにしよう」により、
今回は追加の単体テスト・lint・Release/AAB/ABI別ビルド・実機マトリクスを省略し、
ユニバーサルdebug署名APK（`me.zssu.ime.debug`）を作成した。
APKのGradleビルド以外の最終版合格は主張しない。上記の正式版受け入れ基準を
満たした扱いにはしない。追加システム辞書の切り替え・再起動・復帰・変換品質は未検証。
詳細と全件処理の未対応行は`docs/extra-dictionaries/`と配布ソースに記録する。
