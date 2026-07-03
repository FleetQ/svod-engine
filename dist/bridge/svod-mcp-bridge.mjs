#!/usr/bin/env node
// svod-mcp-bridge — minimal stdio ⇄ streamable-HTTP bridge for the Svod MCP server.
//
// Replaces `mcp-remote`, which wedges permanently when the engine restarts: it gives up
// SSE reconnection after 2 attempts and then swallows the 404 "Session not found" on every
// subsequent POST without answering the client — each tool call hangs until the client's
// 4-minute timeout (incident 2026-07-03). This bridge instead:
//   * caps every request (SVOD_BRIDGE_TIMEOUT_MS, default 60s) and returns a JSON-RPC
//     error instead of hanging;
//   * on 404 (session lost — engine restarted) transparently re-initializes and retries
//     the call once;
//   * skips SSE entirely — the engine answers POSTs with JSON (enableJsonResponse) and
//     emits no server-initiated notifications (tools listChanged=false), so there is no
//     stream to lose.
//
// Env: SVOD_MCP_URL (default http://127.0.0.1:7620/mcp), SVOD_AUTH ("Bearer <token>"),
//      SVOD_BRIDGE_TIMEOUT_MS.

import { createInterface } from "node:readline";

const URL_ = process.env.SVOD_MCP_URL || "http://127.0.0.1:7620/mcp";
const AUTH = process.env.SVOD_AUTH || "";
const TIMEOUT = Number(process.env.SVOD_BRIDGE_TIMEOUT_MS || 60_000);

let sessionId = null;
let initParams = null;   // client's initialize params, replayed on re-init
let reinitInFlight = null;

const log = (msg) => process.stderr.write(`[svod-bridge] ${msg}\n`);
const send = (obj) => process.stdout.write(JSON.stringify(obj) + "\n");

function headers() {
  const h = {
    "Content-Type": "application/json",
    "Accept": "application/json, text/event-stream",
  };
  if (AUTH) h["Authorization"] = AUTH;
  if (sessionId) h["Mcp-Session-Id"] = sessionId;
  return h;
}

/** POST one JSON-RPC message. Returns {status, json|null} or throws on network/timeout. */
async function post(msg) {
  const res = await fetch(URL_, {
    method: "POST",
    headers: headers(),
    body: JSON.stringify(msg),
    signal: AbortSignal.timeout(TIMEOUT),
  });
  const sid = res.headers.get("mcp-session-id");
  if (sid && sid !== sessionId) log(`session: ${sid}`);
  if (sid) sessionId = sid;
  const text = await res.text();
  if (!text) return { status: res.status, json: null };
  // enableJsonResponse gives plain JSON; tolerate an SSE-framed body just in case.
  const m = /^data: (.*)$/m.exec(text);
  try { return { status: res.status, json: JSON.parse(m ? m[1] : text) }; }
  catch { return { status: res.status, json: null, text }; }
}

/** Re-initialize after a lost session (single-flight). */
function reinitialize() {
  if (reinitInFlight) return reinitInFlight;
  reinitInFlight = (async () => {
    sessionId = null;
    const params = initParams || {
      protocolVersion: "2025-03-26",
      capabilities: {},
      clientInfo: { name: "svod-bridge", version: "1.0" },
    };
    const r = await post({ jsonrpc: "2.0", id: "bridge-reinit", method: "initialize", params });
    if (r.status !== 200 || !sessionId) throw new Error(`re-initialize failed: HTTP ${r.status}`);
    await post({ jsonrpc: "2.0", method: "notifications/initialized" }).catch(() => {});
    log(`session re-initialized: ${sessionId}`);
  })().finally(() => { reinitInFlight = null; });
  return reinitInFlight;
}

function rpcError(id, message, code = -32001) {
  return { jsonrpc: "2.0", id, error: { code, message: `svod-bridge: ${message}` } };
}

async function handleRequest(msg) {
  if (msg.method === "initialize") initParams = msg.params;
  try {
    let r = await post(msg);
    if (r.status === 404) {           // session lost (engine restarted) → recover
      log(`404 for ${msg.method} — session lost, re-initializing`);
      await reinitialize();
      r = await post(msg);
    }
    if (r.json != null) send(r.json);
    else if (r.status >= 400) send(rpcError(msg.id, `HTTP ${r.status} from engine`));
    else send(rpcError(msg.id, `empty response (HTTP ${r.status})`));
  } catch (e) {
    const why = e.name === "TimeoutError" ? `timed out after ${TIMEOUT}ms` : (e.message || String(e));
    log(`${msg.method} id=${msg.id} failed: ${why}`);
    send(rpcError(msg.id, why));
  }
}

async function handleNotification(msg) {
  try { await post(msg); } catch (e) { log(`notification ${msg.method} dropped: ${e.message}`); }
}

const rl = createInterface({ input: process.stdin, terminal: false });
rl.on("line", (line) => {
  line = line.trim();
  if (!line) return;
  let msg;
  try { msg = JSON.parse(line); } catch { log(`unparseable input dropped: ${line.slice(0, 120)}`); return; }
  if (msg.id !== undefined && msg.method) handleRequest(msg);
  else if (msg.method) handleNotification(msg);
  // responses from client (to server requests) can't occur: the engine never sends requests
});
rl.on("close", () => process.exit(0));
log(`ready → ${URL_} (timeout ${TIMEOUT}ms)`);
