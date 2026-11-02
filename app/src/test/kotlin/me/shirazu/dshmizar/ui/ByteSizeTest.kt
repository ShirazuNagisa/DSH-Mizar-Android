package me.shirazu.dshmizar.ui

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * Byte counts, as the attachment chip and the file preview print them.
 *
 * Characterisation: the numbers and the units are what both call sites produced
 * before they shared one function. The locale is pinned so the decimal separator
 * is part of the assertion rather than an accident of the machine running it.
 */
class ByteSizeTest {

    private lateinit var previous: Locale

    @Before
    fun pinLocale() {
        previous = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(previous)
    }

    @Test
    fun `under a kilobyte is raw bytes`() {
        assertEquals("0 B", Format.bytes(0))
        assertEquals("1 B", Format.bytes(1))
        assertEquals("1023 B", Format.bytes(1023))
    }

    @Test
    fun `kilobytes are whole and truncated, not rounded`() {
        assertEquals("1 KB", Format.bytes(1024))
        assertEquals("1 KB", Format.bytes(1536))
        assertEquals("1023 KB", Format.bytes(1024L * 1024 - 1))
    }

    @Test
    fun `megabytes keep one decimal`() {
        assertEquals("1.0 MB", Format.bytes(1024L * 1024))
        assertEquals("1.5 MB", Format.bytes(1024L * 1024 * 3 / 2))
    }

    /**
     * There is no gigabyte unit, and there never was: a 5 GiB file reads as
     * "5120.0 MB". Pinned so a future "improvement" is a visible decision.
     */
    @Test
    fun `a gigabyte is still counted in megabytes`() {
        assertEquals("5120.0 MB", Format.bytes(5L * 1024 * 1024 * 1024))
    }
}
