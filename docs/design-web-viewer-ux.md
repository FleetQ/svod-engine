# Design: web viewer usability and accessibility fixes

Scope: `examples/web-viewer/` only. No engine, API or contract change.

## Problem

A design review on 2026-11-19 (run against `origin/main` at a990a6e, with a mocked engine) found:

1. When the engine is unreachable, the offline overlay covers the header, so the engine URL field
   cannot be edited. A wrong URL leaves the user stuck: Retry tries the same URL.
2. At 390px width the page is 449px wide and the connection status is cut off.
3. Feed rows, search results and history commits are `<li>` with click handlers: Tab never reaches
   them. The engine URL field has no label. Inputs remove the focus outline.
4. The "→ diff" hint is a 4th grid child in a 3-column grid, so it wraps to its own row and makes
   every row with a diff taller (70-91px vs 64px).
5. A conflict is shown only by a red border, with no text.
6. `--ink-faint` (#646b78) is 3.3-3.6:1 against the backgrounds it sits on; WCAG AA needs 4.5:1.
7. Agent badge colours are assigned in arrival order, so an agent changes colour between reloads.
8. Minor: dark only; the row animation ignores `prefers-reduced-motion`; the diff shows the
   `diff --git` and `index` lines; the `⌘` mark is the macOS Command key glyph.

## Who and why

People running a Svod engine locally with `webViewerPath` set (the viewer is not part of the hosted
team image). They watch agents write and review memories. Keyboard users and anyone on a laptop
with a light system theme are blocked or strained today.

## Out of scope

New features, a redesign of the layout, a build step or dependencies.
