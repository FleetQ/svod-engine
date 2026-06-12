# ADR-0007 — Lifecycle: launchd, single-instance, graceful shutdown, config, self-update

- Status: **Accepted**
- Date: 2026-06-12
- Scope: Build step 5 (process lifecycle + `dist/`)

## Context

The engine is a headless daemon that must outlive the UI, restart on crash, start on demand,
shut down without losing data, and update itself without breaking the clients that speak its
contract. macOS uses launchd; the engine itself must stay OS-agnostic.

## Decisions

### 1. `SvodNode` assembles and owns the lifecycle
A single `start(config)` validates config, acquires the single-instance lock, brings up the
index, the MCP endpoint, the App API, and the file watcher, then flips readiness on. The
engine package stays OS-agnostic; all macOS specifics live in `dist/`.

### 2. Centralized, validated config
One JSON file (`SvodConfig`) is the single source for vault path, ports, embedder provider,
and the agent tokens the MCP endpoint accepts. It is **validated at startup** — bad port,
non-loopback host, unknown provider, duplicate/blank tokens, bad roles all fail fast with a
clear message. Loopback is enforced here, so the App API can never be misconfigured off
127.0.0.1 (invariant 7).

### 3. Single-instance via the vault lock
No separate PID file: `SvodEngine.open` already holds an exclusive OS lock on the vault, so a
second node on the same vault refuses to start. One vault ⇒ one writer ⇒ one engine.

### 4. Graceful, ordered shutdown — no data loss
On SIGTERM the JVM shutdown hook runs `SvodNode.shutdown()`: (1) stop the App API + MCP
servers (no new work), (2) stop the watcher, (3) close the Lucene index, (4) close the engine
— which **drains the write-actor queue** and releases the lock. An in-flight write completes;
a committed write is always recoverable. *(Proven: write → shutdown → a fresh node on the
same vault reads it back, and the port is closed.)*

### 5. `/health` (liveness) vs `/ready` (readiness)
`/health` is up-ness; `/ready` reflects a readiness flag the node flips true only after the
whole stack is serving (and 503 before). The UI's flow is detect (`/health`) → start →
poll (`/ready`) → connect. *(Both states tested.)*

### 6. launchd: KeepAlive + kickstart, NOT fd socket activation
The plist uses `RunAtLoad` + `KeepAlive{SuccessfulExit=false}` (start at login, restart on
crash) and the engine binds a configured loopback port. True launchd **socket activation**
(launchd holds the listening socket and passes the fd) is *not* used: the JVM cannot cleanly
inherit a launchd socket fd. The same user-visible behavior — start-on-demand + auto-restart
— is achieved with `launchctl kickstart`, which is exactly what the UI runs for its
one-button start. Honest trade-off; revisit if a native (JNA `launch_activate_socket`) path
becomes worthwhile.

### 7. Self-update gated on API compatibility
`ApiCompatibility` compares App API contract versions by semver: same MAJOR ⇒ compatible
(additive), MAJOR bump ⇒ refused (breaking; needs coordinated client migration), downgrade ⇒
refused. `dist/self-update.sh` must pass this preflight before swapping binaries. The real
artifact download + `jpackage`/`jlink` app-image swap is the next packaging iteration; the
compatibility gate is implemented and tested now.

## Consequences

- The engine is a well-behaved launchd daemon: starts at login, restarts on crash, starts on
  demand, stops cleanly.
- A bad config can't bring up a half-broken or off-loopback server.
- Updates can't silently break clients speaking an older contract major.

## Alternatives considered

- **systemd-style socket activation via inherited fd** — JVM-hostile on macOS; KeepAlive +
  kickstart is equivalent for the UI and far simpler. Deferred.
- **Separate PID-file single-instance** — redundant with the vault lock, and weaker (stale
  PID files). Rejected.
- **No readiness, only liveness** — would have the UI connect before the index is built.
  Rejected for the explicit `/ready` flag.
- **Version-blind self-update** — risks shipping a breaking API to old clients. Rejected for
  the semver compat gate.
