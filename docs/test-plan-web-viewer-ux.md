# Test plan: web viewer usability and accessibility fixes

Result: PASS 2026-11-19 (all 13 rows plus the two edge cases, headless Chrome 1280px and 390px, dark and light)

The viewer has no JS test suite. Checks run in headless Chrome (Playwright) against the static files,
with `/ready`, `/api/v1/index/status`, `/api/v1/memory/review`, `/api/v1/file/diff` mocked and a fake
WebSocket that emits five events (write, conflict without commit, watcher commit, promote, delete).
The engine suite (`./gradlew test`, incl. `WebViewerTest`) must stay green.

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
| 6 | Every visible text, dark and light | contrast ≥ 4.5:1 (≥ 3:1 at 18px+) |
| 7 | Same events in reverse order | each agent keeps its badge class |
| 8 | Light colour scheme | body background is light |
| 8b | Stylesheet | has a `prefers-reduced-motion` rule |
| 8c | Opened diff | contains no `diff --git` / `index` line |

Edge cases: an agent name that is empty or undefined (history rows with no agent) must still get a
colour class; a search result's tag chip must still filter, not open the note, by click and by Enter;
Enter on a search result and Space on a history commit open the note and the diff.
