# Komorebi IME と、ほかのキーボード

<p class="claim">入力は、あなたのもの。<strong>Komorebi IME は、入力した文字を端末の外へ送りません。</strong>変換も、AI も、辞書も、ぜんぶ端末の中です。</p>

<div class="metrics">
<div class="metric"><span class="n">0</span><span class="l">外へ送る入力データ</span></div>
<div class="metric"><span class="n">7</span><span class="l">要求する権限（NFC・Wi-Fi Direct含む）</span></div>
<div class="metric"><span class="n">0</span><span class="l">広告SDK・解析SDK</span></div>
<div class="metric"><span class="n">100%</span><span class="l">オフラインで変換</span></div>
</div>

<p>権限は用途別です。<code>INTERNET</code> はモデル・追加辞書の任意取得、<code>NFC</code> は Meets Beam と NFC 移行、Wi-Fi Direct は <code>ACCESS_WIFI_STATE</code> / <code>CHANGE_WIFI_STATE</code> / <code>NEARBY_WIFI_DEVICES</code>、Android 12 以下では位置情報（Wi-Fi Direct の端末検出のみ）を使います。Android 13 以降でも Wi-Fi Direct の検出には端末の位置情報モードをオンにする必要があります。位置の記録・外部送信は行いません。広告・解析用の権限はありません。</p>

## 一目でわかる比較

公開情報（2026年11月時点）にもとづく比較です。

| | Komorebi IME | Gboard | Google 日本語入力 | Simeji |
| --- | :---: | :---: | :---: | :---: |
| 入力した文字を外に送らない | <span class="yes">✓</span> | <span class="partial">△</span> | <span class="yes">✓</span><sup>1</sup> | <span class="partial">△</span> |
| 利用統計を送らない | <span class="yes">✓</span> | <span class="no">✗</span> | <span class="partial">△</span> | <span class="no">✗</span> |
| 広告のために入力を使わない | <span class="yes">✓</span> | <span class="partial">△</span> | <span class="muted">—</span> | <span class="no">✗</span> |
| ソースコードが公開されている | <span class="yes">✓</span> | <span class="no">✗</span> | <span class="no">✗</span> | <span class="no">✗</span> |
| 変換がオフラインで動く | <span class="yes">✓</span> | <span class="partial">△</span> | <span class="yes">✓</span> | <span class="partial">△</span> |
| AI 変換が端末内で完結する | <span class="yes">✓</span> | <span class="no">✗</span> | <span class="muted">—</span> | <span class="no">✗</span> |
| AI 文章校正が端末内で完結する | <span class="yes">✓</span> | <span class="muted">—</span> | <span class="muted">—</span> | <span class="no">✗</span> |
| 辞書を TSV で入出力できる | <span class="yes">✓</span> | <span class="no">✗</span> | <span class="muted">—</span> | <span class="no">✗</span> |
| 学習履歴を暗号化して保存する | <span class="yes">✓</span> | <span class="unknown">?</span> | <span class="unknown">?</span> | <span class="unknown">?</span> |
| クラウドバックアップを無効にできる | <span class="yes">✓</span> | <span class="unknown">?</span> | <span class="unknown">?</span> | <span class="unknown">?</span> |
| ネット辞書（NEologd / SKK）を使える | <span class="yes">✓</span> | <span class="no">✗</span> | <span class="no">✗</span> | <span class="no">✗</span> |
| NFC / Wi-Fi Direct で端末間転送できる | <span class="yes">✓</span> | <span class="muted">—</span> | <span class="muted">—</span> | <span class="muted">—</span> |
| 要求する権限の用途をコードで確認できる | <span class="yes">✓</span> | <span class="unknown">?</span> | <span class="unknown">?</span> | <span class="unknown">?</span> |
| 音声入力できる | <span class="muted">—</span> | <span class="yes">✓</span> | <span class="muted">—</span> | <span class="yes">✓</span> |
| 翻訳できる | <span class="muted">—</span> | <span class="yes">✓</span> | <span class="muted">—</span> | <span class="yes">✓</span> |
| 多言語を入力できる | <span class="partial">△</span> | <span class="yes">✓</span> | <span class="partial">△</span> | <span class="yes">✓</span> |

<div class="legend">
<span class="yes">✓</span> できる・している　<span class="no">✗</span> できない・していない　<span class="partial">△</span> 条件つき　<span class="muted">—</span> その機能がない　<span class="unknown">?</span> 公開情報では分からない
</div>

<small><sup>1</sup> 2013年に Google が公表した説明。現在の取り扱いは Google のプライバシーポリシーに従います。</small>

> [!IMPORTANT]
> 一番右の3列に注目してください。**ソースコードが非公開**なので、✓ も ✗ も、会社の説明か報道で知るしかありません。**あなた自身が確かめる方法はありません。**

## 乗り換える理由

<div class="hl">
<span class="hl-num">01</span>
<h3>入力した文字を、外に出さない</h3>
<p>変換は Mozc を端末に組み込んで実行します。AI 変換も AI 文章校正も端末内。外部通信は、あなたがモデルや追加辞書の「取得」を押したときだけです。Meets Beam のファイル・辞書転送は NFC と Wi-Fi Direct の端末間通信だけで、クラウドを経由しません。入力・文脈・辞書・学習履歴は外部へ送りません。</p>
</div>

<div class="hl">
<span class="hl-num">02</span>
<h3>入力は、広告の材料にしない</h3>
<p>広告SDKも解析SDKも組み込んでいません。無料で使えますが、入力を見返りに広告主へ売る作りにはしていません。これは会社の善意ではなく、<strong>そういう作りにしていない</strong>という話です。</p>
</div>

<div class="hl">
<span class="hl-num">03</span>
<h3>「送っていません」を、確かめられる</h3>
<p>ソースコードを公開しています（Apache-2.0）。Mozc への変更もパッチとして公開しています。会社の説明を信じる必要はありません。<strong>コードと権限を見れば済みます。</strong></p>
</div>

<div class="hl">
<span class="hl-num">04</span>
<h3>辞書も学習も、自分のもの</h3>
<p>ユーザー辞書は TSV で入出力できます。学習履歴・辞書・優先候補は Android Keystore の鍵で暗号化して保存。クラウドバックアップは無効です。機種変更はファイルか NFC で移せます。</p>
</div>

## 2013年に Simeji で起きたこと

| 日付 | 出来事 |
| --- | --- |
| 2013年12月26日 | セキュリティ会社が「Simeji が入力内容を無断で外部に送っている」と指摘 |
| 同日 | 運営会社が「プログラムの不具合」と説明。**記録をOFFにしていても、一部が送られていた** |
| 同日 | 不具合は**2013年3月の版から約8か月間**続いていたと判明 |
| 2013年12月27日 | 修正版を公開。あわせて「クラウド変換」を**初期設定でOFF**に変更 |

最後の行がすべてです。直すときに、それまで初期ONだったクラウド変換をOFFに変えた。**問題の一部は不具合ではなく、最初からONにしていた設定**でした。同じ会社のパソコン用ソフトでも、同じ時期に同じ問題が起きています。

## よくある質問

<details class="faq-item">
<summary>Komorebi IME は Gboard より安全ですか？</summary>
<p>「安全」を「入力が外に出ないことを自分で確かめられる」と定義するなら、はい。Gboard は検索・利用統計・音声を Google に送ると自社ヘルプに書いています。Komorebi IME は入力も統計も送らず、コードも公開しています。</p>
</details>

<details class="faq-item">
<summary>入力は本当に送られませんか？</summary>
<p>外部サーバーへ送りません。要求する権限は、任意取得用の <code>INTERNET</code>、Meets Beam・NFC移行用の <code>NFC</code>、Wi-Fi Direct 用の Wi-Fi/近接端末権限、Android 12 以下に限り Wi-Fi Direct 検出用の位置情報です。位置の記録・送信はしません。Meets Beam は端末間 P2P だけで、クラウドを経由しません。疑うなら、コードを読むか通信を自分で観測してください。それができるのが、ソース公開の意味です。</p>
</details>

<details class="faq-item">
<summary>オフラインで使えますか？</summary>
<p>使えます。Mozc を端末に組み込んでいるので、変換に通信は不要です。AI 機能を使う場合だけ、モデルを一度取得します。取得後の推論はオフラインです。</p>
</details>

<details class="faq-item">
<summary>AI はクラウドですか？</summary>
<p>いいえ。AI 変換（zenzai-plus / Karukan）も AI 文章校正（Qwen / Gemma）も端末内で動きます。文章を外部へ送りません。</p>
</details>

<details class="faq-item">
<summary>無料ですか？広告は入りますか？</summary>
<p>無料です。広告は表示しません。広告SDK・解析SDK・クラッシュレポートSDKも組み込んでいません。</p>
</details>

<details class="faq-item">
<summary>辞書はほかの IME から移せますか？</summary>
<p>Mozc / Google 日本語入力互換の TSV をそのまま取り込めます。書き出しもできます。取り込みは現在の辞書を置き換えるので、先に書き出しておいてください。</p>
</details>

<details class="faq-item">
<summary>音声入力や翻訳はできますか？</summary>
<p>できません。ここは大手に負けています（比較表の「—」）。必要なら、音声入力は OS や別アプリに任せてください。Komorebi IME は日本語入力と変換に絞っています。</p>
</details>

<details class="faq-item">
<summary>多言語は？</summary>
<p>日本語が中心です。英語の入力はできますが、多言語の切り替えは大手ほど得意ではありません。</p>
</details>

<details class="faq-item">
<summary>何が劣っていますか？</summary>
<p>機能の数、多言語、音声、翻訳、そして実績です。まだ Release Candidate で、AI 機能は一部の CPU だけ、実機での確認が続いている項目もあります。便利さの合計では負けます。</p>
</details>

<details class="faq-item">
<summary>元に戻せますか？</summary>
<p>戻せます。Android の設定でキーボードを切り替えるだけです。アンインストールすれば、端末内のデータも削除されます。</p>
</details>

## 乗り換えは、3ステップです

1. **APK をインストール** — [GitHub Releases](https://github.com/zenskypj/Komorebi-IME/releases/latest) から取得して開きます。
2. **キーボードを有効化・選択** — 設定アプリ、またはアプリ内の案内から Komorebi IME をオンにして選びます。
3. **試し入力** — 設定の「試し入力」で変換を確認します。AI やネット辞書は、必要になってから追加できます。

<div class="cta-row">
<a class="btn btn-filled" href="https://github.com/zenskypj/Komorebi-IME/releases/latest" target="_blank" rel="noopener noreferrer">最新版をダウンロード</a>
<a class="btn btn-outlined" href="#/doc/manual/00-hajimete">インストール手順を読む</a>
</div>

## それでも、ひとつだけ

Komorebi IME は、大手のように「すべての人に便利」ではありません。多言語も音声も翻訳もありません。流行の機能を先回りもしません。

代わりに、**あなたの入力が誰のものか**をはっきりさせます。それが必要なら、乗り換える価値はあります。

> [!NOTE]
> Android の「すべての入力内容の収集を許可しますか」という警告は、**どのキーボードにも表示されます**。Google 日本語入力のように「送っていない」と明言しているものにも出ます。警告の有無では安全かどうかは分かりません。判断材料は、ソース公開・権限・収集の初期値・収益構造です。

## 出典

- [バイドゥ、IMEアプリ「Simeji」で入力ログを無断送信（CNET Japan, 2013-12-26）](https://japan.cnet.com/article/35041966/)
- [「Simeji」が入力ログ無断送信バグを修正 「クラウド変換」は初期設定でオフに（ITmedia, 2013-12-27）](https://www.itmedia.co.jp/news/articles/1312/27/news036.html)
- [バイドゥ、「Baidu IME」「Simeji」でユーザーの入力内容を無断送信（ITmedia, 2013-12-26）](https://www.itmedia.co.jp/news/article/1312/26/1131226055/)
- [AndroidはIMEインストール時に“警告”表示で注意喚起（ITmedia, 2013-12-26）](https://www.itmedia.co.jp/news/article/1312/26/1131226108/)
- [Simeji プライバシーポリシー（2023-08-25 改定）](https://simeji.me/privacy/)
- [Gboard ヘルプ: Gboard が Google に送る情報](https://support.google.com/gboard/answer/9058584?hl=ja)
