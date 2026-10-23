package me.shirazu.mizar.kit

/**
 * A bounded record of what the protocol actually did.
 *
 * The two failures that are hardest to diagnose from a phone are "the frame never
 * left" and "it left and was refused": the screen shows the same thing either way,
 * and the app's own notes only say what it decided, not what crossed the wire. This
 * is the other half of that record — one line per request, per answer, per event.
 *
 * What is deliberately absent: payloads and results. A method name and an error
 * code are enough to tell a refused token from a malformed frame; the arguments are
 * where the operator's prompts and the agent's answers live, and a diagnostics
 * archive is meant to be emailed.
 *
 * The buffer is a ring: the newest [capacity] entries, because the last minute
 * before a failure is the part worth reading. It is written from the socket's
 * reader coroutine and read from the export path, so every access is locked.
 */
class ProtocolLog(private val capacity: Int = DEFAULT_CAPACITY) {

    /** Which way a line went. */
    enum class Direction { OUT, IN }

    /**
     * One thing that crossed the wire, or was supposed to.
     *
     * @property atMillis when it happened, wall clock, for lining up with `activity.log`.
     * @property direction out for a request, in for an answer or an event.
     * @property method the protocol method, or `event:<name>` for a pushed frame.
     * @property durationMillis how long the answer took, for an answer.
     * @property outcome `ok`, `error:CODE`, `timeout`, `unsent` or `event`.
     * @property bytes the encoded frame's size, so a truncated payload is visible.
     */
    data class Entry(
        val atMillis: Long,
        val direction: Direction,
        val method: String,
        val durationMillis: Long? = null,
        val outcome: String = "ok",
        val bytes: Int? = null,
    )

    private val lock = Any()
    private val entries = ArrayDeque<Entry>(capacity)

    /** A request left this phone. */
    fun sent(method: String, bytes: Int, atMillis: Long = System.currentTimeMillis()) {
        add(Entry(atMillis, Direction.OUT, method, outcome = "sent", bytes = bytes))
    }

    /** A request was answered. */
    fun answered(
        method: String,
        durationMillis: Long,
        bytes: Int?,
        atMillis: Long = System.currentTimeMillis(),
    ) {
        add(Entry(atMillis, Direction.IN, method, durationMillis, "ok", bytes))
    }

    /** A request was answered with a refusal. */
    fun refused(
        method: String,
        durationMillis: Long?,
        code: String,
        atMillis: Long = System.currentTimeMillis(),
    ) {
        add(Entry(atMillis, Direction.IN, method, durationMillis, "error:$code", null))
    }

    /** A request that never made it out, or never came back. */
    fun failed(
        method: String,
        outcome: String,
        durationMillis: Long? = null,
        atMillis: Long = System.currentTimeMillis(),
    ) {
        add(Entry(atMillis, Direction.IN, method, durationMillis, outcome, null))
    }

    /** A frame the host pushed without being asked. */
    fun event(name: String, bytes: Int?, atMillis: Long = System.currentTimeMillis()) {
        add(Entry(atMillis, Direction.IN, "event:$name", outcome = "event", bytes = bytes))
    }

    /** Everything remembered, oldest first. */
    fun snapshot(): List<Entry> = synchronized(lock) { entries.toList() }

    /** The same, one JSON object per line, for `requests.jsonl`. */
    fun jsonLines(): List<String> = snapshot().map { entry ->
        buildString {
            append('{')
            append("\"at\":").append(entry.atMillis)
            append(",\"direction\":\"").append(if (entry.direction == Direction.OUT) "out" else "in").append('"')
            append(",\"method\":").append(quote(entry.method))
            entry.durationMillis?.let { append(",\"ms\":").append(it) }
            append(",\"outcome\":").append(quote(entry.outcome))
            entry.bytes?.let { append(",\"bytes\":").append(it) }
            append('}')
        }
    }

    private fun add(entry: Entry) {
        synchronized(lock) {
            if (entries.size >= capacity) entries.removeFirst()
            entries.addLast(entry)
        }
    }

    /** JSON string escaping for the five characters a method name can contain. */
    private fun quote(value: String): String {
        val out = StringBuilder(value.length + 2).append('"')
        for (character in value) {
            when (character) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (character < ' ') out.append("\\u%04x".format(character.code)) else out.append(character)
            }
        }
        return out.append('"').toString()
    }

    companion object {
        /** Enough for a minute of polling plus a burst of events, and small. */
        const val DEFAULT_CAPACITY = 400
    }
}
