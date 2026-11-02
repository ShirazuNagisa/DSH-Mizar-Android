package me.shirazu.dshmizar.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The avatar palette, parsed.
 *
 * The account screen and the sidebar avatar each had their own copy of this; the
 * cases are the ones both copies actually accepted or refused.
 */
class HexColorTest {

    @Test
    fun `six digits are opaque`() {
        assertEquals(0xFFFF0000.toInt(), hexToArgb("#ff0000"))
        assertEquals(0xFFFF0000.toInt(), hexToArgb("ff0000"))
        assertEquals(0xFFFF0000.toInt(), hexToArgb("#FF0000"))
        assertEquals(0xFF1A2B3C.toInt(), hexToArgb("#1a2b3c"))
    }

    @Test
    fun `eight digits carry their own alpha`() {
        assertEquals(0x80FF0000.toInt(), hexToArgb("#80ff0000"))
        assertEquals(0x00000000, hexToArgb("#00000000"))
        assertEquals(0xFFFFFFFF.toInt(), hexToArgb("#FFFFFFFF"))
    }

    @Test
    fun `anything else is refused rather than guessed`() {
        assertNull(hexToArgb(""))
        assertNull(hexToArgb("#"))
        assertNull(hexToArgb("#fff"))
        assertNull(hexToArgb("#ff00000"))
        assertNull(hexToArgb("#gggggg"))
        assertNull(hexToArgb("red"))
    }
}
