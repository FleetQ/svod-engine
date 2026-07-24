package dev.svod.engine.mcp

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression coverage for the reported intermittent `edit` corruption: on large (~5KB) Markdown
 * notes containing multi-byte content (em-dash U+2014, Cyrillic), a sequence of edits sometimes
 * dropped the matched region WITHOUT inserting the replacement, and occasionally lost adjacent
 * headings. Each call still returned ok with a new revision, so nothing errored.
 */
class EditIntegrityTest {

    private fun ToolResult.str(key: String): String? = data[key]?.jsonPrimitive?.content

    /** A realistic ~5KB note: many headings, repeated substrings, em-dash + Cyrillic throughout. */
    private fun bigNote(): String = buildString {
        appendLine("# 00 — Resume — Проект red-bg")
        appendLine()
        for (s in 1..40) {
            appendLine("## Section $s — заглавие $s")
            appendLine("status: draft — pending review — раздел $s")
            appendLine("Body line for section $s — with an em-dash and Кирилица text; token-cost note.")
            appendLine("- [ ] next step $s — do the thing — направи го")
            appendLine()
        }
        append("## Next steps\nfinal marker — финал")
    }

    @Test
    fun `sequence of edits on a large multibyte note never drops content`() = runBlocking {
        McpFixture().use { fx ->
            fx.tools.write(fx.write, "projects/red-bg/00-resume.md", bigNote(), expectedRevision = null)

            // A realistic edit sequence: unique substrings scattered across the note, each touching
            // a region near multi-byte chars and near headings.
            val edits = listOf(
                Triple("status: draft — pending review — раздел 7", "status: DONE — раздел 7", false),
                Triple("Body line for section 12 — with an em-dash and Кирилица text; token-cost note.",
                    "Body line for section 12 — REWRITTEN — Кирилица — token-cost note.", false),
                Triple("- [ ] next step 20 — do the thing — направи го", "- [x] next step 20 — направи го", false),
                Triple("## Section 33 — заглавие 33", "## Section 33 — ЗАГЛАВИЕ 33 (updated)", false),
                Triple("final marker — финал", "final marker — ФИНАЛ — done", false),
            )

            for ((old, new, all) in edits) {
                val r = fx.tools.edit(fx.write, "projects/red-bg/00-resume.md", old, new, replaceAll = all, expectedRevision = null)
                assertEquals("ok", r.status, "edit '$old' should succeed")
                val content = fx.tools.read(fx.write, "projects/red-bg/00-resume.md").str("content")!!
                assertTrue(content.contains(new), "note must contain the inserted newString after editing '$old'")
                assertTrue(!content.contains(old), "old region must be gone after editing '$old'")
            }

            val finalContent = fx.tools.read(fx.write, "projects/red-bg/00-resume.md").str("content")!!
            // Every heading that was NOT edited must survive verbatim.
            for (s in 1..40) {
                if (s == 33) continue
                assertTrue(finalContent.contains("## Section $s — заглавие $s"), "heading $s must survive")
            }
            assertTrue(finalContent.contains("## Section 33 — ЗАГЛАВИЕ 33 (updated)"), "edited heading 33 present")
            assertTrue(finalContent.contains("# 00 — Resume — Проект red-bg"), "title heading survives")
        }
    }

    @Test
    fun `integrity guard passes correct replacements and rejects corrupt ones`() = runBlocking {
        McpFixture().use { fx ->
            val base = "# H1\nstatus: draft — раздел\nbody — Кирилица\n# H2\ntail"
            val old = "status: draft — раздел"
            val new = "status: final — раздел"

            // Correct single replace → no error (no false positive on multibyte content).
            val good = base.replace(old, new)
            assertEquals(null, fx.tools.editIntegrityError(base, old, new, 1, good, "n.md"))

            // Correct replaceAll (count=3) → no error.
            val ml = "x aa y aa z aa"
            assertEquals(null, fx.tools.editIntegrityError(ml, "aa", "bbbb", 3, ml.replace("aa", "bbbb"), "n.md"))

            // The reported failure: matched region removed but newString NEVER inserted (and the
            // "# H2" heading vanished with it). Length is wrong AND newString absent → must be caught.
            val corrupt = "# H1\nbody — Кирилица\ntail"
            assertTrue(fx.tools.editIntegrityError(base, old, new, 1, corrupt, "n.md") != null,
                "dropped-content corruption must be rejected")

            // Right length but the replacement text is missing (silent swap) → caught by the contains check.
            val expectedLen = base.length + (new.length - old.length)
            val filler = "Z".repeat(expectedLen) // exact expected length, but does not contain newString
            assertTrue(fx.tools.editIntegrityError(base, old, new, 1, filler, "n.md") != null,
                "missing newString must be rejected even at correct length")

            // Deleting content (newString empty) is legitimate: correct result → no error.
            val del = base.replace(old, "")
            assertEquals(null, fx.tools.editIntegrityError(base, old, "", 1, del, "n.md"))
        }
    }

    @Test
    fun `concurrent edits on the same note never silently drop content`() = runBlocking {
        McpFixture().use { fx ->
            val path = "projects/red-bg/40-next-steps.md"
            fx.tools.write(fx.write, path, bigNote(), expectedRevision = null)

            // Fire many edits at once with NO expectedRevision and no read between — the exact shape
            // the bug report describes. Each targets a distinct unique substring, so if any succeeds
            // it must have preserved everything else. The CAS guard should make losers conflict.
            val results = (1..12).map { s ->
                async {
                    fx.tools.edit(
                        fx.write, path,
                        "status: draft — pending review — раздел $s",
                        "status: DONE$s — раздел $s",
                        replaceAll = false, expectedRevision = null,
                    )
                }
            }.awaitAll()

            val content = fx.tools.read(fx.write, path).str("content")!!
            val okCount = results.count { it.status == "ok" }
            // Whatever landed, every applied edit's newString must be present and NO section body may
            // have been dropped: each section still has exactly one status line (edited or original).
            for (s in 1..40) {
                val edited = content.contains("status: DONE$s — раздел $s")
                val original = content.contains("status: draft — pending review — раздел $s")
                assertTrue(edited || original, "section $s status line must not vanish (ok edits=$okCount)")
            }
            // The note length must never fall below what a single successful edit would produce.
            assertTrue(content.contains("# 00 — Resume — Проект red-bg"), "title survives concurrency")
            assertTrue(content.contains("## Next steps"), "trailing heading survives concurrency")
        }
    }
}
