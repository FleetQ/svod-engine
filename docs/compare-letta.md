# Svod vs Letta Context Repositories

Letta (formerly MemGPT) builds stateful agents with long-term memory. Its newer Context
Repositories keep an agent's memory in a git-backed repository, which brings it closer to what
Svod does: a git repo of Markdown as agent memory. If you are choosing between them, this page
sets out what they have in common, where Svod differs, and where Letta is ahead. It aims to be
fair: facts about Svod come from this repository, and facts about Letta come from its public
pages (see [Sources and date](#sources-and-date)). Anything we could not confirm is marked ⚠️.

## What both share

- **Git as history.** ⚠️ With Context Repositories, Letta keeps memory changes as git commits,
  so earlier versions can be inspected. Svod makes one git commit per write.
- **Markdown files.** ⚠️ Context Repositories hold memory as files, Markdown included. Svod's
  store of record is a folder of ordinary `.md` files with frontmatter that any editor can open.
- **Long-lived agent memory.** Both are meant for memory that outlives a single conversation.

## Where Svod differs

| | Svod | Letta Context Repositories |
|---|---|---|
| **Concurrency** | A single serialized writer with an optimistic `expectedRevision` | ⚠️ Not documented as a guarantee |
| **Conflicts** | A stale write is surfaced as a conflict and never silently merged | ⚠️ Not documented |
| **Durability** | Atomic writes (temp file + rename), soft-delete to `.trash/` plus a commit, and startup crash recovery that completes or rolls back a partial write | ⚠️ Not documented as a guarantee |
| **Provenance** | The commit author of each write is the specific agent, the UI or the external editor that made the change | ⚠️ Git commits in Context Repositories. Check its docs for how authors are recorded |
| **Search** | Hybrid BM25 + vector search fused with RRF | ⚠️ Check its docs for the current search support |
| **Agent interface** | MCP, 21 tools, usable from any MCP-capable agent | Letta's own API and SDKs |
| **Runtime** | Fully local on one JVM, with in-process embeddings. No Python and no separate server | ⚠️ A Letta server (self-hosted or Letta Cloud) |
| **Licence** | Apache-2.0 | ⚠️ Apache-2.0 for the open-source server; the hosted tier is a paid service |
| **Replication** | Git transport between hosts | ⚠️ Not documented for Context Repositories |

What this means in practice: if you want memory that any MCP agent can read and write, with
conflicts surfaced and "which agent wrote this, when, and what did it say before?" answered by
git, Svod does that locally without any setup.

## Where Letta is ahead

- **A hosted tier.** Letta offers Letta Cloud. Svod is self-host only today. A separate hosted
  layer is the stated long-term direction ([ADR-0002](adr/0002-repo-split-and-license.md)), but
  nothing is built and the product decision has not been taken.
  - ⚠️ Reported limits, not verified and subject to change: Free with 3 agents and 5 MiB per
    repository; Pro at $20/month with 50 MiB per repository.
- **Stateful, self-improving agents.** Letta is an agent framework: its agents manage and edit
  their own memory as part of how they run. Svod is a memory substrate and leaves the agent loop
  to you.
- **SDKs.** Letta ships client SDKs for building agents against its API. Svod's agent interface
  is MCP, plus a local App API for UIs.

## Sources and date

Written in 2026-11. The Letta facts (Context Repositories, Letta Cloud, plans and limits) come
from its public pages listed below. They were not fetched again when this page was written.
Features, prices and limits change, so confirm them on Letta's own pages before you rely on
them:

- Letta website, docs and pricing: <https://www.letta.com> and <https://docs.letta.com>
- Letta repository and licence: <https://github.com/letta-ai/letta> (see its `LICENSE` file)
- Svod facts: this repository's [README](../README.md) feature tour and its [LICENSE](../LICENSE)

The ⚠️ cells were not confirmed against Letta's documentation when this page was written.
Corrections are welcome.
