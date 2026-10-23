package me.shirazu.dshmizar.ui.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reveal's arithmetic, on the JVM.
 *
 * These are the assertions that used to be eyeballed on a phone: whether a list
 * arrives item by item, whether a table keeps its columns while it is being typed,
 * and whether the finished state is the untouched message. None of it needs a
 * device, and all of it is where a "typewriter looks wrong" regression starts.
 */
class MarkdownRevealTest {

    private val paragraph = MarkdownBlock.Paragraph("Hello there")
    private val heading = MarkdownBlock.Heading(2, "Title")
    private val list = MarkdownBlock.ListBlock(
        listOf(
            MarkdownBlock.ListItem("•", "one", 0),
            MarkdownBlock.ListItem("•", "two", 0),
        ),
    )
    private val table = MarkdownBlock.Table(
        header = listOf("a", "bb"),
        align = listOf(CellAlign.START, CellAlign.END),
        rows = listOf(listOf("c", "dd")),
    )

    @Test
    fun `characters are counted in reading order with no separators`() {
        val blocks = listOf(paragraph, heading, list)

        // "Hello there" + "Title" + "one" + "two"
        assertEquals("Hello thereTitleonetwo", MarkdownReveal.plainText(blocks))
        assertEquals(22, MarkdownReveal.characterCount(blocks))
    }

    @Test
    fun `nothing visible draws nothing`() {
        assertTrue(MarkdownReveal.blocks(listOf(paragraph), 0).isEmpty())
    }

    @Test
    fun `a finished reveal is the message itself`() {
        val blocks = listOf(paragraph, heading)

        // Identity, not a copy: the finished path is the one that runs on every
        // settled message, and rebuilding it would be work for no change.
        assertSame(blocks, MarkdownReveal.blocks(blocks, MarkdownReveal.characterCount(blocks)))
        assertSame(blocks, MarkdownReveal.blocks(blocks, 10_000))
    }

    @Test
    fun `a paragraph is cut where the budget ends`() {
        val revealed = MarkdownReveal.blocks(listOf(paragraph, heading), 5)

        assertEquals(1, revealed.size)
        assertEquals("Hello", (revealed[0] as MarkdownBlock.Paragraph).text)
    }

    @Test
    fun `a list arrives item by item`() {
        val first = MarkdownReveal.blocks(listOf(list), 3)
        assertEquals(1, (first[0] as MarkdownBlock.ListBlock).items.size)
        assertEquals("one", (first[0] as MarkdownBlock.ListBlock).items[0].text)

        val second = MarkdownReveal.blocks(listOf(list), 6)
        assertEquals(2, (second[0] as MarkdownBlock.ListBlock).items.size)
        assertEquals("two", (second[0] as MarkdownBlock.ListBlock).items[1].text)
    }

    @Test
    fun `a table keeps its column count while it is typed`() {
        // Four characters: the whole header ("a" + "bb") and the first cell of the
        // first row.
        val revealed = MarkdownReveal.blocks(listOf(table), 4)
        val shown = revealed[0] as MarkdownBlock.Table

        // Two columns at every point: a table that changed width as it was typed
        // would jump sideways on every row.
        assertEquals(listOf("a", "bb"), shown.header)
        assertEquals(1, shown.rows.size)
        assertEquals(2, shown.rows[0].size)
        assertEquals(listOf("c", ""), shown.rows[0])
        assertEquals(listOf(CellAlign.START, CellAlign.END), shown.align)
    }

    @Test
    fun `a table with only its header typed has no rows yet`() {
        val shown = MarkdownReveal.blocks(listOf(table), 3)[0] as MarkdownBlock.Table

        assertEquals(listOf("a", "bb"), shown.header)
        assertTrue(shown.rows.isEmpty())
    }

    @Test
    fun `a divider costs nothing and appears once reached`() {
        val blocks = listOf(MarkdownBlock.Divider, paragraph)

        assertEquals(11, MarkdownReveal.characterCount(blocks))
        assertEquals(2, MarkdownReveal.blocks(blocks, 1).size)
        assertTrue(MarkdownReveal.blocks(blocks, 1)[0] is MarkdownBlock.Divider)
    }

    @Test
    fun `a code block reveals its text but never its fence`() {
        val code = MarkdownBlock.Code("kotlin", "val x = 1")
        val revealed = MarkdownReveal.blocks(listOf(code), 3)

        assertEquals("val", (revealed[0] as MarkdownBlock.Code).text)
        assertEquals("kotlin", (revealed[0] as MarkdownBlock.Code).language)
    }
}
