# ADR-0009 — Multi-host sync: replicated engines, merge authority, frontmatter-aware merge

- Status: **Accepted**
- Date: 2026-06-12
- Scope: Build step 7 (the most complex subsystem)

## Context

The vault must replicate across hosts (Mac + `friday` + `sage-production`), each writing
locally and offline, converging through git — while preserving the prime directive: **never
lose files**, and **never silently overwrite**. Two independent hosts merging the same
divergence could produce different merge commits (non-deterministic history). And a
line-based merge mangles YAML frontmatter.

## Decisions

### 1. Replicated engines over git; each host is a full clone
Every host runs its own `SvodEngine` on its own clone and holds the **entire history**, so a
"lost" file is recoverable on any host. Sync is fetch → reconcile → push against a shared
remote's canonical branch. Hosts work offline and reconcile when connected.

### 2. A designated merge authority for determinism
Only the **merge authority** creates merge commits. A replica that diverges does **not**
merge: it pushes its commits to a proposal branch (`svod/<hostId>`) and **fast-forwards**
once the authority has folded them into the canonical branch. So the fleet's history is
identical everywhere — no divergent merge hashes, no merge races. (Fast-forward pulls/pushes
happen on any host; only *merging* is centralized.)

### 3. Frontmatter-aware structured merge
`FrontmatterMerge` merges a markdown note as **two layers**:
- **YAML frontmatter at the key level** (not line-based): a key changed on one side wins;
  both-same is fine; tag-style **lists union**; a scalar changed *differently* on both sides
  is a real conflict.
- **The body** via git's own 3-way line algorithm (jgit `MergeAlgorithm`).
Cyrillic/UTF-8 is emitted literally (`allowUnicode`). *(7 merge tests, incl. Cyrillic.)*

### 4. Conflicts surfaced, never auto-resolved
A true conflict is recorded in `ConflictStore` (path + base/ours/theirs + reasons) and exposed
at `GET /api/v1/conflicts` for a 3-way merge UI or an agent to resolve. The authority keeps
**ours** in the merged tree (no silent overwrite by theirs); **theirs is never lost** — it
lives in the conflict record and in git history (the proposal branch is a parent of the merge
commit). *(Proven: conflicting edits → surfaced, both versions recoverable.)*

### 5. Sync writes go through the single writer
Fetch/push and all ancestry/tree reads use a separate read-only `SyncGit` handle (they don't
race the writer). But every ref-moving WRITE — fast-forward and the merge commit — goes
through the engine's write-actor (`fastForwardTo`, `applyMerge`), so the Step-1 single-writer
guarantees hold during sync.

## Consequences

- Offline-first, eventually-consistent replication that converges deterministically.
- Frontmatter (tags, status, dates) merges sensibly instead of conflicting on every edit.
- The `/api/v1/conflicts` endpoint (shaped since Step 4) is now populated; the UI's 3-way
  merge resolves them.

## Known simplifications (documented, not hidden)

- A conflicting file currently defaults to **ours** in the merged tree (flagged for
  resolution) rather than writing inline `<<<<<<<` markers. Both versions are preserved; the
  resolution flow (write the merged content) supersedes.
- Delete-vs-modify across hosts is surfaced as a conflict (kept as-is, recorded).
- One remote per engine is wired; multi-remote fan-out is a later iteration.

## Alternatives considered

- **Every host merges (no authority)** — risks divergent merge commits and merge races across
  the fleet; rejected for the authority + proposal-branch model.
- **Plain git 3-way merge (`git merge`)** — line-based; mangles YAML frontmatter and conflicts
  on trivially-mergeable tag edits. Rejected for the structured frontmatter merge.
- **CRDT store** — would abandon git as the source of truth (the whole point: readable,
  diffable, restorable). Rejected.
- **Auto-resolving conflicts (last-writer-wins / ours/theirs)** — violates "never silently
  overwrite". Rejected for surfacing.
