package me.shirazu.dshmizar.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.shirazu.mizar.kit.ApprovalOutcome
import me.shirazu.mizar.kit.PendingInteractions
import me.shirazu.mizar.kit.QuestionAnswer
import me.shirazu.mizar.kit.jsonObjectOf
import me.shirazu.mizar.kit.jsonString

/**
 * The cards waiting for an answer: what the host is asking, and what answering does.
 *
 * Two things make this domain worth its own object. First, the poll's *silence* is
 * the signal: on a relay a socket can be dead while both ends believe it is open --
 * the tunnel drops the mapping, the relay keeps answering websocket pings, and
 * neither read loop ever fails. The only proof is a question that goes unanswered,
 * which is why the poll carries a short timeout and returns whether the host spoke.
 * Second, an unseen card is a blocked agent, so one lost poll is retried before the
 * app admits it cannot see.
 */
internal class InteractionsCoordinator(
    private val store: StateStore,
    private val scope: CoroutineScope,
    private val describe: (Throwable) -> String,
    /** The notice for a batch nobody is looking at; the notification domain decides if it buzzes. */
    private val announcePending: (PendingInteractions) -> Unit,
    /** The live link, or `null` when nothing is connected. */
    private val link: () -> InteractionsLink?,
) {

    fun refresh() {
        scope.launch { refreshNow() }
    }

    /**
     * Read the pending interactions, and say whether the host answered at all.
     *
     * @returns whether the host answered, or `true` when there is no connection to
     *   judge (a reconnect already owns that case).
     */
    suspend fun refreshNow(): Boolean {
        val socket = link() ?: return true
        // One retry: a refresh that loses a race with a reconnect, or that lands in
        // the same instant as the host restarting, would otherwise leave a card the
        // operator cannot see until the next poll -- and an agent is blocked the
        // whole time it is unseen.
        val pending = runCatching {
            socket.pending()
        }.getOrElse {
            delay(RETRY_DELAY_MILLIS)
            runCatching { socket.pending() }.getOrNull()
        } ?: return false
        store.update { it.copy(pending = pending) }
        announcePending(pending)
        return true
    }

    fun answerApproval(requestId: String, outcome: ApprovalOutcome) {
        scope.launch {
            val socket = link() ?: return@launch
            runCatching {
                socket.answerApproval(
                    jsonObjectOf(
                        "requestId" to jsonString(requestId),
                        "outcome" to jsonString(outcome.wire),
                    ),
                )
            }.onFailure { error -> store.update { it.copy(problem = describe(error)) } }
            // Whether or not the answer was accepted, what is on screen is now stale:
            // the card may have been answered from the computer a second earlier.
            refreshNow()
        }
    }

    fun answerQuestions(callId: String, answers: List<QuestionAnswer>) {
        scope.launch {
            val socket = link() ?: return@launch
            runCatching {
                socket.answerQuestions(questionsPayload(callId, answers))
            }.onFailure { error -> store.update { it.copy(problem = describe(error)) } }
            refreshNow()
        }
    }

    /** `question.answer`: one entry per question, `custom` only when it was typed. */
    private fun questionsPayload(callId: String, answers: List<QuestionAnswer>): JsonObject =
        buildJsonObject {
            put("callId", callId)
            putJsonArray("answers") {
                for (answer in answers) {
                    add(
                        buildJsonObject {
                            put("id", answer.id)
                            putJsonArray("selected") { answer.selected.forEach { add(JsonPrimitive(it)) } }
                            answer.custom?.let { put("custom", it) }
                        },
                    )
                }
            }
        }

    private companion object {
        /** Long enough for a reconnect's teardown to finish, short enough to stay unseen. */
        const val RETRY_DELAY_MILLIS = 500L
    }
}
