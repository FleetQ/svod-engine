# Architecture: web viewer usability and accessibility fixes

Design: [design-web-viewer-ux.md](design-web-viewer-ux.md). Files: `examples/web-viewer/{index.html,style.css,app.js}`.
The viewer stays dependency-free; nothing is added to the engine.

## Target host

The hosted team engine on fleetq-01 does not serve the viewer: `/srv/svod-teams/nikola/config/config.json`
has no `webViewerPath`, and `svod-engine:1.33.0-hosted` contains no viewer files (checked 2026-11-19).
`dist/package.sh` and `release.yml` do not package it either. So this change ships with the repo and
reaches users who point `webViewerPath` at their checkout; there is no image to roll out.

## Changes

- **Colour tokens.** All colours in `style.css` become tokens on `:root`, including the header and
  overlay backgrounds, the text on accent buttons and the five agent badge colours. A light set is
  defined under `@media (prefers-color-scheme: light)`. `--ink-faint` becomes #858c99 (dark).
- **Offline overlay** starts below the header (`inset: 52px 0 0`), so the URL field stays usable.
- **Narrow screens** (≤ 640px): the tagline is hidden and the URL field shrinks to fit. Heights use `dvh`.
- **Keyboard.** One helper makes a clickable row focusable (`tabindex=0`) and opens it on Enter or
  Space. Rows keep their `listitem` role (no `role="button"`), so screen readers still announce the
  lists; search results also contain their own tag buttons. A global `:focus-visible` outline replaces the removed outlines. The URL
  field gets `aria-label="Engine URL"`.
- **Feed row** grid gets a 4th column for the "→ diff" hint. Conflict rows get a "conflict" label.
- **Agent colour** is a hash of the agent name modulo 5.
- **Motion**: the row animation is off under `prefers-reduced-motion: reduce`.
- **Diff**: `diff --git` and `index` lines are dropped; `---`/`+++` stay.
- **Brand**: the `⌘` mark is removed; the wordmark stays.
- **Fallbacks**: every `dvh` height has a `vh` line before it, and every `color-mix()` border a solid one.

## Tasks

- [x] T1 Colour tokens, light theme, `--ink-faint` contrast fix
- [x] T2 Offline overlay below the header
- [x] T3 Header fits at 390px, `dvh` heights
- [x] T4 Keyboard access for feed rows, search results and history commits; focus ring; URL field label
- [x] T5 "→ diff" hint on the same row
- [x] T6 Conflict text label
- [x] T7 Stable agent colour by name
- [x] T8 Reduced motion, diff meta lines, remove `⌘`
- [x] T9 Fallbacks for browsers without `dvh` and `color-mix()` {added}
- [x] T10 Light badge colours, reviewed rows and placeholders at ≥ 4.5:1 (found by the verifier) {added}
