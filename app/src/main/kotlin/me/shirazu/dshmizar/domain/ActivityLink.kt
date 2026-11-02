package me.shirazu.dshmizar.domain

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.shirazu.mizar.kit.MizarConnection
import me.shirazu.mizar.kit.SessionActivityResult
import me.shirazu.mizar.kit.jsonInt

/**
 * The running turn's activity log, as the host serves it.
 *
 * One method with two optional narrowing keys, because that is the whole protocol:
 * the newest page asks for a count, an older page adds where to start and which
 * turn. Typed rather than a generic call so decoding and the error codes stay in the
 * connection, where they are already tested.
 */
internal interface ActivityLink {

    suspend fun page(
        sessionId: String,
        maxEntries: Int,
        beforeSeq: Int? = null,
        turn: Int? = null,
    ): SessionActivityResult
}

/** The live socket, behind [ActivityLink]. */
internal class SocketActivityLink(private val socket: MizarConnection) : ActivityLink {

    override suspend fun page(
        sessionId: String,
        maxEntries: Int,
        beforeSeq: Int?,
        turn: Int?,
    ): SessionActivityResult = socket.call(
        "session.activity",
        buildJsonObject {
            put("sessionId", sessionId)
            put("maxEntries", jsonInt(maxEntries))
            beforeSeq?.let { put("beforeSeq", jsonInt(it)) }
            turn?.let { put("turn", jsonInt(it)) }
        },
    )
}
