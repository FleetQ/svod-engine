# ADR-0018 — Fact classification on the `remember` promotion gate

Status: accepted

## Context

`remember` (ADR-0005 / the memory-system work) promotes an observation into a durable typed memory
note. Until now it checked the incoming memory against exactly one thing: a deterministic content
hash. Identical content and type produced the same path, so an exact restatement was a no-op; a
`supersedes` argument revoked and linked a predecessor. Anything else was written blind.

That is enough to stop literal duplication and nothing else. An agent that learned "Prod DB is in
us-east-1" on Monday and "Prod DB is in eu-west-1" on Friday produced two `provisional` facts about
the same subject with no recorded relationship — recall would later surface both, with no signal
that they disagree. The failure mode the memory system exists to prevent (an agent-written KB
poisoning itself) was only half addressed: we prevented *repetition*, not *contradiction*.

We want the gate to answer, before persisting: is this NEW, a DUPLICATE, an UPDATE of something
stored, a CONTRADICTION of it, or genuinely UNCERTAIN?

## Decision

### 1. Classify against existing memory, per subject

`FactClassifier` retrieves comparable memories through the **existing** hybrid path
(`IndexService.search` → BM25 + kNN + RRF) with `type` as a filter and `includeAll = true`, then
takes the top-k (default 5). `includeAll` is not optional: `fact`/`policy` memories enter
`provisional` and are hidden from ordinary recall, yet they are precisely what an incoming fact must
be compared against.

Subject scoping is applied **after** retrieval, in the classifier, by reading each candidate's
`subject` frontmatter. `subject` is not an indexed field and we deliberately did not make it one —
"no new index" was a constraint, and top-k is small enough that post-filtering costs nothing.
Candidates that are already retired (`status: revoked`, or carrying `superseded_by`) are dropped:
a retired memory cannot be contradicted.

### 2. Deterministic rules first; the LLM only in the middle band

Ordered cheapest-and-most-certain first, stopping at the first rule that settles the question:

1. **Normalized-text equality** — case-folded, punctuation-stripped, whitespace-collapsed. An exact
   restatement is `DUPLICATE` at confidence 1.0, with no model of any kind involved.
2. **Token overlap (Jaccard)** — a deterministic lexical signal that works with *no embedder at
   all*, which matters because `none` (BM25-only) is the engine's guaranteed baseline (ADR-0004).
3. **Embedding cosine** — used only when the configured `Embedder.isActive`, and only to place the
   pair in a band. Embedding failures are caught and fall through to the lexical signal.
4. **LLM adjudication** — consulted *only* for the ambiguous band, and only if configured.

Thresholds:

| Signal | Duplicate at | Ambiguous band | Unrelated below |
|---|---|---|---|
| embedding cosine | ≥ 0.97 | 0.82 – 0.97 | 0.82 |
| token overlap (no embedder) | ≥ 0.90 | 0.35 – 0.90 | 0.35 |

The cosine numbers are chosen against e5-family behavior, where paraphrases of the same statement
sit very high: 0.97 is tight enough that only restatements collapse, and 0.82 is the point below
which same-type/same-subject notes are, in practice, about different things. They are constants on
`FactClassifier`, not config — one knob per band is a tuning surface we do not yet have evidence to
expose. The lexical band is wider because token overlap is a coarser signal; it is a fallback, not
a peer.

Only the ambiguous band can ever yield `UPDATE` or `CONTRADICTION`, and only from an adjudicator.
**No deterministic rule invents a semantic relationship** — the rules can say "the same" or
"unrelated", never "these disagree".

### 3. The LLM is optional, and its absence is honest

`MemoryAdjudicator` is an interface with no implementation in the engine and `null` by default. No
new dependency, no network client, no configuration required. With no adjudicator — or one that
returns `null`, throws, or is unreachable — the ambiguous band resolves to `UNCERTAIN`. It never
guesses a confident class to fill the gap, and it never fails the write.

### 4. Behavior per class

| Class | Effect |
|---|---|
| `NEW` | Written as before. |
| `DUPLICATE` | No write. Returns `deduped` with a reference to the existing note. |
| `UPDATE` | New note written with `supersedes:`; predecessor gets `status: revoked` + `superseded_by:` in the **same commit**. jgit keeps the predecessor's earlier content reachable. |
| `CONTRADICTION` | **Both** memories persist. The new note carries `contradicts: <path>`; the predecessor is left byte-for-byte untouched, and the disagreement is surfaced in the tool response. Never a silent overwrite. |
| `UNCERTAIN` | Persisted with `needs-review: true`. |

`needs-review` is kebab-case while the older reserved keys are snake_case (`superseded_by`,
`expires_at`). That inconsistency is deliberate only in the sense that it was specified that way;
it is worth normalizing if these keys ever grow a parser of their own.

### 5. Plan off the actor, validate and apply inside it

This is the load-bearing concurrency decision.

Classification is impure and potentially slow: a Lucene query, an embedding call that may cross the
network, and possibly an LLM call. The write-actor (ADR-0001) is a *single thread* executing
blocking lambdas, and ADR-0017 established — and `IndexConcurrencyTest` proves — that **writes never
wait on embedding**. Running classification inside `actor.submit` would stall every write in the
vault, plus sync and watcher ingest, for the duration of a remote round-trip. That is not an
acceptable price for a `remember` call.

Classifying *before* the actor instead is a TOCTOU: the evidence could change between the decision
and the commit.

So we do neither, and take the shape the codebase already uses for exactly this problem —
`writeBatch(expected =)` and `applyMerge(expectedHead =)`: **plan off-actor, validate-and-apply
on-actor.** The classifier returns a `ClassificationPlan` carrying `guards` — the path→revision map
of every candidate the verdict was computed against. A new engine primitive,
`SvodEngine.writeGuarded(files, guards, author, message)`, runs one actor task that re-checks every
guard against live blob ids, then writes all files in a single commit. A mismatch is
`GuardedWrite.Stale`: nothing is written and the caller re-plans against live state (once; a second
staleness returns `conflict` rather than looping). Because the check and the write share one actor
task, nothing can interleave between them — the single-writer invariant holds, and the decision is
only ever committed against the state it was actually derived from.

Two honest limits of the guard set:

- It catches **mutation** of examined candidates. A candidate *created* concurrently, in the window
  between retrieval and apply, has no prior revision to guard and can be missed.
- What it does guarantee absolutely is the duplicate case: identical content and type hash to the
  same deterministic path, so two concurrent identical `remember` calls collide on one path, the
  loser is `Stale`, re-plans, and sees the winner — exactly one note results.

### 6. Secret-scan moved ahead of classification

Previously the secret scanner ran inside `doWrite`, at the end. With classification in front of the
write, content would reach a **remote** embedder or an LLM *before* the scanner refused it — a
credential would leave the machine and only then be blocked from disk. `remember` therefore calls
`engine.scanSecrets` first and refuses up front. The engine still re-scans on write; this only moves
the refusal earlier. Covered by a regression test asserting the adjudicator never observes secret
content.

### 7. MCP surface

The `remember` response gains `classification`, `relatedNote`, `confidence` (plus `rationale`, and
`contradicts`/`needsReview` where they apply). Existing fields — `status`, `path`, `type`,
`memoryStatus`, `revision`, `commit`, `superseded` — keep their meaning and values, so existing
callers are unaffected. No argument changes. Contract bumped to **0.23.0** (additive: three new
reserved frontmatter keys, documented on `/search`).

## Explicit non-goal

**This is not knowledge-graph entity resolution.** Scope is per-subject fact consistency: two
memories are compared only when they share a `type` and, when the incoming memory declares one, a
`subject` string. The engine makes **no attempt** to decide that two differently spelled subjects
denote the same entity ("prod-db" vs "production database"), to extract subjects from free text, to
normalize or canonicalize subject names, or to reason transitively across a chain of related facts.
A memory with no `subject` is compared by type alone. Cross-entity reasoning, coreference, and an
actual entity graph are out of scope and are not partially implemented here — the classifier would
rather return `UNCERTAIN` than pretend to resolve an entity.

## Consequences

- The gate now catches contradictions instead of silently accumulating them, and a contradiction is
  recorded as a link rather than resolved by whoever wrote last.
- `UNCERTAIN` will be common on `none`-embedder installs with no adjudicator: the lexical band is
  wide, so related-but-not-identical facts land in review rather than being classified confidently.
  This is the intended failure direction — a flagged note is recoverable, a wrong `UPDATE` that
  revoked a good memory is not.
- `remember` now costs a search (and possibly an embed/LLM call) per call. It is an explicit
  promotion gate, not a hot path, and none of that cost lands on the write-actor.
- `writeGuarded` is a general primitive; the multi-file atomic commit it provides is what makes
  UPDATE's revoke-and-write a single commit rather than the two independent writes it used to be.
- Adding a real adjudicator later is a constructor argument, not a redesign.
