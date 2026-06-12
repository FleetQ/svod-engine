# Svod

A local, git-backed markdown knowledge base for multiple AI agents that **never loses
files**. Hybrid search (BM25 + vectors), MCP for agents, a swappable UI over a versioned
contract.

- **Svod** — headless engine (Kotlin/JVM); the single writer and source of truth.
- **Svod UI** — native macOS client (SwiftUI); swappable via the OpenAPI contract.

## Layout

| Dir | What |
|---|---|
| `engine/` | Kotlin + Gradle engine. |
| `ui-macos/` | SwiftUI client (later). |
| `contract/` | OpenAPI spec — single source of truth for all clients (later). |
| `dist/` | launchd plist, jpackage/jlink config, installers (later). |
| `docs/` | Architecture + ADRs. Start at [docs/architecture.md](docs/architecture.md). |

## Status

Step 1 of 8 — **integrity core** — is complete and its concurrency + crash-recovery gate
is green. See [docs/build-order.md](docs/build-order.md).

## Build & test the engine

```sh
cd engine
JAVA_HOME=$(/usr/libexec/java_home -v 20) ./gradlew test
```

Requires a JDK (20 used here). The Gradle wrapper is committed.
