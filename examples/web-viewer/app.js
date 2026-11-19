/* Svod reference viewer — watch agents write, then git-diff their memory.
   Dependency-free. Talks only to the App API (same origin by default). */
(() => {
  "use strict";

  const $ = (id) => document.getElementById(id);
  const feed = $("feed");
  const feedEmpty = $("feedEmpty");
  const statusEl = $("status");
  const endpointEl = $("endpoint");
  const keyEl = $("apiKey");
  const offline = $("offline");

  // Default to the engine that serves this page, including a path prefix (a hosted engine sits
  // behind /t/<team>/); allow override.
  const defaultBase = location.protocol.startsWith("http")
    ? location.origin + location.pathname.replace(/\/[^/]*$/, "")
    : "http://127.0.0.1:7517";
  // Storage is per origin, and one origin can host many viewers (/t/<team>/): key it by the page's path.
  const scope = location.pathname.replace(/[^/]*$/, "");
  const endpointKey = scope === "/" ? "svod.endpoint" : "svod.endpoint:" + scope;
  let base = localStorage.getItem(endpointKey) || defaultBase;
  endpointEl.value = base;
  // A personal API key, needed when the engine is not this machine's own. Kept for this tab only,
  // and per engine URL, so one engine's key is never sent to another.
  const keyStoreKey = () => "svod.key:" + base;
  let apiKey = sessionStorage.getItem(keyStoreKey()) || "";
  keyEl.value = apiKey;

  // fetch() with the key, when there is one. A keyless request is the local engine's own UI.
  function api(url, opts = {}) {
    if (!apiKey) return fetch(url, opts);
    return fetch(url, { ...opts, headers: { ...(opts.headers || {}), Authorization: "Bearer " + apiKey } });
  }

  let ws = null;
  let reconnectTimer = null;
  const shownCommits = new Set(); // de-dupe agent.activity vs commit.created for the same commit

  // Hash the name, so an agent keeps its colour across reloads and in every pane.
  function colorFor(agent) {
    let h = 0;
    for (const ch of String(agent ?? "")) h = (h * 31 + ch.codePointAt(0)) >>> 0;
    return "c" + (h % 5);
  }

  // Lets a clickable row be reached with Tab and opened with Enter or Space. No role="button":
  // it would replace the row's listitem role, and a search result also holds its own tag buttons.
  function activatable(li, onActivate) {
    li.tabIndex = 0;
    li.addEventListener("click", onActivate);
    li.addEventListener("keydown", (ev) => {
      if (ev.target !== li || (ev.key !== "Enter" && ev.key !== " ")) return;
      ev.preventDefault();
      onActivate(ev);
    });
  }

  function setConnected(on) {
    statusEl.textContent = on ? "connected" : "disconnected";
    statusEl.className = "status " + (on ? "on" : "off");
    offline.classList.toggle("hidden", on);
    $("offlineUrl").textContent = base;
  }

  function fmtTime(ts) {
    const d = new Date(ts);
    return d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit" });
  }

  const VERBS = { write: "wrote", delete: "deleted", move: "moved", promote: "promoted", restore: "restored" };

  function addItem(ev) {
    const d = ev.data || {};
    // We render agent.activity (rich) and api-sourced commit.created; skip noisy duplicates.
    const path = d.path;
    if (!path) return;
    const commit = d.commit || null;
    if (commit && shownCommits.has(commit)) return; // already rendered via agent.activity
    if (commit) shownCommits.add(commit);

    const agent = d.agentId || d.author || (d.source === "watcher" ? "external" : "ui");
    const tool = d.tool || "wrote";

    feedEmpty?.remove();
    const li = document.createElement("li");
    const conflict = ev.type === "conflict";
    li.className = "item" + (conflict ? " conflict" : "");
    if (commit) { li.classList.add("has-diff"); li.dataset.commit = commit; li.dataset.path = path; }

    li.innerHTML =
      `<span class="agent ${colorFor(agent)}">${esc(agent)}</span>` +
      `<span>${conflict ? `<span class="flag">conflict</span>` : ""}` +
      `<span class="verb">${esc(VERBS[tool] || tool)}</span> <span class="path">${esc(path)}</span></span>` +
      `<span class="when">${fmtTime(ev.ts)}</span>`;

    if (commit) activatable(li, () => selectItem(li, path, commit));
    feed.prepend(li);
    while (feed.children.length > 200) feed.lastChild.remove();
  }

  // One commit is selected at a time, whether it was picked in the feed or in a note's history.
  function selectItem(li, path, commit) {
    document.querySelectorAll(".item.active, .history .commit.active").forEach((e) => e.classList.remove("active"));
    li.classList.add("active");
    showDiff(path, commit);
  }

  // Shared by the feed and a note's history: diff a commit against its parent.
  // Only the latest request may render, so a slow earlier diff can't overwrite a newer one.
  let diffSeq = 0;
  async function showDiff(path, commit) {
    const seq = ++diffSeq;
    const render = (text, added) => { if (seq === diffSeq) renderDiff(text, added); };
    $("diffPath").textContent = path + " @ " + commit.slice(0, 8);
    render("loading…");
    try {
      const url = `${base}/api/v1/file/diff?path=${encodeURIComponent(path)}` +
        `&from=${encodeURIComponent(commit + "~1")}&to=${encodeURIComponent(commit)}`;
      const r = await api(url);
      if (r.ok) {
        const j = await r.json();
        render(j.diff && j.diff.trim() ? j.diff : "(no textual change)");
      } else {
        // first commit of the file → no parent; show the content as freshly added
        const rev = await api(`${base}/api/v1/file/revision?path=${encodeURIComponent(path)}&revision=${encodeURIComponent(commit)}`);
        if (rev.ok) {
          const j = await rev.json();
          render(j.content.split("\n").map((l) => "+" + l).join("\n"), true);
        } else {
          render("(diff unavailable)");
        }
      }
    } catch (e) {
      render("(diff failed: " + e + ")");
    }
  }

  function renderDiff(text, added) {
    // The pane head already names the file and commit, so git's own header lines are noise.
    const lines = text.split("\n").filter((l) => !l.startsWith("diff --git ") && !l.startsWith("index "));
    const out = lines.map((line) => {
      const c = esc(line);
      if (line.startsWith("@@")) return `<span class="hunk">${c}</span>`;
      if (line.startsWith("+") && !line.startsWith("+++")) return `<span class="add">${c}</span>`;
      if (line.startsWith("-") && !line.startsWith("---")) return `<span class="del">${c}</span>`;
      if (line.startsWith("diff ") || line.startsWith("index ") || line.startsWith("+++") || line.startsWith("---"))
        return `<span class="meta">${c}</span>`;
      return c;
    }).join("\n");
    $("diff").innerHTML = out;
  }

  // ── search ────────────────────────────────────────────────────────────
  const searchStatus = $("searchStatus");
  const searchResults = $("searchResults");

  function splitTags(s) {
    return s.split(",").map((t) => t.trim().replace(/^#/, "")).filter(Boolean);
  }

  // Only the latest search may render, so a slow earlier response can't overwrite newer results.
  let searchSeq = 0;
  async function runSearch(e) {
    if (e) e.preventDefault();
    const seq = ++searchSeq;
    const q = $("searchQuery").value.trim();
    const mode = $("searchMode").value;
    const tags = splitTags($("searchTags").value);
    const pathPrefix = $("searchPath").value.trim();
    if (!q && !tags.length && !pathPrefix) {
      searchStatus.textContent = "type a query or pick a tag";
      searchResults.innerHTML = "";
      return;
    }
    // One `tags` parameter per tag; q is left out when blank (filter-only browse).
    const parts = [];
    if (q) parts.push("q=" + encodeURIComponent(q));
    parts.push("mode=" + encodeURIComponent(mode), "limit=20");
    tags.forEach((t) => parts.push("tags=" + encodeURIComponent(t)));
    if (pathPrefix) parts.push("pathPrefix=" + encodeURIComponent(pathPrefix));
    searchStatus.textContent = "searching…";
    try {
      const r = await api(`${base}/api/v1/search?${parts.join("&")}`);
      if (!r.ok) {
        let msg = "search failed (" + r.status + ")";
        try { const j = await r.json(); if (j && j.message) msg = j.message; } catch (_) {}
        if (seq !== searchSeq) return;
        searchStatus.textContent = msg;
        searchResults.innerHTML = "";
        return;
      }
      const j = await r.json();
      if (seq === searchSeq) renderResults(j);
    } catch (err) {
      if (seq !== searchSeq) return;
      searchStatus.textContent = "search failed: " + err;
      searchResults.innerHTML = "";
    }
  }

  function renderResults(j) {
    const hits = j.hits || [];
    const mode = String(j.mode || "").toLowerCase();
    let status = `${hits.length} result${hits.length === 1 ? "" : "s"} · ${mode}`;
    // A failed query embed in semantic mode has no keyword leg to fall back on, so only claim
    // keyword results when there are some.
    if ((j.degraded || []).includes("semantic"))
      status += " · semantic unavailable" + (hits.length ? " — showing keyword results" : "");
    searchStatus.textContent = status;
    searchResults.innerHTML = "";
    if (!hits.length) {
      const li = document.createElement("li");
      li.className = "empty";
      li.textContent = "No notes match.";
      searchResults.append(li);
      return;
    }
    for (const h of hits) {
      const li = document.createElement("li");
      li.className = "result";
      li.dataset.path = h.path;
      const badges =
        (h.matchedKeyword ? `<span class="badge kw">keyword</span>` : "") +
        (h.matchedSemantic ? `<span class="badge sem">semantic</span>` : "");
      const chips = (h.tags || []).map((t) =>
        `<button type="button" class="tag-chip" data-tag="${esc(t)}">#${esc(t)}</button>`).join("");
      li.innerHTML =
        `<div class="result-head"><span class="path">${esc(h.path)}</span><span class="badges">${badges}</span></div>` +
        (h.heading ? `<div class="heading">${esc(h.heading)}</div>` : "") +
        `<div class="snippet">${esc(h.snippet || "")}</div>` +
        (chips ? `<div class="chips">${chips}</div>` : "");
      activatable(li, (ev) => {
        const chip = ev.target.closest(".tag-chip");
        if (chip) {
          ev.stopPropagation();
          $("searchTags").value = chip.dataset.tag;
          runSearch();
          return;
        }
        document.querySelectorAll(".result.active").forEach((x) => x.classList.remove("active"));
        li.classList.add("active");
        openNote(h.path);
      });
      searchResults.append(li);
    }
  }

  // Only the latest opened note may render, so a slow earlier note can't overwrite (or append its
  // history to) the one the user picked last.
  let noteSeq = 0;
  async function openNote(path) {
    const seq = ++noteSeq;
    const p = encodeURIComponent(path);
    $("note").classList.remove("hidden");
    $("notePath").textContent = path;
    $("noteMeta").textContent = "";
    $("noteContent").textContent = "loading…";
    const history = $("noteHistory");
    history.innerHTML = "";
    try {
      const [fr, hr] = await Promise.all([
        api(`${base}/api/v1/file?path=${p}`),
        api(`${base}/api/v1/file/history?path=${p}&max=50`),
      ]);
      const f = fr.ok ? await fr.json() : null;
      const h = hr.ok ? await hr.json() : null;
      if (seq !== noteSeq) return;
      if (f) {
        $("noteContent").textContent = f.content;
        const by = f.lastModifiedBy;
        if (by) $("noteMeta").textContent = "last modified by " + (by.agent ? `${by.agent} (${by.name})` : by.name);
      } else {
        $("noteContent").textContent = "(note unavailable)";
      }
      for (const c of (h && h.commits) || []) {
        const li = document.createElement("li");
        li.className = "commit";
        // History spans days, so show the date too (the live feed's time-of-day is not enough).
        const when = new Date(c.epochSeconds * 1000).toLocaleString([], {
          year: "numeric", month: "short", day: "numeric", hour: "2-digit", minute: "2-digit",
        });
        li.innerHTML =
          `<span class="mono sha">${esc(c.commit.slice(0, 8))}</span>` +
          `<span class="agent ${colorFor(c.agent || c.author)}">${esc(c.agent || c.author)}</span>` +
          `<span class="when">${esc(when)}</span>` +
          `<span class="msg">${esc(c.message)}</span>`;
        activatable(li, () => selectItem(li, path, c.commit));
        history.append(li);
      }
    } catch (err) {
      if (seq === noteSeq) $("noteContent").textContent = "(note failed: " + err + ")";
    }
  }

  // ── memory review ─────────────────────────────────────────────────────
  // The queue (GET /api/v1/memory/review) lists only provisional or needs-review memories, so a row
  // that was approved or declined here drops out of it. Such rows are kept in `reviewed` (this
  // session only) with the returned status and revision, so the person can still Reopen them.
  const reviewList = $("reviewList");
  const reviewMsg = $("reviewMsg");
  const reviewed = new Map(); // path → { item, status, revision }
  const reviewItems = new Map(); // path → item, from the last list

  let reviewSeq = 0;
  let reviewTimer = null;

  function scheduleReview() {
    clearTimeout(reviewTimer);
    reviewTimer = setTimeout(loadReview, 500);
  }

  async function loadReview() {
    clearTimeout(reviewTimer);
    const seq = ++reviewSeq;
    try {
      const r = await api(`${base}/api/v1/memory/review?limit=100`, { cache: "no-store" });
      if (!r.ok) {
        const msg = await errorText(r, "review list failed");
        if (seq === reviewSeq) { reviewMsg.textContent = msg; $("reviewCount").textContent = ""; }
        return;
      }
      const j = await r.json();
      if (seq === reviewSeq) renderReview(j);
    } catch (err) {
      if (seq === reviewSeq) reviewMsg.textContent = "review list failed: " + err;
    }
  }

  async function errorText(r, prefix) {
    try { const j = await r.json(); if (j && j.message) return j.message; } catch (_) {}
    return `${prefix} (${r.status})`;
  }

  function renderReview(j) {
    const items = j.items || [];
    const total = typeof j.total === "number" ? j.total : items.length;
    reviewItems.clear();
    for (const i of items) reviewItems.set(i.path, i);
    // A memory back in the queue (reopened elsewhere) is shown as a queue row again.
    for (const p of [...reviewed.keys()]) if (reviewItems.has(p)) reviewed.delete(p);

    $("reviewCount").textContent =
      `${total} waiting` + (total > items.length ? ` · showing ${items.length}` : "");
    reviewList.innerHTML = "";
    if (!items.length && !reviewed.size) {
      const li = document.createElement("li");
      li.className = "empty";
      li.textContent = "Nothing is waiting for review.";
      reviewList.append(li);
      return;
    }
    for (const it of items) reviewList.append(reviewRow(it, null));
    for (const [, done] of reviewed) reviewList.append(reviewRow(done.item, done));
  }

  function reviewRow(it, done) {
    const li = document.createElement("li");
    const flagged = !done && (it.needsReview || it.contradicts);
    li.className = "review-item" + (done ? " done" : "") + (flagged ? " flagged" : "");
    li.dataset.path = it.path;
    li.dataset.revision = done ? done.revision : it.revision;
    const badges =
      (!done && it.needsReview ? `<span class="badge warn">needs review</span>` : "") +
      (!done && it.contradicts ? `<span class="badge warn">contradicts</span>` : "") +
      (done ? `<span class="badge done">${esc(done.status)}</span>` : "");
    const meta = [it.type, done ? null : it.status].filter(Boolean).map(esc);
    if (typeof it.confidence === "number") meta.push("confidence " + esc(it.confidence.toFixed(2)));
    if (it.source) meta.push("by " + esc(it.source));
    const actions = done
      ? `<button type="button" class="ghost" data-action="reopen">Reopen</button>`
      : `<button type="button" data-action="approve">Approve</button>` +
        `<button type="button" class="ghost" data-action="decline">Decline</button>`;
    li.innerHTML =
      `<div class="result-head"><span class="review-title">${esc(it.title || it.path)}</span>` +
      `<span class="badges">${badges}</span></div>` +
      `<div class="path">${esc(it.path)}</div>` +
      (it.excerpt ? `<div class="excerpt">${esc(it.excerpt)}</div>` : "") +
      (meta.length ? `<div class="muted">${meta.join(" · ")}</div>` : "") +
      (it.contradicts ? `<div class="muted link">contradicts <span class="mono">${esc(it.contradicts)}</span></div>` : "") +
      (it.supersedes ? `<div class="muted link">supersedes <span class="mono">${esc(it.supersedes)}</span></div>` : "") +
      `<div class="review-actions">${actions}</div>`;
    return li;
  }

  async function act(path, action, revision, item) {
    const row = [...reviewList.querySelectorAll(".review-item")].find((li) => li.dataset.path === path);
    const buttons = row ? row.querySelectorAll("button") : [];
    buttons.forEach((b) => { b.disabled = true; });
    reviewMsg.textContent = `${action} ${path}…`;
    try {
      const r = await api(`${base}/api/v1/memory/review`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ path, action, expectedRevision: revision }),
      });
      if (r.ok) {
        const j = await r.json();
        if (action === "reopen") reviewed.delete(path);
        else reviewed.set(path, { item: item || { path, title: path }, status: j.status, revision: j.revision });
        reviewMsg.textContent = `${path} → ${j.status}`;
      } else if (r.status === 409) {
        let j = null;
        try { j = await r.json(); } catch (_) {}
        reviewed.delete(path);
        reviewMsg.textContent = j && j.error === "superseded" && j.message
          ? j.message
          : `${path} changed since you loaded it, reloaded`;
      } else if (r.status === 401 || r.status === 403) {
        reviewMsg.textContent = "This engine needs a writer or admin to review memories.";
      } else {
        reviewMsg.textContent = await errorText(r, `${action} failed`);
      }
    } catch (err) {
      reviewMsg.textContent = `${action} failed: ${err}`;
    }
    await loadReview();
  }

  reviewList.addEventListener("click", (ev) => {
    const btn = ev.target.closest("button[data-action]");
    if (!btn) return;
    const li = btn.closest(".review-item");
    const path = li.dataset.path;
    const done = reviewed.get(path);
    // Keep the listed item so an acted-on row can still be drawn after it leaves the queue.
    const item = done ? done.item : reviewItems.get(path);
    act(path, btn.dataset.action, li.dataset.revision, item);
  });

  function onEvent(ev) {
    const tool = (ev.data || {}).tool;
    switch (ev.type) {
      case "agent.activity":
      case "conflict":
      case "commit.created":
        // addItem de-dupes by commit id, so an MCP write (agent.activity + commit.created)
        // appears once, while API/watcher commits (commit.created only) still show.
        addItem(ev);
        if (tool === "memory.review" || tool === "remember") scheduleReview();
        break;
      case "index.updated":
        refreshIndexInfo();
        scheduleReview();
        break;
      case "engine.status":
        // no-op visual; connection pill already reflects it
        break;
    }
  }

  async function refreshIndexInfo() {
    try {
      const r = await api(`${base}/api/v1/index/status`);
      if (r.ok) {
        const j = await r.json();
        $("indexInfo").textContent = `${j.docCount} chunks indexed · ${j.model}`;
      }
    } catch (_) {}
  }

  function connectWs() {
    const wsUrl = base.replace(/^http/, "ws") + "/api/v1/events";
    // A browser cannot send a header on a WebSocket: the key goes as an offered subprotocol.
    ws = apiKey ? new WebSocket(wsUrl, ["svod.events", "svod.key." + apiKey]) : new WebSocket(wsUrl);
    ws.onmessage = (m) => { try { onEvent(JSON.parse(m.data)); } catch (_) {} };
    ws.onclose = () => { setConnected(false); scheduleReconnect(); };
    ws.onerror = () => { try { ws.close(); } catch (_) {} };
  }

  function scheduleReconnect() {
    clearTimeout(reconnectTimer);
    reconnectTimer = setTimeout(connect, 1500);
  }

  async function connect() {
    try {
      const r = await fetch(`${base}/ready`, { cache: "no-store" });
      if (!r.ok) throw new Error("not ready");
      setConnected(true);
      // /ready is open; this one says whether the engine accepts us. Without a key the socket
      // would only be refused and retried every 1.5 s.
      const probe = await api(`${base}/api/v1/index/status`, { cache: "no-store" });
      if (probe.status === 401) {
        statusEl.textContent = apiKey ? "key rejected" : "API key needed";
        statusEl.className = "status off";
        return;
      }
      refreshIndexInfo();
      loadReview();
      connectWs();
    } catch (e) {
      setConnected(false);
      scheduleReconnect();
    }
  }

  function esc(s) {
    return String(s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  }

  endpointEl.addEventListener("change", () => {
    base = endpointEl.value.trim().replace(/\/$/, "");
    localStorage.setItem(endpointKey, base);
    apiKey = sessionStorage.getItem(keyStoreKey()) || "";
    keyEl.value = apiKey;
    reviewed.clear(); // acted-on rows belong to the previous engine
    reviewMsg.textContent = "";
    if (ws) try { ws.close(); } catch (_) {}
    connect();
  });
  keyEl.addEventListener("change", () => {
    apiKey = keyEl.value.trim();
    if (apiKey) sessionStorage.setItem(keyStoreKey(), apiKey);
    else sessionStorage.removeItem(keyStoreKey());
    clearTimeout(reconnectTimer);
    if (ws) try { ws.onclose = null; ws.close(); } catch (_) {}
    connect();
  });
  $("retry").addEventListener("click", connect);
  $("searchForm").addEventListener("submit", runSearch);
  $("reviewRefresh").addEventListener("click", () => { reviewMsg.textContent = ""; loadReview(); });
  $("searchMode").addEventListener("change", () => {
    if ($("searchQuery").value.trim()) runSearch();
  });

  connect();
})();
