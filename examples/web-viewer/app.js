/* Svod reference viewer — watch agents write, then git-diff their memory.
   Dependency-free. Talks only to the App API (same origin by default). */
(() => {
  "use strict";

  const $ = (id) => document.getElementById(id);
  const feed = $("feed");
  const feedEmpty = $("feedEmpty");
  const statusEl = $("status");
  const endpointEl = $("endpoint");
  const offline = $("offline");

  // Default to same origin (the engine serves this page); allow override.
  const defaultBase = location.protocol.startsWith("http") ? location.origin : "http://127.0.0.1:7517";
  let base = localStorage.getItem("svod.endpoint") || defaultBase;
  endpointEl.value = base;

  let ws = null;
  let reconnectTimer = null;
  const agentColors = new Map();
  const shownCommits = new Set(); // de-dupe agent.activity vs commit.created for the same commit
  let colorN = 0;

  function colorFor(agent) {
    if (!agentColors.has(agent)) agentColors.set(agent, "c" + (colorN++ % 5));
    return agentColors.get(agent);
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
    li.className = "item" + (ev.type === "conflict" ? " conflict" : "");
    if (commit) { li.classList.add("has-diff"); li.dataset.commit = commit; li.dataset.path = path; }

    li.innerHTML =
      `<span class="agent ${colorFor(agent)}">${esc(agent)}</span>` +
      `<span><span class="verb">${esc(VERBS[tool] || tool)}</span> <span class="path">${esc(path)}</span></span>` +
      `<span class="when">${fmtTime(ev.ts)}</span>`;

    if (commit) li.addEventListener("click", () => selectItem(li, path, commit));
    feed.prepend(li);
    while (feed.children.length > 200) feed.lastChild.remove();
  }

  function selectItem(li, path, commit) {
    document.querySelectorAll(".item.active").forEach((e) => e.classList.remove("active"));
    li.classList.add("active");
    showDiff(path, commit);
  }

  // Shared by the feed and a note's history: diff a commit against its parent.
  async function showDiff(path, commit) {
    $("diffPath").textContent = path + " @ " + commit.slice(0, 8);
    renderDiff("loading…");
    try {
      const url = `${base}/api/v1/file/diff?path=${encodeURIComponent(path)}` +
        `&from=${encodeURIComponent(commit + "~1")}&to=${encodeURIComponent(commit)}`;
      const r = await fetch(url);
      if (r.ok) {
        const j = await r.json();
        renderDiff(j.diff && j.diff.trim() ? j.diff : "(no textual change)");
      } else {
        // first commit of the file → no parent; show the content as freshly added
        const rev = await fetch(`${base}/api/v1/file/revision?path=${encodeURIComponent(path)}&revision=${encodeURIComponent(commit)}`);
        if (rev.ok) {
          const j = await rev.json();
          renderDiff(j.content.split("\n").map((l) => "+" + l).join("\n"), true);
        } else {
          renderDiff("(diff unavailable)");
        }
      }
    } catch (e) {
      renderDiff("(diff failed: " + e + ")");
    }
  }

  function renderDiff(text, added) {
    const out = text.split("\n").map((line) => {
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

  async function runSearch(e) {
    if (e) e.preventDefault();
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
      const r = await fetch(`${base}/api/v1/search?${parts.join("&")}`);
      if (!r.ok) {
        let msg = "search failed (" + r.status + ")";
        try { const j = await r.json(); if (j && j.message) msg = j.message; } catch (_) {}
        searchStatus.textContent = msg;
        searchResults.innerHTML = "";
        return;
      }
      renderResults(await r.json());
    } catch (err) {
      searchStatus.textContent = "search failed: " + err;
      searchResults.innerHTML = "";
    }
  }

  function renderResults(j) {
    const hits = j.hits || [];
    const mode = String(j.mode || "").toLowerCase();
    let status = `${hits.length} result${hits.length === 1 ? "" : "s"} · ${mode}`;
    if ((j.degraded || []).includes("semantic")) status += " · semantic unavailable — showing keyword results";
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
      li.addEventListener("click", (ev) => {
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

  async function openNote(path) {
    const p = encodeURIComponent(path);
    $("note").classList.remove("hidden");
    $("notePath").textContent = path;
    $("noteMeta").textContent = "";
    $("noteContent").textContent = "loading…";
    const history = $("noteHistory");
    history.innerHTML = "";
    try {
      const [fr, hr] = await Promise.all([
        fetch(`${base}/api/v1/file?path=${p}`),
        fetch(`${base}/api/v1/file/history?path=${p}&max=50`),
      ]);
      if (fr.ok) {
        const f = await fr.json();
        $("noteContent").textContent = f.content;
        const by = f.lastModifiedBy;
        if (by) $("noteMeta").textContent = "last modified by " + (by.agent ? `${by.agent} (${by.name})` : by.name);
      } else {
        $("noteContent").textContent = "(note unavailable)";
      }
      if (hr.ok) {
        const h = await hr.json();
        for (const c of h.commits || []) {
          const li = document.createElement("li");
          li.className = "commit";
          li.innerHTML =
            `<span class="mono sha">${esc(c.commit.slice(0, 8))}</span>` +
            `<span class="agent ${colorFor(c.agent || c.author)}">${esc(c.agent || c.author)}</span>` +
            `<span class="when">${esc(fmtTime(c.epochSeconds * 1000))}</span>` +
            `<span class="msg">${esc(c.message)}</span>`;
          li.addEventListener("click", () => {
            history.querySelectorAll(".commit.active").forEach((x) => x.classList.remove("active"));
            li.classList.add("active");
            showDiff(path, c.commit);
          });
          history.append(li);
        }
      }
    } catch (err) {
      $("noteContent").textContent = "(note failed: " + err + ")";
    }
  }

  function onEvent(ev) {
    switch (ev.type) {
      case "agent.activity":
      case "conflict":
      case "commit.created":
        // addItem de-dupes by commit id, so an MCP write (agent.activity + commit.created)
        // appears once, while API/watcher commits (commit.created only) still show.
        addItem(ev);
        break;
      case "index.updated":
        refreshIndexInfo();
        break;
      case "engine.status":
        // no-op visual; connection pill already reflects it
        break;
    }
  }

  async function refreshIndexInfo() {
    try {
      const r = await fetch(`${base}/api/v1/index/status`);
      if (r.ok) {
        const j = await r.json();
        $("indexInfo").textContent = `${j.docCount} chunks indexed · ${j.model}`;
      }
    } catch (_) {}
  }

  function connectWs() {
    const wsUrl = base.replace(/^http/, "ws") + "/api/v1/events";
    ws = new WebSocket(wsUrl);
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
      refreshIndexInfo();
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
    localStorage.setItem("svod.endpoint", base);
    if (ws) try { ws.close(); } catch (_) {}
    connect();
  });
  $("retry").addEventListener("click", connect);
  $("searchForm").addEventListener("submit", runSearch);
  $("searchMode").addEventListener("change", () => {
    if ($("searchQuery").value.trim()) runSearch();
  });

  connect();
})();
