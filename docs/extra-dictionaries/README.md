# 追加システム辞書（rc.33-dev 試験版）

初期状態はAPK同梱の標準Mozcです。「変換エンジン・辞書」で追加データを明示取得し、
「使用する」を選びます。入力欄を離れて未確定文字列がなくなってから切り替えます。
キーボードを隠しただけで入力セッションが続いている場合は待機します。
次の入力でも待機していた場合、入力終了後に再度適用します。

追加語もシステム辞書として文章変換へ参加します。大量データを個人辞書へ投入しません。
Karukan・zenzai-plusのモデル自体に語彙を追加する機能ではありません。
Mozc側の候補生成とフォールバックで使用されます。

## 提供単位と全件処理

利用者の指定は「NEologd全seed＋SKK個別辞書を含む公式配布一式」です。
単体6種に加え、複数の辞書をまとめて有効化できる統合データセット
（NEologd統合／SKK統合／全部入り）を提供します。画面のチェックで複数を選ぶと、
選択したすべての辞書を含む最小の統合データセットが使われます。Mozcへ読み込ませるのは
常に1データセットで、その中に複数の辞書が含まれます。

| データセット | 元データ | 条件 |
| --- | --- | --- |
| NEologd 新語・固有名詞 | mecab-user-dict-seed 全行 | Apache-2.0、原典の帰属表示を保持 |
| NEologd 表記ゆれ・活用語 | その他11 seed 全行 | 同上 |
| SKK 総合・人名・地名・専門語 | GPL系公式個別辞書 | 原典のGPL条件を保持 |
| SKK 英和 | edict・edict2 | CC BY-SA 3.0 |
| SKK 絵文字・異体字 | emoji・ivd | Unicode License |
| SKK 沖縄・郵便番号・異体字・pubdic+ | okinawa・zipcode・office.zipcode・itaiji・pubdic+ | PD / Pubdic+原条件 |

NEologd全12 seedは5,572,307行です。最初に全件を一つへ統合した生成では、
Mozcのword trieの22bit制限（2^22未満）に対して4,280,389となり生成が停止しました。
語を上限件数で切り捨てず、原典ファイル群を2つの完全データセットに分けていました。
統合データセットは、生成側だけに22bit→24bit緩和パッチ
（`patches/dictionary-generation/0001-relax-value-trie-id-limit.patch`）を当て、
crammedでない形式が元々使える3バイト（24bit）まで格納できるようにして生成します。
出力形式は変えないため、アプリ同梱のlibmozcは無改修で読み出せます（互換指紋も不変）。
統合後の追加語彙の異なり数は2^24未満に収まります。各データセットには標準Mozcも含まれます。

SKKは固定コミットにある全`SKK-JISYO.*`と郵便番号2辞書を処理します。
`.header`は辞書のメタ情報として保持し、独立した語彙としては重複処理しません。
wrong/wrong.annotated/noregistは公式の検査・登録抑止用資料として取得・件数記録し、
通常候補にはしません。L.unannotated、S/M/MLなどの重複は整理します。

「全件処理」は全行を変換候補にできたという意味ではありません。
SKKの動的Lisp、数値テンプレート、未解決の送り仮名・活用は有限のMozc語彙にそのまま
置けないため、ファイル・行番号・候補・理由を`unresolved.jsonl`に保持します。
Lispを実行したり、送り仮名ありの語を一律名詞へ置換することはしません。
定数concatの文字列だけを安全に復元します。既知の活用は標準Mozcの語幹・活用規則を使用します。
品詞のない送り仮名なしの静的語は名詞を基本にし、人名・地名・記号は辞書の種類に応じたIDを使います。
これが全語の最適な品詞・順位を保証するものではありません。

NEologdはMeCabの数値IDを流用せず、品詞・活用をMozcの`id.def`へ対応付けます。
文語形容詞の共有接続、イイ型の接続など、表現の違いは生成報告へ記録します。
コストの初期式は `9000 + floor(clamp(MeCabCost, -6000, 12000) / 4)`。
SKKは `min(12500, 9500 + 原典内候補順 * 40)`。読み・表記が標準辞書と同じ語は
標準の分析・コストを維持します。派生語や同音語の順位が改善するとの検証はしていません。

## 安全な取得と適用

- 標準データ、追加データ、個人プロフィールを別ファイルとして保持。
- HTTPS、圧縮前後の上限サイズ、SHA-256、Mozc magic、アプリ同梱ランタイムの指紋を確認。
- 一時ファイルへ取得・展開してfsyncし、確認後にrename。未完了ファイルは再起動時に除去。
- 既存セッションを保存・同期し、入力欄も未確定文字列もない間だけ再読み込み。
- `ACCEPTED`は受付のみ。`CREATE_SESSION`での実際の`RELOADED`とデータバージョンで適用確認。
- 新しい入力が始まったらポーリングを終了し、現在の変換を継続。次の入力終了時に再試行。
- セッション世代が変わったら入力方式・設定を再適用。
- 不整合・互換性変更・適用失敗では標準を要求。削除はそのファイルが使用中でなくなってから。
- 入力・周辺文脈・個人辞書・学習履歴を取得先へ送らない。

同時に有効化できるのは1つの統合データセットで、その中に複数の辞書を含められます。
共存する導入済みデータ、圧縮ファイル、展開中ファイルのために追加容量が必要です。画面には取得・保存サイズと導入時の追加必要容量を表示します。
再配布物の正確なサイズ・ハッシュはAPKの`system-dictionaries.json`と配布カタログを参照してください。

## 再生成

固定ソース:

- Mozc: `3f235b4eb6fcff7d14ef5f0fb8ee56de7ee4c732`
- NEologd: `abc61e33d8be3d0ead202e6b1df064c72d5ccf11`
- SKK: `0a164e6b990c5eb5b59eb7d8789f08865dc2f644`

Mozcの標準データをこの固定ソースから生成したSHA-256はAPK同梱版と一致しました:
`fbb9a23419d677a33968145cfe1979dac6e7d3c28712d469854b4707a5965ed1`。
既存`scripts/build_mozc.sh`の別のpinを、そのまま追加辞書へ使うことはしません。
標準データまたはnativeライブラリを変更した場合は、互換性の基準を再確立して追加データも再生成します。

隔離した`build/extra-dictionary/checkout`へMozcの固定コミットをarchiveで展開し、
`src`でBazelisk 9.0.2の`--config oss_linux --config release_build`により標準データを生成します。
標準データの一致を確認した後、リポジトリのルートから実行します。
`build_packs.py`は実行前に`patches/dictionary-generation/*.patch`をその隔離checkoutへ
冪等適用します（`third_party/mozc`には適用しないため、アプリのlibmozcと互換指紋は変わりません）。

```bash
python3 scripts/extra_dictionary/fetch_sources.py --output build/extra-dictionary/sources --lock docs/extra-dictionaries/sources.lock.json
python3 scripts/extra_dictionary/convert_neologd.py --sources build/extra-dictionary/sources --mozc build/extra-dictionary/checkout/src --output build/extra-dictionary/neologd-main --group main
python3 scripts/extra_dictionary/convert_neologd.py --sources build/extra-dictionary/sources --mozc build/extra-dictionary/checkout/src --output build/extra-dictionary/neologd-variants --group variants
python3 scripts/extra_dictionary/convert_skk.py --sources build/extra-dictionary/sources --mozc build/extra-dictionary/checkout/src --output build/extra-dictionary/skk
python3 scripts/extra_dictionary/build_packs.py
```

GPL-2・CC BY-SA 3.0の本文は`scripts/extra_dictionary/licenses/`に保持しています。
再配布用ソース・変換報告・原典の帰属表示は辞書データReleaseへ添付します。
ソース取得は固定コミットのGit blob ID、サイズ、SHA-256を記録し、既存lockとの差異があれば停止します。
`report.json`には元件数、不正・重複・未解決・変換後件数、サイズと生成時間を残します。
Bazel常駐プロセスのピークメモリは未計測です。

## 今回の検証範囲

利用者の指定により、精製後の追加品質比較・自動テスト・lint・実機検証は省略し、APKを作成します。
新しい初回配列選択とパネル幅の変更の初期段階では単体テストとlintを通過しましたが、
その後の辞書取得・JNI切り替えを含む最終版の合格を意味しません。
端末は提供できないとの指定もあり、切り替え、再起動、標準復帰、速度・メモリ、
通常語・固有名詞・活用・複数文節の変換品質は未確認です。正式版としての動作保証はしません。
