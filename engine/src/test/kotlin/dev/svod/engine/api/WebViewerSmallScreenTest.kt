package dev.svod.engine.api

import dev.svod.engine.index.NoneEmbedder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The reference viewer in its one-column layout (style.css stacks the panes at max-width 1100px).
 * Picking a feed row, a history row or a search result there must bring the note or diff heading
 * into view and move focus to it (WCAG 2.4.3). There is no JS test runner by design (ADR-0008), so
 * this checks the served markup and script.
 */
class WebViewerSmallScreenTest {

    private fun viewerDir(): Path {
        val projectDir = System.getProperty("svod.projectDir")
        assertNotNull(projectDir, "the svod.projectDir system property must be set by engine/build.gradle.kts")
        val dir = Paths.get(projectDir, "..", "examples", "web-viewer").normalize()
        assertTrue(Files.isDirectory(dir), "the reference viewer folder must exist at $dir")
        return dir
    }

    private fun serve(): ApiFixture =
        ApiFixture(NoneEmbedder, apiConfig = AppApiServer.Config(webViewerPath = viewerDir().toString()))

    private fun page(path: String): String = serve().use { fx ->
        val r = fx.get(path)
        assertEquals(200, r.statusCode())
        r.body()
    }

    @Test
    fun `the note and diff headings can take focus`() {
        val html = page("/")
        assertTrue(html.contains("<h2 id=\"noteHeading\" tabindex=\"-1\">Note</h2>"), "the note heading is focusable by script")
        assertTrue(html.contains("<h2 id=\"diffHeading\" tabindex=\"-1\">git diff</h2>"), "the diff heading is focusable by script")
    }

    @Test
    fun `the diff placeholder does not point left`() {
        val html = page("/")
        assertFalse(html.contains("on the left"), "the placeholder must hold in the one-column layout too")
        val diff = html.indexOf("id=\"diff\"")
        assertTrue(diff >= 0, "the diff pane is present")
        val end = html.indexOf("</pre>", diff)
        assertTrue(end > diff && html.substring(diff, end).contains("class=\"empty\""), "the diff pane keeps its placeholder")
    }

    @Test
    fun `the script brings the picked pane into view only in the one-column layout`() {
        val js = page("/app.js")
        for (needle in listOf(
            "matchMedia(\"(max-width: 1100px)\")", "scrollIntoView", "focus({ preventScroll: true })",
            "\"noteHeading\"", "\"diffHeading\"",
        )) assertTrue(js.contains(needle), "app.js must contain '$needle'")
    }
}
