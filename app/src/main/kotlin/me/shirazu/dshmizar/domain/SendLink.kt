package me.shirazu.dshmizar.domain

import kotlinx.serialization.json.JsonObject
import me.shirazu.mizar.kit.AcceptanceResult
import me.shirazu.mizar.kit.MizarConnection
import me.shirazu.mizar.kit.PromptResult

/**
 * The two calls that change what the host is doing in a session.
 *
 * They are here together and not with the reads because they are the only writes:
 * a prompt starts work, a cancel asks for it to stop. Both answer with an
 * acknowledgement rather than data, and the acknowledgement is all this domain
 * needs -- what the turn actually did arrives through the transcript.
 */
internal interface SendLink {

    suspend fun prompt(payload: JsonObject): PromptResult

    suspend fun cancel(payload: JsonObject): AcceptanceResult
}

/** The live socket, behind [SendLink]. */
internal class SocketSendLink(private val socket: MizarConnection) : SendLink {

    override suspend fun prompt(payload: JsonObject): PromptResult =
        socket.call("session.prompt", payload)

    override suspend fun cancel(payload: JsonObject): AcceptanceResult =
        socket.call("session.cancel", payload)
}
