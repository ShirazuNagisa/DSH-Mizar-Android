package me.shirazu.mizar.kit

import kotlinx.serialization.json.decodeFromJsonElement
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `ConversationTurn.endSeq`: the log position of the `turn/end` that closed a turn.
 *
 * `session.fork` branches after a finished turn, so the phone needs one exact log
 * boundary: the end record's `seq`. It is recorded from the same field `turn/start`
 * uses for [ConversationTurn.startSeq], survives both merge paths (a refresh and a
 * continuation), and is `null` when there is no boundary -- a caller that has no
 * end must not guess one.
 */
class ConversationEndSeqTest {

    private fun page(json: String): SessionMessagesResult =
        WIRE_JSON.decodeFromJsonElement(WIRE_JSON.parseToJsonElement(json.trimIndent()))

    @Test
    fun `a turn records the log position of its end`() {
        val turns = Conversation.turns(
            page(
                """
                {"sessionId":"s","asOfSeq":3,"records":[
                  {"type":"event","event":{"type":"turn/start","seq":1,"time":1000,"data":{"turn":1}}},
                  {"type":"event","event":{"type":"assistant/message","seq":2,"time":1100,
                    "data":{"turn":1,"step":1,"message":{"role":"assistant","content":[{"type":"text","text":"Done."}]}}}},
                  {"type":"event","event":{"type":"turn/end","seq":7,"time":1200,
                    "data":{"turn":1,"reason":{"kind":"completed"}}}}
                ]}
                """,
            ),
        )

        val turn = turns.single()
        assertEquals(1, turn.startSeq)
        assertEquals(7, turn.endSeq)
    }

    @Test
    fun `a running turn has no end position`() {
        val turns = Conversation.turns(
            page(
                """
                {"sessionId":"s","asOfSeq":1,"records":[
                  {"type":"event","event":{"type":"turn/start","seq":1,"time":1000,"data":{"turn":1}}}
                ]}
                """,
            ),
        )
        assertEquals(1, turns.single().startSeq)
        assertNull(turns.single().endSeq)
    }

    @Test
    fun `merging keeps the end position whichever copy carries it`() {
        val prompt = ConversationItem.Message("p1", ConversationItem.Role.USER, "hi", 900)
        val withEnd = ConversationTurn(
            turn = 1,
            prompts = listOf(prompt),
            steps = emptyList(),
            startedAtMillis = 1000,
            endedAtMillis = 1200,
            endedReason = "completed",
            endSeq = 7,
        )
        val withoutEnd = withEnd.copy(endSeq = null)

        assertEquals(7, withoutEnd.merging(withEnd).endSeq)
        assertEquals(7, withEnd.merging(withoutEnd).endSeq)
        assertNull(withoutEnd.merging(withoutEnd).endSeq)
    }

    @Test
    fun `absorbing a continuation keeps the end position`() {
        val prompt = ConversationItem.Message("p1", ConversationItem.Role.USER, "hi", 900)
        val opened = ConversationTurn(
            turn = 1,
            prompts = listOf(prompt),
            steps = emptyList(),
            startedAtMillis = 1000,
            endedAtMillis = null,
            endedReason = null,
        )
        val closed = opened.copy(endedAtMillis = 1300, endedReason = "completed", endSeq = 9)
        assertEquals(9, opened.absorbing(closed).endSeq)
    }
}
