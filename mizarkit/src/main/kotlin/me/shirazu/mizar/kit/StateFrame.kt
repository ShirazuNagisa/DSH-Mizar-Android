package me.shirazu.mizar.kit

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * The state channel: one small binary frame carrying a session state.
 *
 * A JSON object for "the agent is thinking" is 40+ bytes; the phone redraws it
 * every five seconds; over a metered relay that is real money. This frame is 13
 * bytes plus whatever text the state carries.
 *
 * ```
 * 0   u8   0x53 'S'  frame type. JSON frames always start with '{', so they never collide
 * 1   u8   state     SessionState code
 * 2   u8   flags     see [StateFrameFlag]
 * 3   u32  interval  monotonic; a new interval means "this state starts now" (big endian)
 * 7   u32  textSeq   byte offset of the first byte of text in this interval
 * 11  u16  length    payload bytes
 * 13  ...  payload   UTF-8 body text, when the frame carries any
 * ```
 *
 * Byte-for-byte the same frame as `AGI Mizar/crates/agi-protocol/src/state_frame.rs`
 * and `iOS/MizarKit/Sources/MizarKit/SessionStateFrameCodec.swift`; all three decode
 * the same golden frames, so a change to one without the others fails a test rather
 * than a device.
 *
 * ## Why an interval id and not a timestamp
 *
 * The phone needs one cheap question answered: *is this frame newer than what I am
 * showing, or a duplicate heartbeat?* An interval id answers it with an integer
 * compare and no clock assumptions. Timestamps would additionally need the host's
 * clock to be sane, which it is not guaranteed to be.
 *
 * ## Why heartbeats
 *
 * An interval state is a promise: "this is still true". If the host dies, the phone
 * must stop believing it. A heartbeat every five seconds is that promise being
 * renewed; [StateChannel.isStale] is when the phone stops believing it. Terminal
 * states do not heartbeat -- they are the end of the story.
 */

/** One bit of a state frame's flag byte, mirroring `state_frame.rs::Flags`. */
enum class StateFrameFlag(val mask: Int) {
    /** A renewal of the current interval. Carries no text. */
    HEARTBEAT(0b0000_0001),

    /** Text is appended at `textSeq`. */
    APPEND(0b0000_0010),

    /** Text replaces the buffer (the first frame of an interval, and after a reconnect). */
    SNAPSHOT(0b0000_0100),

    /** This is the last chunk of the stream. */
    FINAL(0b0000_1000),
}

/**
 * The flag byte, as an option set.
 *
 * A value class over the byte rather than a `Set`, so a frame that arrives every
 * five seconds does not allocate a set to say "nothing changed". `contains` reads
 * like the Swift `OptionSet` and the Rust `Flags::has` it mirrors.
 */
@JvmInline
value class StateFrameFlags(val bits: Int) {

    /** Whether every bit of [flag] is set. */
    operator fun contains(flag: StateFrameFlag): Boolean = bits and flag.mask != 0

    /** The union of two flag sets. */
    infix fun or(other: StateFrameFlags): StateFrameFlags = StateFrameFlags(bits or other.bits)

    companion object {
        /** No flags: a bare "this state starts now". */
        val NONE = StateFrameFlags(0)

        /** Liveness only, no text. */
        val HEARTBEAT = StateFrameFlags(StateFrameFlag.HEARTBEAT.mask)

        /** Text follows, appended at `textSeq`. */
        val APPEND = StateFrameFlags(StateFrameFlag.APPEND.mask)

        /** Text replaces the interval's buffer. */
        val SNAPSHOT = StateFrameFlags(StateFrameFlag.SNAPSHOT.mask)

        /** The stream ends here. */
        val FINAL = StateFrameFlags(StateFrameFlag.FINAL.mask)

        /** The flag byte as received, including bits this build does not know. */
        fun of(bits: Int): StateFrameFlags = StateFrameFlags(bits and 0xFF)

        /** The union of [flags]. */
        fun of(vararg flags: StateFrameFlag): StateFrameFlags {
            var bits = 0
            for (flag in flags) bits = bits or flag.mask
            return StateFrameFlags(bits)
        }
    }
}

/**
 * Why a byte buffer is not a state frame.
 *
 * The same seven cases as Rust's `FrameError` and Swift's `StateFrameError`, because
 * every side rejects the same bytes for the same stated reason.
 */
sealed class StateFrameError(message: String) : Exception(message) {

    /** Shorter than the header. */
    class Truncated : StateFrameError("frame shorter than its header")

    /** Does not start with [StateFrame.MAGIC]. */
    class NotAStateFrame : StateFrameError("not a state frame")

    /** The declared payload length and the actual length disagree. */
    class LengthMismatch : StateFrameError("declared payload length is wrong")

    /** A heartbeat carrying text, or text on a frame with no text flag. */
    class ContradictoryFlags : StateFrameError("flags contradict the payload")

    /** The state code is not one this build knows. */
    data class UnknownState(val code: Int) : StateFrameError("unknown state code $code")

    /** Body text on a state that cannot carry any. */
    data class TextNotAllowed(val code: Int) : StateFrameError("state $code carries no text")

    /** The payload is not valid UTF-8. */
    class NotUTF8 : StateFrameError("text payload is not UTF-8")
}

/**
 * One frame of the state channel.
 *
 * @property state what the session is doing.
 * @property interval monotonic per session; a new value starts a new interval.
 * @property textSeq byte offset in the interval's text. Meaningless without text.
 * @property flags the frame's intent, see [StateFrameFlag].
 * @property text body text, empty for a heartbeat.
 */
data class StateFrame(
    val state: SessionState,
    val interval: Long,
    val textSeq: Long = 0,
    val flags: StateFrameFlags = StateFrameFlags.NONE,
    val text: String = "",
) {

    /** Whether this frame renews rather than announces. */
    val isHeartbeat: Boolean get() = StateFrameFlag.HEARTBEAT in flags

    /** Whether this frame expects a heartbeat to follow. */
    val isTerminal: Boolean get() = state.kind == SessionState.Kind.TERMINAL

    /**
     * Encode to bytes: the message body the socket carries.
     *
     * The text length is a u16 on the wire. A producer that does not split long
     * messages would otherwise write a length that does not match the body, and
     * every decoder rejects the frame as [StateFrameError.LengthMismatch]. Clamping
     * at a character boundary here keeps the frame decodable; the missing tail is a
     * bug in the producer, not a corrupt frame, and the receiver's `textSeq` check
     * reports it as a gap.
     */
    fun encode(): ByteArray {
        var payload = text.encodeToByteArray()
        if (payload.size > MAX_PAYLOAD_BYTES) {
            var take = MAX_PAYLOAD_BYTES
            while (take > 0 && payload[take].toInt() and 0xC0 == 0x80) take--
            payload = payload.copyOfRange(0, take)
        }
        val out = ByteArray(HEADER_LEN + payload.size)
        out[0] = MAGIC.toByte()
        out[1] = state.code.toByte()
        out[2] = flags.bits.toByte()
        writeU32(out, 3, interval)
        writeU32(out, 7, textSeq)
        out[11] = ((payload.size ushr 8) and 0xFF).toByte()
        out[12] = (payload.size and 0xFF).toByte()
        payload.copyInto(out, HEADER_LEN)
        return out
    }

    companion object {
        /** First byte of every state frame. JSON frames start with `{`. */
        const val MAGIC: Int = 0x53

        /** Fixed header length. */
        const val HEADER_LEN: Int = 13

        /** How often the host renews an interval state. */
        const val HEARTBEAT_MILLIS: Long = 5_000

        /** When a phone should consider an interval dead: slightly over two missed beats. */
        const val STALE_AFTER_MILLIS: Long = 12_000

        private const val MAX_PAYLOAD_BYTES = 0xFFFF

        /** Start a new interval. The first frame is a snapshot even when empty. */
        fun begin(state: SessionState, interval: Long, text: String = ""): StateFrame {
            val carries = state.carriesText
            return StateFrame(
                state = state,
                interval = interval,
                flags = if (carries) {
                    StateFrameFlags.SNAPSHOT or StateFrameFlags.APPEND
                } else {
                    StateFrameFlags.NONE
                },
                text = if (carries) text else "",
            )
        }

        /** Renew an interval. No text, by construction. */
        fun heartbeat(state: SessionState, interval: Long): StateFrame = StateFrame(
            state = state,
            interval = interval,
            flags = StateFrameFlags.HEARTBEAT,
        )

        /** Append streamed text at [textSeq]. */
        fun append(state: SessionState, interval: Long, textSeq: Long, chunk: String): StateFrame =
            StateFrame(
                state = state,
                interval = interval,
                textSeq = textSeq,
                flags = StateFrameFlags.APPEND,
                text = chunk,
            )

        /** Replace the buffer with everything so far: sent first, and after a reconnect. */
        fun snapshot(
            state: SessionState,
            interval: Long,
            textSeq: Long = 0,
            text: String,
        ): StateFrame = StateFrame(
            state = state,
            interval = interval,
            textSeq = textSeq,
            flags = StateFrameFlags.SNAPSHOT or StateFrameFlags.APPEND,
            text = text,
        )

        /** Mark the stream as finished. An empty chunk is legal: "that is all". */
        fun finish(state: SessionState, interval: Long, textSeq: Long): StateFrame = StateFrame(
            state = state,
            interval = interval,
            textSeq = textSeq,
            flags = StateFrameFlags.FINAL,
        )

        /**
         * Decode bytes, rejecting anything this build cannot honour.
         *
         * A `Result` rather than an exception because the caller is a read loop: the
         * frame is additive, and one unreadable binary message must be skipped and
         * logged, never allowed to break the connection.
         */
        fun decode(bytes: ByteArray): Result<StateFrame> {
            if (bytes.size < HEADER_LEN) return Result.failure(StateFrameError.Truncated())
            if (bytes[0].toInt() and 0xFF != MAGIC) {
                return Result.failure(StateFrameError.NotAStateFrame())
            }

            val code = bytes[1].toInt() and 0xFF
            val state = SessionState.fromCodeOrNull(code)
                ?: return Result.failure(StateFrameError.UnknownState(code))
            val flags = StateFrameFlags.of(bytes[2].toInt() and 0xFF)
            val interval = readU32(bytes, 3)
            val textSeq = readU32(bytes, 7)
            val length = ((bytes[11].toInt() and 0xFF) shl 8) or (bytes[12].toInt() and 0xFF)
            if (bytes.size != HEADER_LEN + length) {
                return Result.failure(StateFrameError.LengthMismatch())
            }

            val hasText = StateFrameFlag.APPEND in flags
            // A heartbeat is a promise that nothing changed. Checked first so the
            // error names the real fault instead of a downstream symptom.
            if (StateFrameFlag.HEARTBEAT in flags && (hasText || length != 0)) {
                return Result.failure(StateFrameError.ContradictoryFlags())
            }
            if (!hasText && length != 0) {
                return Result.failure(StateFrameError.ContradictoryFlags())
            }
            if (hasText && !state.carriesText) {
                return Result.failure(StateFrameError.TextNotAllowed(code))
            }

            val text = try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, HEADER_LEN, length))
                    .toString()
            } catch (_: CharacterCodingException) {
                return Result.failure(StateFrameError.NotUTF8())
            }

            return Result.success(
                StateFrame(state = state, interval = interval, textSeq = textSeq, flags = flags, text = text),
            )
        }

        private fun writeU32(out: ByteArray, offset: Int, value: Long) {
            val bits = value and 0xFFFF_FFFFL
            out[offset] = ((bits ushr 24) and 0xFF).toByte()
            out[offset + 1] = ((bits ushr 16) and 0xFF).toByte()
            out[offset + 2] = ((bits ushr 8) and 0xFF).toByte()
            out[offset + 3] = (bits and 0xFF).toByte()
        }

        private fun readU32(bytes: ByteArray, offset: Int): Long =
            ((bytes[offset].toLong() and 0xFF) shl 24) or
                ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
                ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
                (bytes[offset + 3].toLong() and 0xFF)
    }
}

/**
 * One binary socket message: the session id, then the state frame.
 *
 * The socket carries every session's states and the frame itself does not say which
 * one it belongs to, so the transport prefixes it: `u8` session-id length, the id's
 * UTF-8 bytes, then the 13-byte frame. This is the envelope
 * `AGI Mizar/crates/agi-mizar/src/server.rs` assembles for every connected phone
 * (`serve_peer`, "one byte per session-state change"), and the one
 * `iOS/MizarKit/Sources/MizarKit/MizarConnection.swift` parses in
 * `receivedStateFrame`.
 *
 * @property sessionId the session the frame describes.
 * @property frame the decoded frame.
 */
data class DeliveredStateFrame(val sessionId: String, val frame: StateFrame)

/** Encode and decode the session-id prefix around a [StateFrame]. */
object StateFrameEnvelope {

    /** The largest session id the one-byte prefix can name. */
    const val MAX_SESSION_ID_BYTES: Int = 255

    /** Wrap one frame for [sessionId]. */
    fun encode(sessionId: String, frame: StateFrame): ByteArray {
        val id = sessionId.encodeToByteArray()
        require(id.size <= MAX_SESSION_ID_BYTES) {
            "a session id of ${id.size} bytes does not fit the one-byte prefix"
        }
        val body = frame.encode()
        val out = ByteArray(1 + id.size + body.size)
        out[0] = id.size.toByte()
        id.copyInto(out, 1)
        body.copyInto(out, 1 + id.size)
        return out
    }

    /**
     * Unwrap one binary message.
     *
     * A message that is not a state frame is a failure, not an exception: the
     * channel is additive, and a newer host may grow a second binary dialect. The
     * caller logs and ignores it; the socket stays up.
     */
    fun decode(bytes: ByteArray): Result<DeliveredStateFrame> {
        if (bytes.isEmpty()) return Result.failure(StateFrameError.Truncated())
        val idLength = bytes[0].toInt() and 0xFF
        val start = 1 + idLength
        if (bytes.size < start + StateFrame.HEADER_LEN) {
            return Result.failure(StateFrameError.Truncated())
        }
        val sessionId = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, 1, idLength))
                .toString()
        } catch (_: CharacterCodingException) {
            return Result.failure(StateFrameError.NotUTF8())
        }
        val frame = StateFrame.decode(bytes.copyOfRange(start, bytes.size)).getOrElse {
            return Result.failure(it)
        }
        return Result.success(DeliveredStateFrame(sessionId, frame))
    }
}

/** What a display should do after receiving a frame. */
enum class StateApplied {
    /** A new interval: replace everything. */
    BEGAN,

    /** Same state, just a liveness renewal. Redraw nothing. */
    RENEWED,

    /** Text changed. */
    UPDATED,

    /** The stream ended. */
    FINISHED,

    /** An older frame arrived after a newer one. Dropped. */
    STALE,
}

/**
 * The phone's view of one session's state channel.
 *
 * Kept in the kit rather than in the app so both ends apply the same rules, and so
 * "is this frame newer?" has exactly one answer. The rules are the state-channel
 * spec's (`protocol/state-channel-v1.md` §3-§5) and match
 * `agi_protocol::state_frame::StateChannel` line for line:
 *
 *  - an interval smaller than the current one is a late frame ([StateApplied.STALE]);
 *  - a larger one begins a new interval, replacing state and text;
 *  - an append must land exactly where the interval's text ends, otherwise a frame
 *    was lost and splicing would draw a sentence that was never said -- the
 *    receiver waits for the next snapshot ([StateApplied.STALE]);
 *  - a snapshot replaces the buffer;
 *  - a terminal state never goes stale: "failed" stays true after the host
 *    disconnects, because the failure already happened.
 */
class StateChannel {

    /** The interval currently displayed, `null` before the first frame. */
    var interval: Long? = null
        private set

    /** The state being shown. */
    var state: SessionState? = null
        private set

    /** The body text accumulated in this interval. */
    var text: String = ""
        private set

    /** Whether the stream in this interval has been marked final. */
    var finished: Boolean = false
        private set

    /** When the last frame of the current interval arrived (epoch ms). */
    private var lastAtMillis: Long = 0

    /**
     * Feed a frame.
     *
     * @param frame the frame as received.
     * @param nowMillis the receiver's clock, used only for staleness.
     * @returns what the display should do.
     */
    fun apply(frame: StateFrame, nowMillis: Long): StateApplied {
        val current = interval
        if (current == null) return begin(frame, nowMillis)
        if (frame.interval < current) return StateApplied.STALE
        if (frame.interval > current) return begin(frame, nowMillis)

        // Same interval.
        lastAtMillis = nowMillis
        if (frame.isHeartbeat) return StateApplied.RENEWED
        if (StateFrameFlag.SNAPSHOT in frame.flags) {
            text = frame.text
        } else if (StateFrameFlag.APPEND in frame.flags) {
            if (frame.textSeq != text.encodeToByteArray().size.toLong()) return StateApplied.STALE
            text += frame.text
        }
        if (StateFrameFlag.FINAL in frame.flags) {
            finished = true
            return StateApplied.FINISHED
        }
        return if (StateFrameFlag.APPEND in frame.flags || StateFrameFlag.SNAPSHOT in frame.flags) {
            StateApplied.UPDATED
        } else {
            StateApplied.RENEWED
        }
    }

    /** Whether the host has gone quiet on an interval that promised heartbeats. */
    fun isStale(nowMillis: Long): Boolean {
        val shown = state ?: return false
        if (!shown.needsHeartbeat) return false
        return nowMillis - lastAtMillis > StateFrame.STALE_AFTER_MILLIS
    }

    private fun begin(frame: StateFrame, nowMillis: Long): StateApplied {
        interval = frame.interval
        state = frame.state
        val carriesText = StateFrameFlag.SNAPSHOT in frame.flags || StateFrameFlag.APPEND in frame.flags
        text = if (carriesText) frame.text else ""
        finished = StateFrameFlag.FINAL in frame.flags
        lastAtMillis = nowMillis
        return StateApplied.BEGAN
    }
}
