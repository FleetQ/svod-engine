#!/usr/bin/env python3
"""Record a Profile-Guided Optimization profile for the native binary (Oracle GraalVM).

usage: pgo-workload.py <instrumented-binary> <profile-out.iprof>

Starts a binary built with `--pgo-instrument` on a generated vault (embedder `none`, one agent), drives the
code paths a real engine spends its time on (keyword search, note reads, tree, history, writes, links/graph,
MCP tool calls), then stops it with SIGTERM so the shutdown writes the profile. The binary must be built with
`--install-exit-handlers` (it is): without it SIGTERM kills the process and no profile is written.

POSIX only (Linux, macOS): Windows has no SIGTERM, so the release builds Windows with `-O3` instead.
Standard library only.
"""
import json
import os
import signal
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

KEY = "svk_" + "p" * 40
AGENT = "pgo-agent-token-0123456789abcdef"
NOTES = 3000
WORDS = ("deploy server engine vault team client invoice payment search index memory agent policy decision "
         "release migration database sprint review security token backup restore nginx docker postgres redis "
         "решение причина проблем грешка поправка тест проверка сървър памет диск мрежа").split()


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    binary, profile = os.path.abspath(sys.argv[1]), os.path.abspath(sys.argv[2])
    work = tempfile.mkdtemp(prefix="svod-pgo-")
    vault = os.path.join(work, "vault")
    for i in range(NOTES):
        folder = os.path.join(vault, ("projects", "decisions", "notes", "people", "runbooks")[i % 5])
        os.makedirs(folder, exist_ok=True)
        words = " ".join(WORDS[(i * 7 + k * 13) % len(WORDS)] for k in range(160))
        with open(os.path.join(folder, "note-%d.md" % i), "w", encoding="utf-8") as f:
            f.write("---\ntype: note\ntags: [%s]\n---\n\n# Note %d\n\n%s\n\nRelated: [[note-%d]] [[note-%d]]\n"
                    % (WORDS[i % len(WORDS)], i, words, (i + 1) % NOTES, (i * 31) % NOTES))
    git = ["git", "-c", "user.name=pgo", "-c", "user.email=pgo@local", "-C", vault]
    subprocess.run(git + ["init", "-q"], check=True)
    subprocess.run(git + ["add", "-A"], check=True)
    subprocess.run(git + ["commit", "-qm", "seed"], check=True)
    os.makedirs(os.path.join(work, "secrets"))
    key_file = os.path.join(work, "secrets", "owner.key")
    with open(key_file, "w") as f:
        f.write(KEY)
    os.chmod(key_file, 0o600)
    app, mcp = free_port(), free_port()
    cfg = {
        "host": "127.0.0.1", "appApiPort": app, "mcpPort": mcp, "hostId": "pgo",
        "vaults": [{"id": "main", "path": vault}], "defaultVault": "main", "embedder": {"provider": "none"},
        "localAdmin": False,
        "users": [{"userId": "owner", "name": "Owner", "email": "o@local", "keyRef": "file:" + key_file,
                   "admin": True, "grants": [{"vault": "main", "role": "EDITOR"}]}],
        "agents": [{"token": AGENT, "agentId": "pgo", "role": "WRITE", "vaults": ["main"]}],
    }
    config = os.path.join(work, "config.json")
    with open(config, "w") as f:
        json.dump(cfg, f)
    base = "http://127.0.0.1:%d" % app
    log = open(os.path.join(work, "engine.log"), "w")
    proc = subprocess.Popen([binary, "-XX:ProfilesDumpFile=" + profile, config], cwd=work,
                            env=dict(os.environ, HOME=work), stdout=log, stderr=subprocess.STDOUT)

    def call(path, method="GET", body=None):
        req = urllib.request.Request(base + path, data=json.dumps(body).encode() if body is not None else None,
                                     method=method, headers={"Authorization": "Bearer " + KEY,
                                                             "Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                return r.status, r.read()
        except urllib.error.HTTPError as e:
            return e.code, e.read()

    try:
        for _ in range(1200):
            try:
                urllib.request.urlopen(base + "/ready", timeout=1)
                break
            except Exception:
                if proc.poll() is not None:
                    sys.exit("engine exited before /ready (see %s)" % log.name)
                time.sleep(0.1)
        for _ in range(1200):  # the keyword index must be built, or search exercises nothing
            st, body = call("/api/v1/index/status")
            if st == 200 and json.loads(body).get("docCount", 0) >= NOTES:
                break
            time.sleep(0.25)

        q = urllib.parse.quote
        t0 = time.time()
        for i in range(600):
            words = " ".join(WORDS[(i * 3 + k * 5) % len(WORDS)] for k in range(1 + i % 3))
            call("/api/v1/search?mode=keyword&q=" + q(words))
            path = "%s/note-%d.md" % (("projects", "decisions", "notes", "people", "runbooks")[i % 5], (i * 37) % NOTES // 5 * 5 + i % 5)
            call("/api/v1/file?path=" + q(path))
            if i % 4 == 0:
                call("/api/v1/file/history?max=5&path=" + q(path))
                call("/api/v1/file/links?path=" + q(path))
            if i % 3 == 0:
                call("/api/v1/file?path=" + q("pgo/w-%d.md" % i), "PUT",
                     {"content": "# W %d\n\n%s [[note-%d]]\n" % (i, words, i)})
            if i % 25 == 0:
                call("/api/v1/tree")
                call("/api/v1/graph")
                call("/api/v1/stats")

        hdr = {"Authorization": "Bearer " + AGENT, "Accept": "application/json, text/event-stream",
               "Content-Type": "application/json"}
        murl = "http://127.0.0.1:%d/mcp" % mcp

        def rpc(msg):
            req = urllib.request.Request(murl, data=json.dumps(msg).encode(), method="POST", headers=hdr)
            with urllib.request.urlopen(req, timeout=60) as r:
                sid = r.headers.get("Mcp-Session-Id")
                r.read()
                return sid
        sid = rpc({"jsonrpc": "2.0", "id": 1, "method": "initialize",
                   "params": {"protocolVersion": "2025-03-26", "capabilities": {}, "clientInfo": {"name": "pgo", "version": "0"}}})
        if sid:
            hdr["Mcp-Session-Id"] = sid
        rpc({"jsonrpc": "2.0", "method": "notifications/initialized"})
        for i in range(150):
            name, args = (
                ("search", {"query": WORDS[i % len(WORDS)], "mode": "keyword"}),
                ("read", {"path": "notes/note-%d.md" % ((i * 5 + 2) % NOTES)}),
                ("write", {"path": "pgo/mcp-%d.md" % i, "content": "# M %d\n\nwritten by the pgo workload\n" % i}),
                ("grep", {"pattern": WORDS[(i * 7) % len(WORDS)]}),
                ("context_pack", {"query": WORDS[(i * 11) % len(WORDS)]}),
            )[i % 5]
            rpc({"jsonrpc": "2.0", "id": 100 + i, "method": "tools/call", "params": {"name": name, "arguments": args}})
        print("pgo workload: %.1f s of calls" % (time.time() - t0))
    finally:
        if proc.poll() is None:
            proc.send_signal(signal.SIGTERM)
            try:
                proc.wait(timeout=120)
            except subprocess.TimeoutExpired:
                proc.kill()
                proc.wait()
        log.close()
    if not os.path.isfile(profile) or os.path.getsize(profile) == 0:
        sys.exit("no profile written to %s (engine exit %s, log %s)" % (profile, proc.returncode, log.name))
    print("pgo workload: profile %s (%d bytes), engine exit %s" % (profile, os.path.getsize(profile), proc.returncode))


if __name__ == "__main__":
    main()
