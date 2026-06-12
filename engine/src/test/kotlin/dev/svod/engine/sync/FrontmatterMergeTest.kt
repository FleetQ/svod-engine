package dev.svod.engine.sync

import kotlin.test.Test
import kotlin.test.assertTrue

class FrontmatterMergeTest {

    private fun merged(o: FrontmatterMerge.Outcome): String {
        assertTrue(o is FrontmatterMerge.Outcome.Merged, "expected Merged, got $o")
        return (o as FrontmatterMerge.Outcome.Merged).content
    }

    @Test
    fun `non-overlapping body edits merge cleanly`() {
        val base = "# Note\nalpha\nbeta\ngamma\n"
        val ours = "# Note\nALPHA\nbeta\ngamma\n"
        val theirs = "# Note\nalpha\nbeta\nGAMMA\n"
        val c = merged(FrontmatterMerge.merge(base, ours, theirs))
        assertTrue("ALPHA" in c && "GAMMA" in c && "beta" in c, c)
    }

    @Test
    fun `frontmatter key added on one side is kept`() {
        val base = "---\ntitle: Note\n---\nbody\n"
        val ours = "---\ntitle: Note\nstatus: draft\n---\nbody\n"
        val theirs = "---\ntitle: Note\n---\nbody\n"
        val c = merged(FrontmatterMerge.merge(base, ours, theirs))
        assertTrue("status: draft" in c, c)
        assertTrue("title: Note" in c, c)
    }

    @Test
    fun `tag lists union`() {
        val base = "---\ntags: [a]\n---\nbody\n"
        val ours = "---\ntags: [a, b]\n---\nbody\n"
        val theirs = "---\ntags: [a, c]\n---\nbody\n"
        val c = merged(FrontmatterMerge.merge(base, ours, theirs))
        assertTrue("a" in c && "b" in c && "c" in c, "union of tags: $c")
    }

    @Test
    fun `scalar changed differently on both sides conflicts`() {
        val base = "---\ntitle: Original\n---\nbody\n"
        val ours = "---\ntitle: Mine\n---\nbody\n"
        val theirs = "---\ntitle: Theirs\n---\nbody\n"
        val o = FrontmatterMerge.merge(base, ours, theirs)
        assertTrue(o is FrontmatterMerge.Outcome.Conflict, "expected Conflict, got $o")
        assertTrue((o as FrontmatterMerge.Outcome.Conflict).reasons.any { it.contains("title") })
    }

    @Test
    fun `conflicting body line edits conflict`() {
        val base = "# H\nthe shared line\n"
        val ours = "# H\nMY version of the line\n"
        val theirs = "# H\nTHEIR version of the line\n"
        val o = FrontmatterMerge.merge(base, ours, theirs)
        assertTrue(o is FrontmatterMerge.Outcome.Conflict, "expected Conflict, got $o")
        // both sides preserved for a 3-way merge UI
        val cf = o as FrontmatterMerge.Outcome.Conflict
        assertTrue("MY version" in cf.ours && "THEIR version" in cf.theirs)
    }

    @Test
    fun `cyrillic frontmatter and body merge`() {
        val base = "---\nзаглавие: Заметка\nтеги: [кот]\n---\n# Кот\nпервая строка\nвторая строка\nтретья строка\n"
        val ours = "---\nзаглавие: Заметка\nтеги: [кот, дом]\n---\n# Кот\nпервая строка (громко)\nвторая строка\nтретья строка\n"
        val theirs = "---\nзаглавие: Заметка\nтеги: [кот, сон]\n---\n# Кот\nпервая строка\nвторая строка\nтретья строка (тихо)\n"
        val c = merged(FrontmatterMerge.merge(base, ours, theirs))
        assertTrue("дом" in c && "сон" in c, "Cyrillic tag union: $c")
        assertTrue("громко" in c && "тихо" in c, "Cyrillic body merge: $c")
    }

    @Test
    fun `new file on both sides without base - identical merges, different conflicts`() {
        // base null (file created independently on both)
        val same = FrontmatterMerge.merge(null, "# A\nshared\n", "# A\nshared\n")
        assertTrue(same is FrontmatterMerge.Outcome.Merged)
        val diff = FrontmatterMerge.merge(null, "# A\nmine\n", "# A\ntheirs\n")
        assertTrue(diff is FrontmatterMerge.Outcome.Conflict)
    }
}
