package me.shirazu.mizar.kit

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rule this replaced cost two real notifications: a per-session flag that nothing
 * cleared between turns, so a later completion arrived as "already announced" on a
 * phone that had heard nothing. These tests pin the property that made it fail — that
 * the memory must expire — rather than the plumbing around it.
 */
class CompletionAnnouncerTest {

    @Test
    fun `a session that was never announced is announced`() {
        val announcer = CompletionAnnouncer(windowMillis = 5_000)
        assertFalse(announcer.announcedRecently("s", now = 1_000))
    }

    @Test
    fun `a notice posted moments ago is the same turn`() {
        val announcer = CompletionAnnouncer(windowMillis = 5_000)
        announcer.record("s", now = 1_000)
        // The event and the poll land milliseconds apart; the second one is a duplicate.
        assertTrue(announcer.announcedRecently("s", now = 1_003))
    }

    @Test
    fun `the memory expires, so the next turn is announced`() {
        val announcer = CompletionAnnouncer(windowMillis = 5_000)
        announcer.record("s", now = 1_000)
        // The next turn ends later. A flag would still be set here -- that is the bug.
        assertFalse(announcer.announcedRecently("s", now = 1_000 + 5_000))
        assertFalse(announcer.announcedRecently("s", now = 60_000))
    }

    @Test
    fun `sessions do not share the memory`() {
        val announcer = CompletionAnnouncer(windowMillis = 5_000)
        announcer.record("a", now = 1_000)
        assertFalse(announcer.announcedRecently("b", now = 1_001))
    }

    @Test
    fun `seeing a session running again forgets it`() {
        val announcer = CompletionAnnouncer(windowMillis = 5_000)
        announcer.record("s", now = 1_000)
        announcer.forget("s")
        assertFalse(announcer.announcedRecently("s", now = 1_001))
    }

    @Test
    fun `recording again restarts the window`() {
        val announcer = CompletionAnnouncer(windowMillis = 5_000)
        announcer.record("s", now = 1_000)
        announcer.record("s", now = 4_000)
        assertTrue(announcer.announcedRecently("s", now = 8_000))
        assertFalse(announcer.announcedRecently("s", now = 9_000))
    }
}
