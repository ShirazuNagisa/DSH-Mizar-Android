package me.shirazu.dshmizar.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.shirazu.dshmizar.R
import me.shirazu.mizar.kit.ConversationItem
import me.shirazu.mizar.kit.jsonObjectOf
import me.shirazu.mizar.kit.jsonString
import java.util.UUID

/**
 * Sending a prompt, and asking a turn to stop.
 *
 * Three rules live here, and all three are about what the operator sees *after* the
 * button:
 *
 *  - **A submission is refused before it is sent, not after.** An attachment that is
 *    still uploading, or one that failed, is a reason to say so on the spot; sending
 *    anyway produces a prompt the host accepts and the operator never sees complete.
 *  - **A fresh `requestId` per submission.** Reusing one makes a second identical
 *    message look like a retry of the first, and the host drops it.
 *  - **The log is written asynchronously, so the read that follows can miss the
 *    message.** A single read after an accepted prompt usually does not contain it,
 *    which looks exactly like a message that was silently dropped -- hence the
 *    bounded re-read loop in [awaitRecorded].
 *
 * The other domains are reached through four narrow functions rather than their
 * coordinators: "read the open conversation again", "drop the staged files", "take
 * the card down". Sending is not a peer of reading or of the lock-screen card; it is
 * a caller of both, and saying so keeps this object testable without building them.
 */
internal class SendCoordinator(
    private val store: StateStore,
    private val scope: CoroutineScope,
    private val strings: Text,
    /** The sentence a failed call shows, built where the diagnostics live. */
    private val describe: (Throwable) -> String,
    /** The live link, or `null` when nothing is connected. */
    private val link: () -> SendLink?,
    /** A full page, after the host has accepted something. */
    private val reload: suspend () -> Unit,
    /** One page, for the bounded wait that follows a send. */
    private val readOnce: suspend () -> Unit,
    /** The staged files are the host's once the prompt is accepted. */
    private val clearAttachments: () -> Unit,
    /** A stop takes the lock-screen card down without waiting for the host. */
    private val hideCard: () -> Unit,
) {

    /**
     * A submission already on its way to the host.
     *
     * The composer has two ways to send -- the button and the keyboard's action -- and
     * both call this. Without this flag a single press that reaches both produces two
     * frames with two request ids, so the host records the message twice: one turn
     * runs and answers, the other is left with no turn at all and is drawn as
     * "未完成" forever. A ghost like that also keeps the session looking busy, which
     * holds the lock-screen card up after the real turn has finished.
     *
     * Held only until the frame is away, not through the wait for the log: two
     * messages typed in quick succession are two prompts, not a double press.
     */
    private var submitting = false

    /**
     * Send what the composer holds, or explain why it cannot go.
     *
     * @param steer whether the host should treat this as guidance for the running
     *   turn instead of a queued message. Only written when true: an older host
     *   that does not know the key must keep queueing, which is its default.
     */
    fun send(message: String, steer: Boolean = false) {
        val current = store.state.value
        val ready = current.attachments.filter { it.readyId != null }
        if (message.isBlank() && ready.isEmpty()) return
        if (current.attachments.any { it.readyId == null && it.error == null }) {
            store.update { it.copy(problem = strings.get(R.string.attachment_still_uploading)) }
            return
        }
        current.attachments.firstNotNullOfOrNull { it.error }?.let { failure ->
            store.update { it.copy(problem = failure) }
            return
        }
        if (submitting) return
        submitting = true
        scope.launch {
            val socket = link()
            val sessionId = store.state.value.selectedSessionId
            if (socket == null || sessionId == null) {
                submitting = false
                return@launch
            }
            // A fresh key per submission: reusing one would make a second identical
            // message look like a retry of the first and be dropped by the host.
            val requestId = UUID.randomUUID().toString()
            try {
                val result = try {
                    socket.prompt(
                        buildJsonObject {
                            put("sessionId", sessionId)
                            put("requestId", requestId)
                            // The attached-prompt path requires a string for text even
                            // when the file is the whole message: an omitted key is a
                            // malformed frame, not "no text".
                            put("text", message)
                            if (steer) put("mode", "steer")
                            if (ready.isNotEmpty()) {
                                putJsonArray("attachmentIds") {
                                    ready.forEach { add(JsonPrimitive(requireNotNull(it.readyId))) }
                                }
                            }
                        },
                    )
                } finally {
                    // Released as soon as the frame is away. Holding it through the
                    // wait below would swallow a second message typed quickly, and the
                    // wait is not a submission.
                    submitting = false
                }
                if (!result.accepted) {
                    store.update { it.copy(problem = "the host did not accept the message") }
                    return@launch
                }
                // The host owns the bytes once the prompt is accepted; the staging
                // files have done their job and any retry would be a new prompt.
                clearAttachments()
                if (message.isNotBlank()) awaitRecorded(message)
                reload()
            } catch (error: Throwable) {
                store.update { it.copy(problem = describe(error)) }
            }
        }
    }

    /** Stop the open session's turn. */
    fun cancelTurn() {
        val sessionId = store.state.value.selectedSessionId ?: return
        cancel(sessionId)
    }

    /**
     * Cancel one session's turn, from the screen or from the live update's stop button.
     *
     * Split from [cancelTurn] because the notification names its own session: the
     * operator can stop a turn without the conversation being the open one.
     */
    fun cancel(sessionId: String) {
        scope.launch {
            val socket = link() ?: return@launch
            // The acknowledgement is not the point: the host may refuse a stop that
            // arrives after the turn already ended, and the transcript is what tells
            // the operator what actually happened.
            runCatching { socket.cancel(jsonObjectOf("sessionId" to jsonString(sessionId))) }
            if (store.state.value.selectedSessionId == sessionId) reload()
            // The card is about a turn that was just asked to stop; waiting for the
            // host's `turn.settled` would leave it up for however long the stop takes.
            hideCard()
        }
    }

    /**
     * Wait for the host to record what was just sent.
     *
     * Bounded on purpose: eight reads half a second apart is about four seconds, which
     * is longer than a local write and short enough that a host which never records it
     * does not hold the composer hostage.
     */
    private suspend fun awaitRecorded(message: String) {
        repeat(RECORD_ATTEMPTS) {
            readOnce()
            val recorded = store.state.value.conversation.any {
                it is ConversationItem.Message && it.role == ConversationItem.Role.USER && it.text == message
            }
            if (recorded) return
            delay(RECORD_DELAY_MILLIS)
        }
    }

    private companion object {
        /** How many reads to spend waiting for the log to catch up. */
        const val RECORD_ATTEMPTS = 8

        /** The gap between them: long enough for a local write, short enough to notice. */
        const val RECORD_DELAY_MILLIS = 500L
    }
}
