package me.shirazu.mizar.kit

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A navigation/connection generation prevents A -> B -> A from accepting a late A result. */
data class SettingsScope(val hostId: String, val deviceId: String, val sessionId: String, val generation: Long)

object SessionSettings {
    /** A live projection always wins; never a permanent optimistic phone-side override. */
    fun current(stats: SessionStatsFrame?, catalog: ModelCatalog?): ModelSelection? =
        stats?.model?.next ?: stats?.model?.lastUsed ?: catalog?.defaultSelection

    fun option(catalog: ModelCatalog?, selection: ModelSelection?): ModelOption? =
        catalog?.providers?.firstOrNull { it.id == selection?.provider }?.models?.firstOrNull { it.id == selection?.model }

    fun efforts(option: ModelOption?): List<ReasoningEffortOption> =
        option?.efforts.orEmpty().filter { it.id.isNotBlank() }.distinctBy { it.id }

    /** Preserve a valid current effort only when selecting the same provider/model. */
    fun seed(provider: String, option: ModelOption, current: ModelSelection?): ModelSelection {
        val supported = efforts(option).map { it.id }
        val existing = current?.takeIf { it.provider == provider && it.model == option.id }?.reasoningEffort
        val effort = existing?.takeIf { it in supported } ?: option.defaultEffort?.takeIf { it in supported }
        return ModelSelection(provider, option.id, effort)
    }

    fun valid(catalog: ModelCatalog?, selection: ModelSelection): Boolean {
        val option = option(catalog, selection) ?: return false
        val supported = efforts(option)
        return if (supported.isEmpty()) selection.reasoningEffort == null else supported.any { it.id == selection.reasoningEffort }
    }

    fun permissions(catalog: PermissionCatalog?): List<PermissionOption> =
        catalog?.options.orEmpty().filter { it.value.isNotBlank() && it.value != "custom" }.distinctBy { it.value }

    fun modelPayload(scope: SettingsScope, selection: ModelSelection): JsonObject = buildJsonObject {
        put("sessionId", scope.sessionId)
        put("provider", selection.provider)
        put("model", selection.model)
        selection.reasoningEffort?.let { put("reasoningEffort", it) }
    }

    fun mayApply(request: SettingsScope, current: SettingsScope?, revision: Long, currentRevision: Long): Boolean =
        request == current && revision == currentRevision

    fun confirmedModel(scope: SettingsScope, result: SelectModelResult, stats: SessionStatsFrame?): SessionStatsFrame {
        require(result.sessionId == scope.sessionId) { "Model response belongs to another session" }
        require(result.selected.provider.isNotBlank() && result.selected.model.isNotBlank()) { "Host returned no selected model" }
        return (stats ?: SessionStatsFrame()).copy(model = ModelSelectionPair(stats?.model?.lastUsed, result.selected))
    }

    /** Stats keys are independently optional; a partial projection must not erase unrelated figures. */
    fun mergeStats(previous: SessionStatsFrame?, fresh: SessionStatsFrame?): SessionStatsFrame? {
        if (fresh == null) return previous
        if (previous == null) return fresh
        return SessionStatsFrame(
            tokenUsage = fresh.tokenUsage ?: previous.tokenUsage,
            cacheHit = fresh.cacheHit ?: previous.cacheHit,
            context = fresh.context ?: previous.context,
            stats = fresh.stats ?: previous.stats,
            model = fresh.model ?: previous.model,
            permissionPreset = fresh.permissionPreset ?: previous.permissionPreset,
            title = fresh.title ?: previous.title,
        )
    }

    fun permissionConfirmed(scope: SettingsScope, desired: String, result: SessionStatsResult): Boolean =
        result.sessionId == scope.sessionId && result.stats?.permissionPreset == desired
}
