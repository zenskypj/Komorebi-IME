# 詳細仕様

この章は、ふだん使ううえでは知らなくても困らない**細かい仕様と制約**をまとめた付録です。各章の「補足」はここへ集約し、必要な箇所から参照します。

## 技術仕様

| 項目 | 値 |
| --- | --- |
| application ID（正式） | `me.zssu.ime` |
| application ID（debug） | `me.zssu.ime.debug` |
| minSdk / targetSdk | 24 / 36 |
| ネイティブ | Mozc（BSD 3-Clause）を組み込み。`libmozc.so`（4 ABI） |
| 変換基盤 | Kotlin ラッパー（`MozcEngine` / `MozcSession`）経由で JNI を扱う |
| 入力方式 | かな/英字フリック、かな/英字 QWERTY、混在、かなだけ QWERTY |
| 権限 | `INTERNET`（任意取得時のみ） |
| クラウドバックアップ | 無効 |

## ネイティブへの適用パッチ

上流 Mozc への変更は `patches/` に分離し、ビルド前に適用します。

| パッチ | 内容 |
| --- | --- |
| `0001-rule-based-typing-correction.patch` | OSS 版 Mozc の補正フックへ軽量なルールベース補正 |
| `0002-android-keystore-profile-encryption.patch` | 履歴・ユーザー辞書を Keystore 由来の鍵で暗号化 |
| `0003-trim-jni-output.patch` | JNI へ返す候補を上位 32 件に絞る |
| `dictionary-generation/0001-relax-value-trie-id-limit.patch` | 追加辞書の生成専用。value-trie id 上限を 22bit→24bit へ緩和 |

> [!NOTE]
> `dictionary-generation` のパッチは**追加辞書の生成時だけ**隔離 checkout へ適用します。アプリ同梱の libmozc には適用しないため、ランタイム指紋は変わりません。

## 追加辞書の生成仕様

| 項目 | 内容 |
| --- | --- |
| 固定ソース | Mozc `3f235b4` / NEologd `abc61e33` / SKK `0a164e6b` |
| 出力 | 1 データセット = 1 つの完全な Mozc データ（標準 Mozc を含む） |
| 語彙数 | NEologd 統合 5,419,330 行 / SKK 統合 591,262 行 / 全部入り 5,963,293 行 |
| 統合 | ソース間の重複を `(reading, lid, surface)` の最小コストで統合 |
| 形式 | gzip（level 9, mtime=0）。アプリ側は展開後に magic と SHA-256 を検証 |

## 同梱固有名詞の仕様（rc.38）

| 項目 | 内容 |
| --- | --- |
| 有効・無効 | 「変換エンジン・辞書」→「変換辞書の切り替え」→「有名固有名詞」 |
| 追加取得 | 不要。APKに同梱 |
| 元データ | 既存532行（読み・表記で517語）＋提供JSONL4,468行 |
| 重複整理 | 既存側15行、追加分との重複106語 |
| 合併後 | 4,879語（純増4,362語） |
| 品詞 | 人名1,408／地名809／組織751／一般の固有名詞1,911 |
| 品詞の具体化 | 既存の一般的な固有名詞94語を、人名・地名・組織へ更新 |
| 正規化 | 読みはNFKC・ひらがな、表記はNFC。読み・表記の組で重複整理 |
| 取り込み | 同じ同梱辞書名で更新。GUIユーザー辞書・学習履歴は保持。無効設定は維持 |
| 元のcost | 2,000〜7,000。原本に保持し、既存の3列TSV取り込みでは順位コストに使用しない |
| 再生成 | `python3 scripts/merge_bundled_proper_nouns.py` |

原本・生成件数・ハッシュは[統合記録](https://github.com/zenskypj/Komorebi-IME/tree/main/data/dictionaries)に保存しています。
この辞書は任意取得のNEologd・SKKデータセットとは別です。
rc.38は利用者指定により追加テスト・lint・実機確認を省略しており、語の候補順位は未確認です。

## 保存ファイル

| パス | 内容 | 暗号化 |
| --- | --- | --- |
| `files/user_dictionary.enc` | GUI ユーザー辞書 | AES-GCM + Keystore |
| `files/mozc/user_dictionary.db` | Mozc ユーザー辞書 | Keystore 由来の鍵 |
| `files/mozc/.history.db` | 変換学習履歴 | Keystore 由来の鍵 |
| `files/priority_candidates.enc` | 最優先候補（★） | AES-GCM + Keystore |
| `files/mozc_profile_key.enc` | Mozc プロファイル鍵 | Keystore |
| `noBackupFilesDir/system-dictionaries/*.data` | 追加システム辞書 | なし（バックアップ対象外領域） |
| `noBackupFilesDir/...`（モデル） | AI モデル | なし（バックアップ対象外領域） |

## 既知の制約

| 制約 | 内容 |
| --- | --- |
| AI 変換 | 現在 `arm64-v8a` のみ。品質は保証せず、文節操作は Mozc を維持 |
| AI 文章校正 | `arm64-v8a` のみ。品質は保証せず、差分確認が前提 |
| 意味辞書 | 候補文字列との完全一致。活用形の推定・意味の自動生成はしない |
| 視覚エディタ | フリック方向や特殊アクションの全項目編集には JSON エディタが必要 |
| Mozc 暗号化ストレージ | 64 MB を超えるファイルは読み込めない |
| 追加システム辞書 | 同時に有効化できるのは 1 データセット。統合データは内部に複数辞書を含む |
| テスト用 APK | すべて debug 署名の pre-release。正式版とは application ID が異なる |
| RC 中 | 設定形式・内部ファイル形式が変更される可能性がある |

## 補足：この文書での「補足」の扱い

このマニュアルでは、補足・制約を次の 3 層に分けて扱います。

| 層 | 置き場所 | 用途 |
| --- | --- | --- |
| 局所的な注意 | 各章のコールアウト（NOTE / TIP / IMPORTANT / WARNING） | その操作の直前に知るべき前提・落とし穴 |
| 横断的な仕様 | この「詳細仕様」章 | 複数の章から参照される数値・制約・実装 |
| 変更履歴 | [リリースノート](https://github.com/zenskypj/Komorebi-IME/tree/main/docs/releases) | 版ごとの追加・修正 |

原則:

1. **must（守らないと困る）と nice（知ると得）を分ける**。must は IMPORTANT / WARNING、nice は NOTE / TIP。
2. **1 か所に書いて、他から参照する**。同じ注意を各所へ複製しない（重複は必ず食い違う）。
3. **アンカーで指せる**。見出しは安定した ID を持ち、他章から `#既知の制約` のように参照できるようにする。
4. **版で錆びる数値は出典へリンクする**。サイズ・SHA-256・語彙数はリリースのカタログ／報告を正とする。

## リポジトリ構成

```text
app/              InputMethodService、キーボード UI、設定画面
mozc/             JNI シム、Kotlin ラッパー、生成 protobuf、ネイティブ成果物
karukan/          Karukan ラッパー、arm64-v8a ネイティブ成果物
patches/          Mozc に適用するパッチ
scripts/          Mozc ビルド、配列生成、辞書取得・生成
docs/             設計・検証・このマニュアル
third_party/      上流 checkout（Mozc は Git 管理対象外）
```

## ライセンス

本ソフトウェアは Apache License 2.0 で提供される [soichi11208/Zinna-IME](https://github.com/soichi11208/Zinna-IME) の派生物で、ZenSky Project による変更部分も Apache License 2.0 の条件で提供します。

| 構成要素 | ライセンス |
| --- | --- |
| 本体（Zinna-IME 派生物） | Apache-2.0 |
| Mozc | BSD 3-Clause |
| Karukan | MIT（および Apache-2.0 を含む） |
| llama.cpp / GGML（zenzai / 校正） | MIT |
| カタカナ英語辞書 | CC BY-SA 3.0 |
| NEologd | Apache-2.0 |
| SKK 辞書 | GPL-2.0 / CC BY-SA 3.0 / Unicode License / PD ほか |
| zenzai-plus モデル | CC BY-SA 4.0 |

完全な帰属表示は [NOTICE](https://github.com/zenskypj/Komorebi-IME/blob/main/NOTICE) を参照してください。設定画面の「オープンソースライセンス」からも全文を閲覧できます。
