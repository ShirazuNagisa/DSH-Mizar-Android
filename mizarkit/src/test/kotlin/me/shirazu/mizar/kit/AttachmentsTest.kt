package me.shirazu.mizar.kit

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AttachmentsTest {
    @TempDir lateinit var directory: File
    private val scope = AttachmentScope("host", "phone", "session")

    // The host's own numbers, as `upload.capabilities` reports them.
    private val limits = AttachmentCapabilities(
        chunkBytes = 65_536,
        imageBytes = ATTACHMENT_MAX_IMAGE_BYTES,
        fileBytes = ATTACHMENT_MAX_FILE_BYTES,
        promptBytes = ATTACHMENT_MAX_PROMPT_BYTES,
        attachments = 5,
        images = 4,
        expiresSeconds = 1800,
        imageMediaTypes = listOf("image/jpeg", "image/png", "image/webp", "image/gif"),
    )

    private fun source(bytes: ByteArray = ByteArray(150_001) { (it % 251).toByte() }): AttachmentSource {
        val file = File(directory, "source-${directory.list()?.size ?: 0}.bin").apply { writeBytes(bytes) }
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        return AttachmentSource(file, "sample.bin", bytes.size.toLong(), sha, "file")
    }

    private inner class Host : AttachmentRpc {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        val received = ByteArrayOutputStream()
        var receivedChunks = 0
        var chunkLost = false
        var finishLost = false
        var corruptReceipt = false
        var cancelledChunk = false
        var lastChunk: JsonObject? = null
        var began: JsonObject? = null
        override suspend fun call(method: String, payload: JsonObject): JsonObject {
            calls += method to payload
            return when (method) {
                "upload.capabilities" -> WIRE_JSON.encodeToJsonElement(limits).jsonObject
                "upload.begin" -> {
                    began = payload
                    buildJsonObject { put("uploadId", "up-1"); put("chunkBytes", 65_536) }
                }
                "upload.chunk" -> {
                    if (cancelledChunk) { cancelledChunk = false; throw CancellationException("pause") }
                    val index = payload.getValue("index").jsonPrimitive.int
                    val data = Base64.getDecoder().decode(payload.getValue("data").jsonPrimitive.content)
                    assertTrue(data.size <= 65_536)
                    assertFalse(payload.getValue("data").jsonPrimitive.content.contains('\n'))
                    if (index == receivedChunks) {
                        received.write(data)
                        receivedChunks += 1
                        lastChunk = payload
                    } else {
                        // A replay must carry the same bytes for the same index.
                        assertEquals(lastChunk, payload, "uncertain chunk retry must be byte-identical")
                    }
                    if (chunkLost) { chunkLost = false; throw MizarError("REQUEST_TIMEOUT", "lost acknowledgement", true) }
                    buildJsonObject { put("received", receivedChunks + if (corruptReceipt) 1 else 0) }
                }
                "upload.finish" -> {
                    if (finishLost) { finishLost = false; throw MizarError("REQUEST_TIMEOUT", "lost finish", true) }
                    buildJsonObject {
                        put("attachmentId", "ready-1"); put("name", "sample.bin")
                        put("kind", "file"); put("mediaType", "application/octet-stream")
                        put("byteSize", received.size())
                    }
                }
                "upload.abort" -> buildJsonObject { put("aborted", true) }
                else -> error("unexpected $method")
            }
        }
    }

    @Test fun `streams bounded canonical base64 chunks and never transmits local paths`() = runBlocking {
        val host = Host()
        val uploader = AttachmentUploader(host)
        val file = source()
        val progress = mutableListOf<Long>()
        val ready = uploader.upload(scope, file, uploader.capabilities(), AttachmentCheckpoint(scope), progress::add)
        assertEquals("ready-1", ready.attachmentId)
        assertEquals(150_001L, ready.byteSize)
        assertContentEquals(file.file.readBytes(), host.received.toByteArray())
        assertEquals(listOf(0L, 65_536L, 131_072L, 150_001L), progress)
        val begin = requireNotNull(host.began)
        assertEquals(setOf("sessionId", "kind", "name", "mediaType", "byteSize", "sha256"), begin.keys)
        assertEquals(file.sha256, begin.getValue("sha256").jsonPrimitive.content)
        assertEquals("file", begin.getValue("kind").jsonPrimitive.content)
        assertFalse(host.calls.any { it.second.toString().contains(file.file.absolutePath) })
    }

    @Test fun `lost chunk reply retries last exact chunk instead of starting another upload`() = runBlocking {
        val host = Host().apply { chunkLost = true }
        val uploader = AttachmentUploader(host)
        val checkpoint = AttachmentCheckpoint(scope)
        val file = source()
        assertFailsWith<MizarError> { uploader.upload(scope, file, limits, checkpoint) }
        assertEquals(0L, checkpoint.offset)
        assertEquals("up-1", checkpoint.uploadId)
        uploader.upload(scope, file, limits, checkpoint)
        assertEquals(1, host.calls.count { it.first == "upload.begin" })
        val chunks = host.calls.filter { it.first == "upload.chunk" }
        // Same index, same bytes: the host compares them rather than trusting them.
        assertEquals(chunks[0].second, chunks[1].second)
        assertContentEquals(file.file.readBytes(), host.received.toByteArray())
    }

    @Test fun `lost finish reply repeats finish and completed retry sends no chunks`() = runBlocking {
        val host = Host().apply { finishLost = true }
        val uploader = AttachmentUploader(host)
        val checkpoint = AttachmentCheckpoint(scope)
        val file = source(byteArrayOf(1, 2, 3))
        assertFailsWith<MizarError> { uploader.upload(scope, file, limits, checkpoint) }
        assertEquals(3L, checkpoint.offset)
        val ready = uploader.upload(scope, file, limits, checkpoint)
        assertEquals(ready, uploader.upload(scope, file, limits, checkpoint))
        assertEquals(1, host.calls.count { it.first == "upload.chunk" })
        assertEquals(3, host.calls.count { it.first == "upload.finish" })
    }

    @Test fun `invalid acknowledgement does not advance checkpoint`() = runBlocking {
        val host = Host().apply { corruptReceipt = true }
        val checkpoint = AttachmentCheckpoint(scope)
        assertFailsWith<IllegalArgumentException> { AttachmentUploader(host).upload(scope, source(), limits, checkpoint) }
        assertEquals(0L, checkpoint.offset)
        assertFalse(host.calls.any { it.first == "upload.finish" })
    }

    @Test fun `cancellation keeps checkpoint for retry and abort sends only opaque ID`() = runBlocking {
        val host = Host().apply { cancelledChunk = true }
        val uploader = AttachmentUploader(host)
        val checkpoint = AttachmentCheckpoint(scope)
        val file = source()
        assertFailsWith<CancellationException> { uploader.upload(scope, file, limits, checkpoint) }
        assertTrue(file.file.exists())
        uploader.upload(scope, file, limits, checkpoint)
        assertContentEquals(file.file.readBytes(), host.received.toByteArray())
        uploader.abort(checkpoint)
        assertEquals(buildJsonObject { put("uploadId", "up-1") }, host.calls.last().second)
    }

    @Test fun `wrong host session and device are rejected before any network call`() = runBlocking {
        val host = Host()
        val uploader = AttachmentUploader(host)
        val file = source()
        val checkpoint = AttachmentCheckpoint(scope)
        for (other in listOf(scope.copy(hostId = "other"), scope.copy(deviceId = "other"), scope.copy(sessionId = "other"))) {
            assertFailsWith<IllegalArgumentException> { uploader.upload(other, file, limits, checkpoint) }
        }
        assertTrue(host.calls.isEmpty())
    }

    @Test fun `advertised limits are bounded locally and lowered limits are enforced`() = runBlocking {
        assertEquals(ATTACHMENT_MAX_FILE_BYTES, limits.copy(fileBytes = Long.MAX_VALUE).checked().fileBytes)
        assertEquals(ATTACHMENT_MAX_IMAGE_BYTES, limits.copy(imageBytes = Long.MAX_VALUE).checked().imageBytes)
        // The host's count is capped by what this client knows how to send.
        assertEquals(ATTACHMENT_MAX_COUNT, limits.copy(attachments = 999).checked().attachments)
        // A chunk size is honoured, not rewritten: it is the host's offset convention.
        assertEquals(1_048_576, limits.copy(chunkBytes = Int.MAX_VALUE).checked().chunkBytes)
        val host = Host()
        val uploader = AttachmentUploader(host)
        val file = source(byteArrayOf(1, 2))
        assertFailsWith<IllegalArgumentException> { uploader.upload(scope, file, limits.copy(fileBytes = 1), AttachmentCheckpoint(scope)) }
        assertFailsWith<IllegalArgumentException> { uploader.upload(scope, file.copy(kind = "image", mediaType = "image/heic"), limits, AttachmentCheckpoint(scope)) }
        assertFailsWith<IllegalArgumentException> { uploader.upload(scope, file.copy(bytes = 0), limits, AttachmentCheckpoint(scope)) }
        assertFailsWith<IllegalArgumentException> { uploader.upload(scope, file.copy(bytes = ATTACHMENT_MAX_FILE_BYTES + 1), limits, AttachmentCheckpoint(scope)) }
        assertFailsWith<IllegalArgumentException> { uploader.upload(scope, file.copy(name = "../x"), limits, AttachmentCheckpoint(scope)) }
        assertFailsWith<IllegalArgumentException> { uploader.upload(scope, file.copy(mediaType = ""), limits, AttachmentCheckpoint(scope)) }
        assertTrue(host.calls.isEmpty())
    }

    @Test fun `an oversized chunk size from the host is refused rather than allocated`() = runBlocking {
        val host = object : AttachmentRpc {
            override suspend fun call(method: String, payload: JsonObject): JsonObject = when (method) {
                "upload.begin" -> buildJsonObject { put("uploadId", "up-1"); put("chunkBytes", 64 * 1024 * 1024) }
                else -> error("unexpected $method")
            }
        }
        val checkpoint = AttachmentCheckpoint(scope)
        assertFailsWith<IllegalArgumentException> {
            AttachmentUploader(host).upload(scope, source(), limits, checkpoint)
        }
        // The id is kept: a refused chunk size must still be abortable.
        assertEquals("up-1", checkpoint.uploadId)
    }

    @Test fun `attachment only prompt retries exact frozen payload and text only stays backward compatible`() {
        val attached = AttachmentPromptAttempt(scope, "", listOf("a", "b"))
        assertEquals(attached.payload(scope), attached.payload(scope))
        assertEquals("", attached.payload(scope).getValue("text").jsonPrimitive.content)
        // The host reads `attachmentIds`; any other key is a message with no files.
        assertTrue("attachmentIds" in attached.payload(scope))
        assertFalse("attachments" in attached.payload(scope))
        assertFailsWith<IllegalArgumentException> { attached.payload(scope.copy(sessionId = "elsewhere")) }
        val text = AttachmentPromptAttempt(scope, "hello", emptyList())
        assertFalse("attachmentIds" in text.payload(scope))
        assertNotEquals(text.requestId, AttachmentPromptAttempt(scope, "hello", emptyList()).requestId)
        assertFailsWith<IllegalArgumentException> { AttachmentPromptAttempt(scope, "", emptyList()) }
        assertFailsWith<IllegalArgumentException> { AttachmentPromptAttempt(scope, "x", List(ATTACHMENT_MAX_COUNT + 1) { "$it" }) }
        assertFailsWith<IllegalArgumentException> { AttachmentPromptAttempt(scope, "x", listOf("a", "a")) }
    }

    @Test fun `display names obey host UTF8 limit preserve suffix and remove path bidi and reserved names`() {
        val longName = attachmentDisplayName("文件".repeat(100) + ".pdf")
        assertTrue(longName.endsWith(".pdf"))
        assertTrue(longName.toByteArray(Charsets.UTF_8).size <= 240)
        assertEquals(longName, attachmentDisplayName(longName))
        assertEquals("_CON.txt", attachmentDisplayName("C:\\folder\\CON.txt"))
        assertEquals("photo_.png", attachmentDisplayName("../photo\u202e.png"))
        assertEquals("attachment", attachmentDisplayName(".."))
        val emoji = attachmentDisplayName("😀".repeat(150) + ".txt")
        assertTrue(emoji.toByteArray(Charsets.UTF_8).size <= 240)
        assertTrue(emoji.endsWith(".txt"))
        assertEquals(emoji, String(emoji.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
    }

    @Test fun `an upload the host no longer knows restarts from a fresh begin`() = runBlocking {
        val host = Host()
        var forgotten = true
        val rpc = AttachmentRpc { method, payload ->
            if (method == "upload.chunk" && forgotten) {
                forgotten = false
                // Absent, expired and someone else's all answer the same way.
                throw MizarError("UPLOAD_UNKNOWN", "upload not found", retryable = false)
            }
            host.call(method, payload)
        }
        val uploader = AttachmentUploader(rpc)
        val checkpoint = AttachmentCheckpoint(scope)
        val file = source()
        assertFailsWith<MizarError> { uploader.upload(scope, file, limits, checkpoint) }
        assertEquals(null, checkpoint.uploadId)
        assertEquals(0L, checkpoint.offset)
        uploader.upload(scope, file, limits, checkpoint)
        assertEquals(2, host.calls.count { it.first == "upload.begin" })
    }

    @Test fun `production adapter uses current authenticated call pattern and propagates unsupported host`() = runBlocking {
        val sent = mutableListOf<JsonObject>()
        val inbox = Channel<String>(Channel.UNLIMITED)
        val transport = object : WebSocketTransport {
            override suspend fun connect(url: String, headers: Map<String, String>) = Unit
            override suspend fun send(text: String) {
                val frame = WIRE_JSON.parseToJsonElement(text).jsonObject
                sent += frame
                if (frame.getValue("type").jsonPrimitive.content == "hello") {
                    inbox.send(welcomeFrame())
                } else {
                    inbox.send(errorFrame("NOT_IMPLEMENTED", id = frame.getValue("id").jsonPrimitive.content))
                }
            }
            override suspend fun receive(): String? = inbox.receiveCatching().getOrNull()
            override suspend fun close() { inbox.close() }
        }
        val connection = MizarConnection(MizarConnection.Configuration(authority = "localhost:1234", token = "private")) { transport }
        try {
            connection.connect()
            val error = assertFailsWith<MizarError> { AttachmentUploader(connection.attachmentRpc()).capabilities() }
            assertEquals("NOT_IMPLEMENTED", error.code)
            val request = sent.last()
            assertEquals(JsonPrimitive(1), request["v"])
            assertEquals("upload.capabilities", request.getValue("type").jsonPrimitive.content)
            assertEquals(buildJsonObject {}, request["payload"])
            assertFalse(request.containsKey("token"))
        } finally { connection.disconnect() }
    }
}
