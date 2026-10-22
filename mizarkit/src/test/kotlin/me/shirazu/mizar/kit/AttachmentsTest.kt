package me.shirazu.mizar.kit

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
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
    private val limits = AttachmentCapabilities(1, ATTACHMENT_MAX_BYTES, ATTACHMENT_MAX_BYTES, 5, 65_536, 1800,
        listOf("image/jpeg", "image/png", "image/webp", "image/gif"))

    private fun source(bytes: ByteArray = ByteArray(150_001) { (it % 251).toByte() }): AttachmentSource {
        val file = File(directory, "source-${directory.list()?.size ?: 0}.bin").apply { writeBytes(bytes) }
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        return AttachmentSource(file, "sample.bin", bytes.size.toLong(), sha, "file")
    }

    private inner class Host : AttachmentRpc {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        val received = ByteArrayOutputStream()
        var chunkLost = false
        var finishLost = false
        var corruptOffset = false
        var cancelledChunk = false
        var lastChunk: JsonObject? = null
        var began: JsonObject? = null
        override suspend fun call(method: String, payload: JsonObject): JsonObject {
            calls += method to payload
            return when (method) {
                "attachment.capabilities" -> WIRE_JSON.encodeToJsonElement(limits).jsonObject
                "attachment.begin" -> {
                    began = payload
                    buildJsonObject { put("uploadId", "up-1"); put("offset", 0); put("chunkBytes", 65_536) }
                }
                "attachment.chunk" -> {
                    if (cancelledChunk) { cancelledChunk = false; throw CancellationException("pause") }
                    val start = payload.getValue("offset").jsonPrimitive.long
                    val data = Base64.getDecoder().decode(payload.getValue("data").jsonPrimitive.content)
                    assertTrue(data.size <= 65_536)
                    assertFalse(payload.getValue("data").jsonPrimitive.content.contains('\n'))
                    if (start == received.size().toLong()) {
                        received.write(data)
                        lastChunk = payload
                    } else {
                        assertEquals(lastChunk, payload, "uncertain chunk retry must be byte-identical")
                    }
                    if (chunkLost) { chunkLost = false; throw MizarError("REQUEST_TIMEOUT", "lost acknowledgement", true) }
                    buildJsonObject { put("offset", received.size() + if (corruptOffset) 1 else 0) }
                }
                "attachment.finish" -> {
                    if (finishLost) { finishLost = false; throw MizarError("REQUEST_TIMEOUT", "lost finish", true) }
                    buildJsonObject {
                        put("attachmentId", "ready-1"); put("name", "sample.bin")
                        put("bytes", received.size()); put("kind", "file")
                    }
                }
                "attachment.abort" -> buildJsonObject { put("aborted", true) }
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
        assertContentEquals(file.file.readBytes(), host.received.toByteArray())
        assertEquals(listOf(0L, 65_536L, 131_072L, 150_001L), progress)
        val begin = requireNotNull(host.began)
        assertEquals(setOf("sessionId", "name", "bytes", "sha256", "kind"), begin.keys)
        assertEquals(file.sha256, begin.getValue("sha256").jsonPrimitive.content)
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
        assertEquals(1, host.calls.count { it.first == "attachment.begin" })
        val chunks = host.calls.filter { it.first == "attachment.chunk" }
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
        assertEquals(1, host.calls.count { it.first == "attachment.chunk" })
        assertEquals(3, host.calls.count { it.first == "attachment.finish" })
    }

    @Test fun `invalid acknowledgement does not advance checkpoint`() = runBlocking {
        val host = Host().apply { corruptOffset = true }
        val checkpoint = AttachmentCheckpoint(scope)
        assertFailsWith<IllegalArgumentException> { AttachmentUploader(host).upload(scope, source(), limits, checkpoint) }
        assertEquals(0L, checkpoint.offset)
        assertFalse(host.calls.any { it.first == "attachment.finish" })
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
        assertEquals(ATTACHMENT_MAX_BYTES, limits.copy(maxFileBytes = Long.MAX_VALUE).checked().maxFileBytes)
        assertEquals(5, limits.copy(maxAttachments = 999).checked().maxAttachments)
        assertEquals(65_536, limits.copy(chunkBytes = Int.MAX_VALUE).checked().chunkBytes)
        val host = Host()
        val uploader = AttachmentUploader(host)
        val file = source(byteArrayOf(1, 2))
        assertFailsWith<IllegalArgumentException> { uploader.upload(scope, file, limits.copy(maxFileBytes = 1), AttachmentCheckpoint(scope)) }
        assertFailsWith<IllegalArgumentException> { uploader.upload(scope, file.copy(kind = "image", mediaType = "image/heic"), limits, AttachmentCheckpoint(scope)) }
        assertFailsWith<IllegalArgumentException> { uploader.upload(scope, file.copy(bytes = 0), limits, AttachmentCheckpoint(scope)) }
        assertFailsWith<IllegalArgumentException> { uploader.upload(scope, file.copy(bytes = ATTACHMENT_MAX_BYTES + 1), limits, AttachmentCheckpoint(scope)) }
        assertFailsWith<IllegalArgumentException> { uploader.upload(scope, file.copy(name = "../x"), limits, AttachmentCheckpoint(scope)) }
        assertTrue(host.calls.isEmpty())
    }

    @Test fun `attachment only prompt retries exact frozen payload and text only stays backward compatible`() {
        val attached = AttachmentPromptAttempt(scope, "", listOf("a", "b"))
        assertEquals(attached.payload(scope), attached.payload(scope))
        assertEquals("", attached.payload(scope).getValue("text").jsonPrimitive.content)
        assertTrue("attachments" in attached.payload(scope))
        assertFailsWith<IllegalArgumentException> { attached.payload(scope.copy(sessionId = "elsewhere")) }
        val text = AttachmentPromptAttempt(scope, "hello", emptyList())
        assertFalse("attachments" in text.payload(scope))
        assertNotEquals(text.requestId, AttachmentPromptAttempt(scope, "hello", emptyList()).requestId)
        assertFailsWith<IllegalArgumentException> { AttachmentPromptAttempt(scope, "", emptyList()) }
        assertFailsWith<IllegalArgumentException> { AttachmentPromptAttempt(scope, "x", List(6) { "$it" }) }
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

    @Test fun `expired uploads restart only on explicit retry before admission`() = runBlocking {
        val host = Host()
        var expired = true
        val rpc = AttachmentRpc { method, payload ->
            if (method == "attachment.chunk" && expired) {
                expired = false
                throw MizarError("UPLOAD_EXPIRED", "expired")
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
        assertEquals(2, host.calls.count { it.first == "attachment.begin" })
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
            assertEquals("attachment.capabilities", request.getValue("type").jsonPrimitive.content)
            assertEquals(buildJsonObject {}, request["payload"])
            assertFalse(request.containsKey("token"))
        } finally { connection.disconnect() }
    }
}
