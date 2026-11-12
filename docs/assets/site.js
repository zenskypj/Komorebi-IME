/* Komorebi IME docs — fetches Markdown from GitHub and renders it in place.
   No build step, no bundler. Single source of truth: the repository's own docs. */
(() => {
  "use strict";

  const REPO = "zenskypj/Komorebi-IME";
  const REF = "main";
  const RAW = `https://raw.githubusercontent.com/${REPO}/${REF}/`;
  const BLOB = `https://github.com/${REPO}/blob/${REF}/`;

  /* ---------------- content index ---------------- */

  const INDEX = [
    {
      group: "マニュアル",
      items: [
        ["manual/00-hajimete", "はじめての Komorebi IME", "インストールと最初の設定", "docs/manual/00-hajimete.md", "install setup はじめに"],
        ["manual/01-nyuryoku", "入力方式と基本操作", "フリック・QWERTY・カーソルの動かし方", "docs/manual/01-nyuryoku.md", "keyboard 操作"],
        ["manual/02-henkan", "変換と候補", "変換のしかたと、候補が並ぶ順番", "docs/manual/02-henkan.md", "conversion 変換"],
        ["manual/03-ai", "AI 機能", "AI変換・AI文章校正・文体補正", "docs/manual/03-ai.md", "ai zenzai karukan 校正"],
        ["manual/04-jisho", "辞書", "単語の登録、追加辞書、意味辞書", "docs/manual/04-jisho.md", "dictionary 辞書"],
        ["manual/05-gaikan", "外観と配列", "背景・テーマ・キーごとの外観と配列", "docs/manual/05-gaikan.md", "theme layout profile 外観 着せ替え 枠 画像 動画"],
        ["manual/13-settings-ui", "設定画面", "カテゴリ一覧・検索・キーボードの初期設定", "docs/manual/13-settings-ui.md", "settings 設定 検索 キーボード"],
        ["manual/06-privacy", "プライバシーとセキュリティ", "何が送られて、何が端末に残るか", "docs/manual/06-privacy.md", "privacy security 安全"],
        ["manual/07-iten", "データの移行", "機種変更のときの引っ越し", "docs/manual/07-iten.md", "backup nfc beam 移行"],
        ["manual/08-troubleshoot", "トラブルシューティング", "症状から引く対処", "docs/manual/08-troubleshoot.md", "trouble 不具合"],
        ["manual/09-spec", "詳細仕様", "細かい仕様・ファイル・制限", "docs/manual/09-spec.md", "spec 仕様 補足"],
        ["manual/10-faq", "よくある質問", "短い質問と答え", "docs/manual/10-faq.md", "faq 質問"],
      ],
    },
    {
      group: "タイムライン",
      items: [
        ["manual/12-history", "全バージョンのタイムライン", "どの版で何が変わったか（全55件）", "docs/manual/12-history.md", "history timeline リリース 歴史 全バージョン"],
      ],
    },
    {
      group: "比較",
      items: [
        ["manual/11-hikaku", "ほかのキーボードと比べる", "Gboard・Simeji と ✓/✗ で比較", "docs/manual/11-hikaku.md", "compare privacy gboard simeji 比較", "compare.html"],
        ["compare/zinna", "Zinna-IME と比べる", "プライバシーは互角、機能で比べる", "docs/compare-zinna.html", "zinna フォーク 比較", "compare-zinna.html"],
      ],
    },
    {
      group: "設計・仕様",
      items: [
        ["conversion-ui", "変換とキーボードの動き", "候補と文節の動き", "docs/conversion-ui.md", "conversion ui"],
        ["research/simeji-customization", "着せ替えの機能比較", "Simejiの公式情報とKomorebiの対応範囲", "docs/research/simeji-customization.md", "Simeji theme comparison 着せ替え"],
        ["research/keyboard-theme-art", "着せ替え素材", "同梱画像の一覧と生成記録", "docs/research/keyboard-theme-art.md", "theme art AI 画像 背景 キー"],
        ["keyboard-strengthening", "キーボードの強化点", "文節操作・学習・編集パネル", "docs/keyboard-strengthening.md", "keyboard"],
        ["zenzai-plus", "AI変換の仕組み", "zenzai-plus が端末内で変換するまで", "docs/zenzai-plus.md", "zenzai ai"],
        ["proofreading", "AI文章校正の仕組み", "校正モデルと処理の流れ", "docs/proofreading.md", "proofread 校正"],
        ["profile-encryption", "保存データの暗号化", "何を、どう暗号化して保存するか", "docs/profile-encryption.md", "encryption keystore"],
        ["extra-dictionaries/README", "追加辞書の中身", "NEologd・SKK の収録内容と作り方", "docs/extra-dictionaries/README.md", "dictionary neologd skk"],
        ["nfc-migration", "NFC で設定を移す", "2台の端末のあいだで引っ越す", "docs/nfc-migration.md", "nfc migration"],
        ["meets-beam", "NFC で送る（Meets Beam）", "テキスト・URL・アプリを送る", "docs/meets-beam.md", "beam nfc"],
        ["license-audit", "ライセンスの確認項目", "公開前のチェックリスト", "docs/license-audit.md", "license"],
        ["upstream-sync", "フォーク元との差分", "何を取り入れて、何を変えたか", "docs/upstream-sync.md", "upstream fork"],
        ["release-readiness", "正式版を出す前の確認", "端末ごとの確認項目", "docs/release-readiness.md", "release 基準"],
      ],
    },
    {
      group: "リリースノート",
      items: [
        ["releases/v0.3.0-rc.62-dev", "rc.62-dev", "マイク許可と音声入力の起動を改善", "docs/releases/v0.3.0-rc.62-dev.md", "release rc62 音声 マイク"],
        ["releases/v0.3.0-rc.61-dev", "rc.61-dev", "VTuberの名前を同梱辞書に追加", "docs/releases/v0.3.0-rc.61-dev.md", "release rc61 VTuber 辞書"],
        ["releases/v0.3.0-rc.60-dev", "rc.60-dev", "変換候補の全件表示とページを越えた選択", "docs/releases/v0.3.0-rc.60-dev.md", "release rc60 変換 候補"],
        ["releases/v0.3.0-rc.59-dev", "rc.59-dev", "音声入力をキーボード内に統合", "docs/releases/v0.3.0-rc.59-dev.md", "release rc59 音声"],
        ["releases/v0.3.0-rc.58-dev", "rc.58-dev", "更新の自動確認・音声入力の起動修正", "docs/releases/v0.3.0-rc.58-dev.md", "release rc58 音声 更新"],
        ["releases/v0.3.0-rc.57-dev", "rc.57-dev", "任意の音声入力・日本語モデル管理", "docs/releases/v0.3.0-rc.57-dev.md", "release rc57 音声"],
        ["releases/v0.3.0-rc.56-dev", "rc.56-dev", "初期設定・入力ガイド・変換案内を改善", "docs/releases/v0.3.0-rc.56-dev.md", "release rc56 初期設定 ガイド"],
        ["releases/v0.3.0-rc.55-dev", "rc.55-dev", "きせかえ項目がIMEに隠れる問題を修正", "docs/releases/v0.3.0-rc.55-dev.md", "release rc55 ime insets"],
        ["releases/v0.3.0-rc.54-dev", "rc.54-dev", "きせかえ画面を刷新し実際のIMEで試し入力", "docs/releases/v0.3.0-rc.54-dev.md", "release rc54 theme preview"],
        ["releases/v0.3.0-rc.53-dev", "rc.53-dev", "小・濁点キーを文字キーの見た目に統一", "docs/releases/v0.3.0-rc.53-dev.md", "release rc53 濁点 キー"],
        ["releases/v0.3.0-rc.52-dev", "rc.52-dev", "きせかえ専用画面・常時プレビュー・キーの形", "docs/releases/v0.3.0-rc.52-dev.md", "release rc52 theme live preview きせかえ キー"],
        ["releases/v0.3.0-rc.51-dev", "rc.51-dev", "背景13種・完成テーマ9種、キーの枠と画像、写真・動画の着せ替え", "docs/releases/v0.3.0-rc.51-dev.md", "release rc51 theme wallpaper customization 着せ替え"],
        ["releases/v0.3.0-rc.50-dev", "rc.50-dev", "設定を4カテゴリの一覧に整理・検索とバックアップの導線を改善", "docs/releases/v0.3.0-rc.50-dev.md", "release rc50 settings search backup ui"],
        ["releases/v0.3.0-rc.49-dev", "rc.49-dev", "Liquid Glassの設定ホームと5タブ・検索・二層フッター", "docs/releases/v0.3.0-rc.49-dev.md", "release rc49 settings liquid glass ui"],
        ["releases/v0.3.0-rc.48-dev", "rc.48-dev", "変換キー2回目で候補を進めない", "docs/releases/v0.3.0-rc.48-dev.md", "release rc48 convert candidate"],
        ["releases/v0.3.0-rc.47-dev", "rc.47-dev", "Wi-Fi Directを標準のグループ交渉へ変更", "docs/releases/v0.3.0-rc.47-dev.md", "release rc47 beam wifi direct group"],
        ["releases/v0.3.0-rc.46-dev", "rc.46-dev", "端末アドレスが取れない場合のWi-Fi Direct接続", "docs/releases/v0.3.0-rc.46-dev.md", "release rc46 beam wifi direct address"],
        ["releases/v0.3.0-rc.45-dev", "rc.45-dev", "Wi-Fi Directの検出と接続を修正", "docs/releases/v0.3.0-rc.45-dev.md", "release rc45 beam wifi direct"],
        ["releases/v0.3.0-rc.44-dev", "rc.44-dev", "Meets BeamのWi-Fi Directハンドオフと大容量ファイル・辞書共有", "docs/releases/v0.3.0-rc.44-dev.md", "release rc44 beam nfc direct"],
        ["releases/v0.3.0-rc.43-dev", "rc.43-dev", "変換1回直後の確定で文全体を確定", "docs/releases/v0.3.0-rc.43-dev.md", "release rc43 確定"],
        ["releases/v0.3.0-rc.42-dev", "rc.42-dev", "Mozc非同期化と候補表示の軽量化", "docs/releases/v0.3.0-rc.42-dev.md", "release rc42 性能"],
        ["releases/v0.3.0-rc.41-dev", "rc.41-dev", "英語の完全一致候補より日本語を優先", "docs/releases/v0.3.0-rc.41-dev.md", "release rc41 英語"],
        ["releases/v0.3.0-rc.40-dev", "rc.40-dev", "固有名詞・登録語を途中から先読み", "docs/releases/v0.3.0-rc.40-dev.md", "release rc40 予測"],
        ["releases/v0.3.0-rc.39-dev", "rc.39-dev", "フリック削除を単語・行の二段階に", "docs/releases/v0.3.0-rc.39-dev.md", "release rc39 削除"],
        ["releases/v0.3.0-rc.38-dev", "rc.38-dev", "提供された固有名詞を同梱辞書へ合併", "docs/releases/v0.3.0-rc.38-dev.md", "release rc38"],
        ["releases/v0.3.0-rc.37-dev", "rc.37-dev", "補正と最優先指定・送信後の入力状態を修正", "docs/releases/v0.3.0-rc.37-dev.md", "release rc37"],
        ["releases/v0.3.0-rc.36-dev", "rc.36-dev", "辞書の切り替えを高速化・ドキュメントサイト公開", "docs/releases/v0.3.0-rc.36-dev.md", "release rc36"],
        ["releases/v0.3.0-rc.35-dev", "rc.35-dev", "複数の辞書を同時に使えるように", "docs/releases/v0.3.0-rc.35-dev.md", "release rc35"],
        ["releases/v0.3.0-rc.34-dev", "rc.34-dev", "登録した単語を AI が横取りしないように", "docs/releases/v0.3.0-rc.34-dev.md", "release rc34"],
        ["releases/v0.3.0-rc.32-dev", "rc.32-dev", "キーの高さ調整・予測の順位・次の文節へ自動で進む", "docs/releases/v0.3.0-rc.32-dev.md", "release rc32"],
        ["releases/v0.3.0-rc.31-dev", "rc.31-dev", "文節の区切りを引き継ぐ", "docs/releases/v0.3.0-rc.31-dev.md", "release rc31"],
        ["releases/v0.3.0-rc.30-dev", "rc.30-dev", "候補の行数・スワイプ・文節の調整", "docs/releases/v0.3.0-rc.30-dev.md", "release rc30"],
        ["releases/v0.3.0-rc.29-dev", "rc.29-dev", "AI校正の結果を守る", "docs/releases/v0.3.0-rc.29-dev.md", "release rc29"],
        ["releases/v0.3.0-rc.28-dev", "rc.28-dev", "AI校正の修正", "docs/releases/v0.3.0-rc.28-dev.md", "release rc28"],
        ["releases/v0.3.0-rc.27-dev", "rc.27-dev", "変換中のカーソルを安定させる", "docs/releases/v0.3.0-rc.27-dev.md", "release rc27"],
        ["releases/v0.3.0-rc.26-dev", "rc.26-dev", "ツールの並びと「自動」エンジン", "docs/releases/v0.3.0-rc.26-dev.md", "release rc26"],
        ["releases/v0.3.0-rc.25-dev", "rc.25-dev", "定型文・文節操作・カーソル移動", "docs/releases/v0.3.0-rc.25-dev.md", "release rc25"],
      ],
    },
    {
      group: "リポジトリ",
      items: [
        ["README", "README", "プロジェクト全体の説明", "README.md", "readme 概要"],
        ["PRIVACY", "プライバシーポリシー", "公開している方針", "PRIVACY.md", "privacy policy"],
      ],
    },
  ];

  const ITEMS = INDEX.flatMap((g) => g.items.map(([id, title, blurb, path, tags, page]) => ({ group: g.group, id, title, blurb, path, tags, page })));
  const BY_ID = new Map(ITEMS.map((it) => [it.id, it]));
  const BY_PATH = new Map(ITEMS.map((it) => [it.path, it]));

  /** Some entries are hand-built pages rather than Markdown rendered in place. */
  const hrefFor = (item) => (item && item.page ? item.page : `#/doc/${item ? item.id : ""}`);

  /* ---------------- helpers ---------------- */

  const $ = (sel, root = document) => root.querySelector(sel);
  const el = (tag, attrs = {}, children = []) => {
    const node = document.createElement(tag);
    for (const [k, v] of Object.entries(attrs)) {
      if (k === "class") node.className = v;
      else if (k === "text") node.textContent = v;
      else if (v === true) node.setAttribute(k, "");
      else if (v !== false && v != null) node.setAttribute(k, v);
    }
    for (const c of [].concat(children)) if (c) node.append(c);
    return node;
  };

  function routeToItem(route) {
    const id = route.replace(/^\/?/, "").replace(/^doc\//, "").split("#")[0];
    return BY_ID.get(id) || null;
  }

  function parseHash() {
    const raw = location.hash.replace(/^#/, "");
    if (!raw || raw === "/") return { view: "home" };
    const hashAt = raw.indexOf("#", 1);
    const route = hashAt === -1 ? raw : raw.slice(0, hashAt);
    const anchor = hashAt === -1 ? "" : decodeURIComponent(raw.slice(hashAt + 1));
    if (route.startsWith("/doc/")) return { view: "doc", item: routeToItem(route), anchor };
    return { view: "home" };
  }

  function joinPath(dir, rel) {
    const parts = (dir ? dir.split("/") : []).concat(rel.split("/"));
    const out = [];
    for (const p of parts) {
      if (!p || p === ".") continue;
      if (p === "..") out.pop();
      else out.push(p);
    }
    return out.join("/");
  }

  function repoRoute(path) {
    const item = BY_PATH.get(path);
    return item ? `#/doc/${item.id}` : null;
  }

  /* ---------------- markdown ---------------- */

  const CALLOUTS = { NOTE: "note", TIP: "tip", IMPORTANT: "important", WARNING: "warning", CAUTION: "caution" };
  const CALLOUT_LABEL = { note: "補足", tip: "ヒント", important: "重要", warning: "注意", caution: "注意" };

  function slug(text) {
    return text.trim().toLowerCase().replace(/[\s]+/g, "-").replace(/[　]+/g, "-").replace(/[「」『』【】（）()〔〕\[\]{}、。，．・:：;；!！?？"'`#*_~|/\\<>=+]/g, "").replace(/-+/g, "-");
  }

  function setupRenderer(baseDir) {
    const renderer = new marked.Renderer();
    const used = new Map();

    renderer.heading = function (text, level) {
      const plain = String(text).replace(/<[^>]+>/g, "");
      let id = slug(plain);
      if (used.has(id)) {
        const n = used.get(id) + 1;
        used.set(id, n);
        id = `${id}-${n}`;
      } else used.set(id, 0);
      const tag = level <= 2 ? "h2" : level === 3 ? "h3" : "h4";
      return `<${tag} id="${id}">${text}</${tag}>\n`;
    };

    renderer.link = function (href, title, text) {
      const resolved = resolveHref(href, baseDir);
      const external = /^https?:\/\//i.test(resolved);
      const attrs = external ? ' target="_blank" rel="noopener noreferrer"' : "";
      const t = title ? ` title="${title}"` : "";
      return `<a href="${resolved}"${t}${attrs}>${text}</a>`;
    };

    renderer.code = function (code, lang) {
      const cls = lang ? ` class="language-${lang}"` : "";
      return `<pre><code${cls}>${code}</code></pre>\n`;
    };

    return renderer;
  }

  function resolveHref(href, baseDir) {
    if (!href) return href;
    const anchorAt = href.indexOf("#");
    const anchor = anchorAt === -1 ? "" : href.slice(anchorAt);
    const target = anchorAt === -1 ? href : href.slice(0, anchorAt);
    if (!target) return href;
    if (/^[a-z][a-z0-9+.-]*:/i.test(target)) return href;
    if (target.startsWith("/")) {
      const p = target.replace(/^\/+/, "");
      return (repoRoute(p) || BLOB + p) + anchor;
    }
    const resolvedPath = joinPath(baseDir, target);
    const route = repoRoute(resolvedPath);
    if (route) return route + (anchor ? anchor.replace(/^#/, "#") : "");
    return BLOB + resolvedPath + anchor;
  }

  function decorateCallouts(root) {
    root.querySelectorAll("blockquote").forEach((q) => {
      const first = q.querySelector("p");
      if (!first) return;
      const m = first.textContent.match(/^\[!(\w+)\]\s*/);
      if (!m) return;
      const kind = CALLOUTS[m[1].toUpperCase()];
      if (!kind) return;
      first.innerHTML = first.innerHTML.replace(/^\[!\w+\]\s*(<br\s*\/?>)?\s*/i, "");
      if (!first.textContent.trim()) first.remove();
      const box = el("div", { class: "callout", "data-kind": kind });
      box.append(el("span", { class: "callout-label", text: CALLOUT_LABEL[kind] }));
      while (q.firstChild) box.append(q.firstChild);
      q.replaceWith(box);
    });
  }

  /* Version headings on the history page become a vertical timeline. */
  function buildTimeline(body) {
    const isVersion = (node) =>
      node.nodeType === 1 && node.tagName === "H3" && /^v?\d+\.\d+/.test(node.textContent.trim());
    const heads = Array.from(body.querySelectorAll("h3")).filter(isVersion);
    if (heads.length < 3) return;
    const rail = el("div", { class: "timeline" });
    body.insertBefore(rail, heads[0]);
    let current = null;
    Array.from(body.childNodes).forEach((node) => {
      if (node === rail) return;
      if (isVersion(node)) {
        current = el("section", { class: "tl-entry" });
        rail.append(current);
        current.append(node);
        return;
      }
      if (node.nodeType === 1 && node.tagName === "H2") { current = null; return; }
      if (current) current.append(node);
    });
  }

  /* The comparison table is the one whose header names Komorebi IME. */
  function decorateTables(body) {
    body.querySelectorAll("table").forEach((table) => {
      const firstRow = table.querySelector("thead tr, tr");
      if (firstRow && firstRow.textContent.includes("Komorebi IME")) table.classList.add("compare");
    });
  }

  function collectToc(root) {
    const headings = root.querySelectorAll("h2, h3");
    return Array.from(headings).map((h) => ({ id: h.id, text: h.textContent, depth: h.tagName === "H3" ? 3 : 2 }));
  }

  async function fetchMarkdown(path) {
    const res = await fetch(RAW + path, { cache: "no-cache" });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    return res.text();
  }

  /* ---------------- views ---------------- */

  const view = $("#view");

  function showLoading() {
    const wrap = el("div", { class: "content" }, [
      el("div", { class: "skeleton", "aria-hidden": "true" }, [
        el("span"), el("span"), el("span"), el("span"), el("span"),
      ]),
    ]);
    wrap.append(el("p", { class: "skip", text: "読み込み中" }));
    view.replaceChildren(wrap);
  }

  function showError(err, item) {
    const box = el("div", { class: "errorbox" }, [
      el("h2", { text: "読み込めませんでした" }),
      el("p", {
        text: `${item ? item.title : "ドキュメント"}を取得できませんでした（${err.message}）。ネットワーク、または GitHub 上のファイルを確認してください。`,
      }),
    ]);
    const row = el("div", { class: "row" });
    const retry = el("button", { class: "btn btn-filled", type: "button", text: "再試行" });
    retry.addEventListener("click", () => renderFromHash(true));
    row.append(retry);
    if (item) row.append(el("a", { class: "btn btn-outlined", href: BLOB + item.path, target: "_blank", rel: "noopener noreferrer", text: "GitHub で開く" }));
    box.append(row);
    view.replaceChildren(el("div", { class: "content" }, [box]));
  }

  async function renderDoc(item, anchor) {
    if (!item) return renderNotFound();
    showLoading();
    let markdown;
    try {
      markdown = await fetchMarkdown(item.path);
    } catch (err) {
      return showError(err, item);
    }
    const baseDir = item.path.slice(0, item.path.lastIndexOf("/"));
    marked.setOptions({ gfm: true, breaks: false, headerIds: false, mangle: false });
    const html = marked.parse(markdown, { renderer: setupRenderer(baseDir) });
    const safe = DOMPurify.sanitize(html, { ADD_ATTR: ["target", "rel"] });

    const body = el("div", { class: "prose" });
    body.innerHTML = safe;
    decorateCallouts(body);
    buildTimeline(body);
    decorateTables(body);

    const toc = collectToc(body);
    const main = el("h1", { text: item.title });
    const bread = el("nav", { class: "breadcrumb", "aria-label": "パンくず" }, [
      el("a", { href: "#/", text: "ホーム" }),
      document.createTextNode(" / "),
      document.createTextNode(item.group),
    ]);
    const head = el("header", { class: "doc-head" }, [
      bread,
      main,
      el("a", { class: "source-link", href: BLOB + item.path, target: "_blank", rel: "noopener noreferrer" }, [
        document.createTextNode("GitHub で原文を見る ↗"),
      ]),
    ]);

    const article = el("article", { class: "doc" }, [head, body]);

    const tocNav = el("nav", { class: "toc", id: "toc", "aria-label": "このページの目次" });
    if (toc.length) {
      const ul = el("ul");
      toc.forEach((h) => {
        const a = el("a", { href: `#/doc/${item.id}#${encodeURIComponent(h.id)}`, "data-depth": String(h.depth), text: h.text });
        ul.append(el("li", {}, [a]));
      });
      tocNav.append(el("h2", { text: "このページ" }), ul);
    }

    view.replaceChildren(el("div", { class: "content" }, [article, tocNav]));
    document.title = `${item.title} — Komorebi IME`;
    updateNavCurrent(item.id);
    if (anchor) scrollToAnchor(anchor);
    else window.scrollTo({ top: 0 });
    observeHeadings(toc);
  }

  function scrollToAnchor(anchor) {
    const target = document.getElementById(anchor);
    if (target) requestAnimationFrame(() => target.scrollIntoView({ block: "start" }));
  }

  let headingObserver = null;
  function observeHeadings(toc) {
    if (headingObserver) headingObserver.disconnect();
    if (!toc.length) return;
    const links = new Map(Array.from(document.querySelectorAll(".toc a")).map((a) => [decodeURIComponent(a.hash.split("#").pop()), a]));
    headingObserver = new IntersectionObserver((entries) => {
      entries.forEach((entry) => {
        if (!entry.isIntersecting) return;
        links.forEach((a) => a.removeAttribute("aria-current"));
        const link = links.get(entry.target.id);
        if (link) link.setAttribute("aria-current", "true");
      });
    }, { rootMargin: "-72px 0px -70% 0px", threshold: 0 });
    toc.forEach((h) => {
      const node = document.getElementById(h.id);
      if (node) headingObserver.observe(node);
    });
  }

  function renderNotFound() {
    const box = el("div", { class: "errorbox" }, [
      el("h2", { text: "ページが見つかりません" }),
      el("p", { text: "指定されたドキュメントは目次にありません。" }),
    ]);
    box.append(el("div", { class: "row" }, [el("a", { class: "btn btn-filled", href: "#/", text: "ホームへ戻る" })]));
    view.replaceChildren(el("div", { class: "content" }, [box]));
  }

  /* ---------------- home ---------------- */

  const BADGES = [
    ["release", `https://img.shields.io/github/v/release/${REPO}?include_prereleases&sort=semver&style=flat-square&label=release&color=006d3b`],
    ["license", `https://img.shields.io/github/license/${REPO}?style=flat-square&color=44474a`],
    ["android", `https://img.shields.io/badge/Android-7.0%2B%20(API%2024)-3ddc84?style=flat-square&logo=android&logoColor=white`],
    ["offline", `https://img.shields.io/badge/conversion-offline-44474a?style=flat-square`],
    ["telemetry", `https://img.shields.io/badge/telemetry-none-44474a?style=flat-square`],
    ["mozc", `https://img.shields.io/badge/Mozc-BSD--3--Clause-44474a?style=flat-square`],
    ["ethics", `https://gijutsusharin.li/badge.svg`],
  ];

  const RECENT = [
    ["2026-11-12", "v0.3.0-rc.62-dev", "音声入力のマイク許可と録音開始を改善"],
    ["2026-11-12", "v0.3.0-rc.61-dev", "VTuberの名前を同梱固有名詞辞書に追加"],
    ["2026-11-11", "v0.3.0-rc.60-dev", "変換候補の全件表示と連続選択"],
    ["2026-11-11", "v0.3.0-rc.59-dev", "音声入力をキーボード内に統合"],
    ["2026-11-11", "v0.3.0-rc.58-dev", "更新確認・音声入力の起動を修正"],
    ["2026-11-11", "v0.3.0-rc.57-dev", "音声入力モジュール・日本語モデル管理"],
    ["2026-11-11", "v0.3.0-rc.56-dev", "初期設定・入力の練習・変換案内を分かりやすく"],
    ["2026-11-11", "v0.3.0-rc.55-dev", "IME表示中もきせかえ項目と開閉を表示"],
    ["2026-11-10", "v0.3.0-rc.54-dev", "きせかえをアイコンと背景一覧に変更、実際のIMEで試し入力"],
    ["2026-11-10", "v0.3.0-rc.53-dev", "小・濁点キーに文字キーの色と画像を適用"],
    ["2026-11-10", "v0.3.0-rc.52-dev", "きせかえを独立。テーマ・背景・キーの形を固定プレビューで試せる"],
    ["2026-11-10", "v0.3.0-rc.51-dev", "着せ替えを拡張。背景13種、完成テーマ9種、キー単位の外観、写真・動画"],
    ["2026-11-10", "v0.3.0-rc.50-dev", "設定画面をカテゴリ別の一覧に整理し、横断検索を維持"],
    ["2026-11-07", "v0.3.0-rc.49-dev", "Liquid Glassの設定ホーム、検索、5タブ、二層フッターバー"],
    ["2026-11-07", "v0.3.0-rc.48-dev", "変換キー2回目は候補選択に入るだけで候補を進めない"],
    ["2026-11-06", "v0.3.0-rc.47-dev", "Wi-Fi Directを標準のグループ交渉と対称サーバー構成へ変更"],
    ["2026-11-06", "v0.3.0-rc.46-dev", "端末アドレスが取れない場合のWi-Fi Direct接続を追加"],
    ["2026-11-06", "v0.3.0-rc.45-dev", "Wi-Fi Directの位置情報モード・接続確立・アドレス解決を修正"],
    ["2026-11-06", "v0.3.0-rc.44-dev", "Meets BeamのWi-Fi Direct転送と大容量ファイル共有"],
    ["2026-11-06", "v0.3.0-rc.43-dev", "変換1回直後の確定で文全体を確定"],
    ["2026-11-05", "v0.3.0-rc.42-dev", "Mozcの非同期化・入力処理と候補表示を軽量化"],
    ["2026-11-05", "v0.3.0-rc.41-dev", "英語辞書の完全一致候補も日本語候補より後ろへ"],
    ["2026-11-05", "v0.3.0-rc.40-dev", "固有名詞・登録語の段階的な先読み候補"],
    ["2026-11-05", "v0.3.0-rc.39-dev", "フリック削除キーに単語・行の二段階操作"],
    ["2026-11-05", "v0.3.0-rc.38-dev", "同梱固有名詞辞書を4,879語へ拡張"],
    ["2026-11-05", "v0.3.0-rc.37-dev", "表記ゆれ補正・AIより最優先・送信後の状態破棄"],
    ["2026-11-04", "v0.3.0-rc.35-dev", "統合データセット（複数辞書の同時有効化）"],
    ["2026-11-04", "v0.3.0-rc.34-dev", "個別指定した辞書語の AI 優先 / NFC 移行の修正"],
    ["2026-11-04", "v0.3.0-rc.33-dev", "追加システム辞書（NEologd / SKK）"],
    ["2026-11-04", "v0.3.0-rc.32-dev", "キーボード高さ・予測順位・次文節への自動移行"],
    ["2026-11-04", "v0.3.0-rc.31-dev", "文節境界の引き継ぎと左右キーの段階"],
    ["2026-11-04", "v0.3.0-rc.30-dev", "候補の行数・スワイプ・文節調整の分離"],
  ];

  function recentTimeline() {
    const rail = el("div", { class: "timeline timeline-compact" });
    RECENT.forEach(([date, version, summary]) => {
      const notes = BY_ID.get(`releases/${version}`);
      const heading = notes ? el("h3", {}, [el("a", { href: hrefFor(notes), text: version })]) : el("h3", { text: version });
      rail.append(el("section", { class: "tl-entry" }, [
        el("span", { class: "tl-date", text: date }),
        heading,
        el("p", { text: summary }),
      ]));
    });
    return el("section", { class: "section" }, [
      el("h2", { text: "最近のリリース" }),
      el("p", { class: "sub", text: "過去の変更は「全バージョンのタイムライン」から確認できます。" }),
      rail,
      el("p", { class: "sub" }, [el("a", { href: "#/doc/manual/12-history", text: "全バージョンのタイムラインを見る" })]),
    ]);
  }

  function renderHome() {
    document.title = "Komorebi IME ドキュメント";
    const home = el("div", { class: "home" });

    const badges = el("div", { class: "badges" });
    BADGES.forEach(([name, src]) => badges.append(el("img", { src, alt: name, loading: "lazy", height: "20" })));

    const steps = el("ol", { class: "steps" });
    [
      ["インストール", "GitHub Releases から APK を取得して開く"],
      ["有効化", "設定 →「キーボードを有効化」で Komorebi IME をオン"],
      ["選択", "「キーボードを選択」で Komorebi IME を選ぶ"],
      ["試し入力", "設定の「試し入力」で変換を確認"],
    ].forEach(([t, s]) => steps.append(el("li", {}, [el("strong", { text: t }), document.createTextNode(" — " + s)])));

    home.append(
      el("header", { class: "intro" }, [
        el("h1", { text: "Komorebi IME ドキュメント" }),
        el("p", { class: "lede", text: "Android 向け日本語IME「Komorebi IME」のユーザーマニュアルと仕様です。変換も AI も端末内で完結し、入力内容を端末の外へ送りません。" }),
        el("div", { class: "actions" }, [
          el("a", { class: "btn btn-filled", href: "#/doc/manual/00-hajimete", text: "はじめての設定" }),
          el("a", { class: "btn btn-text", href: `https://github.com/${REPO}/releases/latest`, target: "_blank", rel: "noopener noreferrer", text: "最新版" }),
          el("a", { class: "btn btn-text", href: "compare.html", text: "Gboard・Simeji と比較" }),
          el("a", { class: "btn btn-text", href: "compare-zinna.html", text: "Zinna-IME と比較" }),
        ]),
        badges,
      ]),
      el("section", { class: "section" }, [
        el("h2", { text: "はじめかた" }),
        el("p", { class: "sub", text: "インストール後の初期設定は4ステップです。" }),
        steps,
      ]),
      recentTimeline(),
      doclistSection("マニュアル", "目的から探す"),
      doclistSection("タイムライン", "版ごとの変更をたどる"),
      doclistSection("比較", "入力がどこへ行くか"),
      doclistSection("設計・仕様", "細かい仕様と確認した範囲"),
      doclistSection("リリースノート", "各版の変更点"),
      doclistSection("リポジトリ", "プロジェクト全体"),
      el("p", { class: "footnote" }, [
        el("span", { text: "本文は GitHub の docs/ から取得して表示しています。" }),
        el("a", { href: `https://github.com/${REPO}/tree/${REF}/docs`, target: "_blank", rel: "noopener noreferrer", text: "docs/ を GitHub で見る" }),
        el("a", { href: `https://github.com/${REPO}/releases`, target: "_blank", rel: "noopener noreferrer", text: "すべてのリリース" }),
      ]),
    );

    view.replaceChildren(home);
    updateNavCurrent("");
  }

  function doclistSection(groupName, sub) {
    const group = INDEX.find((g) => g.group === groupName);
    const list = el("ul", { class: "doclist" });
    group.items.forEach(([id]) => {
      const item = BY_ID.get(id);
      list.append(el("li", {}, [el("a", { href: hrefFor(item) }, [
        el("span", { class: "t", text: item.title }),
        el("span", { class: "d", text: item.blurb }),
      ])]));
    });
    return el("section", { class: "section" }, [
      el("h2", { text: groupName }),
      sub ? el("p", { class: "sub", text: sub }) : null,
      list,
    ]);
  }

  /* ---------------- navigation ---------------- */

  const navRoot = $("#nav");
  const navEmpty = $("#nav-empty");

  function buildNav() {
    INDEX.forEach((group) => {
      const list = el("ul", { class: "nav-list" });
      group.items.forEach(([id, title]) => {
        const item = BY_ID.get(id);
        list.append(el("li", {}, [el("a", { href: hrefFor(item), "data-nav-id": id, text: title })]));
      });
      navRoot.append(el("div", { class: "nav-group" }, [el("h2", { text: group.group }), list]));
    });
  }

  function updateNavCurrent(id) {
    navRoot.querySelectorAll("a[data-nav-id]").forEach((a) => {
      if (id && a.getAttribute("data-nav-id") === id) a.setAttribute("aria-current", "page");
      else a.removeAttribute("aria-current");
    });
  }

  function filterNav(query) {
    const q = query.trim().toLowerCase();
    let visible = 0;
    navRoot.querySelectorAll(".nav-group").forEach((group) => {
      let groupVisible = 0;
      group.querySelectorAll("li").forEach((li) => {
        const a = li.querySelector("a");
        const item = BY_ID.get(a.getAttribute("data-nav-id"));
        const hay = `${item.title} ${item.blurb} ${item.tags}`.toLowerCase();
        const match = !q || hay.includes(q);
        li.hidden = !match;
        if (match) groupVisible++;
      });
      group.hidden = groupVisible === 0;
      visible += groupVisible;
    });
    navEmpty.hidden = visible !== 0 || !q;
  }

  /* ---------------- theme & drawer ---------------- */

  const THEME_KEY = "komorebi-docs-theme";
  function readTheme() {
    try { return localStorage.getItem(THEME_KEY) === "dark" ? "dark" : "light"; } catch { return "light"; }
  }
  function applyTheme(theme) {
    const dark = theme === "dark";
    document.documentElement.setAttribute("data-theme", dark ? "dark" : "light");
    const sun = $(".ico-sun");
    const moon = $(".ico-moon");
    if (sun) sun.hidden = !dark;
    if (moon) moon.hidden = dark;
    try { localStorage.setItem(THEME_KEY, dark ? "dark" : "light"); } catch { /* ignore */ }
  }

  function setDrawer(open) {
    document.body.setAttribute("data-drawer", open ? "open" : "closed");
    $("#drawer-toggle").setAttribute("aria-expanded", String(open));
  }

  /* ---------------- boot ---------------- */

  function renderFromHash(force) {
    const parsed = parseHash();
    if (parsed.view === "home") renderHome();
    else if (force) renderDoc(parsed.item, parsed.anchor);
    else renderDoc(parsed.item, parsed.anchor);
    setDrawer(false);
  }

  function init() {
    applyTheme(readTheme());
    buildNav();

    $("#theme-toggle").addEventListener("click", () => {
      applyTheme(document.documentElement.getAttribute("data-theme") === "dark" ? "light" : "dark");
    });
    const topbar = $(".topbar");
    const onScroll = () => topbar.classList.toggle("is-scrolled", window.scrollY > 2);
    window.addEventListener("scroll", onScroll, { passive: true });
    onScroll();
    $("#drawer-toggle").addEventListener("click", () => setDrawer(document.body.getAttribute("data-drawer") !== "open"));
    $("#scrim").addEventListener("click", () => setDrawer(false));
    $("#nav-search").addEventListener("input", (e) => filterNav(e.target.value));
    document.addEventListener("keydown", (e) => {
      if (e.key === "Escape") setDrawer(false);
      if (e.key === "/" && !/^(INPUT|TEXTAREA)$/.test(document.activeElement.tagName)) {
        e.preventDefault();
        $("#nav-search").focus();
      }
    });
    navRoot.addEventListener("click", (e) => {
      if (e.target.closest("a")) setDrawer(false);
    });
    window.addEventListener("hashchange", () => renderFromHash());

    if (!window.marked || !window.DOMPurify) {
      view.replaceChildren(el("div", { class: "content" }, [el("div", { class: "errorbox" }, [
        el("h2", { text: "表示ライブラリを読み込めませんでした" }),
        el("p", { text: "ネットワークを確認して再読み込みしてください。Markdown の原文は GitHub で読めます。" }),
        el("a", { class: "btn btn-ghost", href: `https://github.com/${REPO}/tree/${REF}/docs`, target: "_blank", rel: "noopener noreferrer", text: "GitHub で docs/ を見る" }),
      ])]));
      return;
    }
    renderFromHash();
  }

  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", init);
  else init();
})();
