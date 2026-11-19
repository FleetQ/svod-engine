# Test plan: web viewer usability and accessibility fixes

Result: PASS 2026-11-19 (all 13 rows plus the edge cases, headless Chrome 1280px and 390px, dark and light).
A first PASS on the same day was wrong for row 6: the scan skipped translucent backgrounds, opacity and
placeholders. The verifier found light badges `.c2`/`.c4` at 4.34/4.47:1 on selected rows, reviewed rows
at 3.0-4.0:1 and placeholders at 3.6-3.7:1. Fixed and re-scanned; the agent badge colours are also checked
by calculation over all three panel colours (lowest 5.16:1), since the test events do not hit every colour.

The viewer has no JS test suite. Checks run in headless Chrome (Playwright) against the static files,
with `/ready`, `/api/v1/index/status`, `/api/v1/memory/review` (GET and POST), `/api/v1/file/diff`, `/api/v1/search`,
`/api/v1/file`, `/api/v1/file/history` mocked and a fake
WebSocket that emits five events (write, conflict without commit, watcher commit, promote, delete).
`WebViewerTest` and `WebViewerSearchTest` load these real files, so the engine suite must stay green.

| # | Check | Pass when |
|---|---|---|
| 1 | Offline, element at the centre of the URL field | is the URL field |
| 2 | 390px viewport | `scrollWidth` ≤ 390, no header element past 390px |
| 3 | Tab from page start | reaches a feed row with a diff within 25 presses |
| 3b | Enter on that row | diff pane shows the change |
| 3c | Focused row | has a visible outline |
| 3d | URL field | has a label or `aria-label` |
| 4 | Feed row heights | a row with a diff is as tall as a one-line row without |
| 5 | Conflict row text | contains "conflict" |
| 6 | Every visible text and placeholder, dark and light, as rendered and with every row selected, incl. a reviewed (done) review row; translucent backgrounds composited and opacity applied | contrast ≥ 4.5:1 (≥ 3:1 at 18px+) |
| 7 | Same events in reverse order | each agent keeps its badge class |
| 8 | Light colour scheme | body background is light |
| 8b | Stylesheet | has a `prefers-reduced-motion` rule |
| 8c | Opened diff | contains no `diff --git` / `index` line |

Edge cases: an agent name that is empty or undefined (history rows with no agent) must still get a
colour class; a search result's tag chip must still filter, not open the note, by click and by Enter;
Enter on a search result and Space on a history commit open the note and the diff.
