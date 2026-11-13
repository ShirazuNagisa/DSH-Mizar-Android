package me.shirazu.dshmizar.ui.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The inline-picture policy: the gate, the budgets, and the decode target size.
 *
 * Everything here is arithmetic on purpose -- these are the numbers that decide
 * whether a picture crosses the relay and how much memory it may take, and they
 * must be pinned without a device.
 */
class ConversationImagePolicyTest {

    @Test
    fun `a fragment on the path is not part of the file`() {
        val target = ConversationImageTarget(path = "docs/evidence/shot.png#L3", alt = "shot")
        assertEquals("docs/evidence/shot.png", target.lookupPath)
        assertEquals("shot", target.alt)
    }

    @Test
    fun `local paths are local including a windows drive letter`() {
        assertTrue(ConversationImageTarget("shot.png").isLocal)
        assertTrue(ConversationImageTarget("C:\\work\\shot.png").isLocal)
        assertTrue(ConversationImageTarget("file:///work/shot.png").isLocal)
        assertFalse(ConversationImageTarget("https://example.com/shot.png").isLocal)
    }

    @Test
    fun `the gate is the link as much as the account`() {
        assertTrue(InlinePictureAccess.isEntitled(isPaid = true, isLocalNetwork = false))
        assertTrue(InlinePictureAccess.isEntitled(isPaid = false, isLocalNetwork = true))
        assertFalse(InlinePictureAccess.isEntitled(isPaid = false, isLocalNetwork = false))
        assertFalse(InlinePictureAccess.isEntitled(isPaid = true, isLocalNetwork = false, isLocalAgent = true))
    }

    @Test
    fun `extensions and media types are the drawable set`() {
        assertTrue(ConversationImagePolicy.accepts("a/shot.PNG"))
        assertTrue(ConversationImagePolicy.accepts("shot.webp"))
        assertTrue(ConversationImagePolicy.accepts("E:\\shots\\shot.jpeg"))
        assertFalse(ConversationImagePolicy.accepts("report.pdf"))
        assertFalse(ConversationImagePolicy.accepts("Makefile"))
        assertTrue(ConversationImagePolicy.acceptsMediaType("image/png"))
        assertFalse(ConversationImagePolicy.acceptsMediaType("application/pdf"))
    }

    @Test
    fun `the byte ceiling refuses a document-sized file but allows twelve mebibytes`() {
        assertTrue(ConversationImagePolicy.acceptsBytes(0))
        assertTrue(ConversationImagePolicy.acceptsBytes(12L * 1_048_576))
        assertFalse(ConversationImagePolicy.acceptsBytes(12L * 1_048_576 + 1))
        assertFalse(ConversationImagePolicy.acceptsBytes(-1))
    }

    @Test
    fun `the pixel budget is forty megapixels`() {
        assertTrue(ConversationImagePolicy.acceptsPixels(8000, 5000))
        assertFalse(ConversationImagePolicy.acceptsPixels(8001, 5000))
        assertFalse(ConversationImagePolicy.acceptsPixels(0, 100))
        assertNull(ConversationImagePolicy.targetSize(0, 0))
    }

    @Test
    fun `a large picture is decoded down to the long edge, a small one unchanged`() {
        val large = ConversationImagePolicy.targetSize(4000, 3000)!!
        assertEquals(2048, large.width)
        assertEquals(1536, large.height)
        val small = ConversationImagePolicy.targetSize(800, 600)!!
        assertEquals(800, small.width)
        assertEquals(600, small.height)
        // Past the pixel budget nothing is decoded at all.
        assertNull(ConversationImagePolicy.targetSize(9000, 9000))
    }

    @Test
    fun `host error codes map onto the reader's next action`() {
        assertEquals(ConversationImageFailure.MISSING, ConversationImageFailure.fromCode("FILE_UNKNOWN"))
        assertEquals(ConversationImageFailure.FORBIDDEN, ConversationImageFailure.fromCode("PATH_ESCAPES_SANDBOX"))
        assertEquals(ConversationImageFailure.TOO_LARGE, ConversationImageFailure.fromCode("FILE_TOO_LARGE"))
        assertEquals(ConversationImageFailure.OLD_PLUGIN, ConversationImageFailure.fromCode("NOT_IMPLEMENTED"))
        assertEquals(ConversationImageFailure.LOCKED, ConversationImageFailure.fromCode("PREVIEW_LOCKED"))
        assertEquals(ConversationImageFailure.OFFLINE, ConversationImageFailure.fromCode("SOCKET_CLOSED"))
        assertEquals(ConversationImageFailure.UNREADABLE, ConversationImageFailure.fromCode("MALFORMED_FRAME"))
        assertEquals(ConversationImageFailure.UNREADABLE, ConversationImageFailure.fromCode("SOMETHING_NEW"))
    }
}
