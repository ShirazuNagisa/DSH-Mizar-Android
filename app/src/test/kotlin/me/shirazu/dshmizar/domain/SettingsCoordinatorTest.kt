package me.shirazu.dshmizar.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.shirazu.dshmizar.UiState
import me.shirazu.mizar.kit.ContextPressure
import me.shirazu.mizar.kit.ModelCatalog
import me.shirazu.mizar.kit.ModelSelection
import me.shirazu.mizar.kit.ModelSelectionPair
import me.shirazu.mizar.kit.PairedHost
import me.shirazu.mizar.kit.PermissionCatalog
import me.shirazu.mizar.kit.SelectModelResult
import me.shirazu.mizar.kit.SessionStatsFrame
import me.shirazu.mizar.kit.SessionStatsResult
import me.shirazu.mizar.kit.SetPermissionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The model pill and the permission preset, without a socket.
 *
 * What is pinned here is the part that is about *time*, because that is where this
 * domain breaks: a choice shown before the host agrees, an answer arriving from a
 * socket that has since been replaced, and a host that accepts the command without
 * changing the preset. None of those crash; they leave a pill naming a model that
 * is not running, which is worse.
 */
class SettingsCoordinatorTest {

    private class Fixture(startEpoch: Int = 1) {
        var epoch = startEpoch
        var connected = true
        val link = FakeLink()
        val notes = mutableListOf<String>()
        val state = MutableStateFlow(
            UiState(
                host = PairedHost(
                    hostId = "h1",
                    hostName = "studio",
                    deviceId = "d1",
                    deviceToken = "t1",
                ),
                selectedSessionId = "s1",
            ),
        )
        val coordinator = SettingsCoordinator(
            store = FlowStateStore(state),
            // Unconfined, and every fake answers without suspending: a call has
            // finished by the time it returns.
            scope = CoroutineScope(Dispatchers.Unconfined),
            note = { notes += it },
            describe = { error -> "described:${error.message}" },
            epoch = { this.epoch },
            link = { if (connected) link else null },
        )

        val ui get() = state.value
    }

    private class FakeLink : SettingsLink {
        val calls = mutableListOf<String>()
        var catalog = ModelCatalog()
        var permissions = PermissionCatalog()
        var modelFailure: Throwable? = null
        var permissionFailure: Throwable? = null
        var modelAnswer = SelectModelResult("s1", ModelSelection("deepseek", "chat"))
        var statsAnswer = SessionStatsResult(sessionId = "s1", stats = SessionStatsFrame())
        var lastModelPayload: JsonObject? = null
        var lastPermissionPayload: JsonObject? = null
        var newSessionPayload: JsonObject? = null

        /** Runs while the host is "thinking", which is where a socket can be replaced. */
        var whileAnswering: (() -> Unit)? = null

        override suspend fun modelCatalog(): ModelCatalog {
            calls += "model.catalog"
            return catalog
        }

        override suspend fun permissionCatalog(): PermissionCatalog {
            calls += "permissions.catalog"
            return permissions
        }

        override suspend fun setModel(payload: JsonObject): SelectModelResult {
            calls += "session.model.set"
            lastModelPayload = payload
            modelFailure?.let { throw it }
            whileAnswering?.invoke()
            return modelAnswer
        }

        override suspend fun setPermission(payload: JsonObject): SetPermissionResult {
            calls += "session.permission.set"
            lastPermissionPayload = payload
            permissionFailure?.let { throw it }
            return SetPermissionResult()
        }

        override suspend fun stats(sessionId: String): SessionStatsResult {
            calls += "session.stats"
            return statsAnswer
        }

        override suspend fun setNewSessionModel(payload: JsonObject): JsonObject {
            calls += "new-session-model"
            newSessionPayload = payload
            return JsonObject(emptyMap())
        }
    }

    @Test
    fun `choosing a model shows it, then takes the host's answer as the truth`() {
        val f = Fixture()
        // The host answers with a different level than the phone asked for.
        f.link.modelAnswer = SelectModelResult("s1", ModelSelection("deepseek", "chat", "low"))

        f.coordinator.setModel(ModelSelection("deepseek", "chat", "high"))

        assertEquals(
            "the transient choice is cleared on the answer",
            emptyMap<String, ModelSelection>(),
            f.ui.modelChoice,
        )
        assertEquals("chat", f.ui.stats?.model?.next?.model)
        assertEquals(
            "the host's own level wins over the optimistic one",
            "low",
            f.ui.stats?.model?.next?.reasoningEffort,
        )
        assertTrue("the call must carry the choice", f.link.lastModelPayload.toString().contains("chat"))
        assertTrue(f.notes.any { it.startsWith("model set:") })
        // The stats readback is what lets the host's own value be the one shown.
        assertTrue("session.stats" in f.link.calls)
    }

    @Test
    fun `a refused model clears the choice and says why`() {
        val f = Fixture()
        f.link.modelFailure = IllegalStateException("no such model")

        f.coordinator.setModel(ModelSelection("deepseek", "chat"))

        assertEquals(emptyMap<String, ModelSelection>(), f.ui.modelChoice)
        assertEquals("described:no such model", f.ui.problem)
        assertNull("nothing may be applied", f.ui.stats?.model)
        assertTrue(f.notes.any { it.startsWith("model set failed:") })
    }

    @Test
    fun `an answer from a replaced socket is dropped`() {
        val f = Fixture(startEpoch = 1)
        // The socket is replaced while the host is answering: the reply belongs to
        // a host that is no longer the one on screen.
        f.link.whileAnswering = { f.epoch = 2 }

        f.coordinator.setModel(ModelSelection("deepseek", "chat"))

        assertNull("another host's model must not land here", f.ui.stats?.model)
        assertTrue("no confirmation is announced", f.notes.none { it.startsWith("model set:") })
    }

    @Test
    fun `the preset the host reports is the one the screen shows`() {
        val f = Fixture()
        f.link.statsAnswer = SessionStatsResult(
            sessionId = "s1",
            stats = SessionStatsFrame(permissionPreset = "auto"),
        )

        f.coordinator.setPermission("auto")

        assertEquals("auto", f.ui.stats?.permissionPreset)
        assertNull(f.ui.problem)
        assertTrue(f.link.lastPermissionPayload.toString().contains("auto"))
    }

    @Test
    fun `a host that accepts the command without changing the preset is called out`() {
        val f = Fixture()
        f.link.statsAnswer = SessionStatsResult(
            sessionId = "s1",
            stats = SessionStatsFrame(permissionPreset = "read-only"),
        )

        f.coordinator.setPermission("auto")

        assertEquals("read-only", f.ui.stats?.permissionPreset)
        assertEquals("the host did not confirm the new permission", f.ui.problem)
    }

    @Test
    fun `a failed permission change says why`() {
        val f = Fixture()
        f.link.permissionFailure = IllegalStateException("denied")

        f.coordinator.setPermission("auto")

        assertEquals("described:denied", f.ui.problem)
    }

    @Test
    fun `catalogs are read once, and again when forced`() {
        val f = Fixture()

        f.coordinator.loadCatalogs()
        assertEquals(listOf("model.catalog", "permissions.catalog"), f.link.calls)

        f.coordinator.loadCatalogs()
        assertEquals("a cached catalog is not asked for again", 2, f.link.calls.size)

        f.coordinator.loadCatalogs(force = true)
        assertEquals(4, f.link.calls.size)
    }

    @Test
    fun `a stats frame merges instead of replacing`() {
        val f = Fixture()
        f.coordinator.applyStats(
            SessionStatsFrame(model = ModelSelectionPair(next = ModelSelection("deepseek", "chat"))),
        )

        f.coordinator.applyStats(SessionStatsFrame(context = ContextPressure(percent = 35)))

        assertEquals("chat", f.ui.stats?.model?.next?.model)
        assertEquals(35, f.ui.stats?.context?.percent)
    }

    @Test
    fun `the home model is applied to the session that just started`() {
        val f = Fixture()
        f.coordinator.setHomeModel(ModelSelection("deepseek", "reasoner", "max"))

        runBlocking { f.coordinator.applyToNewSession(f.link, "s2") }

        val payload = f.link.newSessionPayload ?: error("no call")
        assertEquals(JsonPrimitive("s2"), payload["sessionId"])
        assertEquals(JsonPrimitive("reasoner"), payload["model"])
        assertEquals(JsonPrimitive("max"), payload["reasoningEffort"])
        assertNotNull("the new session shows the choice until its stats arrive", f.ui.modelChoice["s2"])
    }

    @Test
    fun `nothing is applied to a new session when the home screen chose nothing`() {
        val f = Fixture()

        runBlocking { f.coordinator.applyToNewSession(f.link, "s2") }

        assertTrue(f.link.calls.isEmpty())
    }

    /**
     * A choice made with no connection is dropped, not kept.
     *
     * The entry is written before the call so the pill answers the tap at once, and it
     * is removed by one of the two answers. With no connection there is no answer, so
     * the entry stayed for ever: the pill named a model that was not running, and
     * nothing in the app would ever correct it.
     */
    @Test
    fun `a model chosen with no connection does not stay on the pill`() {
        val f = Fixture()
        f.connected = false

        f.coordinator.setModel(ModelSelection("deepseek", "chat", "high"))

        assertNull("the host was never told, so nothing may claim it", f.ui.modelChoice["s1"])
        assertTrue("and the skip is in the log: ${f.notes}", f.notes.any { it.contains("no connection") })
    }
}
