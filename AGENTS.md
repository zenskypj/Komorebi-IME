# Komorebi IME: AI作業用リリース手順

このファイルは、今後このリポジトリを扱うAIエージェントが同じ品質ゲートを再現するための
永続的な作業メモである。公開リリースを依頼された場合は、READMEと
`docs/release-readiness.md`も読み、次を省略しない。

## リリース前

1. `app/build.gradle.kts` の `versionCode` と `versionName` を更新する。
2. `README.md`、`PRIVACY.md`、`NOTICE`、`app/src/main/assets/legal/` の内容を確認する。
   プライバシー本文を変えた場合、公開版とアプリ内版を一致させる。
3. JDK 17以上を使い、次を実行する。

```bash
./gradlew :app:clean :app:testDebugUnitTest :karukan:testDebugUnitTest \
  :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease \
  :app:bundleRelease
```

4. ABI別APKはAABと同じGradle呼び出しで作らず、別に実行する。

```bash
./gradlew -PenableAbiSplits=true :app:assembleDebug
./gradlew -PenableAbiSplits=true :app:assembleRelease
```

## 成果物の確認

- `aapt dump badging` で全APKのapplication ID、versionCode、versionNameを確認する。
- `zipalign -c -P 16 -v 4` でrelease APKの16 KBページ境界対応を確認する。
- APK内の `libmozc.so`、arm64版の `libkarukan_android.so` / `libc++_shared.so`、
  `assets/mozc.data`、`assets/legal/` を確認する。
- R8 mappingで `MozcJNI` と `KarukanNative` のJNIメソッド名が保持されていることを確認する。
- API 24 / 29 / 34 / 35 / 36、3ボタン / ジェスチャーナビ、背景・濃さ変更直後、
  複数行 / 検索 / 送信 / 端末欄のEnterを実機またはエミュレータで確認する。

## 署名とGitHub公開

- `release`成果物は署名設定がない限り未署名であり、そのまま利用者向けAPKとして公開しない。
- 正式署名鍵はリポジトリへ置かない。CIまたは管理された外部環境で署名する。
- RCでdebug署名APKを配る場合は、テスト用でapplication IDが `.debug` になることを
  リリースノートに明記する。
- 差分と生成物を再確認し、コミット、`v<versionName>`タグ、`main`とタグのpush、
  GitHub Release作成の順で行う。公開後にタグ、リリースURL、添付ファイルを再取得して確認する。

## 追加辞書の生成

- 追加辞書（`scripts/extra_dictionary/`）は`build/extra-dictionary/checkout`の隔離Mozcで生成する。
  `build_packs.py`が`patches/dictionary-generation/*.patch`をそのcheckoutへ冪等適用する。
- 生成専用パッチを`patches/`直下に置かない。`build_mozc.sh`は`patches/[0-9][0-9][0-9][0-9]-*.patch`を
  `third_party/mozc`へ適用するため、直下に置くとlibmozcが再ビルドされ互換指紋が変わる。
- 統合データセット（`neologd`／`skk`／`all`）は複数の変換結果を`(reading,lid,surface)`の
  最小コストで統合してから1回のBazelビルドで作る。上限件数での切り捨ては行わない。

## 作業用PCの資源

- 2026-11-05にPCの占有が指摘された。通常のAPK作成は`--no-daemon --max-workers=1`
  と`-Dorg.gradle.jvmargs=-Xmx2g -Pkotlin.compiler.execution.strategy=in-process`を使う。
- 不要な大規模辞書・nativeの再生成をしない。必要な生成も同時実行を増やさない。
- 作業が終わった隔離checkoutのBazel常駐は`bazelisk shutdown`で解放する。
  他アプリ・データベースや、別作業中のプロセスは停止しない。

## 機能変更時のドキュメント更新

- 2026-11-05の利用者指定により、今後は実装変更と同じ作業で`docs/`以下も更新する。
- 操作・仕様が変わった章を`docs/manual/`から特定し、利用手順・初期値・件数・制約を実装に合わせる。
  FAQや詳細仕様に同じ項目がある場合も揃える。機能に関係のない章を無理に書き換えない。
- リリース時は`docs/releases/v<versionName>.md`、`docs/manual/12-history.md`、
  `docs/assets/site.js`のリリース索引・タイムライン、READMEの最新版表記も更新する。
- 未検証の挙動は未検証と書き、データ原本や統合件数は対応する記録へリンクする。
  ドキュメントのみの追補で、不要なAPKやnativeの再ビルドをしない。
