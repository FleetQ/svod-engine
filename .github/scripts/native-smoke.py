#!/usr/bin/env python3
"""Smoke-test a BUILT svod-engine native binary against a real (tiny) vault.

usage: native-smoke.py <path-to-svod-engine-binary>

Starts the binary with a real config (one vault, `embedder: none`), waits for /ready, runs a
keyword search over seeded notes, PUTs a new note, reads it back, finds it by search, calls the
MCP endpoint (initialize + tools/list), checks the log for native-image failure signatures, then
stops the process. Exit code 0 only if every step passed.

A native build that compiles proves nothing: v1.30.0 shipped a binary that crashed at startup
(Lucene FFM, missing reflection metadata) because nobody ever ran it. Release CI runs this
before the binary can be uploaded. Needs only Python 3 and the binary (no JVM, no git).
"""
import json
import os
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

KEY = "svk_" + "s" * 40
AGENT_TOKEN = "smoke-agent-token"
# Strings that mean a native-image reachability/feature problem even when the process stays up
# (a missing logback appender class is only logged, not fatal).
BAD_LOG = ("UnsupportedFeatureError", "ClassNotFoundException", "NoClassDefFoundError",
           "MissingReflectionRegistrationError", "MissingResourceRegistrationError",
           "SerializationException", "Serializer for class", "Failed to convert request body")
# GET endpoints that must answer 200 on a one-vault config. Each response body goes through
# kotlinx.serialization, which a native image resolves by reflection: a DTO missing from the reflection
# metadata fails only when its endpoint is called (v1.34.0 broke 33 of 41 endpoints and tools this way).
API_GETS = [
    "/health", "/api/v1/tree", "/api/v1/trash", "/api/v1/file/history?path=notes/seed-0.md",
    "/api/v1/file/links?path=notes/seed-0.md", "/api/v1/graph", "/api/v1/graph/status",
    "/api/v1/graph/communities", "/api/v1/tags", "/api/v1/settings", "/api/v1/index/status", "/api/v1/metrics",
    "/api/v1/conflicts", "/api/v1/vaults", "/api/v1/agents", "/api/v1/me", "/api/v1/stats", "/api/v1/users",
    "/api/v1/sync/config", "/api/v1/sync/status", "/api/v1/sources", "/api/v1/memory/sessions",
    "/api/v1/memory/proposals", "/api/v1/memory/dashboard", "/api/v1/memory/review", "/api/v1/memory/rulebook",
    # the reference web viewer's first calls (examples/web-viewer/app.js)
    "/api/v1/index/status", "/api/v1/memory/review", "/api/v1/search?q=zebracrossing&mode=keyword",
    "/api/v1/file?path=notes/seed-0.md",
]


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def fail(msg, log_path=None):
    print("SMOKE FAIL: " + msg, file=sys.stderr)
    if log_path and os.path.exists(log_path):
        with open(log_path, errors="replace") as f:
            print("--- engine log (tail) ---\n" + "".join(f.readlines()[-60:]), file=sys.stderr)
    sys.exit(1)


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    binary = os.path.abspath(sys.argv[1])
    work = tempfile.mkdtemp(prefix="svod-smoke-")
    vault = os.path.join(work, "vault")
    os.makedirs(os.path.join(vault, "notes"))
    os.makedirs(os.path.join(work, "home"))
    keyfile = os.path.join(work, "owner.key")
    with open(keyfile, "w") as f:
        f.write(KEY)
    os.chmod(keyfile, 0o600)
    for i in range(5):
        with open(os.path.join(vault, "notes", "seed-%d.md" % i), "w", encoding="utf-8") as f:
            f.write("# Seed %d\n\nThe quarterly zebracrossing review covers deployment and docker.\n" % i)
    api, mcp = free_port(), free_port()
    cfg = {
        "host": "127.0.0.1", "appApiPort": api, "mcpPort": mcp, "hostId": "native-smoke",
        "vaults": [{"id": "main", "path": vault}], "defaultVault": "main",
        "embedder": {"provider": "none"}, "localAdmin": False,
        "users": [{"userId": "owner", "name": "Owner", "email": "o@local", "keyRef": "file:" + keyfile,
                   "admin": True, "grants": [{"vault": "main", "role": "EDITOR"}]}],
        "agents": [{"token": AGENT_TOKEN, "agentId": "smoke", "role": "WRITE", "vaults": ["main"]}],
    }
    cfg_path = os.path.join(work, "config.json")
    with open(cfg_path, "w") as f:
        json.dump(cfg, f)
    log_path = os.path.join(work, "engine.log")
    env = dict(os.environ, HOME=work + os.sep + "home", USERPROFILE=work + os.sep + "home",
               SVOD_DISCOVERY_FILE=os.path.join(work, "home", "engine.json"),
               SVOD_LOG_DIR=os.path.join(work, "logs"))
    base = "http://127.0.0.1:%d" % api

    def call(path, method="GET", body=None, timeout=30, expect=(200,)):
        h = {"Authorization": "Bearer " + KEY}
        data = None
        if body is not None:
            data = json.dumps(body).encode()
            h["Content-Type"] = "application/json"
        req = urllib.request.Request(base + path, data=data, method=method, headers=h)
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                status, text = r.status, r.read().decode("utf-8", "replace")
        except urllib.error.HTTPError as e:
            status, text = e.code, e.read().decode("utf-8", "replace")
        if status not in expect:
            fail("%s %s returned %s (wanted %s): %s" % (method, path, status, expect, text[:200]), log_path)
        return status, text

    def search(q):
        _, body = call("/api/v1/search?mode=keyword&q=" + urllib.parse.quote(q))
        return json.loads(body)

    def search_until(q, path_fragment, seconds=30):
        end = time.time() + seconds
        while time.time() < end:
            if path_fragment in json.dumps(search(q)):
                return
            time.sleep(0.5)
        fail("search %r never returned %r" % (q, path_fragment), log_path)

    log = open(log_path, "wb")
    proc = subprocess.Popen([binary, cfg_path], env=env, stdout=log, stderr=subprocess.STDOUT)
    try:
        end = time.time() + 90
        while True:
            if proc.poll() is not None:
                fail("engine exited with %s before /ready" % proc.returncode, log_path)
            try:
                urllib.request.urlopen(base + "/ready", timeout=1)
                break
            except Exception:
                if time.time() > end:
                    fail("/ready not up within 90s", log_path)
                time.sleep(0.2)
        print("ok  /ready")

        search_until("zebracrossing", "seed-")
        print("ok  keyword search over seeded notes")

        note = "notes/smoke-write.md"
        status, body = call("/api/v1/file?path=" + urllib.parse.quote(note), "PUT",
                            {"content": "# Written by smoke\n\nUnique marker quokkaparade.\n"})
        status, body = call("/api/v1/file?path=" + urllib.parse.quote(note))
        if "quokkaparade" not in body:
            fail("read-back of the written file lacks its content: " + body[:200], log_path)
        print("ok  PUT + read back")
        search_until("quokkaparade", "smoke-write")
        print("ok  written note is searchable")

        for g in API_GETS:
            call(g)
        print("ok  %d App API GET endpoints answer 200" % len(API_GETS))

        # typed error bodies (ErrorDto, ConflictBodyDto) and request bodies (MoveRequestDto, CaptureRequestDto)
        _, text = call("/api/v1/file?path=notes/no-such-note.md", expect=(404,))
        if "error" not in text:
            fail("404 body is not an ErrorDto: " + text[:200], log_path)
        _, text = call("/api/v1/file?path=%s&expectedRevision=%s" % (urllib.parse.quote(note), "0" * 40), "DELETE",
                       expect=(409,))
        if "current" not in text:
            fail("conflict body lacks `current`: " + text[:200], log_path)
        _, cur = call("/api/v1/file?path=" + urllib.parse.quote(note))
        rev = json.loads(cur)["revision"]
        call("/api/v1/file/move", "POST", {"from": note, "to": "notes/smoke-moved.md", "expectedRevision": rev})
        call("/api/v1/file?path=notes/smoke-moved.md")
        now = int(time.time() * 1000)
        call("/api/v1/memory/capture", "POST", {"sessionId": "smoke", "transcript": "user prefers tabs",
                                                "startedAt": now - 1000, "endedAt": now}, expect=(200, 201, 202))
        call("/api/v1/maintenance/reindex", "POST", {})
        print("ok  404 ErrorDto, 409 conflict body, move, memory capture, reindex")

        # the viewer follows /api/v1/events over a WebSocket: check the upgrade is accepted
        with socket.create_connection(("127.0.0.1", api), timeout=10) as sk:
            sk.sendall(("GET /api/v1/events HTTP/1.1\r\nHost: 127.0.0.1\r\nUpgrade: websocket\r\n"
                        "Connection: Upgrade\r\nSec-WebSocket-Key: c21va2VzbW9rZXNtb2tlcw==\r\n"
                        "Sec-WebSocket-Version: 13\r\nSec-WebSocket-Protocol: svod.events, svod.key.%s\r\n\r\n"
                        % KEY).encode())
            head = sk.recv(256).decode("latin-1")
        if " 101 " not in head.split("\r\n")[0]:
            fail("WebSocket /api/v1/events not upgraded: " + head[:120], log_path)
        print("ok  /api/v1/events WebSocket upgrade")

        mcp_url = "http://127.0.0.1:%d/mcp" % mcp
        hdr = {"Authorization": "Bearer " + AGENT_TOKEN, "Accept": "application/json, text/event-stream",
               "Content-Type": "application/json"}
        init = {"jsonrpc": "2.0", "id": 1, "method": "initialize",
                "params": {"protocolVersion": "2025-03-26", "capabilities": {},
                           "clientInfo": {"name": "native-smoke", "version": "0"}}}
        with urllib.request.urlopen(urllib.request.Request(mcp_url, data=json.dumps(init).encode(),
                                                           method="POST", headers=hdr), timeout=30) as r:
            sid = r.headers.get("Mcp-Session-Id")
            text = r.read().decode("utf-8", "replace")
        if "serverInfo" not in text:
            fail("MCP initialize gave no serverInfo: " + text[:200], log_path)
        if sid:
            hdr["Mcp-Session-Id"] = sid
        for msg in ({"jsonrpc": "2.0", "method": "notifications/initialized"},
                    {"jsonrpc": "2.0", "id": 2, "method": "tools/list"}):
            with urllib.request.urlopen(urllib.request.Request(mcp_url, data=json.dumps(msg).encode(),
                                                               method="POST", headers=hdr), timeout=30) as r:
                text = r.read().decode("utf-8", "replace")
        if "search" not in text:
            fail("MCP tools/list lacks a search tool: " + text[:300], log_path)
        print("ok  MCP initialize + tools/list")

        def tool(rid, name, args, expect):
            msg = {"jsonrpc": "2.0", "id": rid, "method": "tools/call", "params": {"name": name, "arguments": args}}
            end, out = time.time() + 30, ""
            while time.time() < end:  # a write is indexed a moment after it returns
                with urllib.request.urlopen(urllib.request.Request(mcp_url, data=json.dumps(msg).encode(),
                                                                   method="POST", headers=hdr), timeout=30) as r:
                    out = r.read().decode("utf-8", "replace")
                if expect in out:
                    return
                time.sleep(0.5)
            fail("MCP tools/call %s lacks %r: %s" % (name, expect, out[:300]), log_path)

        tool(3, "write", {"path": "notes/mcp-note.md", "content": "# MCP\n\nwritten over mcp narwhalsymphony\n"}, "mcp-note")
        tool(4, "read", {"path": "notes/mcp-note.md"}, "narwhalsymphony")
        tool(5, "edit", {"path": "notes/mcp-note.md", "oldString": "narwhalsymphony", "newString": "narwhalsonata"}, "mcp-note")
        tool(6, "search", {"query": "narwhalsonata", "mode": "keyword"}, "mcp-note")
        tool(7, "grep", {"pattern": "zebracrossing"}, "seed-")
        tool(8, "history", {"path": "notes/mcp-note.md"}, "mcp-note")
        tool(9, "list", {}, "seed-0")
        tool(11, "tree", {}, "folders")
        tool(12, "link", {"path": "notes/mcp-note.md"}, "mcp-note")
        tool(13, "graph_query", {"path": "notes/mcp-note.md"}, "mcp-note")
        tool(14, "graph_status", {}, "state")
        tool(15, "context_pack", {"query": "zebracrossing"}, "seed-")
        tool(16, "remember", {"content": "Smoke prefers dark mode", "type": "preference"}, "written")
        tool(17, "move", {"from": "notes/mcp-note.md", "to": "notes/mcp-moved.md"}, "conflict")  # no expectedRevision
        tool(10, "read", {"path": "notes/does-not-exist.md"}, "")  # an error result still has to serialise
        print("ok  MCP tools/call write, read, edit, search, grep, history, list, tree, link, graph, context_pack, remember, move")
    except urllib.error.URLError as e:
        fail("HTTP error: %r" % e, log_path)
    finally:
        if proc.poll() is None:
            proc.terminate()
            try:
                proc.wait(timeout=30)
            except subprocess.TimeoutExpired:
                proc.kill()
                proc.wait()
        log.close()

    with open(log_path, errors="replace") as f:
        text = f.read()
    for sig in BAD_LOG:
        if sig in text:
            fail("engine log contains %s" % sig, log_path)
    print("ok  clean log, clean stop")
    print("SMOKE PASS")


if __name__ == "__main__":
    main()
