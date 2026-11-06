# Linux版のビルド（M0）

Linuxフロントエンドは `linux/` のRustワークスペースと、`third_party/mozc` に当てる2つの追加パッチで構成します。現在は **M0** です。パッチ済みMozcをLinux共有ライブラリとしてビルドし、Rustコアから `convert` できるところまでで、fcitx5アドオンはまだありません。

## 構成

| パス | 内容 |
| --- | --- |
| `linux/crates/komorebi-mozc` | MozcのC ABIバインディング、prostによるプロトコル生成 |
| `linux/crates/komorebi-core` | エンジン起動、プロファイル鍵、セッション、読み→候補 |
| `linux/crates/komorebictl` | CLI（`convert`, `data-version`） |
| `patches/0008-linux-engine-bridge.patch` | `SessionHandler` をC ABIで公開する `//linux:komorebi_mozc` |
| `patches/0009-linux-injected-password.patch` | 外部から注入した32バイト鍵を使うパスワード管理 |

## 必要なもの

- Linux x86_64（M0の確認範囲）
- Rust 1.92 以上
- Python 3.12 以上
- Bazelisk または Bazel
- C++ツールチェーン（Bazelが使うもの）
- `git`, `unzip`

Android SDK / NDKは不要です。Mozcのホストツールを `--config oss_linux` でビルドします。

## ビルド

```bash
# Mozcのcheckout（未取得の場合）
git clone --no-checkout https://github.com/google/mozc.git third_party/mozc
git -C third_party/mozc checkout --detach 2fc7403f231fc201e9d7bdce5fc6f5bca7f534b8

# 追加パッチ適用、libkomorebi_mozc.so と mozc.data の生成、アセット配置
./scripts/build_mozc_linux.sh
```

このリポジトリの作業環境では、次のローカル環境を用意済みです。スクリプトは自動でこれらを優先します。

```text
tools/bazelisk       Bazelisk 1.29（Mozcの .bazeliskrc により Bazel 9.0.2 を使用）
tools/mozc-linux     2fc7403 のshallow clone
tools/bazel-root     Bazelの出力ルート（tools/bazel-root がある場合の既定）
```

別の場所を使う場合:

```bash
MOZC_DIR=/path/to/mozc BAZEL=/path/to/bazelisk ./scripts/build_mozc_linux.sh
# リソースを絞る場合
BAZEL_ARGS="--jobs=2" ./scripts/build_mozc_linux.sh
# Bazelの出力先を分ける場合
BAZEL_STARTUP_ARGS="--output_user_root=/path/to/bazel-root" ./scripts/build_mozc_linux.sh
```

出力:

```text
linux/bridge/lib/libkomorebi_mozc.so
linux/bridge/include/komorebi_mozc.h
linux/data/mozc.data
linux/data/runtime.sha256
linux/data/{layouts,themes,meaning_dictionaries,unicode,romaji.tsv,...}
```

`third_party/mozc` はGit管理対象外です。Mozcのリビジョンは `2fc7403f231fc201e9d7bdce5fc6f5bca7f534b8` に固定しています。別のcheckoutを使う場合は `MOZC_DIR=/path/to/mozc ./scripts/build_mozc_linux.sh` のように指定してください。`third_party/mozc` が別リビジョンのままだとスクリプトは停止します。

## テストと実行

```bash
cd linux
cargo test -p komorebi-core
cargo run -p komorebictl -- data-version
cargo run -p komorebictl -- convert きょうはいいてんき
cargo run -p komorebictl -- convert きょうはいいてんき --commit
```

`convert` は最初の文節の候補と、変換後のpreedit全体を表示します。`--commit` を付けると確定も行い、Mozcの学習履歴を更新します。

`libkomorebi_mozc.so` が無い状態でも、エンジンを使わないユニットテストは通ります。エンジンを使うバイナリのリンクには上記の共有ライブラリが必要です。別の場所に置いた場合は `KOMOREBI_MOZC_DIR` でディレクトリを指定します。

## 保存場所

```text
~/.local/share/komorebi/mozc/    # Mozcプロファイル（学習・ユーザー辞書）
~/.local/share/komorebi/key      # Mozcプロファイル鍵（32バイト、0600）
```

鍵は初回起動時に `/dev/urandom` から生成します。Mozcの履歴とユーザー辞書は、この鍵を注入した `EncryptedStringStorage` で保存されます。Android版の `.zinnabak` からの移行はM5で実装します。

## 現在の制限

- fcitx5 / IBus / OSKのフロントエンドは未実装です。
- デスクトップ向けのローマ字補正プロファイルは未実装です（既存の補正はフリック12キー前提のまま）。
- Karukan/zenzaiのニューラル変換、辞書管理UI、Android移行は未実装です。
- libsecretによる鍵保管は未実装で、0600の鍵ファイルを使います。
- Linux x86_64でのM0確認のみ行っています。他アーキテクチャとデスクトップ環境での入力は未確認です。
