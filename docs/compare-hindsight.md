# Svod vs Hindsight

Hindsight, by Vectorize, is an open-source memory system for AI agents. It takes what an agent
sees, uses an LLM to extract facts from it, and later "reflects" over those facts to form
higher-level observations. Svod takes a different path: memory is a git repository of Markdown
that people and agents both read and edit. If you are choosing between them, this page sets out
what they have in common, where Svod differs, and where Hindsight is ahead. It aims to be fair:
facts about Svod come from this repository, and facts about Hindsight come from its public pages
(see [Sources and date](#sources-and-date)). Anything we could not confirm from a primary source
is marked ⚠️ together with the date it was reported.

## What both share

- **Open source.** Svod is Apache-2.0. ⚠️ Hindsight is reported as MIT-licensed (as of 2026-11-27,
  not confirmed against its `LICENSE` file).
- **MCP access.** Svod's agent interface is MCP. ⚠️ Hindsight is reported to offer an MCP server
  (as of 2026-11-27).
- **Claude Code integration.** Svod ships a ready-to-paste `claude mcp add` setup and imports
  Claude Code auto-memory folders. ⚠️ Hindsight is reported to offer a Claude Code integration
  (as of 2026-11-27).
- **Long-lived agent memory.** Both are meant for memory that outlives a single conversation.

## Where Svod differs

| | Svod | Hindsight |
|---|---|---|
| **Store of record** | A folder of ordinary `.md` files with frontmatter, in a git repository, readable and editable in any editor | ⚠️ A database (reported as Postgres with pgvector, as of 2026-11-27) |
| **Provenance** | Every change is a git commit whose author is the specific agent, the UI or the external editor that made it | ⚠️ Not documented as per-change authorship (as of 2026-11-27) |
| **Memory model** | Typed memories (policy / preference / fact / episode / procedure) with a lifecycle and an optional per-agent review queue | LLM-extracted facts, consolidated by a reflect step |
| **Durability** | Never loses a file: atomic writes, soft-delete to `.trash/` plus a commit, crash recovery; a stale write is surfaced as a conflict, never silently merged | ⚠️ Not documented as a guarantee (as of 2026-11-27) |
| **Search** | Hybrid BM25 + vector search fused with RRF, in one JVM with in-process embeddings | ⚠️ Check its docs for the current retrieval strategies |
| **Write path cost** | No LLM call is required to store a memory and there is no per-token cloud cost | ⚠️ Fact extraction calls an LLM on write (as of 2026-11-27) |
| **Agent interface** | 21 MCP tools, usable from any MCP-capable agent | ⚠️ API, SDKs and an MCP server |
| **Lock-in** | Leaving is a `git clone`: the memory is plain Markdown | ⚠️ An export from its database |

What this means in practice: if you want memory a person can open, read, correct and diff, with
"which agent wrote this, when, and what did it say before?" answered by git, Svod does that
locally with no extra service and no model calls on the write path.

## Where Hindsight is ahead

- **Automatic fact extraction.** Hindsight turns raw conversations into facts with an LLM (as of
  2026-11-27), so nobody has to structure memories by hand. Svod stores what the agent or person chose to write
  and does not extract facts for you.
- **Reflection.** ⚠️ Hindsight's reflect step merges memories into observations and mental
  models (as of 2026-11-27). Svod has no equivalent consolidation step.
- **Database scale.** ⚠️ A Postgres/pgvector backend (as of 2026-11-27) is built for larger
  memory stores and more concurrent clients than a single-writer git repository.
- **A hosted cloud.** ⚠️ Vectorize is reported to offer a hosted, pay-per-use Hindsight cloud
  with no operations work (as of 2026-11-27, pricing not confirmed). Svod is self-host only today.
- **A larger community.** ⚠️ The Hindsight repository was reported at about 63.4k GitHub stars
  (as of 2026-11-27, not confirmed).

## Sources and date

Checked 2026-11-27. The Hindsight facts on this page (licence, star count, storage backend,
cloud pricing model, Thoughtworks Technology Radar ring and date) come from research notes and
were not confirmed against the primary sources below when this page was written, so they are
marked ⚠️. The Radar entry was reported as "Assess" with a date of 2026-11-04 (⚠️ not confirmed).
Features, prices and rankings change, so confirm them on Hindsight's own pages before you rely
on them:

- Hindsight repository and licence: <https://github.com/vectorize-io/hindsight> (see its `LICENSE` file)
- Hindsight documentation: <https://hindsight.vectorize.io>
- Thoughtworks Technology Radar: <https://www.thoughtworks.com/radar>
- Svod facts: this repository's [README](../README.md) feature tour and its [LICENSE](../LICENSE)

Corrections are welcome.
