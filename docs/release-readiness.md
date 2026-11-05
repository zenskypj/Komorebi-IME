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

## rc.37-dev の作成条件

利用者の「精製したら検証は省いてapkにしよう」を継続し、今回も追加テスト・lint・
Release/AAB/ABI別ビルド・実機確認を省略。ユニバーサルdebug署名APKのみ作成する。
rc.36までの別AI更新を土台とし、正式版の受け入れ基準を満たしたとは扱わない。

## rc.38-dev の作成条件

利用者提供の固有名詞JSONLを既存同梱辞書へ統合。前の「検証は省いてapk」の指定を
継続し、追加テスト・lint・Release/AAB/ABI別ビルド・実機確認は省略。
精製後にユニバーサルdebug署名APKを作成し、正式版の検証済みとは扱わない。

## rc.39-dev の作成条件

フリック削除キーの単語／論理行削除を追加。前の指定を継続し、追加テスト・lint・
Release/AAB/ABI別ビルド・実機確認は省略。ユニバーサルdebug署名APKだけ作成する。
実機での削除範囲、方向・距離の操作感、長押しとの排他、エディタ互換性は未確認。
関連docsとアプリ内ガイドを実装とともに更新する。

## rc.40-dev の作成条件

更新されたAGENTS.mdに従い、JDK 21・Gradle worker 1・ヒープ上限2 GiBで自動ゲートを実行。
前方一致候補の段階的な昇格、英語辞書の除外、完全一致登録と未完成の読みの区別、
AI・★指定との優先関係を単体テストへ追加した。

単体テスト255件（app 253、karukan 2）成功、debug／releaseのlintエラー0件。
通常debug／release APK・AABと、別呼び出しのABI別APK（各4 ABI＋universal）を生成した。
全10 ABI別APKのID・versionCode 64・versionName、全release APKの16 KB配置、
Mozc・arm64 Karukan／C++・辞書・法務文書を確認。プライバシーとNOTICEの公開／アプリ内版は一致。
R8 mappingとrelease DEXを併用し、MozcJNI／KarukanNativeのクラス名・全nativeメソッド名を確認した。
公開用の通常ユニバーサルdebug APKも内容・署名を確認し、SHA-256を添付する。

端末を用意できない旨の利用者回答により、
API 24 / 29 / 34 / 35 / 36の実機・エミュレータマトリクスは引き続き未実施。
候補順位・入力遅延・入力先ごとの互換性を実機で検証済みとは扱わず、正式版合格も主張しない。
公開するのはユニバーサルdebug署名APK（`me.zssu.ime.debug`）のみであり、
未署名release APKとAABは配布しない。

## rc.41-dev の作成条件

英語辞書の完全一致候補の表示順を修正。内蔵・追加辞書相当のNative候補、旧ユーザー辞書属性、
全角英字、かな表記の補充、辞書完全一致優先での再昇格、★と個人登録の例外、
初回変換で元のNative IDを選択する経路を単体テストで確認する。
低負荷設定でAGENTS.mdの自動ゲートを実施。単体テスト262件（app 260、karukan 2）成功、
debug／releaseのlintエラー0件。通常APK・AABと別呼び出しのABI別APKを生成した。
全10 ABI別APKのID・versionCode 65・versionName、全release APKの16 KB配置、
Mozc・arm64 Karukan／C++・辞書・法務文書、プライバシーとNOTICEの一致を確認。
R8 mappingとrelease DEXでJNI名を確認。公開APKは署名検証成功、rc.40と同一証明書。
SHA-256を添付する。
実機・エミュレータは用意できないためAPI 24 / 29 / 34 / 35 / 36のマトリクスは未実施。
未署名release成果物は公開せず、`.debug`のテスト用ユニバーサルAPKを配布する。
