# Svod vs Mem0

Both projects give AI agents a long-term memory that survives between sessions. If you are
choosing between them, or moving off a hosted memory store, this page sets out what they have in
common, where Svod differs, and where Mem0 is ahead. It aims to be fair: facts about Svod come
from this repository, and facts about Mem0 come from its public pages (see
[Sources and date](#sources-and-date)). Anything we could not confirm is marked ⚠️.

## What both share

- **Long-term memory for agents.** Agents store facts, preferences and decisions and get the
  relevant ones back later by search.
- **Open source.** Svod is Apache-2.0. ⚠️ Mem0's open-source library is published under
  Apache-2.0 as well; confirm on its repository before relying on it.
- **Self-hostable.** Both can run on infrastructure you control.

## Where Svod differs

| | Svod | Mem0 |
|---|---|---|
| **Store of record** | A git repository of Markdown files | ⚠️ A vector store plus a key-value store behind its API |
| **Readable and editable** | Ordinary `.md` files with frontmatter. Open, edit and grep them in any editor, including Obsidian | ⚠️ Memories are read and changed through its API or dashboard |
| **History** | One attributed git commit per write. `log`, `diff` and restore of any earlier revision are built in | ⚠️ A per-memory change history through its API. Restoring an earlier revision is not documented |
| **Concurrency** | A single serialized writer with an optimistic `expectedRevision`. A stale write is surfaced as a conflict and never silently merged | ⚠️ Not documented as a guarantee |
| **Durability** | Atomic writes (temp file + rename), soft-delete to `.trash/` plus a commit, and startup crash recovery that completes or rolls back a partial write | ⚠️ Depends on the backing stores you configure |
| **Search** | Hybrid BM25 + vector search fused with RRF | ⚠️ Vector-first. Check its docs for the current keyword and graph options |
| **Runtime** | Fully in-process on one JVM, with in-process embeddings. No Python and no separate server | ⚠️ A Python or JS library with an external LLM, embedder and vector store, or the hosted platform |
| **Agent interface** | MCP, with 22 MCP tools for reading, searching and writing memory | Python and JS SDKs and a hosted REST API. ⚠️ An MCP server (OpenMemory) is also offered |
| **Lock-in** | None: leaving means keeping a git clone of Markdown files | ⚠️ Higher: memories live in its stores. Its export is JSON, which Svod can import (below) |

What this means in practice: if you need to read your agent's memory yourself, fix a wrong fact
by hand, or answer "which agent wrote this, when, and what did it say before?", Svod's Markdown
files and git history do that without any setup.

## Where Mem0 is ahead

- **A managed hosted platform.** Mem0 offers a hosted service with no operations work on your
  side. Svod is self-host only today. A separate hosted layer is the stated long-term direction
  ([ADR-0002](adr/0002-repo-split-and-license.md)), but nothing is built and the product decision
  has not been taken.
- **SDKs and framework integrations.** Mem0 ships official Python and JS SDKs and integrations
  with many agent frameworks. Svod's interface is MCP, which agent frameworks reach through
  their MCP clients.
- **Automatic extraction.** Mem0 extracts memories from conversations for you by default. In
  Svod, agents usually write notes explicitly through MCP tools. Svod's `extract_memories` tool is
  opt-in: it needs a configured local model, and every memory it proposes is provisional and waits
  for a person to approve it in the Memory review queue.
- **A larger community.** Mem0 has a larger user base and ecosystem today.

## Importing an existing Mem0 export

Svod's local App API imports a Mem0 JSON export file into a vault:

```bash
curl -X POST http://127.0.0.1:7517/api/v1/import \
  -H 'Content-Type: application/json' \
  -d '{"source": "/path/to/mem0-export.json", "format": "mem0"}'
```

- **Accepted shapes:** a top-level array of memories, or an object holding a `results` or
  `memories` array.
- **One note per memory** at `mem0/‹id›.md`. Pass `"into"` to use another folder; the default is
  `mem0/`. Mem0 categories become tags.
- **Idempotent:** the path depends only on the Mem0 id, so running the same import again changes
  nothing. A note you have edited locally is skipped and never overwritten.
- **Through the single writer:** the import is committed to git like any other write.
- `7517` is the default `appApiPort`; use the value from your config if you changed it.
- `source` is a path on the machine running the engine, not on the caller's machine. The App API
  binds to 127.0.0.1 by default; a shared engine binds to the network and every caller presents a
  personal key.

There is no CLI subcommand for the Mem0 import yet; use the App API call above.

## Sources and date

Written in 2026-11. The Mem0 facts (licence, hosted platform, SDKs, automatic extraction) come
from its public pages listed below. They were not fetched again when this page was written.
Licences, prices and features change, so confirm on the project's own pages before you rely on
them:

- Mem0 repository and licence: <https://github.com/mem0ai/mem0> (see its `LICENSE` file)
- Mem0 website and hosted platform: <https://mem0.ai>
- Svod facts: this repository's [README](../README.md) feature tour and its [LICENSE](../LICENSE)

The ⚠️ cells were not confirmed against Mem0's documentation when this page was written.
Corrections are welcome.
