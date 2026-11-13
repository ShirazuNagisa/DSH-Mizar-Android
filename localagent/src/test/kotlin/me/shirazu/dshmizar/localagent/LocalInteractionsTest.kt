package me.shirazu.dshmizar.localagent

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.shirazu.mizar.kit.PendingQuestion
import me.shirazu.mizar.kit.QuestionItem
import me.shirazu.mizar.kit.QuestionOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two wire payloads the automatic interaction policy sends.
 *
 * These are the shapes a kernel upgrade could break, so they get a plain JVM test:
 * `approval.answer` accepts exactly two outcomes, and `question.answer` requires
 * every question in the batch answered exactly once.
 */
class LocalInteractionsTest {

    @Test
    fun theDenyPolicyAnswersRejected() {
        val payload = LocalInteractions.approvalAnswerPayload(LocalInteractionPolicy.AUTO_DENY, "ap-1")
        assertEquals("ap-1", payload["requestId"]!!.jsonPrimitive.content)
        assertEquals("rejected", payload["outcome"]!!.jsonPrimitive.content)
        assertEquals("local-auto", payload["by"]!!.jsonPrimitive.content)
    }

    @Test
    fun theAllowPolicyAnswersAllowedOnce() {
        val payload = LocalInteractions.approvalAnswerPayload(LocalInteractionPolicy.AUTO_ALLOW, "ap-2")
        assertEquals("ap-2", payload["requestId"]!!.jsonPrimitive.content)
        assertEquals("allowed-once", payload["outcome"]!!.jsonPrimitive.content)
    }

    @Test
    fun theWireSpellingsMatchTheKernelsTwoOutcomes() {
        // The kernel rejects anything else with MALFORMED_FRAME; the enum is the
        // single place that spelling lives.
        assertEquals("allowed-once", LocalInteractionPolicy.AUTO_ALLOW.approvalOutcome)
        assertEquals("rejected", LocalInteractionPolicy.AUTO_DENY.approvalOutcome)
    }

    @Test
    fun everyQuestionIsAnsweredExactlyOnceWithItsFirstOption() {
        val batch = PendingQuestion(
            callId = "call-1",
            questions = listOf(
                QuestionItem(
                    id = "q1",
                    question = "Which workspace?",
                    options = listOf(QuestionOption(label = "alpha"), QuestionOption(label = "beta")),
                ),
                QuestionItem(
                    id = "q2",
                    question = "Anything else?",
                    options = emptyList(),
                ),
            ),
        )

        val payload = LocalInteractions.questionAnswerPayload(batch)
        assertEquals("call-1", payload["callId"]!!.jsonPrimitive.content)
        val answers = payload["answers"]!!.jsonArray
        assertEquals("one answer per question", 2, answers.size)
        assertEquals("q1", answers[0].jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals(
            "the placeholder picks the first option",
            "alpha",
            answers[0].jsonObject["selected"]!!.jsonArray[0].jsonPrimitive.content,
        )
        assertEquals("q2", answers[1].jsonObject["id"]!!.jsonPrimitive.content)
        assertTrue(
            "a free-text question has no option to pick",
            answers[1].jsonObject["selected"]!!.jsonArray.isEmpty(),
        )
    }
}
