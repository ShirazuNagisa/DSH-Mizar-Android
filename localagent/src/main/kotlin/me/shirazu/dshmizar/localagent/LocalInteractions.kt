package me.shirazu.dshmizar.localagent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.shirazu.mizar.kit.MizarConnection
import me.shirazu.mizar.kit.PendingApproval
import me.shirazu.mizar.kit.PendingInteractions
import me.shirazu.mizar.kit.PendingQuestion
import me.shirazu.mizar.kit.ServerFrame
import me.shirazu.mizar.kit.WIRE_JSON

/**
 * How the local link answers an interaction it cannot show a card for.
 *
 * The minimal local loop has no approval dock yet. The kernel is fail-closed — a
 * prompt that needs a permission and gets no answer keeps the turn running until the
 * connection dies, which then fails the turn — so "do nothing" is the worst answer
 * available. These two policies are the honest placeholders:
 *
 *  - [AUTO_DENY] is the safe default: a tool action is refused, the turn ends with a
 *    clear refusal instead of hanging, and the operator loses nothing they did not
 *    already see (there was no card to tap).
 *  - [AUTO_ALLOW] is for trusted workspaces: `allowed-once` for every approval, which
 *    is what a "local sandbox, I wrote the prompt" run expects.
 *
 * The real dock (`AppViewModel.answerApproval`) replaces this by simply not starting
 * the responder; nothing else in the local link depends on it.
 */
enum class LocalInteractionPolicy {
    AUTO_DENY,
    AUTO_ALLOW,
    ;

    /** The wire outcome this policy answers an approval with. */
    val approvalOutcome: String
        get() = when (this) {
            AUTO_DENY -> "rejected"
            AUTO_ALLOW -> "allowed-once"
        }
}

/**
 * Answers the kernel's approval/question events automatically.
 *
 * Split into pure payload builders and one thin coroutine loop so the wire shapes
 * have JVM tests (they are the part a kernel upgrade could break) and the loop stays
 * boring.
 */
object LocalInteractions {

    /** `approval.answer` payload for one approval. */
    fun approvalAnswerPayload(policy: LocalInteractionPolicy, requestId: String): JsonObject =
        buildJsonObject {
            put("requestId", requestId)
            put("outcome", policy.approvalOutcome)
            // `by` is optional on the wire; naming the automatic answer keeps the
            // kernel's log honest about who decided.
            put("by", "local-auto")
        }

    /**
     * `question.answer` payload for one question batch.
     *
     * The kernel requires every question in the batch to be answered exactly once,
     * so the placeholder picks the first option of each (an empty list when a
     * question offers none — a free-text question has nothing to pick, and the kernel
     * accepts the empty answer as "no choice").
     */
    fun questionAnswerPayload(batch: PendingQuestion): JsonObject = buildJsonObject {
        put("callId", batch.callId)
        putJsonArray("answers") {
            for (question in batch.questions) {
                addJsonObject {
                    put("id", question.id)
                    putJsonArray("selected") {
                        question.options.firstOrNull()?.let { add(it.label) }
                    }
                }
            }
        }
    }

    /**
     * Start answering this connection's interactions with [policy].
     *
     * The returned job ends when the connection's event stream ends (the socket
     * closed). Callers cancel it when the operator takes over with the real dock.
     */
    fun launch(
        scope: CoroutineScope,
        connection: MizarConnection,
        policy: LocalInteractionPolicy = LocalInteractionPolicy.AUTO_DENY,
    ): Job = scope.launch {
        connection.events().collect { frame ->
            // The event stream carries welcome/ok/error too on a real socket; only the
            // event frames name an interaction, so everything else is skipped.
            val event = frame as? ServerFrame.Event ?: return@collect
            when (event.name) {
                "approval.request" -> {
                    val approval = runCatching {
                        WIRE_JSON.decodeFromJsonElement(PendingApproval.serializer(), event.data)
                    }.getOrNull() ?: return@collect
                    runCatching {
                        connection.request("approval.answer", approvalAnswerPayload(policy, approval.requestId))
                    }
                }

                "question.request" -> {
                    val batch = runCatching {
                        WIRE_JSON.decodeFromJsonElement(PendingQuestion.serializer(), event.data)
                    }.getOrNull() ?: return@collect
                    runCatching {
                        connection.request("question.answer", questionAnswerPayload(batch))
                    }
                }

                // The host only says *that* the pending set changed; the set itself is
                // one call away. Answering here as well makes the policy survive a
                // dropped event — the kernel treats a second answer to the same
                // interaction as a harmless no-op.
                "interactions.changed" -> answerPending(connection, policy)
            }
        }
    }

    private suspend fun answerPending(connection: MizarConnection, policy: LocalInteractionPolicy) {
        val raw = runCatching { connection.request("interactions.pending") }.getOrNull() ?: return
        val pending = runCatching {
            WIRE_JSON.decodeFromJsonElement(PendingInteractions.serializer(), raw)
        }.getOrNull() ?: return
        for (approval in pending.approvals) {
            runCatching {
                connection.request("approval.answer", approvalAnswerPayload(policy, approval.requestId))
            }
        }
        for (batch in pending.questions) {
            runCatching {
                connection.request("question.answer", questionAnswerPayload(batch))
            }
        }
    }
}
