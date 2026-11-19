# Svod vs Basic Memory

Both projects give AI agents a long-term memory made of plain Markdown files they can read and
write over MCP. If you are choosing between them, this page sets out what they have in common,
where Svod differs, and where Basic Memory is ahead. It aims to be fair: facts about Svod come
from this repository, and facts about Basic Memory come from its public pages (see
[Sources and date](#sources-and-date)). Anything we could not confirm is marked ⚠️.

## What both share

- **Markdown is the store of record.** Notes are ordinary `.md` files with frontmatter. You can
  open, edit and grep them with any editor, including Obsidian.
- **MCP is the agent interface.** Agents read, search and write memory through MCP tools rather
  than a vendor SDK.
- **Local-first.** Both run on your own machine against a folder you own. Neither needs a cloud
  account to work.
- **Low lock-in.** Leaving either one means keeping a folder of Markdown files. Svod can also
  import a Basic Memory project directly with
  `svod-engine import-basic-memory <config.json> <dir> [into]`. Observations, relations and
  frontmatter are kept verbatim, and relations become resolvable wikilinks.

## Where Svod differs

| | Svod | Basic Memory |
|---|---|---|
| **Licence** | Apache-2.0 (permissive) | AGPL-3.0 (copyleft, network clause) |
| **History** | One attributed git commit per write. `log`, `diff` and restore of any earlier revision are built in | ⚠️ No built-in per-write history. Bring your own git or sync |
| **Concurrency** | A single serialized writer with an optimistic `expectedRevision`. A stale write is surfaced as a conflict and never silently merged | ⚠️ Not documented as a guarantee |
| **Durability** | Atomic writes (temp file + rename), soft-delete to `.trash/` plus a commit, and startup crash recovery that completes or rolls back a partial write | ⚠️ Not documented as a guarantee |
| **Provenance** | The commit author is the specific agent, the UI or the external editor that made the change | ⚠️ Not documented |
| **Runtime** | Fully in-process on one JVM, with in-process embeddings. No Python and no separate server | ⚠️ A Python package with a local SQLite index |
| **Search** | Hybrid BM25 + vector search fused with RRF | ⚠️ Full-text first. Check its docs for the current semantic search support |
| **Replication** | Git transport between hosts | ⚠️ Its cloud product syncs between devices |

What this means in practice: if several agents write to the same memory, or if you need to
answer "which agent wrote this, when, and what did it say before?", Svod's writer and git
history do that without any setup.

## Where Basic Memory is ahead

- **A hosted product.** Basic Memory offers a paid cloud tier with apps. Svod is self-host only
  today. It has no hosted product and has made no decision about one.
- **Team seats.** ⚠️ Basic Memory's paid plans advertise team use. Svod supports shared vaults
  with users and roles (ADR-0020), but you run the server yourself.
- **Onboarding.** A polished hosted sign-up gets a non-technical user going faster than
  installing and configuring a JVM service.
- **Copyleft, if that is what you want.** Some teams prefer AGPL-3.0 because it keeps hosted
  forks open.

## Sources and date

Written in 2026-11. The Basic Memory facts (AGPL-3.0, paid cloud tier, team use) come from its
public pages listed below. They were not fetched again when this page was written. Licences,
prices and features change, so confirm on the project's own pages before you rely on them:

- Basic Memory repository and licence: <https://github.com/basicmachines-co/basic-memory>
  (see its `LICENSE` file)
- Basic Memory website, cloud and pricing: <https://basicmemory.com>
- Svod facts: this repository's [README](../README.md) feature tour and its [LICENSE](../LICENSE)

The ⚠️ cells were not confirmed against Basic Memory's documentation when this page was written.
Corrections are welcome.
