package me.shirazu.dshmizar.domain

import kotlinx.serialization.json.JsonObject
import me.shirazu.mizar.kit.ModelCatalog
import me.shirazu.mizar.kit.MizarConnection
import me.shirazu.mizar.kit.PermissionCatalog
import me.shirazu.mizar.kit.SelectModelResult
import me.shirazu.mizar.kit.SessionStatsResult
import me.shirazu.mizar.kit.SetPermissionResult
import me.shirazu.mizar.kit.jsonObjectOf
import me.shirazu.mizar.kit.jsonString

/**
 * The six requests the settings screen makes of the host.
 *
 * Typed methods rather than a generic `call(method, payload)`: decoding, the
 * empty-result rule and the error codes all stay in the connection, which is where
 * they were tested. A JSON-level seam would have moved the decode into this domain
 * and quietly changed what a malformed answer does.
 */
internal interface SettingsLink {

    suspend fun modelCatalog(): ModelCatalog

    suspend fun permissionCatalog(): PermissionCatalog

    suspend fun setModel(payload: JsonObject): SelectModelResult

    suspend fun setPermission(payload: JsonObject): SetPermissionResult

    suspend fun stats(sessionId: String): SessionStatsResult

    suspend fun setNewSessionModel(payload: JsonObject): JsonObject
}

/** The live socket, behind [SettingsLink]. */
internal class SocketSettingsLink(private val socket: MizarConnection) : SettingsLink {

    override suspend fun modelCatalog(): ModelCatalog = socket.call("model.catalog")

    override suspend fun permissionCatalog(): PermissionCatalog = socket.call("permissions.catalog")

    override suspend fun setModel(payload: JsonObject): SelectModelResult =
        socket.call("session.model.set", payload)

    override suspend fun setPermission(payload: JsonObject): SetPermissionResult =
        socket.call("session.permission.set", payload)

    override suspend fun stats(sessionId: String): SessionStatsResult =
        socket.call("session.stats", jsonObjectOf("sessionId" to jsonString(sessionId)))

    override suspend fun setNewSessionModel(payload: JsonObject): JsonObject =
        socket.call("session.model.set", payload)
}
