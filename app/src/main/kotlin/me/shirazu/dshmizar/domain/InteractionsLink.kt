package me.shirazu.dshmizar.domain

import kotlinx.serialization.json.JsonObject
import me.shirazu.mizar.kit.AcceptanceResult
import me.shirazu.mizar.kit.MizarConnection
import me.shirazu.mizar.kit.PendingInteractions

/**
 * The three requests this domain makes of the host.
 *
 * Payloads are built by the caller, not here: what `approval.answer` carries is part
 * of the protocol, and a test can only pin it if it is visible at the call site.
 */
internal interface InteractionsLink {

    suspend fun pending(): PendingInteractions

    suspend fun answerApproval(payload: JsonObject): AcceptanceResult

    suspend fun answerQuestions(payload: JsonObject): AcceptanceResult
}

/** The live socket, behind [InteractionsLink]. */
internal class SocketInteractionsLink(private val socket: MizarConnection) : InteractionsLink {

    override suspend fun pending(): PendingInteractions =
        socket.call("interactions.pending", timeoutMillis = POLL_TIMEOUT_MILLIS)

    override suspend fun answerApproval(payload: JsonObject): AcceptanceResult =
        socket.call("approval.answer", payload)

    override suspend fun answerQuestions(payload: JsonObject): AcceptanceResult =
        socket.call("question.answer", payload)

    private companion object {
        /**
         * How long a poll waits before deciding the host is not there.
         *
         * Much shorter than the 60 s a normal call gets, because this is the
         * heartbeat: the whole value of the poll is noticing silence, and a host on
         * the other end of a tunnel answers in milliseconds when it answers at all.
         */
        const val POLL_TIMEOUT_MILLIS = 5_000L
    }
}
