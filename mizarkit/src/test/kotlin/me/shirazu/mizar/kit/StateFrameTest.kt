package me.shirazu.mizar.kit

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The state-channel wire format, byte for byte.
 *
 * The golden frames below are the same bytes `protocol/state-channel-v1.md` documents
 * and `tools/gen-state-frames.mjs` emits into the iOS/Rust fixture
 * (`iOS/protocol/fixtures/state-frames.json`). They are copied rather than read from
 * that repository because this module's tests must run inside this checkout alone; a
 * change to the frame format has to update all three sides' fixtures together, and
 * the Rust/Swift suites catch the side that did not.
 */
class StateFrameTest {

    /** One documented frame: what it is, its bytes, and what those bytes mean. */
    private data class Golden(
        val name: String,
        val hex: String,
        val state: SessionState,
        val interval: Long,
        val textSeq: Long,
        val flags: Int,
        val text: String,
    )

    private val goldenFrames = listOf(
        Golden("heartbeat", "53010100000003000000000000", SessionState.THINKING, 3, 0, 1, ""),
        Golden(
            "begin final stream",
            "5306060000000400000000000fe58588e8afb4e7bb93e8aebaefbc9a",
            SessionState.STREAM_FINAL, 4, 0, 6, "先说结论：",
        ),
        Golden(
            "append to final stream",
            "530602000000040000000f000ce694b9e5a5bde4ba86e38082",
            SessionState.STREAM_FINAL, 4, 15, 2, "改好了。",
        ),
        Golden("finish", "53060800000004000000130000", SessionState.STREAM_FINAL, 4, 19, 8, ""),
        Golden("awaiting user", "53040000000005000000000000", SessionState.AWAITING_USER, 5, 0, 0, ""),
        Golden("terminal completed", "530b0000000006000000000000", SessionState.COMPLETED, 6, 0, 0, ""),
        Golden("editing", "530a0100000007000000000000", SessionState.EDITING, 7, 0, 1, ""),
        Golden(
            "snapshot after reconnect",
            "5305060000000800000000001be9878de696b0e5908ce6ada5e79a84e4b8ade69687e6ada3e69687",
            SessionState.STREAM_NARRATION, 8, 0, 6, "重新同步的中文正文",
        ),
        Golden(
            "multi-byte boundary",
            "5306060000000900000000001af09f8eaf20656d6f6a6920e4b88ee4b8ade69687e6b7b7e68e92",
            SessionState.STREAM_FINAL, 9, 0, 6, "🎯 emoji 与中文混排",
        ),
    )

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    @Test
    fun `every documented frame decodes to its stated meaning`() {
        for (case in goldenFrames) {
            val bytes = case.hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val frame = StateFrame.decode(bytes).getOrElse {
                throw AssertionError("${case.name}: ${it.message}", it)
            }
            assertEquals(case.state, frame.state, case.name)
            assertEquals(case.interval, frame.interval, case.name)
            assertEquals(case.textSeq, frame.textSeq, case.name)
            assertEquals(case.flags, frame.flags.bits, case.name)
            assertEquals(case.text, frame.text, case.name)
            // Encoding the decoded frame reproduces the exact bytes the host sent.
            assertEquals(case.hex, frame.encode().hex(), "${case.name} does not re-encode byte for byte")
        }
    }

    @Test
    fun `a heartbeat is thirteen bytes and carries no text`() {
        val bytes = StateFrame.heartbeat(SessionState.THINKING, 7).encode()
        assertEquals(StateFrame.HEADER_LEN, bytes.size)
        assertEquals(StateFrame.MAGIC.toByte(), bytes[0])
        assertEquals(SessionState.THINKING.code.toByte(), bytes[1])
        assertTrue(StateFrame.decode(bytes).getOrThrow().isHeartbeat)
    }

    @Test
    fun `every factory round-trips`() {
        val frames = listOf(
            StateFrame.begin(SessionState.IDLE, 1),
            StateFrame.heartbeat(SessionState.AWAITING_USER, 2),
            StateFrame.begin(SessionState.STREAM_FINAL, 3, "先说结论："),
            StateFrame.append(SessionState.STREAM_FINAL, 3, 15, "改好了。"),
            StateFrame.snapshot(SessionState.STREAM_NARRATION, 4, text = "从头再说一遍"),
            StateFrame.finish(SessionState.STREAM_FINAL, 3, 20),
            StateFrame.begin(SessionState.FAILED, 5),
        )
        for (frame in frames) {
            assertEquals(frame, StateFrame.decode(frame.encode()).getOrThrow())
        }
    }

    @Test
    fun `a heartbeat cannot smuggle text`() {
        val bytes = StateFrame.heartbeat(SessionState.THINKING, 1).encode()
        bytes[2] = (bytes[2].toInt() or StateFrameFlag.APPEND.mask).toByte()
        val error = StateFrame.decode(bytes).exceptionOrNull()
        assertIs<StateFrameError.ContradictoryFlags>(error)
    }

    @Test
    fun `text on a stateless state is refused`() {
        val frame = StateFrame.append(SessionState.THINKING, 1, 0, "偷偷带字")
        val error = StateFrame.decode(frame.encode()).exceptionOrNull()
        assertIs<StateFrameError.TextNotAllowed>(error)
        assertEquals(SessionState.THINKING.code, error.code)
    }

    @Test
    fun `a short frame is not silently accepted`() {
        val truncated = ByteArray(4) { StateFrame.MAGIC.toByte() }
        assertIs<StateFrameError.Truncated>(StateFrame.decode(truncated).exceptionOrNull())

        val padded = StateFrame.heartbeat(SessionState.IDLE, 1).encode() + byteArrayOf(0)
        assertIs<StateFrameError.LengthMismatch>(StateFrame.decode(padded).exceptionOrNull())
    }

    @Test
    fun `a frame that is not one is named as such`() {
        val json = """{"v":1,"type":"ok"}""".encodeToByteArray()
        assertIs<StateFrameError.NotAStateFrame>(StateFrame.decode(json).exceptionOrNull())
    }

    @Test
    fun `an unknown code is refused rather than guessed`() {
        val bytes = StateFrame.heartbeat(SessionState.IDLE, 1).encode()
        bytes[1] = 200.toByte()
        val error = StateFrame.decode(bytes).exceptionOrNull()
        assertIs<StateFrameError.UnknownState>(error)
        assertEquals(200, error.code)
    }

    @Test
    fun `a payload that is not UTF-8 is refused`() {
        val bytes = ByteArray(StateFrame.HEADER_LEN + 1)
        bytes[0] = StateFrame.MAGIC.toByte()
        bytes[1] = SessionState.STREAM_FINAL.code.toByte()
        bytes[2] = (StateFrameFlag.APPEND.mask or StateFrameFlag.SNAPSHOT.mask).toByte()
        bytes[11] = 0
        bytes[12] = 1
        bytes[StateFrame.HEADER_LEN] = 0xFF.toByte()
        assertIs<StateFrameError.NotUTF8>(StateFrame.decode(bytes).exceptionOrNull())
    }

    @Test
    fun `a payload longer than u16 is clamped at a character boundary and stays decodable`() {
        // A producer that does not split its messages is a bug, but the frame it
        // writes must still be a *readable* frame: the decoder sees the clamp as a
        // lost tail (the textSeq check catches it), not as corruption.
        val text = "界".repeat(40_000) // 3 bytes each: 120 000 bytes.
        val bytes = StateFrame.begin(SessionState.STREAM_FINAL, 1, text).encode()
        assertEquals(StateFrame.HEADER_LEN + 0xFFFF, bytes.size)
        val decoded = StateFrame.decode(bytes).getOrThrow()
        assertEquals(0xFFFF / 3, decoded.text.length)
        assertTrue(decoded.text.all { it == '界' })
    }

    @Test
    fun `the flag set reads like an option set`() {
        val flags = StateFrameFlags.SNAPSHOT or StateFrameFlags.APPEND
        assertTrue(StateFrameFlag.SNAPSHOT in flags)
        assertTrue(StateFrameFlag.APPEND in flags)
        assertTrue(StateFrameFlag.FINAL !in flags)
        assertEquals(6, flags.bits)
        assertEquals(0, StateFrameFlags.NONE.bits)
        assertEquals(listOf(1, 2, 4, 8), StateFrameFlag.entries.map { it.mask })
    }

    @Test
    @DisplayName("the envelope names the session the frame belongs to")
    fun `the envelope names the session`() {
        val frame = StateFrame.begin(SessionState.STREAM_FINAL, 37, "改好了。")
        val message = StateFrameEnvelope.encode("dsh:abc", frame)

        // u8 length, the id, then the 13-byte frame -- exactly what the host writes.
        assertEquals("dsh:abc".encodeToByteArray().size, message[0].toInt())
        assertEquals("dsh:abc", message.copyOfRange(1, 1 + "dsh:abc".length).decodeToString())

        val delivered = StateFrameEnvelope.decode(message).getOrThrow()
        assertEquals("dsh:abc", delivered.sessionId)
        assertEquals(frame, delivered.frame)
    }

    @Test
    fun `a truncated envelope is refused, not crashed on`() {
        assertIs<StateFrameError.Truncated>(StateFrameEnvelope.decode(ByteArray(0)).exceptionOrNull())
        val message = StateFrameEnvelope.encode("s1", StateFrame.heartbeat(SessionState.IDLE, 1))
        assertIs<StateFrameError.Truncated>(
            StateFrameEnvelope.decode(message.copyOfRange(0, message.size - 1)).exceptionOrNull(),
        )
    }

    @Test
    fun `a newer binary dialect is a failure, never an exception`() {
        // The caller (the read loop) logs and ignores this; the point of the test is
        // that asking does not throw, so one unknown frame cannot break a socket.
        val unknown = byteArrayOf(0x99.toByte(), 0x01, 0x02, 0x03)
        assertNull(StateFrameEnvelope.decode(unknown).getOrNull())
    }

    @Test
    fun `a session id that does not fit the prefix is refused before it is sent`() {
        val long = "s".repeat(256)
        assertFailsWith<IllegalArgumentException> {
            StateFrameEnvelope.encode(long, StateFrame.heartbeat(SessionState.IDLE, 1))
        }
    }
}
