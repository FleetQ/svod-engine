# ADR-0002 — Repo split, FleetQ-agnostic engine, Apache-2.0

- Status: **Accepted** (license: *proposed default, pending owner confirmation before public release*)
- Date: 2026-06-12
- Supersedes the original single-brief assumption of a bundled macOS UI.

## Context

After step 1, the product strategy was settled (svod-plan.md → "Продуктова стратегия"):
Svod is positioned as a standalone OSS product — *auditable, git-backed agent memory you
can read, diff, and restore* — competing with opaque vector stores (Mem0 / Letta / Zep),
not with note apps. This reframing changes how the code is organized and licensed.

## Decisions

### 1. Two repos, not a monorepo
- **`FleetQ/svod-engine`** (public OSS) — working dir `~/htdocs/svod/`. The product:
  `engine/`, `contract/`, `dist/`, `docs/`, `examples/`.
- **`FleetQ/svod-ui-macos`** (private, personal) — working dir `~/htdocs/svod-ui-macos/`.
  A personal SwiftUI client built against the published OpenAPI contract. Explicitly **not**
  a supported product surface: no cross-OS, no marketing, out of product scope.

The engine and UI release independently against the versioned contract, so a separate repo
is the honest boundary. The UI's presence in the product repo would imply support we are
not offering.

### 2. The engine stays FleetQ-agnostic
No FleetQ-specific code, names, or assumptions anywhere in the engine. FleetQ attaches as
**one MCP client** — a first-party *reference* integration and proof point, nothing more.
This keeps Svod adoptable by anyone and prevents the OSS core from leaking a single vendor's
shape. The FleetQ example lives in `examples/` once the MCP server (Step 3) exists.

### 3. The product demo does not depend on the personal UI
The engine exposes the activity/audit stream plus a trivial reference web viewer in
`examples/` (built at Step 4, when the App API exists). The narrative — *"watch agents
write, then `git diff` their memory"* — is demonstrable without the macOS app.

### 4. License: Apache-2.0 (proposed default)
Adoption-first permissive license; the commercial layer comes later as a separate
hosted/control-plane product (Boruna playbook), not via copyleft on the core. **Flagged as
a proposed default — the owner confirms before any public release.**

## UI extraction mechanics

`ui-macos/` was an empty, untracked scaffold (git does not track empty directories), so
there was **no meaningful history to preserve**. A `git subtree split` would have produced
an empty branch. We therefore did a clean removal on the engine side and a fresh `git init`
for the UI repo, rather than a subtree split. (Recorded here so the choice is auditable.)

## Consequences

- `docs/architecture.md` and `build-order.md` updated: the UI is no longer a product step;
  step 6 is tracked in the separate personal repo.
- `examples/` is the home for reference integrations (web viewer, FleetQ-MCP).
- A `NOTICE`/source-header pass is deferred until just before public release, alongside
  license confirmation.

## Alternatives considered

- **Monorepo (engine + UI together)** — simpler tooling, but couples a personal,
  unsupported macOS app to a public OSS product and muddies the support contract. Rejected.
- **AGPL-3.0** — stronger copyleft protection against closed SaaS forks, but raises adoption
  friction for exactly the agent-platform integrators we want (and we plan to monetize via a
  separate control plane, not core copyleft). Rejected in favor of Apache-2.0.
- **`git subtree split` for the UI** — correct when there is history to carry; here it would
  only yield an empty branch, so it added ceremony with no benefit. Rejected.
