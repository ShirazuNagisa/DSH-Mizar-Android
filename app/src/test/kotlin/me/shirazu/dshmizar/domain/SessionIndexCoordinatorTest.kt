package me.shirazu.dshmizar.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import me.shirazu.dshmizar.UiState
import me.shirazu.mizar.kit.PairedHost
import me.shirazu.mizar.kit.SessionIndex
import me.shirazu.mizar.kit.SessionIndexStore
import me.shirazu.mizar.kit.SessionRow
import me.shirazu.mizar.kit.SessionStatsFrame
import me.shirazu.mizar.kit.SessionStatsResult
import me.shirazu.mizar.kit.WorkspaceRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session list: what survives a restart, and what it costs to learn a name.
 *
 * Two failures matter here and neither throws. A stored index that is *applied* rather
 * than *added to* makes the list flicker from full to empty and back on every launch;
 * a title that is re-asked for every scroll turn costs one round trip per row per
 * second on a tunnel. Both look like "the list is a bit slow", which is why they
 * survive a manual test.
 */
class SessionIndexCoordinatorTest {

    private class Fixture(
        hostId: String? = "h1",
        selected: String? = null,
    ) {
        val server = FakeLink()
        val saved = mutableListOf<SessionIndex>()
        val opened = mutableListOf<String>()
        val notes = mutableListOf<String>()
        var stored: SessionIndex? = null
        var lastSeen: String? = null

        private val job = Job()
        val state = MutableStateFlow(
            UiState(
                selectedSessionId = selected,
                host = hostId?.let {
                    PairedHost(hostId = it, hostName = "Mac", deviceId = "d1", deviceToken = "t1")
                },
            ),
        )
        val coordinator = SessionIndexCoordinator(
            store = FlowStateStore(state),
            scope = CoroutineScope(Dispatchers.Unconfined + job),
            note = { notes += it },
            indexStore = FakeStore(),
            link = { server },
            lastSession = { lastSeen },
            openSession = { opened += it },
        )

        val ui get() = state.value

        fun settle() = runBlocking { job.children.toList().forEach { it.join() } }

        private inner class FakeStore : SessionIndexStore {
            override suspend fun load(hostId: String): SessionIndex? = stored

            override suspend fun save(index: SessionIndex) {
                saved += index
            }

            override suspend fun remove(hostId: String) = Unit

            override suspend fun clear() = Unit
        }
    }

    private class FakeLink : TitlesLink {
        val asked = mutableListOf<String>()
        var titles: Map<String, String> = emptyMap()

        override suspend fun stats(payload: JsonObject): SessionStatsResult {
            val id = payload["sessionId"].toString().trim('"')
            asked += id
            return SessionStatsResult(sessionId = id, stats = SessionStatsFrame(title = titles[id]))
        }
    }

    private companion object {

        fun row(
            id: String,
            cwd: String? = "/work/app",
            updated: Double = 1_000.0,
            blank: Boolean = false,
        ): SessionRow = SessionRow(
            sessionId = id,
            updatedAt = updated,
            running = false,
            blank = blank,
            agentAvailable = true,
            cwd = cwd,
        )

        fun workspace(path: String, title: String): WorkspaceRow =
            WorkspaceRow(workspaceId = "w-$path", path = path, title = title)
    }

    @Test
    fun `the stored index fills a screen that has nothing yet`() {
        val f = Fixture()
        f.stored = SessionIndex(hostId = "h1", sessions = listOf(row("a")), workspacePaths = listOf("/work/app"))

        runBlocking { f.coordinator.restore("h1") }

        assertEquals(listOf("a"), f.ui.sessions.map { it.sessionId })
        assertEquals(setOf("/work/app"), f.ui.workspacePaths)
    }

    @Test
    fun `the stored index never replaces what a live read already put up`() {
        val f = Fixture()
        f.state.value = f.ui.copy(sessions = listOf(row("live")), workspacePaths = setOf("/work/live"))
        f.stored = SessionIndex(hostId = "h1", sessions = listOf(row("stale")), workspacePaths = listOf("/work/old"))

        runBlocking { f.coordinator.restore("h1") }

        assertEquals("the list on screen wins", listOf("live"), f.ui.sessions.map { it.sessionId })
        assertEquals("and so does the workspace set it was read with", setOf("/work/live"), f.ui.workspacePaths)
    }

    @Test
    fun `a title learned on this phone is not overwritten by the stored one`() {
        val f = Fixture()
        f.state.value = f.ui.copy(titles = mapOf("a" to "learned now"))
        f.stored = SessionIndex(hostId = "h1", titles = mapOf("a" to "from disk", "b" to "also on disk"))

        runBlocking { f.coordinator.restore("h1") }

        assertEquals("learned now", f.ui.titles["a"])
        assertEquals("a stored title still fills a gap", "also on disk", f.ui.titles["b"])
    }

    @Test
    fun `a session outside the known workspaces is hidden`() {
        val f = Fixture()
        f.state.value = f.ui.copy(
            sessions = listOf(row("in", cwd = "/work/app"), row("out", cwd = "/work/deleted")),
            workspacePaths = setOf("/work/app"),
        )

        f.coordinator.regroup()

        assertEquals(listOf("in"), f.ui.groups.flatMap { it.sessions }.map { it.sessionId })
    }

    @Test
    fun `the session that is open stays visible even when it is blank`() {
        val f = Fixture(selected = "fresh")
        f.state.value = f.ui.copy(
            sessions = listOf(row("fresh", blank = true)),
            workspacePaths = setOf("/work/app"),
        )

        f.coordinator.regroup()

        assertEquals(
            "a just-created session would otherwise vanish on creation",
            listOf("fresh"),
            f.ui.groups.flatMap { it.sessions }.map { it.sessionId },
        )
    }

    @Test
    fun `a workspace's own name becomes the group's name`() {
        val f = Fixture()
        f.state.value = f.ui.copy(
            sessions = listOf(row("a", cwd = "/work/app")),
            workspacePaths = setOf("/work/app"),
            workspaces = listOf(workspace("/work/app", "Quarterly reports")),
        )

        f.coordinator.regroup()

        assertEquals("Quarterly reports", f.ui.groups.single().title)
    }

    @Test
    fun `a blank title is not stored`() {
        val f = Fixture()
        f.state.value = f.ui.copy(sessions = listOf(row("a")))
        f.server.titles = mapOf("a" to "   ")

        f.coordinator.loadTitles(listOf("a"))
        f.settle()

        assertNull("an untitled session has no title, not an empty one", f.ui.titles["a"])
        assertTrue("and nothing worth writing to disk", f.saved.isEmpty())
    }

    @Test
    fun `a row that already has a title is not asked again`() {
        val f = Fixture()
        f.state.value = f.ui.copy(titles = mapOf("a" to "already known"))

        f.coordinator.loadTitles(listOf("a"))
        f.settle()

        assertEquals("every title is a round trip; a known one is not worth one", emptyList<String>(), f.server.asked)
    }

    @Test
    fun `learning a title writes the index`() {
        val f = Fixture()
        f.state.value = f.ui.copy(sessions = listOf(row("a")))
        f.server.titles = mapOf("a" to "Refactor the relay")

        f.coordinator.loadTitles(listOf("a"))
        f.settle()

        assertEquals("Refactor the relay", f.ui.titles["a"])
        assertEquals("Refactor the relay", f.saved.single().titles["a"])
    }

    @Test
    fun `warming reads the newest rows first`() {
        val f = Fixture()
        f.state.value = f.ui.copy(
            sessions = (1..10).map { row("s$it", updated = it * 1_000.0) },
        )

        runBlocking { f.coordinator.warmTitles() }

        assertEquals("s10", f.server.asked.first())
        assertEquals(10, f.server.asked.size)
    }

    @Test
    fun `warming stops at the bound`() {
        val f = Fixture()
        f.state.value = f.ui.copy(
            sessions = (1..200).map { row("s$it", updated = it * 1_000.0) },
        )

        runBlocking { f.coordinator.warmTitles() }

        assertEquals("a host with two hundred sessions must not hold the shell", 80, f.server.asked.size)
    }

    @Test
    fun `warming reports its progress`() {
        val f = Fixture()
        f.state.value = f.ui.copy(sessions = (1..5).map { row("s$it", updated = it * 1_000.0) })

        runBlocking { f.coordinator.warmTitles() }

        assertEquals(5, f.ui.syncProgress?.done)
        assertEquals(5, f.ui.syncProgress?.total)
    }

    @Test
    fun `warming nothing writes nothing`() {
        val f = Fixture()
        f.state.value = f.ui.copy(sessions = listOf(row("a")), titles = mapOf("a" to "known"))

        runBlocking { f.coordinator.warmTitles() }

        assertEquals(0, f.server.asked.size)
        assertTrue(f.saved.isEmpty())
    }

    @Test
    fun `the last session is opened once`() {
        val f = Fixture()
        f.state.value = f.ui.copy(sessions = listOf(row("a"), row("b")))
        f.lastSeen = "b"

        f.coordinator.restoreLastSession()
        f.coordinator.restoreLastSession()

        assertEquals("returning to the list means they want the list", listOf("b"), f.opened)
    }

    @Test
    fun `nothing is opened when a session is already open`() {
        val f = Fixture(selected = "a")
        f.state.value = f.ui.copy(sessions = listOf(row("a"), row("b")))
        f.lastSeen = "b"

        f.coordinator.restoreLastSession()

        assertEquals(emptyList<String>(), f.opened)
    }

    @Test
    fun `nothing is opened when the remembered session is gone`() {
        val f = Fixture()
        f.state.value = f.ui.copy(sessions = listOf(row("a")))
        f.lastSeen = "archived-on-the-computer"

        f.coordinator.restoreLastSession()

        assertEquals(emptyList<String>(), f.opened)
    }

    @Test
    fun `persist writes what the list knows`() {
        val f = Fixture()
        f.state.value = f.ui.copy(
            sessions = listOf(row("a")),
            titles = mapOf("a" to "a name"),
            workspacePaths = setOf("/work/app"),
        )

        runBlocking { f.coordinator.persist() }

        val written = f.saved.single()
        assertEquals("h1", written.hostId)
        assertEquals(listOf("a"), written.sessions.map { it.sessionId })
        assertEquals(mapOf("a" to "a name"), written.titles)
        assertEquals(listOf("/work/app"), written.workspacePaths)
    }

    @Test
    fun `persist does nothing without a host`() {
        val f = Fixture(hostId = null)
        f.state.value = f.ui.copy(sessions = listOf(row("a")))

        runBlocking { f.coordinator.persist() }

        assertTrue("an index belongs to a computer, and there is none", f.saved.isEmpty())
    }

    @Test
    fun `regrouping keeps the groups consistent with the rows`() {
        val f = Fixture()
        f.state.value = f.ui.copy(
            sessions = listOf(row("a", cwd = "/work/app"), row("b", cwd = "/work/other")),
            workspacePaths = setOf("/work/app", "/work/other"),
        )

        f.coordinator.regroup()

        assertEquals(2, f.ui.groups.size)
        assertFalse("every group carries its rows", f.ui.groups.any { it.sessions.isEmpty() })
    }
}
