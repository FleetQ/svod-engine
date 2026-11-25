# ADR-0021 — Encryption at rest: host volume encryption with a key the customer holds

- Status: **Accepted**
- Date: 2026-11-25
- Depends on: ADR-0010 (hardening, `Secrets`), ADR-0013 (production hardening, logs and Sentry),
  ADR-0014 (per-vault backup remotes).

## Context

Security reviews ask whether Svod encrypts data at rest. Until now the answer was "no, use disk
encryption", without saying which data that covers or why the engine does not do it itself.

What the engine keeps on disk:

- **The vault** is an ordinary git repository of Markdown files. That is a product promise: people
  open the same folder in Obsidian, run `git log` and `git diff` on it, and sync it to plain git
  remotes (ADR-0009, ADR-0014).
- **The derived state under the vault's `.svod/`** is not a safe-to-ignore cache. The Lucene index
  holds the note text, the stored fields and the embedding vectors, and the graph sidecar holds
  excerpts and summaries. Anyone who can read `.svod/index` can read the notes. `.svod/` also holds
  the agent audit log.
- **Next to the config and under the service user's home** are personal key and agent token files,
  the App API audit log, logs that can contain note paths and error text, and a few small state files.

The full list of paths, checked against the code, is in
[security-overview.md, Encryption at rest](../security-overview.md#encryption-at-rest).

## Decisions

1. **The supported way to encrypt Svod data at rest is host volume or full-disk encryption whose key
   the customer holds and controls.** Put every path from the list above on that volume:
   - Linux servers: LUKS2 / dm-crypt (the central-instance runbook shows a Debian setup).
   - macOS: FileVault.
   - Windows: BitLocker.

   The key never reaches Svod. The engine reads and writes plain files on a volume the operating
   system has already unlocked, so there is no key in the config, no key management in the engine,
   and nothing Svod could lose or disclose.

2. **Encryption built into the engine is rejected for now.** The options considered:
   - *Encrypting git objects in the app.* The vault would no longer be a usable git repository:
     `git diff`, merges, multi-host sync and outside tools (Obsidian, any git client) would all
     break, and the index would still need its own scheme.
   - *git-crypt or a clean/smudge filter.* Only file contents are encrypted. Paths, commit messages,
     authors and times stay readable, merges of encrypted files need special handling, and the
     Lucene index under `.svod/` would stay plain text, so the notes would still be readable on disk.
   - *An encrypted Lucene `Directory`.* There is no maintained upstream codec for it, every search
     would pay for decryption, and it would cover the index only, not the vault or the logs.

   Each of these adds a key the engine has to manage, while still leaving some note text on disk in
   plain form. Volume encryption covers all of it with one key that the engine never sees.

3. **The volume does not cover data that leaves the machine.** Sync and backup git remotes, and the
   Sentry project when `SENTRY_DSN` is set, receive data outside the volume. The operator must
   encrypt those on their own terms (an encrypted remote host, a provider with encryption at rest).

## Consequences

- No engine code changes. The answer in security reviews becomes "supported through host volume
  encryption with a customer-held key", with a concrete path list instead of a flat "no".
- [`SecurityOverviewDocsTest`](../../engine/src/test/kotlin/dev/svod/engine/security/SecurityOverviewDocsTest.kt)
  reads the fixed paths, the files under `.svod/` and the path environment variables
  (`SVOD_*_FILE`, `SVOD_*_DIR`) from the code, and fails CI when the list misses one of them. Paths
  that come from configuration at runtime (vault paths, external source folders under ADR-0016,
  local git remotes) cannot be found that way; the list names them by their setting, and a new
  setting of that kind has to be added by hand.
- The engine does not check whether it runs on an encrypted volume. Getting that right is the
  operator's job, and the runbook shows how.
- A stolen disk or a copied volume image is protected. A running, unlocked host is not: anyone with
  the service user's access, or root, reads the files in plain text, as with any volume encryption.

## Revisit when

A later ADR should reconsider encryption inside the engine if any of these becomes a real buyer
requirement:

- keys managed through a KMS (bring your own key, rotation through the customer's KMS);
- a separate key per vault, so one vault can be made unreadable without the others;
- a hosted deployment where the host operator must not be able to read vault contents.
