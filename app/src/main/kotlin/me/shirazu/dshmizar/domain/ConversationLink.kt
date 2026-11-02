package me.shirazu.dshmizar.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import me.shirazu.mizar.kit.MizarConnection
import me.shirazu.mizar.kit.PendingInteractions
import me.shirazu.mizar.kit.SessionMessagesResult
import me.shirazu.mizar.kit.SessionStatsResult

/** What `session.refresh` answers with: one round trip for the whole screen. */
@Serializable
internal data class RefreshBundle(
    val messages: SessionMessagesResult,
    val stats: SessionStatsResult? = null,
    val pending: PendingInteractions? = null,
)

/**
 * The three reads the open conversation makes of the host.
 *
 * Typed methods rather than a generic `call(method, payload)`: decoding, the
 * empty-result rule and the error codes stay in the connection, where they were
 * tested. The payload is still built by the coordinator, because which keys a page
 * read carries is a protocol rule this domain is judged on -- and one of those rules
 * is that `afterSeq: null` and "no `afterSeq` at all" mean opposite things.
 */
internal interface ConversationLink {

    suspend fun messages(payload: JsonObject): SessionMessagesResult

    suspend fun refresh(payload: JsonObject): RefreshBundle

    suspend fun stats(payload: JsonObject): SessionStatsResult
}

/** The live socket, behind [ConversationLink]. */
internal class SocketConversationLink(private val socket: MizarConnection) : ConversationLink {

    override suspend fun messages(payload: JsonObject): SessionMessagesResult =
        socket.call("session.messages", payload)

    override suspend fun refresh(payload: JsonObject): RefreshBundle =
        socket.call("session.refresh", payload)

    override suspend fun stats(payload: JsonObject): SessionStatsResult =
        socket.call("session.stats", payload)
}
