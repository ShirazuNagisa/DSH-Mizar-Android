package me.shirazu.dshmizar.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import me.shirazu.dshmizar.R
import me.shirazu.dshmizar.UiState
import me.shirazu.mizar.kit.AttachmentCapabilities
import me.shirazu.mizar.kit.AttachmentCheckpoint
import me.shirazu.mizar.kit.AttachmentReady
import me.shirazu.mizar.kit.AttachmentScope
import me.shirazu.mizar.kit.AttachmentSource
import me.shirazu.mizar.kit.AttachmentTransport
import me.shirazu.mizar.kit.PairedHost
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The staged attachments, without a socket.
 *
 * Two failures here are invisible until they matter: a row that never leaves
 * "uploading" (the composer stays disabled and nothing says why), and a temp file
 * that outlives its row (the phone slowly fills up and nobody can point at the
 * cause). Both are pinned below, along with the refusals that have to happen
 * *before* a byte is sent.
 */
class AttachmentCoordinatorTest {

    private val temporary = mutableListOf<File>()

    @After
    fun cleanUp() {
        temporary.forEach { it.delete() }
    }

    private class Fixture(unlocked: Boolean = true, connected: Boolean = true) {
        val uploader = FakeTransport()
        val events = mutableListOf<String>()
        val state = MutableStateFlow(
            UiState(
                host = PairedHost(
                    hostId = "h1",
                    hostName = "studio",
                    deviceId = "d1",
                    deviceToken = "t1",
                ),
                selectedSessionId = "s1",
                isLocalNetwork = unlocked,
            ),
        )
        val coordinator = AttachmentCoordinator(
            store = FlowStateStore(state),
            scope = CoroutineScope(Dispatchers.Unconfined),
            // The real work runs on IO; Unconfined makes a call finished by the
            // time it returns, which is what the assertions assume.
            io = Dispatchers.Unconfined,
            text = MarkerText(),
            note = { events += it },
            transport = { if (connected) uploader else null },
        )

        val rows get() = state.value.attachments
    }

    private class MarkerText : Text {
        override fun get(id: Int): String = "res:$id"
        override fun get(id: Int, vararg args: Any): String = "res:$id:" + args.joinToString(",")
    }

    private class FakeTransport : AttachmentTransport {
        var caps = AttachmentCapabilities(
            chunkBytes = 1024,
            imageBytes = 200,
            fileBytes = 1000,
            attachments = 2,
            expiresSeconds = 60,
            imageMediaTypes = listOf("image/png"),
        )
        var capabilityReads = 0
        val uploaded = mutableListOf<AttachmentSource>()
        var failure: Throwable? = null
        var progress = listOf(10L)

        override suspend fun capabilities(): AttachmentCapabilities {
            capabilityReads += 1
            return caps
        }

        override suspend fun upload(
            scope: AttachmentScope,
            source: AttachmentSource,
            limits: AttachmentCapabilities,
            checkpoint: AttachmentCheckpoint,
            onProgress: (Long) -> Unit,
        ): AttachmentReady {
            uploaded += source
            progress.forEach(onProgress)
            failure?.let { throw it }
            return AttachmentReady(
                attachmentId = "att-${uploaded.size}",
                kind = source.kind,
                name = source.name,
                mediaType = source.mediaType,
                byteSize = source.bytes,
            )
        }
    }

    private fun file(bytes: Int, name: String = "note.bin"): File {
        val file = File.createTempFile("mizar-attachment", "-$name")
        file.writeBytes(ByteArray(bytes) { 7 })
        temporary += file
        return file
    }

    @Test
    fun `a locked build refuses the file and deletes it`() {
        val f = Fixture(unlocked = false)
        val file = file(50)

        f.coordinator.add(file, "note.bin", "file", null)

        assertEquals("res:${R.string.attachment_lan_only}", f.state.value.problem)
        assertFalse("the local copy must not survive a refusal", file.exists())
        assertTrue(f.rows.isEmpty())
        assertTrue(f.uploader.uploaded.isEmpty())
    }

    @Test
    fun `an empty file is refused and deleted`() {
        val f = Fixture()
        val file = file(0)

        f.coordinator.add(file, "note.bin", "file", null)

        assertEquals("res:${R.string.attachment_unreadable}", f.state.value.problem)
        assertFalse(file.exists())
        assertTrue(f.rows.isEmpty())
    }

    @Test
    fun `a file over the host limit is refused before a byte is sent`() {
        val f = Fixture()
        val file = file(1200)

        f.coordinator.add(file, "note.bin", "file", null)

        val row = f.rows.single()
        assertEquals("res:${R.string.attachment_too_large}", row.error)
        assertNull(row.readyId)
        assertTrue("nothing may be uploaded", f.uploader.uploaded.isEmpty())
        assertEquals(false, f.state.value.attachmentBusy)
    }

    @Test
    fun `the host's limits are read once and reused`() {
        val f = Fixture()

        f.coordinator.add(file(50, "a.bin"), "a.bin", "file", null)
        f.coordinator.add(file(50, "b.bin"), "b.bin", "file", null)

        assertEquals(1, f.uploader.capabilityReads)
        assertEquals(2, f.rows.size)
    }

    @Test
    fun `more rows than the host allows is refused on the row, not by throwing`() {
        val f = Fixture()

        f.coordinator.add(file(50, "a.bin"), "a.bin", "file", null)
        f.coordinator.add(file(50, "b.bin"), "b.bin", "file", null)
        f.coordinator.add(file(50, "c.bin"), "c.bin", "file", null)

        assertEquals(3, f.rows.size)
        assertEquals("res:${R.string.attachment_too_many}", f.rows.last().error)
        assertEquals(2, f.uploader.uploaded.size)
    }

    @Test
    fun `a staged image reaches ready with its id and digest`() {
        val f = Fixture()
        val file = file(120, "shot.png")

        f.coordinator.add(file, "shot.png", "image", "image/png")

        val row = f.rows.single()
        assertNotNull("the row must be sendable", row.readyId)
        assertEquals(120L, row.progress)
        assertEquals(false, f.state.value.attachmentBusy)
        assertEquals("image/png", row.mediaType)
        val source = f.uploader.uploaded.single()
        assertEquals(64, source.sha256?.length)
        assertEquals("shot.png", source.name)
    }

    @Test
    fun `a document is not given a media type it cannot have`() {
        val f = Fixture()

        f.coordinator.add(file(50), "notes.txt", "file", "text/plain")

        assertEquals("application/octet-stream", f.uploader.uploaded.single().mediaType)
    }

    @Test
    fun `a failed upload leaves the row readable and the composer free`() {
        val f = Fixture()
        f.uploader.failure = IllegalStateException("socket died")

        f.coordinator.add(file(50), "note.bin", "file", null)

        val row = f.rows.single()
        assertEquals("socket died", row.error)
        assertNull(row.readyId)
        assertEquals("a stuck composer is the failure this guards", false, f.state.value.attachmentBusy)
    }

    @Test
    fun `no connection fails the row instead of leaving it pending`() {
        val f = Fixture(connected = false)

        f.coordinator.add(file(50), "note.bin", "file", null)

        assertEquals("not connected", f.rows.single().error)
        assertEquals(false, f.state.value.attachmentBusy)
    }

    @Test
    fun `removing a draft deletes the local copy`() {
        val f = Fixture()
        val file = file(50)
        f.coordinator.add(file, "note.bin", "file", null)

        f.coordinator.remove(f.rows.single().key)

        assertTrue(f.rows.isEmpty())
        assertFalse("the temp file must go with the row", file.exists())
    }

    @Test
    fun `clearing deletes every staged file`() {
        val f = Fixture()
        val first = file(50, "a.bin")
        val second = file(50, "b.bin")
        f.coordinator.add(first, "a.bin", "file", null)
        f.coordinator.add(second, "b.bin", "file", null)

        f.coordinator.clear()

        assertTrue(f.rows.isEmpty())
        assertEquals(false, f.state.value.attachmentBusy)
        assertFalse(first.exists())
        assertFalse(second.exists())
    }
}
