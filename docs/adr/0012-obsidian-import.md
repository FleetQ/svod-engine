# ADR-0012 — Obsidian import: one-shot, idempotent, attachments included

- Status: **Accepted**
- Date: 2026-06-12
- Scope: User-triggerable Obsidian → Svod migration (requirements in
  `docs/requirements-multivault-import.md`).

## Context

The import *logic* existed (`ObsidianImport`) but was wired to no entrypoint and was markdown-only.
Discovery: users want a **one-shot migration** (not a live bidirectional bridge) that **carries
attachments** (images, PDF — they're referenced by `![[…]]`) and is safe to re-run.

## Decisions

### 1. One-shot migration, not a live bridge
Import copies an Obsidian vault into a Svod vault through the normal write path: frontmatter and
`[[wikilinks]]` preserved verbatim, `.obsidian/` and dot-dirs skipped, each file an attributed
commit. After migration the user lives in Svod (Obsidian can remain just an editor on the same git
tree). No bidirectional sync was built — out of scope by the user's choice.

### 2. Attachments via a binary-safe write path
Import collects **all** files, not just `.md`. Markdown goes through the text path (secret-scanned
and indexed); everything else goes through the engine's new `writeBytes` (ADR-0011 §7) so binaries
land byte-for-byte and `![[diagram.png]]` keeps resolving. Binaries are committed but not embedded
(the index filters to `.md`).

### 3. Idempotent, never clobbers
Re-running reconciles instead of duplicating: a file already present with **identical** content is
`unchanged`; one present with **different** content is `skipped` (left exactly as-is — a migration
never overwrites a local edit). Equality is a byte compare via `readBytes` (accurate for binaries,
unlike a lossy UTF-8 decode). No `name (1).md` artifacts; a no-op re-import adds no commits.
`Result` is `(imported, unchanged, skipped)`.

### 4. Surfaces: App API + CLI (not MCP)
`POST /api/v1/import {source, into?, vault?}` (the natural fit for a UI "Import" button, loopback)
and `svod-engine import <config> <dir> [into]` (run while the daemon is stopped — the vault lock is
exclusive; while it's up, use the API). A local-filesystem import is an operator/loopback action, so
it is deliberately **not** an MCP tool a remote agent could drive.

### 5. Security: `source` is an arbitrary local path — and that's correct
A code review flagged `POST /api/v1/import { source }` as an arbitrary-path read. It is, **by
design**: importing an Obsidian vault means pointing at a folder *outside* the Svod vault. This is
safe under invariant 7 — the App API binds 127.0.0.1 only and acts as the trusted local UI
identity (the same trust a desktop "choose folder" dialog has), and import is deliberately **not**
an MCP tool, so a remote agent can't reach it. Restricting `source` to vault roots was rejected
because it would break the feature. The boundary that matters is loopback + off-MCP, not a path
allowlist.

## Consequences

- Importing the user's two Obsidian vaults yields the two Svod vaults (`vault` selects the target).
- Export is unchanged — `git clone` per vault. Zero lock-in, both directions.

## Alternatives rejected

- **Markdown-only import** — drops attachments, breaking `![[image]]` references.
- **Overwrite-on-reimport** — would clobber post-migration edits; rejected for skip-if-differs.
- **An MCP `import` tool** — a remote agent shouldn't drive a local filesystem import; App API +
  CLI cover the real surfaces.
