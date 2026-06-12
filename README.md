# Svod

**Auditable, git-backed memory for AI agents — readable, diffable, restorable.**

Svod is a local, git-backed markdown knowledge base that serves multiple local AI agents
and **never loses files**. Every change an agent makes is an atomic, attributed git commit,
so an agent's memory is something you can read, `git diff`, and restore — not an opaque
vector blob. Hybrid search (BM25 + vectors), MCP for agents.

Positioning: an alternative to opaque agent-memory stores (Mem0 / Letta / Zep), not a note
app.

## Products & repos

| Repo | What |
|---|---|
| **`FleetQ/svod-engine`** (this repo, OSS) | The product: headless engine (Kotlin/JVM), the OpenAPI contract, packaging, docs, examples. |
| `FleetQ/svod-ui-macos` (separate, personal) | A personal SwiftUI client built against the published contract. **Not a supported product surface.** |

FleetQ is **one MCP client** — a first-party reference integration, not an embedded
dependency. The engine stays vendor-agnostic (see [ADR-0002](docs/adr/0002-repo-split-and-license.md)).

## Layout (this repo)

| Dir | What |
|---|---|
| `engine/` | Kotlin + Gradle engine. |
| `contract/` | OpenAPI spec — single source of truth for all clients (Step 4). |
| `dist/` | launchd plist, jpackage/jlink config, installers (Step 5). |
| `examples/` | Reference integrations: web viewer + FleetQ-MCP (built from Step 4). |
| `docs/` | Architecture + ADRs. Start at [docs/architecture.md](docs/architecture.md). |

## Status

Step 1 of 8 — **integrity core** — is complete and its concurrency + crash-recovery gate is
green. See [docs/build-order.md](docs/build-order.md).

## Build & test the engine

```sh
cd engine
JAVA_HOME=$(/usr/libexec/java_home -v 20) ./gradlew test
```

Requires a JDK (20 used here). The Gradle wrapper is committed.

## License

Licensed under the **Apache License 2.0** — see [LICENSE](LICENSE).

> **Note:** Apache-2.0 is the *proposed default* and is pending owner confirmation before
> the first public release. Do not treat the license as final until that confirmation lands.
