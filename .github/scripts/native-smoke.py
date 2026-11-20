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
           "MissingReflectionRegistrationError", "MissingResourceRegistrationError")


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

    def call(path, method="GET", body=None, timeout=30):
        h = {"Authorization": "Bearer " + KEY}
        data = None
        if body is not None:
            data = json.dumps(body).encode()
            h["Content-Type"] = "application/json"
        req = urllib.request.Request(base + path, data=data, method=method, headers=h)
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read().decode("utf-8", "replace")

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
        if status != 200:
            fail("PUT file returned %s: %s" % (status, body), log_path)
        status, body = call("/api/v1/file?path=" + urllib.parse.quote(note))
        if "quokkaparade" not in body:
            fail("read-back of the written file lacks its content: " + body[:200], log_path)
        print("ok  PUT + read back")
        search_until("quokkaparade", "smoke-write")
        print("ok  written note is searchable")

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
        tool(10, "read", {"path": "notes/does-not-exist.md"}, "")  # an error result still has to serialise
        print("ok  MCP tools/call write, read, edit, search, grep, history, list")
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
