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

## Linux（M0）

LinuxフロントエンドはAndroid版とは別の成果物です。M0では次を満たすことだけを確認し、fcitx5/IBus/OSKの利用者向け品質は主張しません。

- `./scripts/build_mozc_linux.sh` が成功し、`libkomorebi_mozc.so` と `mozc.data` が生成される
- `cargo test -p komorebi-core` が全件成功する
- `cargo run -p komorebictl -- convert きょうはいいてんき` がMozc候補と`今日はいい天気`のpreeditを返す
- `cargo run -p komorebictl -- convert きょうはいいてんき --commit` で確定でき、`~/.local/share/komorebi/mozc/.history.db` が平文の確定文字列を含まない
- `~/.local/share/komorebi/key` が32バイト・0600で作られ、再起動後も同じ学習が読める
- 追加パッチ `0008` / `0009` が冪等に適用され、Androidビルドの挙動を変えない
- fcitx5アドオン、IBus、OSK、ニューラル変換、辞書管理UI、Android移行は未実装と明記する

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

## rc.42-dev の作成条件

Mozcの専用直列ワーカー化と、入力経路・候補表示の重複処理削減を実施。QWERTY／Godan／単純な数値パッドでフリック連打用コマンドを
送らず、フリックの連打・修飾キー・トグル入力の分岐を単体テストへ追加。
非同期キューの順序・古い結果破棄・UI非ブロック性・解放の単体テストを追加。
AGENTS.mdの自動ゲートを低負荷で実行。単体テスト277件（app 275、karukan 2）成功、
debug／releaseのlintエラー0件。通常APK・AABと、別呼び出しのABI別APKを生成。
全10 ABI別APKのID・versionCode 66・versionName、全release APKの16 KB配置、
Mozc・arm64 Karukan／C++・辞書・法務文書、R8 mappingとDEXのJNI名を確認。
プライバシーとNOTICEの公開／アプリ内版は一致。公開APKは署名検証成功、rc.41と同一証明書。
キューの13件は模擬処理と実ワーカーで検証。実際のAndroid JNI・入力先連携を確認したものではない。
SHA-256を添付する。
実機・エミュレータは利用できないため、API別マトリクス、入力遅延、FPS、メモリ、
連打・2行候補・ツールバー・入力先ごとの互換性は未確認。性能向上率は主張しない。
公開対象は`.debug`のテスト用ユニバーサルAPKであり、未署名release成果物は公開しない。

## rc.43-dev の作成条件

変換キー1回で候補が開いた直後の確定を文全体の確定に変更。文節移動・候補選択・二回目の変換・
候補一覧展開・入力編集の後は従来の文節確定へ戻る。`WholeSentenceConfirm` と3件の単体テストを追加。
AGENTS.mdの自動ゲートを低負荷で実行。単体テスト280件（app 278、karukan 2）成功、
debug／releaseのlintエラー0件。通常debug／release APK・AABと、別呼び出しのABI別APKを生成。
全10 ABI別APKのID・versionCode 67・versionName、全release APKの16 KB配置とzipalign、
Mozc 4 ABI・arm64 Karukan／C++・辞書・法務文書、R8後DEXのJNI名を確認。
プライバシーとNOTICEの公開／アプリ内版は一致。公開APKはAndroid Debug証明書で署名検証成功。
実機・エミュレータは利用できないため、変換操作とAPI 24 / 29 / 34 / 35 / 36のマトリクスは未実施。
公開対象は`.debug`のテスト用ユニバーサルAPKであり、未署名release成果物は公開しない。

## rc.44-dev の作成条件

Meets Beam に Wi-Fi Direct（P2P）の大容量転送、ファイル共有、TSV辞書共有を追加。
NFCでグループオーナーの端末アドレス・IP・一時トークンを交換し、グループオーナーIPへだけ接続。
AES-GCM、宣言サイズ、SHA-256、ファイル名サニタイズ、辞書取り込み前の確認を追加。
`BeamHandoverTest` 8件を追加し、AGENTS.mdの自動ゲートを低負荷で実行。
単体テスト288件（app 286、karukan 2）成功、debug／releaseのlintエラー0件。
通常debug／release APK・AABと、別呼び出しのABI別APKを生成。全10 ABI別APKのID・versionCode 68・
versionName、全release APKの16 KB配置とzipalign、Mozc 4 ABI・arm64 Karukan／C++・辞書・
法務文書・FileProvider、R8後DEXのJNI名を確認。プライバシーとNOTICEの公開／アプリ内版は一致。
公開APKはAndroid Debug証明書で署名検証成功。
**Wi-Fi Directの実機2台接続・転送は未実施**。API 24 / 29 / 34 / 35 / 36のマトリクス、
大容量ファイル、画面消灯・バックグラウンド、共有元アプリごとの互換性は未確認。
公開対象は`.debug`のテスト用ユニバーサルAPKであり、未署名release成果物は公開しない。

## rc.45-dev の作成条件

rc.44のWi-Fi Direct接続失敗を受け、全Androidで位置情報モードを確認、NFC前の受信サーバーバインドと
空きポート通知、グループオーナー端末アドレスの再試行とプレースホルダーMAC拒否、p2pインターフェース
IP走査、受信側の接続リトライ・NetworkInfo確認・タイムアウト延長を追加。共有元サイズの
AssetFileDescriptor補完と段階別の状態表示も追加。
単体テスト289件（app 287、karukan 2）成功、debug／releaseのlintエラー0件。通常APK・AABとABI別APKを生成し、
versionCode 69、全release APKの16 KB配置とzipalign、Mozc 4 ABI・Karukan・辞書・法務文書・FileProvider、
R8後DEXのJNI名を確認。PRIVACY／NOTICEの公開・アプリ内版は一致。公開APKの署名検証成功。
**Wi-Fi Directの実機2台接続・転送は修正後も未実施**。位置情報モード、バインド順、アドレス取得、
接続リトライを修正したが、端末固有のP2P実装差は未確認。rc.44で失敗した端末での再確認が必要。
公開対象は`.debug`のテスト用ユニバーサルAPKであり、未署名release成果物は公開しない。

## rc.46-dev の作成条件

rc.45で「Wi-Fi Directの端末アドレスを取得できません」と表示される端末向けに、端末アドレスなしの
ハンドオフを許可し、端末名と単一ピアによる接続フォールバックを追加。送信側はアドレス取得失敗で
停止せず、`ownerName`をNFC情報へ載せる。受信側はアドレス→端末名→検出ピアが1台のみ、の順で照合する。
AES-GCMトークン認証は維持。単体テスト290件（app 288、karukan 2）成功、debug／releaseのlintエラー0件。
通常APK・AABとABI別APKを生成し、versionCode 70、16 KB配置、zipalign、Mozc 4 ABI・Karukan・辞書・
法務文書・FileProvider、R8後JNI名、PRIVACY／NOTICE一致、署名を確認。権限の変更なし。
**Wi-Fi Directの実機2台接続・転送は修正後も未実施**。rc.45で失敗した端末での再確認が必要。
公開対象は`.debug`のテスト用ユニバーサルAPKであり、未署名release成果物は公開しない。

## rc.47-dev の作成条件

rc.44〜rc.46の実機失敗を受け、Wi-Fi Directを送信側`createGroup`固定から標準のグループ交渉へ変更。
受信側が検出した相手へ`connect`し、グループオーナーはフレームワークが自動決定する。GOが受信サーバーを
開き、クライアントが49152〜49161を探索する対称構成とし、サーバー送信・サーバー受信・クライアント送信・
クライアント受信の4経路を実装。接続待ちは180秒。単体テスト291件（app 289、karukan 2）成功、
debug／releaseのlintエラー0件。通常APK・AABとABI別APKを生成し、versionCode 71、16 KB配置、zipalign、
Mozc 4 ABI・Karukan・辞書・法務文書、R8後JNI名、PRIVACY／NOTICE一致、署名を確認。権限の変更なし。
**Wi-Fi Directの実機2台接続・転送は変更後も未実施**。今回は接続方式自体を変えたため、実機での再確認が必須。
公開対象は`.debug`のテスト用ユニバーサルAPKであり、未署名release成果物は公開しない。

## rc.48-dev の作成条件

変換キー2回目で候補が1つ先へ進む動作を修正。1回目は文節分割と先頭候補の仮変換、2回目は候補選択段階へ
移るだけで候補を進めず、3回目以降で候補を切り替える。1回目直後の確定は文全体、2回目以降の確定は
選択中の文節を確定して次へ進む。未操作の1回目直後だけ2回目を候補選択開始として扱う判定を
`WholeSentenceConfirm.beginsCandidateSelection` に分離。文節移動・候補選択・編集後は従来の候補移動へ戻る。
単体テスト292件（app 290、karukan 2）成功、debug／releaseのlintエラー0件。通常APK・AABとABI別APKを生成し、
versionCode 72、16 KB配置、zipalign、Mozc 4 ABI・Karukan・辞書・法務文書、R8後JNI名、PRIVACY／NOTICE一致、
署名を確認。権限の変更なし。実機でのキー操作は未確認。Wi-Fi Directの実機2台接続・転送も未確認。
公開対象は`.debug`のテスト用ユニバーサルAPKであり、未署名release成果物は公開しない。

## rc.49-dev の作成条件

設定ホームをLiquid Glassの考え方で再構成。ガラスは上部バー・クイック操作バー・タブバーに限定し、
設定カードは不透明なコンテンツ面を維持。5タブ（ホーム・入力・変換・データ・情報）、全設定の横断検索、
クイック操作バー、フローティングタブバー、キーボード状態表示、カテゴリショートカット、コントラスト切替、
Edge-to-edge、設定専用の明暗パレットを追加。既存の詳細編集画面はそのまま開く。
設定カタログの検索・タブ絞り込み・埋め込みバックアップ除外・featured・全タブ到達先を単体テスト。
単体テスト297件（app 295、karukan 2）成功、debug／releaseのlintエラー0件。通常APK・AABとABI別APKを生成し、
versionCode 73、16 KB配置、zipalign、Mozc 4 ABI・Karukan・辞書・法務文書、R8後JNI名、PRIVACY／NOTICE一致、
署名を確認。権限の変更なし。**実機・エミュレータでの見た目、Dark mode、インセット、タブ・検索操作は未確認**。
公開対象は`.debug`のテスト用ユニバーサルAPKであり、未署名release成果物は公開しない。

## rc.50-dev の作成条件

設定ホームを4カテゴリの一覧に変更。既存の詳細画面と横断検索を維持し、
検索結果から戻ったときの検索語を保持する。「バックアップと移行」へ一覧・検索から直接進める。
単体テスト296件（app 294、karukan 2）成功、debug／releaseのlintエラー0件。
実機・エミュレータは利用できず、表示・インセット・検索と戻る操作、端末別の入力は未確認。
公開対象は `me.zssu.ime.debug`（versionCode 74）のテスト用ユニバーサルAPK。
通常APK・AABとABI別APKを作成し、版情報、16 KBのzipalign、ABI・辞書・法務文書、
R8後のJNI名を確認。公開APKの署名証明書はrc.49と一致。権限の変更なし。

## rc.51-dev の作成条件

背景プリセット13種、追加の完成テーマ9種、キーごとの枠・画像・文字、写真のスライドショーと
無音動画、フリックガイド、キー音・入力エフェクトを追加。単体テスト301件（app 299、karukan 2）成功、
debug／releaseのlintエラー0件。Android実機・エミュレータは利用できず、表示・動画再生・キー音・
端末別の入力は未確認。公開対象は `me.zssu.ime.debug`（versionCode 75）のテスト用ユニバーサルAPK。
通常APK・AABとABI別APKを作成し、版情報、16 KBのzipalign、ABI・辞書・法務文書、
R8後のJNI名、素材18点とテーマ9種を照合。公開APKの署名証明書と権限はrc.50と同じ。

## rc.52-dev の作成条件

設定の独立項目「きせかえ」に、テーマ・配列プリセット、背景、キーの形・色・演出を集約。
プレビューを固定し、試し打ちやフリック／QWERTYの切り替えに対応。JSON入出力は詳細に移設。
単体テスト302件（app 300、karukan 2）成功、debug／releaseのlintエラー0件。
実機・エミュレータは利用できず、縦横の表示、試し打ち、動画再生、端末別の入力は未確認。
公開対象は `me.zssu.ime.debug`（versionCode 76）のテスト用ユニバーサルAPK。
通常APK・AABとABI別APKを作成し、版情報、16 KBのzipalign、ABI・辞書・法務文書、
R8後のJNI名、画像18点とテーマ9種を照合。公開APKの署名証明書と権限はrc.51と同じ。

## rc.53-dev の作成条件

小・濁点キーを文字キーの見た目に変更。自動テスト・debug／release Lint・通常APK／AAB・ABI別APKを確認。実機確認は未実施。公開対象はdebug署名の `me.zssu.ime.debug`（versionCode 77）。

## rc.54-dev の作成条件

きせかえ画面を画像の配置に合わせて変更し、選択中の実際のIMEを開閉して試し入力できるようにした。Komorebi IME選択時は編集内容を一時反映。単体テスト303件、debug／release Lintエラー0件、APK・AAB・ABI別APKを確認。実機・エミュレータでのIME連携、表示と操作は未確認。公開APKはdebug署名（versionCode 78）。

## rc.55-dev の作成条件

IME表示中にきせかえ画面が隠れる問題を修正。単体テスト303件、debug／release Lintエラー0件、APK・AAB・ABI別APKを確認。実機・エミュレータでのIME表示と画面操作は未確認。公開APKはdebug署名（versionCode 79）。

## rc.56-dev の作成条件

初期設定、入力ガイド、変換ときせかえの案内を改善。単体テスト303件、debug／release Lintエラー0件、APK・AAB・ABI別APKを確認。実機・エミュレータでの表示と操作は未確認。公開APKはdebug署名（versionCode 80）。
