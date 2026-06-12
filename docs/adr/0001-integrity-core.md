# ADR-0001 — Integrity core: single-writer, atomic, git-backed

- Status: **Accepted**
- Date: 2026-06-12
- Scope: Build step 1 (the foundation everything else depends on)

## Context

Svod must serve multiple local AI agents writing concurrently to one markdown vault
and **never lose a file**. Concurrency, partial writes, crashes, and stale overwrites
are all in scope. This ADR records the decisions that make the storage layer safe, so
later subsystems (index, MCP, App API, sync) can assume a sound substrate.

## Decisions

### 1. One writer, serialized through an actor
All mutations (and reads, for snapshot consistency) run on a single coroutine pinned to
one dedicated thread (`WriteActor`: `Channel` + 1 consumer). No code path mutates the
vault outside it. This is what makes optimistic concurrency *correct*: between reading a
file's current revision and writing, nothing else can interleave.

- Trade-off: writes are not parallel. Acceptable — the bottleneck is git/fsync, not CPU,
  and correctness dominates throughput for a knowledge base. Reads can be moved off the
  actor later if profiling demands it.

### 2. Atomic writes only: tmp → fsync → rename → fsync(dir)
`AtomicFile` writes to a uniquely-named `*.svod-tmp` sibling, fsyncs it, then
`ATOMIC_MOVE`s it over the target and fsyncs the parent dir. A crash leaves the target
either fully old or fully new — never half-written. We never truncate-in-place.

### 3. Revision = git blob id (content-addressed)
A file's revision is `git hash-object` of its exact bytes, computed via
`ObjectInserter.idFor` without writing. The same formula yields the revision a client is
about to write, so optimistic compare-and-set is a pure byte-identity check and never
consults the mutable index. Mismatch ⇒ `Conflict` carrying the live content for a 3-way
merge. We never silently overwrite.

### 4. Soft-delete, never hard rm
Delete moves the file to `.trash/<path>` (disambiguated on collision) and commits the
move. `.trash/` **is tracked** in git, so a deleted file is recoverable two ways: from
history and from the working-tree trash copy. Restore moves it back and commits.

### 5. Every mutation is a git commit authored by the caller
`jgit` stages the whole working tree (adds + deletions) and commits with the
agent/UI identity as author *and* committer. Git is the durable history; a "lost" file
is always `git log`-recoverable.

### 6. Recovery completes, it does not roll back
On open, before serving: (a) delete orphan `*.svod-tmp`; (b) if the working tree differs
from `HEAD`, commit the difference as a `svod-recovery` commit. Rationale: a change that
reached the working tree was fsync'd and atomically renamed, therefore real and durable —
rolling it back would *lose a file*. Half-written content is impossible by construction,
so committing residue is always safe.

### 7. Single-instance via OS advisory lock
`VaultLock` holds an exclusive `FileLock` on `.svod/lock` for the engine's lifetime; a
second open (another process or the same JVM) is refused with `VaultLockedException`.

### 8. UTF-8 / Cyrillic everywhere
`core.quotepath=false`, `core.autocrlf=false`, `i18n.commitEncoding=UTF-8`. Verified by a
test writing a Cyrillic path + content and asserting `git ls-files` shows the raw path.

## Consequences

- The engine is OS-agnostic; no macOS specifics leak into the core.
- Failure is modeled as **values** (`WriteOutcome.Success | Conflict | NotFound`), not
  exceptions, so callers (MCP/App API) map them to protocol responses cleanly.
- The `VaultPath` value class is the single choke point preventing traversal and writes
  into engine-managed dirs (`.git`, `.svod`, `.trash`).

## Verification (gate — must stay green before step 2)

`engine/src/test/.../core`:
- `ConcurrencyTest` — 250 parallel distinct-file writes lose nothing; two writers from one
  base revision ⇒ exactly one wins; 40-writer retry contention keeps every line once;
  randomized fuzz keeps tree == HEAD and `git fsck` clean.
- `CrashRecoveryTest` — crash injected at each of `AFTER_TMP_WRITE / AFTER_FSYNC /
  AFTER_RENAME`; recovery cleans tmp, completes post-rename writes (never lost), refuses a
  second instance.
- `CyrillicTest`, `EngineBasicTest` — round-trips, traversal rejection, lifecycle.

## Alternatives considered

- **Lock-per-file / MVCC store** — more parallelism, far more complexity; rejected, the
  actor is simpler and provably correct.
- **Roll back partial writes on recovery** — simpler mental model but can discard a
  durably-written file; rejected as it violates the prime directive.
- **Revision = commit hash** — coarser (whole-tree), forces serial revisions across
  unrelated files; blob hash is the natural per-file unit.
