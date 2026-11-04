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

  /* motion comparison: one 9 s pass, played when it enters the viewport */
  const player = document.querySelector("[data-motion]");
  if (player) initMotion(player);

  function initMotion(root) {
    const svg = root.querySelector("svg");
    const paths = { a: svg.querySelector("#flow-a"), b: svg.querySelector("#flow-b") };
    const layers = { a: svg.querySelector("#packets-a"), b: svg.querySelector("#packets-b") };
    const counts = { a: svg.querySelector("#count-a"), b: svg.querySelector("#count-b") };
    const toggle = root.querySelector("#motion-toggle");
    const replay = root.querySelector("#motion-replay");
    const bar = root.querySelector("#motion-progress");
    const fill = bar.querySelector("span");
    const iconPlay = toggle.querySelector(".ico-play");
    const iconPause = toggle.querySelector(".ico-pause");
    if (!paths.a || !paths.b) return;

    let length;
    try {
      length = { a: paths.a.getTotalLength(), b: paths.b.getTotalLength() };
    } catch {
      return;
    }

    const DURATION = 9000;
    const SPAWN = 130;
    const TRAVEL = 950;

    let playing = false;
    let elapsed = 0;
    let last = 0;
    let raf = 0;
    let nextSpawn = 0;
    let sent = 0;
    let particles = [];
    let done = false;

    const make = (side) => {
      const circle = document.createElementNS("http://www.w3.org/2000/svg", "circle");
      circle.setAttribute("r", "5");
      circle.setAttribute("class", side === "a" ? "p-other" : "p-komorebi");
      circle.setAttribute("opacity", "0");
      layers[side].appendChild(circle);
      return circle;
    };

    const setToggle = (isPlaying) => {
      iconPlay.hidden = isPlaying;
      iconPause.hidden = !isPlaying;
      toggle.setAttribute("aria-pressed", String(isPlaying));
      toggle.setAttribute("aria-label", isPlaying ? "一時停止" : "再生");
    };

    const clear = () => {
      cancelAnimationFrame(raf);
      for (const p of particles) p.el.remove();
      particles = [];
      nextSpawn = 0;
      sent = 0;
      elapsed = 0;
      last = 0;
      done = false;
      counts.a.textContent = "0";
      counts.b.textContent = "0";
      fill.style.transform = "scaleX(0)";
      bar.setAttribute("aria-valuenow", "0");
    };

    const finish = () => {
      playing = false;
      done = true;
      root.setAttribute("data-state", "done");
      setToggle(false);
      fill.style.transform = "scaleX(1)";
      bar.setAttribute("aria-valuenow", "100");
    };

    const frame = (now) => {
      if (!playing) return;
      if (!last) last = now;
      elapsed += now - last;
      last = now;
      const t = Math.min(elapsed, DURATION);

      while (nextSpawn < t && nextSpawn < DURATION - TRAVEL) {
        particles.push({ side: "a", born: nextSpawn, el: make("a") });
        particles.push({ side: "b", born: nextSpawn, el: make("b") });
        nextSpawn += SPAWN;
      }

      const keep = [];
      for (const p of particles) {
        const age = t - p.born;
        if (age < 0) {
          p.el.setAttribute("opacity", "0");
          keep.push(p);
          continue;
        }
        const k = Math.min(age / TRAVEL, 1);
        const point = paths[p.side].getPointAtLength(k * length[p.side]);
        p.el.setAttribute("cx", String(point.x));
        p.el.setAttribute("cy", String(point.y));
        if (p.side === "a") {
          if (k >= 1 && !p.done) {
            p.done = true;
            sent += 1;
            counts.a.textContent = String(sent);
          }
          p.el.setAttribute("opacity", k >= 1 ? "0" : "1");
        } else {
          p.el.setAttribute("opacity", String(k >= 1 ? 0 : 1 - Math.pow(k, 3)));
        }
        if (age < TRAVEL + 260) keep.push(p);
        else p.el.remove();
      }
      particles = keep;

      fill.style.transform = `scaleX(${(t / DURATION).toFixed(4)})`;
      bar.setAttribute("aria-valuenow", String(Math.round((t / DURATION) * 100)));

      if (t >= DURATION) {
        finish();
        return;
      }
      raf = requestAnimationFrame(frame);
    };

    const play = () => {
      if (done) clear();
      if (playing) return;
      playing = true;
      last = 0;
      root.setAttribute("data-state", "playing");
      setToggle(true);
      raf = requestAnimationFrame(frame);
    };

    const pause = () => {
      if (!playing) return;
      playing = false;
      cancelAnimationFrame(raf);
      root.setAttribute("data-state", "paused");
      setToggle(false);
    };

    toggle.addEventListener("click", () => (playing ? pause() : play()));
    replay.addEventListener("click", () => {
      clear();
      play();
    });
    document.addEventListener("visibilitychange", () => {
      if (document.hidden) pause();
    });

    const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    if (reducedMotion) {
      counts.a.textContent = "72";
      counts.b.textContent = "0";
      fill.style.transform = "scaleX(1)";
      bar.setAttribute("aria-valuenow", "100");
      root.setAttribute("data-state", "done");
      done = true;
    } else if ("IntersectionObserver" in window) {
      const io = new IntersectionObserver((entries) => {
        for (const entry of entries) {
          if (entry.isIntersecting && !done && !playing) play();
          else if (!entry.isIntersecting) pause();
        }
      }, { threshold: 0.35 });
      io.observe(root);
    } else {
      play();
    }
  }
})();
