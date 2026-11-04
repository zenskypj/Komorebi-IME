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
        ["manual/00-hajimete", "はじめての Komorebi IME", "概要・対応環境・インストール・初期設定", "docs/manual/00-hajimete.md", "install setup はじめに"],
        ["manual/01-nyuryoku", "入力方式と基本操作", "フリック・QWERTY・カーソル・ツールバー", "docs/manual/01-nyuryoku.md", "keyboard 操作"],
        ["manual/02-henkan", "変換と候補", "文節変換・候補の並び・補正・学習・AI 優先", "docs/manual/02-henkan.md", "conversion 変換"],
        ["manual/03-ai", "AI 機能", "zenzai-plus・Karukan・AI 文章校正・文体補正", "docs/manual/03-ai.md", "ai zenzai karukan 校正"],
        ["manual/04-jisho", "辞書", "ユーザー辞書・追加システム辞書・意味辞書", "docs/manual/04-jisho.md", "dictionary 辞書"],
        ["manual/05-gaikan", "外観と配列", "テーマ・背景・配列スタジオ・アプリ別プロファイル", "docs/manual/05-gaikan.md", "theme layout profile 外観"],
        ["manual/06-privacy", "プライバシーとセキュリティ", "送信するもの・保存データ・暗号化・削除", "docs/manual/06-privacy.md", "privacy security 安全"],
        ["manual/07-iten", "データの移行", "バックアップ・NFC 移行・Meets Beam", "docs/manual/07-iten.md", "backup nfc beam 移行"],
        ["manual/08-troubleshoot", "トラブルシューティング", "症状別の原因と対処", "docs/manual/08-troubleshoot.md", "trouble 不具合"],
        ["manual/09-spec", "詳細仕様", "技術仕様・ファイル・制約・ライセンス・補足の扱い", "docs/manual/09-spec.md", "spec 仕様 補足"],
        ["manual/10-faq", "よくある質問", "短い Q&A", "docs/manual/10-faq.md", "faq 質問"],
      ],
    },
    {
      group: "比較・検証",
      items: [
        ["manual/11-hikaku", "他IME検証", "閉源IMEの説明と実態、Komorebi の立場", "docs/manual/11-hikaku.md", "compare privacy gboard simeji 比較 検証 批判"],
      ],
    },
    {
      group: "設計・仕様",
      items: [
        ["conversion-ui", "変換とキーボード操作", "候補・文節・ライブ予測の検証範囲", "docs/conversion-ui.md", "conversion ui"],
        ["keyboard-strengthening", "キーボード強化", "文節操作・再変換・学習管理・編集パネル", "docs/keyboard-strengthening.md", "keyboard"],
        ["zenzai-plus", "zenzai-plus", "ニューラル変換と TTT 学習・任意ビルド", "docs/zenzai-plus.md", "zenzai ai"],
        ["proofreading", "端末内のAI文章校正", "Qwen / Gemma モデル・プロンプト・実行部", "docs/proofreading.md", "proofread 校正"],
        ["profile-encryption", "保存データの暗号化", "Keystore・Mozc パッチ・移行の設計", "docs/profile-encryption.md", "encryption keystore"],
        ["extra-dictionaries/README", "追加システム辞書", "収録範囲・ライセンス・再生成手順", "docs/extra-dictionaries/README.md", "dictionary neologd skk"],
        ["nfc-migration", "NFC による設定・学習の移行", "ECDH + AES-GCM・対象範囲", "docs/nfc-migration.md", "nfc migration"],
        ["meets-beam", "Meets Beam", "テキスト・URL・アプリの NFC 送受信", "docs/meets-beam.md", "beam nfc"],
        ["license-audit", "ライセンス監査", "リリースごとの確認項目", "docs/license-audit.md", "license"],
        ["upstream-sync", "上流更新の統合状況", "フォーク元との差分と採用方針", "docs/upstream-sync.md", "upstream fork"],
        ["release-readiness", "正式版リリース受け入れ基準", "自動ゲート・端末マトリクス", "docs/release-readiness.md", "release 基準"],
      ],
    },
    {
      group: "リリースノート",
      items: [
        ["releases/v0.3.0-rc.35-dev", "rc.35-dev", "統合データセット（複数辞書の同時有効化）", "docs/releases/v0.3.0-rc.35-dev.md", "release rc35"],
        ["releases/v0.3.0-rc.34-dev", "rc.34-dev", "辞書の AI 優先・NFC 移行の修正", "docs/releases/v0.3.0-rc.34-dev.md", "release rc34"],
        ["releases/v0.3.0-rc.32-dev", "rc.32-dev", "高さ調整・順位・次文節への移行", "docs/releases/v0.3.0-rc.32-dev.md", "release rc32"],
        ["releases/v0.3.0-rc.31-dev", "rc.31-dev", "変換操作とキーボード操作の改善", "docs/releases/v0.3.0-rc.31-dev.md", "release rc31"],
        ["releases/v0.3.0-rc.30-dev", "rc.30-dev", "変換の安定性の改善", "docs/releases/v0.3.0-rc.30-dev.md", "release rc30"],
        ["releases/v0.3.0-rc.29-dev", "rc.29-dev", "校正の結果保護", "docs/releases/v0.3.0-rc.29-dev.md", "release rc29"],
        ["releases/v0.3.0-rc.28-dev", "rc.28-dev", "校正と変換の修正", "docs/releases/v0.3.0-rc.28-dev.md", "release rc28"],
        ["releases/v0.3.0-rc.27-dev", "rc.27-dev", "文節カーソル同期", "docs/releases/v0.3.0-rc.27-dev.md", "release rc27"],
        ["releases/v0.3.0-rc.26-dev", "rc.26-dev", "ツールと初回変換・入力ガイド", "docs/releases/v0.3.0-rc.26-dev.md", "release rc26"],
        ["releases/v0.3.0-rc.25-dev", "rc.25-dev", "定型文・文節操作・カーソル", "docs/releases/v0.3.0-rc.25-dev.md", "release rc25"],
      ],
    },
    {
      group: "リポジトリ",
      items: [
        ["README", "README", "プロジェクト全体の説明", "README.md", "readme 概要"],
        ["PRIVACY", "プライバシーポリシー", "公開ポリシー（アプリ内表示と同一）", "PRIVACY.md", "privacy policy"],
      ],
    },
  ];

  const ITEMS = INDEX.flatMap((g) => g.items.map(([id, title, blurb, path, tags]) => ({ group: g.group, id, title, blurb, path, tags })));
  const BY_ID = new Map(ITEMS.map((it) => [it.id, it]));
  const BY_PATH = new Map(ITEMS.map((it) => [it.path, it]));

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
    const retry = el("button", { class: "btn btn-primary", type: "button", text: "再試行" });
    retry.addEventListener("click", () => renderFromHash(true));
    row.append(retry);
    if (item) row.append(el("a", { class: "btn btn-ghost", href: BLOB + item.path, target: "_blank", rel: "noopener noreferrer", text: "GitHub で開く" }));
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
    box.append(el("div", { class: "row" }, [el("a", { class: "btn btn-primary", href: "#/", text: "ホームへ戻る" })]));
    view.replaceChildren(el("div", { class: "content" }, [box]));
  }

  /* ---------------- home ---------------- */

  const BADGES = [
    ["release", `https://img.shields.io/github/v/release/${REPO}?include_prereleases&sort=semver&style=for-the-badge&label=release&color=146b46`],
    ["license", `https://img.shields.io/github/license/${REPO}?style=for-the-badge&color=146b46`],
    ["android", `https://img.shields.io/badge/Android-7.0%2B%20(API%2024)-3ddc84?style=for-the-badge&logo=android&logoColor=white`],
    ["offline", `https://img.shields.io/badge/変換-オフライン-146b46?style=for-the-badge`],
    ["telemetry", `https://img.shields.io/badge/telemetry-なし-146b46?style=for-the-badge`],
    ["mozc", `https://img.shields.io/badge/Mozc-BSD--3--Clause-4a6dd8?style=for-the-badge`],
    ["abi", `https://img.shields.io/badge/ABI-arm64--v8a%20%7C%20armeabi--v7a%20%7C%20x86__64%20%7C%20x86-4b5563?style=for-the-badge`],
    ["ethics", `https://gijutsusharin.li/badge.svg`],
  ];

  const SHIELD_SVG = `
    <svg class="emblem" viewBox="0 0 120 134" role="img" aria-labelledby="emblem-title">
      <title id="emblem-title">端末内で完結することを表す盾</title>
      <path d="M60 3 113 21v49c0 34-24 55-53 63C31 125 7 104 7 70V21Z"
            fill="var(--md-sys-color-primary-container)" stroke="var(--md-sys-color-primary)" stroke-width="2.5"/>
      <g stroke="var(--md-sys-color-primary)" stroke-width="4" stroke-linecap="round" opacity="0.9">
        <path d="M60 22v-7M86 32l5-5M34 32l-5-5"/>
      </g>
      <circle cx="60" cy="52" r="16" fill="var(--md-sys-color-primary)"/>
      <path d="M60 78c-14 0-25 8-25 8s11 9 25 9 25-9 25-9-11-8-25-8Z" fill="var(--md-sys-color-primary)" opacity="0.35"/>
      <path d="M60 84v22" stroke="var(--md-sys-color-primary)" stroke-width="4" stroke-linecap="round" opacity="0.5"/>
    </svg>`;

  function renderHome() {
    document.title = "Komorebi IME — ユーザーマニュアルとドキュメント";
    const home = el("div", { class: "home" });

    const badges = el("div", { class: "badges" });
    BADGES.forEach(([name, src]) => badges.append(el("img", { src, alt: name, loading: "lazy", height: "26" })));

    const hero = el("section", { class: "hero" }, [
      el("div", { class: "hero-copy" }, [
        el("p", { class: "eyebrow" }, [el("span", { class: "dot" }), document.createTextNode("Android 日本語IME · オフライン")]),
        el("h1", {}, [document.createTextNode("言葉を、"), el("span", { class: "lit", text: "木漏れ日" }), document.createTextNode("のように。")]),
        el("p", { class: "lede", text: "Mozc をネイティブに組み込んだ日本語IME。変換も AI も端末内で完結し、入力内容を外へ送りません。このサイトは、はじめての人向けの手順から細かい仕様までを一冊にまとめたユーザーマニュアルです。" }),
        el("div", { class: "cta-row" }, [
          el("a", { class: "btn btn-primary", href: "#/doc/manual/00-hajimete", text: "はじめての設定を読む" }),
          el("a", { class: "btn btn-ghost", href: `https://github.com/${REPO}/releases/latest`, target: "_blank", rel: "noopener noreferrer", text: "最新版をダウンロード" }),
        ]),
        badges,
      ]),
      el("figure", { class: "shield-card" }, [
        Object.assign(document.createElement("div"), { innerHTML: SHIELD_SVG }).firstElementChild,
        el("figcaption", {}, [el("strong", { text: "入力は盾の内側に" }), document.createTextNode("変換・AI・辞書はすべて端末内。広告・解析・クラッシュ SDK なし。")]),
      ]),
    ]);

    const steps = el("ol", { class: "steps" });
    [
      ["インストール", "GitHub Releases から APK を取得して開く"],
      ["有効化", "設定 →「キーボードを有効化」で Komorebi IME をオン"],
      ["選択", "「キーボードを選択」で Komorebi IME を選ぶ"],
      ["試し入力", "設定の「試し入力」で変換を確認"],
    ].forEach(([t, s]) => steps.append(el("li", {}, [el("strong", { text: t }), el("span", { text: s })])));

    home.append(
      hero,
      el("section", { class: "section" }, [el("h2", { text: "5 分で使いはじめる" }), el("p", { class: "sub", text: "インストール後の初期設定は 4 ステップです。AI やネット辞書は使う人だけが後から追加します。" }), steps]),
    );
    home.append(tileSection("マニュアル", "目的から探す", "manual/00-hajimete"));
    home.append(tileSection("比較・検証", "他IMEの公開情報と並べて確認する", null));
    home.append(tileSection("設計・仕様", "細かい仕様と検証範囲", "conversion-ui"));
    home.append(tileSection("リリースノート", "版ごとの変更", null));

    home.append(el("p", { class: "footnote" }, [
      el("span", { text: "すべての本文は GitHub の docs/ から取得して表示しています。" }),
      el("a", { href: `https://github.com/${REPO}/tree/${REF}/docs`, target: "_blank", rel: "noopener noreferrer", text: "docs/ を GitHub で見る ↗" }),
      el("a", { href: `https://github.com/${REPO}/releases`, target: "_blank", rel: "noopener noreferrer", text: "すべてのリリース ↗" }),
    ]));

    view.replaceChildren(home);
    updateNavCurrent("");
  }

  function tileSection(groupName, sub, _unused) {
    const group = INDEX.find((g) => g.group === groupName);
    const grid = el("div", { class: "grid" });
    group.items.forEach((it, i) => {
      const item = BY_ID.get(it[0]);
      grid.append(el("a", { class: "tile", href: `#/doc/${item.id}` }, [
        el("h3", {}, [el("span", { class: "num", text: String(i + 1).padStart(2, "0") }), document.createTextNode(item.title)]),
        el("p", { text: item.blurb }),
      ]));
    });
    return el("section", { class: "section" }, [el("h2", { text: groupName }), el("p", { class: "sub", text: sub }), grid]);
  }

  /* ---------------- navigation ---------------- */

  const navRoot = $("#nav");
  const navEmpty = $("#nav-empty");

  function buildNav() {
    INDEX.forEach((group) => {
      const list = el("ul", { class: "nav-list" });
      group.items.forEach(([id, title]) => {
        list.append(el("li", {}, [el("a", { href: `#/doc/${id}`, "data-nav-id": id, text: title })]));
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
