package me.shirazu.dshmizar.domain

import kotlinx.serialization.json.JsonObject
import me.shirazu.mizar.kit.MizarConnection
import me.shirazu.mizar.kit.SessionStatsResult

/**
 * The one read the session list makes: a row's title.
 *
 * A title is not on the list frame -- it lives behind `session.stats` -- so the list
 * learns names one row at a time, as rows are about to be drawn. The call is the same
 * one the open conversation makes for its figures, but the two are asked at different
 * times for different reasons, so they get their own link rather than sharing one.
 */
internal interface TitlesLink {

    suspend fun stats(payload: JsonObject): SessionStatsResult
}

/** The live socket, behind [TitlesLink]. */
internal class SocketTitlesLink(private val socket: MizarConnection) : TitlesLink {

    override suspend fun stats(payload: JsonObject): SessionStatsResult =
        socket.call("session.stats", payload)
}
