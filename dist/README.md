# dist — packaging, lifecycle, install

Everything OS-specific lives here; the engine itself stays OS-agnostic. macOS uses
**launchd**; a Linux unit would go alongside without touching engine code.

## Config

The engine reads one JSON config (first CLI arg). See `dev.svod.engine.lifecycle.SvodConfig`.

```jsonc
{
  "vaultPath": "/Users/you/Svod",
  "host": "127.0.0.1",          // loopback only — enforced at startup
  "appApiPort": 7517,
  "mcpPort": 7518,
  "embedder": { "provider": "onnx-local" },   // onnx-local | ollama | none
  "agents": [
    { "token": "REPLACE_ME", "agentId": "friday", "role": "WRITE" }
  ]
}
```

It is **validated at startup**; bad port, non-loopback host, unknown provider, or duplicate
agent tokens fail fast with a clear message.

## launchd lifecycle (macOS)

`launchd/dev.svod.engine.plist` — a per-user agent. Edit the paths/`__USER_HOME__`, then:

```sh
cp launchd/dev.svod.engine.plist ~/Library/LaunchAgents/
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/dev.svod.engine.plist
```

- **RunAtLoad** starts it at login.
- **KeepAlive { SuccessfulExit=false }** restarts it if it crashes (auto-restart).
- **Single-instance** is guaranteed by the engine's exclusive vault lock — a second process
  on the same vault refuses to start.

### One-button start (the UI's "Start Svod")

When the UI's health check (`GET /health`) fails, it runs:

```sh
launchctl kickstart -k gui/$(id -u)/dev.svod.engine
```

then polls `GET http://127.0.0.1:7517/ready` until `200 {"ready":true,...}` and connects.
`/health` is liveness; `/ready` is readiness (index built, servers up).

### Socket activation — decision

True launchd *socket activation* (launchd holds the listening socket and passes the fd to
the daemon on first connection) is not used: the JVM cannot cleanly inherit a launchd socket
fd. Instead we get the same user-visible behavior — **start-on-demand + auto-restart** — from
`RunAtLoad` + `KeepAlive` + `launchctl kickstart`. The UI's detect→start→poll→connect flow is
identical. Revisit if a native (JNA `launch_activate_socket`) path becomes worthwhile. See
ADR-0007.

## Graceful shutdown

`launchctl bootout gui/$(id -u)/dev.svod.engine` (or SIGTERM) triggers the engine's shutdown
hook: stop the App API + MCP servers, stop the watcher, close the Lucene index, then close
the engine — which **drains the write-actor queue** and releases the lock. No in-flight write
is lost; committed writes are always recoverable from git.

## Self-update

`self-update.sh` is a skeleton: it must call the engine's **API-compat preflight**
(`dev.svod.engine.lifecycle.ApiCompatibility`) before swapping binaries. Same App API major
version ⇒ compatible; a major bump is refused (needs a coordinated client migration);
downgrades are refused. Packaging the engine as a self-contained app image
(`jpackage` + `jlink`) is the next iteration.
