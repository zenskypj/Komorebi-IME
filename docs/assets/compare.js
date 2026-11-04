/* Komorebi IME — switch page. No dependencies. */
(() => {
  "use strict";

  const root = document.documentElement;
  const THEME_KEY = "komorebi-docs-theme";

  /* theme: share the key used by the docs SPA so the choice carries over */
  const readTheme = () => {
    try { return localStorage.getItem(THEME_KEY) === "dark" ? "dark" : "light"; } catch { return "light"; }
  };
  const applyTheme = (theme) => {
    root.setAttribute("data-theme", theme);
    try { localStorage.setItem(THEME_KEY, theme); } catch { /* ignore */ }
  };
  applyTheme(readTheme());
  document.getElementById("theme").addEventListener("click", () => {
    applyTheme(root.getAttribute("data-theme") === "dark" ? "light" : "dark");
  });

  /* nav border on scroll */
  const nav = document.getElementById("nav");
  const onScroll = () => nav.classList.toggle("is-scrolled", window.scrollY > 4);
  window.addEventListener("scroll", onScroll, { passive: true });
  onScroll();

  /* reveal sections once */
  const revealItems = Array.from(document.querySelectorAll("[data-reveal]"));
  const reduced = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  if (!reduced && "IntersectionObserver" in window) {
    const io = new IntersectionObserver((entries) => {
      for (const entry of entries) {
        if (!entry.isIntersecting) continue;
        entry.target.classList.add("is-in");
        io.unobserve(entry.target);
      }
    }, { rootMargin: "0px 0px -8% 0px", threshold: 0.08 });
    revealItems.forEach((el) => io.observe(el));
  } else {
    revealItems.forEach((el) => el.classList.add("is-in"));
  }

  /* highlight gallery: paddles, keyboard, disabled edges */
  const track = document.getElementById("track");
  const prev = document.getElementById("prev");
  const next = document.getElementById("next");
  if (track && prev && next) {
    const step = () => {
      const card = track.querySelector(".cmp-card");
      return card ? Math.round(card.getBoundingClientRect().width + 16) : 340;
    };
    const sync = () => {
      const max = track.scrollWidth - track.clientWidth - 1;
      prev.disabled = track.scrollLeft <= 0;
      next.disabled = track.scrollLeft >= max;
    };
    const move = (dir) => track.scrollBy({ left: dir * step(), behavior: reduced ? "auto" : "smooth" });
    prev.addEventListener("click", () => move(-1));
    next.addEventListener("click", () => move(1));
    track.addEventListener("scroll", sync, { passive: true });
    window.addEventListener("resize", sync);
    track.addEventListener("keydown", (event) => {
      if (event.key === "ArrowRight") { event.preventDefault(); move(1); }
      if (event.key === "ArrowLeft") { event.preventDefault(); move(-1); }
    });
    sync();
  }

  /* Label every product cell so the table can stack on phones without side-scrolling. */
  const comparison = document.querySelector(".cmp-table");
  if (comparison) {
    const labels = Array.from(comparison.querySelectorAll("thead th")).map((th) => th.dataset.label || "");
    comparison.querySelectorAll("tbody tr:not(.group)").forEach((row) => {
      Array.from(row.querySelectorAll("td")).forEach((cell, index) => {
        const label = labels[index + 1];
        if (label) cell.setAttribute("data-product", label);
      });
    });
    buildMobileCards(comparison);
    document.body.classList.add("cards-ready");
  }

  /** Phones get cards instead of a table. Built once; CSS switches the display. */
  function buildMobileCards(table) {
    const labels = Array.from(table.querySelectorAll("thead th")).map((th) => th.dataset.label || "");
    const root = document.createElement("div");
    root.className = "cmp-cards";
    let group = root;

    table.querySelectorAll("tbody tr").forEach((row) => {
      if (row.classList.contains("group")) {
        group = document.createElement("section");
        group.className = "cmp-card-group";
        const heading = document.createElement("h3");
        heading.textContent = row.textContent.trim();
        group.appendChild(heading);
        root.appendChild(group);
        return;
      }

      const feature = document.createElement("article");
      feature.className = "cmp-feature";
      const name = document.createElement("h4");
      name.textContent = (row.querySelector("th") ? row.querySelector("th").textContent : "").trim();
      feature.appendChild(name);

      const list = document.createElement("ul");
      list.className = "cmp-marks";
      Array.from(row.querySelectorAll("td")).forEach((cell, index) => {
        const item = document.createElement("li");
        if (cell.classList.contains("col-komorebi")) item.classList.add("on");
        const who = document.createElement("span");
        who.className = "who";
        who.textContent = labels[index + 1] || "";
        item.appendChild(who);
        const mark = cell.querySelector(".m");
        if (mark) item.appendChild(mark.cloneNode(true));
        const sr = cell.querySelector(".sr-only");
        if (sr) {
          const label = document.createElement("span");
          label.className = "sr-only";
          label.textContent = " " + sr.textContent;
          item.appendChild(label);
        }
        list.appendChild(item);
      });

      feature.appendChild(list);
      group.appendChild(feature);
    });

    const wrap = table.closest(".cmp-table-wrap");
    if (wrap) wrap.insertAdjacentElement("afterend", root);
  }

  /* FAQ: close the others so the page does not jump far away */
  const faq = document.querySelector(".cmp-faq");
  if (faq) {
    faq.addEventListener("toggle", (event) => {
      const opened = event.target;
      if (!(opened instanceof HTMLDetailsElement) || !opened.open) return;
      faq.querySelectorAll("details[open]").forEach((other) => {
        if (other !== opened) other.open = false;
      });
    }, true);
  }
})();
