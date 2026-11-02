package me.shirazu.dshmizar.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import me.shirazu.mizar.kit.ModelCatalog
import me.shirazu.mizar.kit.ModelSelection
import me.shirazu.mizar.kit.PermissionCatalog
import me.shirazu.mizar.kit.SessionSettings
import me.shirazu.mizar.kit.SessionStatsFrame
import me.shirazu.mizar.kit.SettingsScope
import me.shirazu.mizar.kit.jsonObjectOf
import me.shirazu.mizar.kit.jsonString

/**
 * The session's model and permission preset, and the catalogs they are chosen from.
 *
 * Two rules make this domain harder than it looks, and both are about *time*:
 *
 *  - **A reply belongs to the socket that asked.** The connection's epoch is part of
 *    every scope, so an answer that arrives after the socket was replaced is dropped
 *    instead of putting one computer's model on another's session.
 *  - **A newer choice wins.** Every request bumps a revision, and a reply carrying an
 *    older one is dropped: tapping two models quickly must not let the first answer
 *    land last.
 *
 * The phone also shows a choice before the host has confirmed it ([setModel]), which
 * is why `modelChoice` is a separate, transient map rather than being written into
 * the stats frame.
 */
internal class SettingsCoordinator(
    private val store: StateStore,
    private val scope: CoroutineScope,
    private val note: (String) -> Unit,
    /** The sentence a failed call shows, built where the diagnostics live. */
    private val describe: (Throwable) -> String,
    /** The connection's epoch, part of every [SettingsScope]. */
    private val epoch: () -> Int,
    /** The live link, or `null` when nothing is connected. */
    private val link: () -> SettingsLink?,
) {

    /** Bumped per request, so a late answer cannot overwrite a newer one. */
    private var revision = 0L

    /**
     * Read the catalogs, once unless asked again.
     *
     * The host's list of models changes when it is reconfigured, and a phone that
     * cached the old one for the life of the process would offer a model that no
     * longer exists. `force` is what the reconnect path passes.
     */
    fun loadCatalogs(force: Boolean = false) {
        scope.launch {
            val socket = link() ?: return@launch
            if (force || store.state.value.modelCatalog == null) {
                runCatching { socket.modelCatalog() }
                    .onSuccess { catalog -> store.update { it.copy(modelCatalog = catalog) } }
            }
            if (force || store.state.value.permissionCatalog == null) {
                runCatching { socket.permissionCatalog() }
                    .onSuccess { catalog -> store.update { it.copy(permissionCatalog = catalog) } }
            }
        }
    }

    /**
     * The scope a settings call belongs to, or `null` when there is nothing to ask.
     *
     * The connection's epoch is part of it: a reply that arrives after the socket
     * was replaced came from a host that is no longer the one on screen, and
     * applying it would show one computer's model for another's session.
     */
    private fun settingsScope(): SettingsScope? {
        val current = store.state.value
        val host = current.host ?: return null
        val sessionId = current.selectedSessionId ?: return null
        return SettingsScope(host.hostId, host.deviceId, sessionId, epoch().toLong())
    }

    /** Whether a settings reply is still the one this screen is waiting for. */
    private fun stillCurrent(target: SettingsScope, issuedAt: Long): Boolean =
        SessionSettings.mayApply(target, settingsScope(), issuedAt, revision)

    /**
     * Fold a stats frame into what is on screen.
     *
     * Keys arrive independently -- a frame carrying only the context pressure must
     * not blank the model, the title or the permission it did not mention -- so
     * this merges rather than replaces. The conversation's own loads carry a frame
     * too, which is why this is public: a stats frame belongs to the settings the
     * screen shows, whoever delivered it.
     */
    fun applyStats(fresh: SessionStatsFrame?) {
        if (fresh == null) return
        store.update { it.copy(stats = SessionSettings.mergeStats(it.stats, fresh), statsError = null) }
    }

    /**
     * Choose the model for the session on screen.
     *
     * The phone shows the choice while the host is being asked, and then takes the
     * host's answer as the truth: `session.model.set` returns what was actually
     * selected, so a refusal clears the choice instead of leaving a model on screen
     * that is not running. The stats readback that follows is what lets the host's
     * own value be the one the pill names.
     */
    fun setModel(selection: ModelSelection) {
        val target = settingsScope() ?: return
        val issuedAt = ++revision
        // Transient by construction: the entry is removed on either answer below.
        store.update { it.copy(modelChoice = it.modelChoice + (target.sessionId to selection)) }
        scope.launch {
            val socket = link() ?: run {
                // The host was never told. Leaving the entry in place left the pill
                // naming a model that is not running, and nothing would ever clear it:
                // the only two places that remove it are the two answers below.
                store.update { it.copy(modelChoice = it.modelChoice - target.sessionId) }
                note("model set skipped: no connection")
                return@launch
            }
            runCatching {
                socket.setModel(SessionSettings.modelPayload(target, selection))
            }.onSuccess { result ->
                if (!stillCurrent(target, issuedAt)) return@onSuccess
                val confirmed = SessionSettings.confirmedModel(target, result, store.state.value.stats)
                store.update {
                    it.copy(
                        stats = confirmed,
                        statsError = null,
                        modelChoice = it.modelChoice - target.sessionId,
                    )
                }
                note("model set: ${result.selected.provider}/${result.selected.model} effort=${result.selected.reasoningEffort}")
                refreshStats(target, issuedAt)
            }.onFailure { error ->
                if (!stillCurrent(target, issuedAt)) return@onFailure
                note("model set failed: ${error.message}")
                store.update {
                    it.copy(modelChoice = it.modelChoice - target.sessionId, problem = describe(error))
                }
            }
        }
    }

    /** Read the session's stats back, keeping whatever a partial frame omits. */
    private suspend fun refreshStats(target: SettingsScope, issuedAt: Long) {
        val socket = link() ?: return
        runCatching {
            socket.stats(target.sessionId)
        }.onSuccess { result ->
            if (!stillCurrent(target, issuedAt)) return@onSuccess
            applyStats(result.stats)
        }
    }

    fun setHomeModel(selection: ModelSelection) {
        store.update { it.copy(newSessionModel = selection) }
    }

    /**
     * Apply the model chosen on the home screen to the session that just started.
     *
     * The home screen has no session to point `session.model.set` at, so the choice is
     * held in `UiState.newSessionModel` until `session.create` returns an id. Applied
     * before the first prompt: the point of choosing on the home screen is that the
     * session's first turn already runs on it, not the second.
     *
     * The link is passed in because this runs while a *new* session is being opened,
     * on the socket that opened it -- not necessarily the one the screen is on.
     */
    suspend fun applyToNewSession(link: SettingsLink, sessionId: String) {
        val selection = store.state.value.newSessionModel ?: return
        store.update { it.copy(modelChoice = it.modelChoice + (sessionId to selection)) }
        runCatching {
            // The same builder the settings path uses: a session that has just
            // been created and a session being changed send the same four keys,
            // and they used to be two hand-written copies of them.
            link.setNewSessionModel(SessionSettings.modelPayload(sessionId, selection))
        }.onFailure { error ->
            store.update { it.copy(problem = describe(error)) }
        }
    }

    /**
     * Change the session's permission preset.
     *
     * The command's own answer says the request was accepted, not that the preset
     * changed, so the screen waits for `session.stats` to report it and says so
     * when the host disagrees. Reloading the whole conversation for this would have
     * cost a page of records for one line of stats.
     */
    fun setPermission(preset: String) {
        val target = settingsScope() ?: return
        val issuedAt = ++revision
        scope.launch {
            val socket = link() ?: return@launch
            runCatching {
                socket.setPermission(
                    jsonObjectOf(
                        "sessionId" to jsonString(target.sessionId),
                        "preset" to jsonString(preset),
                    ),
                )
            }.onSuccess {
                if (!stillCurrent(target, issuedAt)) return@onSuccess
                runCatching {
                    socket.stats(target.sessionId)
                }.onSuccess { result ->
                    if (!stillCurrent(target, issuedAt)) return@onSuccess
                    applyStats(result.stats)
                    if (!SessionSettings.permissionConfirmed(target, preset, result)) {
                        store.update {
                            it.copy(problem = it.problem ?: "the host did not confirm the new permission")
                        }
                    }
                }
            }.onFailure { error ->
                if (!stillCurrent(target, issuedAt)) return@onFailure
                store.update { it.copy(problem = describe(error)) }
            }
        }
    }
}
