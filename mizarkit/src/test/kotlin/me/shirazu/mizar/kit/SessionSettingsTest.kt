package me.shirazu.mizar.kit

import org.junit.jupiter.api.Test
import kotlin.test.*

class SessionSettingsTest {
    private val option = ModelOption("thinking", "Thinking", efforts = listOf(
        ReasoningEffortOption("low", "Low"), ReasoningEffortOption("max", "Maximum")), defaultEffort = "low")
    private val plain = ModelOption("plain", "Plain")
    private val catalog = ModelCatalog(defaultSelection = ModelSelection("p", "thinking", "low"),
        providers = listOf(ProviderGroup("p", "P", listOf(option, plain))))
    private val scope = SettingsScope("host", "device", "session", 1)

    @Test fun `same model preserves valid effort but changed model uses advertised default`() {
        assertEquals("max", SessionSettings.seed("p", option, ModelSelection("p", "thinking", "max")).reasoningEffort)
        assertEquals("low", SessionSettings.seed("p", option, ModelSelection("other", "thinking", "max")).reasoningEffort)
        assertEquals("low", SessionSettings.seed("p", option, ModelSelection("p", "thinking", "invented")).reasoningEffort)
    }

    @Test fun `unsupported model has no effort and payload omits it`() {
        val selected = SessionSettings.seed("p", plain, ModelSelection("p", "thinking", "max"))
        assertNull(selected.reasoningEffort)
        assertTrue(SessionSettings.valid(catalog, selected))
        assertFalse("reasoningEffort" in SessionSettings.modelPayload(scope, selected))
        assertFalse(SessionSettings.valid(catalog, selected.copy(reasoningEffort = "low")))
    }

    @Test fun `only catalog levels are selectable and invalid default is not invented`() {
        assertFalse(SessionSettings.valid(catalog, ModelSelection("p", "thinking", "high")))
        assertFalse(SessionSettings.valid(catalog, ModelSelection("other", "thinking", "low")))
        assertFalse(SessionSettings.valid(catalog, ModelSelection("p", "thinking")))
        assertNull(SessionSettings.seed("p", option.copy(defaultEffort = "high"), null).reasoningEffort)
        assertEquals(listOf("low", "max"), SessionSettings.efforts(option).map { it.id })
    }

    @Test fun `fresh desktop projection wins over catalog default`() {
        val desktop = ModelSelection("p", "thinking", "max")
        assertEquals(desktop, SessionSettings.current(SessionStatsFrame(model = ModelSelectionPair(next = desktop)), catalog))
        assertEquals(desktop, SessionSettings.current(SessionStatsFrame(model = ModelSelectionPair(lastUsed = desktop)), catalog))
    }

    @Test fun `host response selection rather than request is applied and wrong session rejected`() {
        val actual = ModelSelection("p", "thinking", "max")
        val stats = SessionSettings.confirmedModel(scope, SelectModelResult("session", actual), SessionStatsFrame(title = "keep"))
        assertEquals(actual, stats.model?.next)
        assertEquals("keep", stats.title)
        assertFailsWith<IllegalArgumentException> { SessionSettings.confirmedModel(scope, SelectModelResult("elsewhere", actual), null) }
    }

    @Test fun `scope and revision guard rejects host device session navigation reconnect and older reads`() {
        assertTrue(SessionSettings.mayApply(scope, scope, 2, 2))
        for (other in listOf(scope.copy(hostId = "other"), scope.copy(deviceId = "other"), scope.copy(sessionId = "other"), scope.copy(generation = 2))) {
            assertFalse(SessionSettings.mayApply(scope, other, 2, 2))
        }
        assertFalse(SessionSettings.mayApply(scope, scope, 1, 2))
        assertFalse(SessionSettings.mayApply(scope, null, 2, 2))
    }

    @Test fun `permission success requires matching current readback not command acknowledgement`() {
        assertFalse(SessionSettings.permissionConfirmed(scope, "read-only", SessionStatsResult("session", stats = null)))
        assertFalse(SessionSettings.permissionConfirmed(scope, "read-only", SessionStatsResult("session", stats = SessionStatsFrame(permissionPreset = "custom"))))
        assertFalse(SessionSettings.permissionConfirmed(scope, "read-only", SessionStatsResult("other", stats = SessionStatsFrame(permissionPreset = "read-only"))))
        assertTrue(SessionSettings.permissionConfirmed(scope, "read-only", SessionStatsResult("session", stats = SessionStatsFrame(permissionPreset = "read-only"))))
    }

    @Test fun `permission catalog is dynamic but custom never selectable`() {
        val catalog = PermissionCatalog(options = listOf(PermissionOption("custom", "Custom"), PermissionOption("auto", "Auto"), PermissionOption("danger-full-access", "Full")))
        assertEquals(listOf("auto", "danger-full-access"), SessionSettings.permissions(catalog).map { it.value })
        assertTrue(SessionSettings.permissions(catalog).last().isDangerous)
    }

    @Test fun `partial stats retain unrelated figures while latest model and permission win`() {
        val original = SessionStatsFrame(title = "Keep", tokenUsage = TokenUsage(1, 2, 3, 4, 5),
            model = ModelSelectionPair(next = ModelSelection("p", "thinking", "low")), permissionPreset = "read-only")
        val fresh = SessionStatsFrame(model = ModelSelectionPair(next = ModelSelection("p", "plain")), permissionPreset = "custom")
        val merged = requireNotNull(SessionSettings.mergeStats(original, fresh))
        assertEquals(original.tokenUsage, merged.tokenUsage)
        assertEquals("Keep", merged.title)
        assertEquals(fresh.model, merged.model)
        assertEquals("custom", merged.permissionPreset)
        assertEquals(original, SessionSettings.mergeStats(original, null))
    }
}
