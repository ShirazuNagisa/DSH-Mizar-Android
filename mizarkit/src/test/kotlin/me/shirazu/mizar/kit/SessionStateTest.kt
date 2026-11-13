package me.shirazu.mizar.kit

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The generated session-state table, pinned against the generated fixture.
 *
 * `SessionState.kt` and `Android/protocol/fixtures/session-state.json` are both written
 * by `node tools/gen-states.mjs` from `protocol/states.json`, which also writes the
 * Rust and Swift sides. This test is what makes "same source of truth" true on the
 * Android side: the enum and the fixture are compared field by field, so editing one
 * generated file by hand -- or forgetting to regenerate after a states.json change --
 * fails here rather than on a device.
 */
class SessionStateTest {

    /** The generated fixture, read from the repository like the golden frames. */
    private fun statesFixture(): JsonObject {
        val file = File(fixturesDirectory(), "session-state.json")
        assertTrue(file.isFile, "the generated state fixture is missing at ${file.path}")
        return WIRE_JSON.parseToJsonElement(file.readText()) as JsonObject
    }

    @Test
    fun `the fixture and the enum carry the same table`() {
        val states = statesFixture()["states"] as JsonArray
        assertEquals(
            SessionState.ALL.size,
            states.size,
            "session-state.json and SessionState.ALL disagree on how many states exist",
        )

        for (element in states) {
            val row = element as JsonObject
            val code = row["code"].intValue() ?: throw AssertionError("a state row has no numeric code")
            val id = row["id"].stringValue() ?: throw AssertionError("a state row has no id")
            val state = SessionState.fromCode(code)

            assertEquals(SessionState.fromCode(code), state)
            assertEquals(code, state.code, "$id has another code in the enum")
            // The id is the stable spelling; the enum constant is its uppercase form.
            assertEquals(id.uppercase(), state.name, "$id has another enum constant")
            assertEquals(row["text"].stringValue(), state.text, "$id has another Chinese text")
            assertEquals(row["english"].stringValue(), state.english, "$id has another English text")
            assertEquals(row["tone"].stringValue()?.uppercase(), state.tone.name, "$id has another tone")
            assertEquals(row["kind"].stringValue()?.uppercase(), state.kind.name, "$id has another kind")
            assertEquals(row["carriesText"].boolValue(), state.carriesText, "$id carries text differently")
        }
    }

    @Test
    fun `codes are contiguous from zero and unique`() {
        val codes = SessionState.ALL.map { it.code }
        assertEquals((0 until SessionState.ALL.size).toList(), codes)
        assertFalse(SessionState.UNKNOWN in SessionState.ALL)
        assertEquals(255, SessionState.UNKNOWN.code)
    }

    @Test
    fun `an unknown code never breaks the conversation`() {
        // Any newer host may name a state this build predates. It draws as nothing
        // special; it is never an error the UI has to handle.
        for (code in listOf(16, 99, 200, 255, -1)) {
            assertEquals(SessionState.UNKNOWN, SessionState.fromCode(code))
            assertNull(SessionState.fromCodeOrNull(code))
        }
    }

    @Test
    fun `every known code round-trips and reads as something`() {
        for (state in SessionState.ALL) {
            assertEquals(state, SessionState.fromCode(state.code))
            assertTrue(state.text.isNotEmpty(), "${state.name} has no Chinese text")
            assertTrue(state.english.isNotEmpty(), "${state.name} has no English text")
            assertTrue(state.detail.isNotEmpty(), "${state.name} has no detail")
        }
        assertEquals("", SessionState.UNKNOWN.text)
        assertEquals("", SessionState.UNKNOWN.detail)
        assertEquals("Unknown", SessionState.UNKNOWN.english)
    }

    @Test
    fun `transport kinds are explicit and consistent`() {
        for (state in SessionState.ALL) {
            when (state.kind) {
                SessionState.Kind.TERMINAL -> assertFalse(state.needsHeartbeat, "${state.name} terminal but wants heartbeats")
                else -> assertTrue(state.needsHeartbeat, "${state.name} interval but wants no heartbeats")
            }
            if (state.carriesText) {
                assertEquals(SessionState.Kind.STREAM, state.kind, "${state.name} carries text but is not a stream")
            }
            assertEquals(state.tone == SessionState.Tone.ACTIVE, state.isBusy, "${state.name} isBusy disagrees with tone")
        }
    }

    @Test
    @DisplayName("the states the UI branches on are the ones it expects")
    fun `the states the UI branches on are the ones it expects`() {
        // These four are named directly by the reducer and the conversation screen;
        // pinning them makes a states.json renumbering a test failure, not a silent
        // behavioural change.
        assertEquals(SessionState.IDLE, SessionState.fromCode(0))
        assertEquals(SessionState.STREAM_NARRATION, SessionState.fromCode(5))
        assertEquals(SessionState.STREAM_FINAL, SessionState.fromCode(6))
        assertEquals(SessionState.COMPLETED, SessionState.fromCode(11))
        assertTrue(SessionState.STREAM_NARRATION.carriesText)
        assertTrue(SessionState.STREAM_FINAL.carriesText)
        assertNotNull(SessionState.AWAITING_APPROVAL.tone)
        assertEquals(SessionState.Tone.WAITING, SessionState.AWAITING_USER.tone)
    }
}
