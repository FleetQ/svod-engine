# Test plan — multi-vault + Obsidian import

> Maps to [architecture-multivault-import.md](architecture-multivault-import.md). Every gate
> ships with its tests green before the next. The existing 91-test suite is a regression gate.

## Gate 1 — Import + binary write path

- **T1.1** `writeBytes` round-trips arbitrary bytes (incl. non-UTF-8) through the actor; a read
  back is byte-identical; one git commit; `git fsck` clean.
- **T1.2** `write(String)` still behaves exactly as before (wrapper over `writeBytes`).
- **T1.3** Import collects **all** file types — a vault with `.md` + `.png` + `.pdf` imports all;
  `.obsidian/` and dot-dirs skipped.
- **T1.4** Imported markdown keeps frontmatter + `[[wikilinks]]` verbatim; an attributed commit
  per file.
- **T1.5** Attachment reference `![[diagram.png]]` resolves after import (the binary is present
  in the tree).
- **T1.6** **Idempotency**: re-import the same source → second run reports every file `unchanged`,
  creates **no** `name (1).md`, clobbers nothing, head unchanged.
- **T1.7** Re-import after an external edit of one note → that note reported as `skipped`
  (conflict, left as-is — never overwritten); others `unchanged`.
- **T1.8** Binaries are committed but **not** embedded/searched; markdown IS indexed (search
  finds imported note text).
- **T1.9** `POST /api/v1/import` returns the result DTO and conforms to the contract; CLI
  `--import <src> <vault>` performs the same.
- **T1.10** Cyrillic filenames + an attachment with a Cyrillic name import correctly.

## Gate 2 — Multi-vault core (back-compat is sacred)

- **T2.1** Legacy config (`vaultPath` only) synthesizes a single default vault; **every existing
  endpoint behaves identically** (the existing suite passes unchanged).
- **T2.2** Two-vault config opens both; each acquires its own lock; a second process on either
  vault is refused (single-instance per vault preserved).
- **T2.3** `GET /api/v1/vaults` lists both with `default` flag + per-vault sync role.
- **T2.4** `?vault=work` routes reads/writes to the work vault; omitted ⇒ default vault; unknown
  vault ⇒ 404.
- **T2.5** A write to `work` produces a commit in work's repo only; `personal` untouched.
- **T2.6** Per-vault metrics/index-status/conflicts are isolated.
- **T2.7** Per-vault sync: configuring a remote on one vault syncs only that vault; the other has
  no sync status.
- **T2.8** Graceful shutdown drains **every** vault's writer and releases **every** lock;
  write→shutdown→restart reads it back from each vault.
- **T2.9** Config validation: duplicate vault ids, an agent grant to a non-existent vault, and an
  unresolvable defaultVault each fail fast.

## Gate 3 — Federation + scoping + cross-vault rename

- **T3.1** Qualified link `[[work:project]]` inside `personal/note.md` resolves; `work:project`'s
  backlinks include `personal:note` (cross-vault backlink).
- **T3.2** Unqualified `[[project]]` resolves only within its own vault; an identically-named note
  in the other vault is **not** matched.
- **T3.3** `GET /api/v1/file/links?vault=work&path=project.md` shows the cross-vault backlink from
  personal.
- **T3.4** Federated search (`across=true`) returns hits from both vaults, each tagged with its
  vault, merged by score.
- **T3.5** **Cross-vault rename (D7)**: move `work:project.md`→`work:proj.md`; in-vault backlinks
  rewrite atomically; a `[[work:project]]` in `personal/note.md` is rewritten to `[[work:proj]]`
  in personal's repo as a separate commit. Both commits land; both repos `git fsck` clean.
- **T3.6** Cross-vault rename **never loses data**: if the cross-vault rewrite is skipped/fails,
  the move still succeeds, nothing is deleted, and the stale link surfaces as *unresolved* (not a
  lost file). (Inject a failure on the personal writer; assert work moved + personal note intact.)
- **T3.7** MCP agent scoping: an agent granted only `work` can read/write `work`; a tool call
  naming `personal` is `denied` and audited; `list_vaults` returns only granted vaults.
- **T3.8** An agent granted both vaults can write to each; commits are authored by the agent in
  the respective repo.

## Cross-cutting / regression

- **TX.1** Full existing suite (91) stays green throughout (run at every gate).
- **TX.2** Contract conformance: new/changed responses validate against openapi.yaml 0.3.0; the
  "declared paths == implemented routes" test includes `/api/v1/vaults` + `/api/v1/import`.
- **TX.3** UTF-8/Cyrillic across vault ids, paths, and cross-vault links.
- **TX.4** `git fsck` clean in every vault after the full scenario (integrity invariant).
