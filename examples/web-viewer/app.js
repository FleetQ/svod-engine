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

  // ── sync conflicts ────────────────────────────────────────────────────
  // GET /api/v1/conflicts lists what sync could not merge, with base/ours/theirs for a 3-way view.
  // A row is settled with POST /api/v1/conflicts/resolve: keepMine, acceptIncoming or an edited merge.
  const conflictList = $("conflictList");
  const conflictMsg = $("conflictMsg");
  const conflictItems = new Map(); // path → entry, from the last list
  // path → { ts, text }: unsaved merge text, kept across reloads while the conflict is unchanged
  // (the store gives a re-raised conflict, with a new incoming or local version, a new ts).
  const conflictDrafts = new Map();
  const conflictBusy = new Set(); // paths with a resolve request in flight
  let openConflict = null; // path of the expanded row

  let conflictSeq = 0;
  let conflictTimer = null;

  function scheduleConflicts() {
    clearTimeout(conflictTimer);
    conflictTimer = setTimeout(loadConflicts, 500);
  }

  async function loadConflicts() {
    clearTimeout(conflictTimer);
    const seq = ++conflictSeq;
    try {
      const r = await api(`${base}/api/v1/conflicts`, { cache: "no-store" });
      if (!r.ok) {
        const msg = await errorText(r, "conflict list failed");
        if (seq === conflictSeq) { conflictMsg.textContent = msg; $("conflictCount").textContent = ""; }
        return;
      }
      const j = await r.json();
      if (seq === conflictSeq) renderConflicts(j);
    } catch (err) {
      if (seq === conflictSeq) conflictMsg.textContent = "conflict list failed: " + err;
    }
  }

  function renderConflicts(j) {
    const items = j.conflicts || [];
    conflictItems.clear();
    for (const c of items) conflictItems.set(c.path, c);
    if (openConflict && !conflictItems.has(openConflict)) openConflict = null;
    for (const [p, d] of [...conflictDrafts]) {
      const c = conflictItems.get(p);
      if (!c) { conflictDrafts.delete(p); continue; }
      if (c.ts !== d.ts) {
        // Saving a merge built against the old versions would also answer the new incoming one.
        conflictDrafts.delete(p);
        conflictMsg.textContent = `${p} changed since you started merging it; the unsaved merge was discarded`;
      }
    }

    // Sync reloads this list after every cycle: keep the caret and scroll of a merge being typed.
    const prevEdit = conflictList.querySelector(".conflict-item.open .merge-edit");
    const caret = prevEdit && document.activeElement === prevEdit
      ? { path: openConflict, start: prevEdit.selectionStart, end: prevEdit.selectionEnd, top: prevEdit.scrollTop, height: prevEdit.style.height }
      : null;
    const listTop = conflictList.scrollTop;

    $("conflictCount").textContent = items.length ? `${items.length} open` : "";
    conflictList.innerHTML = "";
    if (!items.length) {
      const li = document.createElement("li");
      li.className = "empty";
      li.textContent = "No open sync conflicts.";
      conflictList.append(li);
      return;
    }
    for (const c of items) conflictList.append(conflictRow(c));
    conflictList.scrollTop = listTop;
    const edit = caret && caret.path === openConflict && conflictList.querySelector(".conflict-item.open .merge-edit");
    if (edit) {
      edit.style.height = caret.height;
      edit.focus({ preventScroll: true });
      edit.setSelectionRange(caret.start, caret.end);
      edit.scrollTop = caret.top;
    }
  }

  // Binary files cannot take the incoming version as is (the server answers 422 unsupported).
  const canTakeTheirs = (c) => c.path.endsWith(".md") || c.quarantined;
  // Sync decodes every side as UTF-8, so a binary file shows replacement characters or NULs.
  const looksBinary = (c) =>
    !c.path.endsWith(".md") && [c.base, c.ours, c.theirs].some((t) => t != null && (t.includes("\u{FFFD}") || t.includes("\0")));

  function conflictRow(c) {
    const li = document.createElement("li");
    li.className = "conflict-item" + (c.quarantined ? " quarantined" : "");
    li.dataset.path = c.path;
    const badges =
      (c.reasons || []).map((r) => `<span class="badge">${esc(r)}</span>`).join("") +
      (c.quarantined ? `<span class="badge bad">quarantined (secrets)</span>` : "");
    const when = c.ts ? new Date(c.ts).toLocaleString() : "";
    li.innerHTML =
      `<div class="result-head"><span class="path">${esc(c.path)}</span>` +
      `<span class="badges">${badges}</span></div>` +
      (when ? `<div class="muted">recorded ${esc(when)}</div>` : "");
    activatable(li, (ev) => {
      if (ev.target.closest(".conflict-detail")) return; // clicks in the merge view don't collapse it
      const open = openConflict !== c.path;
      conflictList.querySelectorAll(".conflict-item.open").forEach((x) => closeConflict(x));
      if (open) openConflictRow(li, c);
    });
    li.setAttribute("aria-expanded", "false");
    if (openConflict === c.path) openConflictRow(li, c);
    return li;
  }

  function closeConflict(li) {
    li.classList.remove("open");
    li.setAttribute("aria-expanded", "false");
    li.querySelector(".conflict-detail")?.remove();
    if (openConflict === li.dataset.path) openConflict = null;
  }

  function openConflictRow(li, c) {
    openConflict = c.path;
    li.classList.add("open");
    li.setAttribute("aria-expanded", "true");
    const detail = document.createElement("div");
    detail.className = "conflict-detail";

    const grid = document.createElement("div");
    grid.className = "merge3";
    for (const [label, text] of [["base", c.base], ["ours (this machine)", c.ours], ["theirs (incoming)", c.theirs]]) {
      const col = document.createElement("div");
      const h = document.createElement("div");
      h.className = "muted";
      h.textContent = label;
      const pre = document.createElement("pre");
      // textContent only: a conflict's text is untrusted note content.
      if (text == null) { pre.textContent = "(absent on this side)"; pre.classList.add("absent"); }
      else pre.textContent = text;
      col.append(h, pre);
      grid.append(col);
    }

    const edit = document.createElement("textarea");
    edit.className = "merge-edit";
    edit.spellcheck = false;
    edit.setAttribute("aria-label", "Merged content for " + c.path);
    edit.value = conflictDrafts.has(c.path) ? conflictDrafts.get(c.path).text : (c.ours ?? c.theirs ?? "");
    edit.addEventListener("input", () => conflictDrafts.set(c.path, { ts: c.ts, text: edit.value }));

    const actions = document.createElement("div");
    actions.className = "conflict-actions";
    actions.innerHTML =
      `<button type="button" class="ghost" data-conflict-action="keepMine">Keep ours</button>` +
      `<button type="button" class="ghost" data-conflict-action="acceptIncoming">Take theirs</button>` +
      `<button type="button" data-conflict-action="content">Save merge</button>`;
    const take = actions.querySelector('[data-conflict-action="acceptIncoming"]');
    if (!canTakeTheirs(c)) {
      take.disabled = true;
      take.title = "Only a text note can take the incoming version as is; save a merge instead.";
    }
    if (looksBinary(c)) {
      // The listed text is a lossy UTF-8 decode: saving it back would corrupt the file.
      edit.readOnly = true;
      const save = actions.querySelector('[data-conflict-action="content"]');
      save.disabled = true;
      save.title = "A binary file cannot be merged as text; keep ours, or write the file you want.";
    }
    if (conflictBusy.has(c.path)) actions.querySelectorAll("button").forEach((b) => { b.disabled = true; });

    detail.append(grid, edit, actions);
    li.append(detail);
  }

  async function resolveConflict(path, resolution, content, ack) {
    const row = [...conflictList.querySelectorAll(".conflict-item")].find((li) => li.dataset.path === path);
    (row ? row.querySelectorAll("button") : []).forEach((b) => { b.disabled = true; });
    conflictBusy.add(path); // a reload mid-request redraws the row: keep its buttons disabled
    conflictMsg.textContent = `${resolution} ${path}…`;
    const body = { path, resolution };
    if (resolution === "content") body.content = content;
    if (ack) body.acknowledgeSecrets = true;
    try {
      const r = await api(`${base}/api/v1/conflicts/resolve`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
      });
      if (r.ok) {
        const j = await r.json();
        conflictDrafts.delete(path);
        if (openConflict === path) openConflict = null;
        conflictMsg.textContent = `${path} resolved (${resolution}) · ${j.remainingConflicts} left`;
      } else if (r.status === 422) {
        let j = null;
        try { j = await r.json(); } catch (_) {}
        const msg = (j && j.message) || `${resolution} failed (422)`;
        conflictMsg.textContent = msg;
        if (j && j.error === "secrets_detected" && !ack &&
            window.confirm(msg + "\n\nTake the incoming version anyway? The override is recorded in the audit log.")) {
          return resolveConflict(path, resolution, content, true);
        }
      } else if (r.status === 409) {
        conflictMsg.textContent = `${path} changed since you loaded it, reloaded`;
      } else if (r.status === 404) {
        conflictDrafts.delete(path);
        conflictMsg.textContent = `${path} is already resolved, reloaded`;
      } else if (r.status === 401 || r.status === 403) {
        conflictMsg.textContent = "This engine needs a writer or admin to resolve conflicts.";
      } else {
        conflictMsg.textContent = await errorText(r, `${resolution} failed`);
      }
    } catch (err) {
      conflictMsg.textContent = `${resolution} failed: ${err}`;
    }
    conflictBusy.delete(path);
    await loadConflicts();
  }

  conflictList.addEventListener("click", (ev) => {
    const btn = ev.target.closest("button[data-conflict-action]");
    if (!btn) return;
    const li = btn.closest(".conflict-item");
    const path = li.dataset.path;
    const resolution = btn.dataset.conflictAction;
    const edit = li.querySelector(".merge-edit");
    resolveConflict(path, resolution, resolution === "content" && edit ? edit.value : null, false);
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
        // Sync's own conflict events carry no path (addItem drops them), so reload the pane on any.
        if (ev.type === "conflict" || (ev.type === "commit.created" && tool === "resolve")) scheduleConflicts();
        break;
      case "sync.finished":
        scheduleConflicts();
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
      loadConflicts();
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
    conflictMsg.textContent = ""; // the open merge editor and its draft belong to the previous engine
    openConflict = null;
    conflictDrafts.clear();
    // Its rows too: a resolve clicked on one would be sent to the new engine. A list request still
    // in flight to the old engine must not draw them back.
    conflictSeq++;
    clearTimeout(conflictTimer);
    conflictItems.clear();
    conflictList.innerHTML = "";
    $("conflictCount").textContent = "";
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
  $("conflictRefresh").addEventListener("click", () => { conflictMsg.textContent = ""; loadConflicts(); });
  $("searchMode").addEventListener("change", () => {
    if ($("searchQuery").value.trim()) runSearch();
  });

  connect();
})();
