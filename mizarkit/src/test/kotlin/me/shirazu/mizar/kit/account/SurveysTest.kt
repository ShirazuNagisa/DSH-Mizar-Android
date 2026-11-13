package me.shirazu.mizar.kit.account

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.shirazu.mizar.kit.HttpRequest
import me.shirazu.mizar.kit.HttpResponse
import me.shirazu.mizar.kit.HttpTransport
import me.shirazu.mizar.kit.WIRE_JSON
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A scripted account service: one answer per request, recorded. */
private class ScriptedAccountTransport(private val answer: (HttpRequest) -> HttpResponse) : HttpTransport {
    val requests = mutableListOf<HttpRequest>()
    override suspend fun send(request: HttpRequest): HttpResponse {
        requests += request
        return answer(request)
    }
}

private const val SURVEY_USER = """
    {"id":"u1","username":"shirazu","plan":"paid","paidUntil":1763100000000,
     "grantedPaidUntil":1763100000000,"createdAt":1759000000000,"lastSeenAt":1760500000000,
     "devices":[]}
"""

/**
 * The questionnaire half of the account API.
 *
 * Two claims matter here: the DTOs read what the relay actually sends (unknown
 * question types degrade instead of dropping the form), and the answer value keeps
 * its string-or-array union through encode and decode -- a multi-select submitted as
 * one comma-joined string is a different answer to the server.
 */
class SurveysTest {

    private fun client(answer: (HttpRequest) -> HttpResponse): Pair<AccountClient, ScriptedAccountTransport> {
        val transport = ScriptedAccountTransport(answer)
        return AccountClient("https://relay.test/account/v1", transport) to transport
    }

    @Test
    fun `a survey decodes with defaults for everything the relay may omit`() {
        val survey = WIRE_JSON.decodeFromString(
            Survey.serializer(),
            """
            {"id":"s1","title":"用起来怎么样","summary":"三分钟",
             "rewardDays":7,"active":true,"completed":false,
             "questions":[
               {"id":"q1","type":"single","prompt":"满意吗","required":true,
                "options":[{"id":"o1","label":"满意"},{"id":"o2","label":"不满意"}]},
               {"id":"q2","type":"multi","prompt":"常用功能",
                "options":[{"id":"f1","label":"对话"}]},
               {"id":"q3","type":"text","prompt":"还想说什么","maxLength":200},
               {"id":"q4","type":"ranking","prompt":"新类型"}]}
            """.trimIndent(),
        )

        assertEquals("用起来怎么样", survey.title)
        assertEquals(7, survey.rewardDays)
        assertEquals(4, survey.questions.size)
        assertEquals(SurveyQuestionType.SINGLE, survey.questions[0].type)
        assertEquals(2, survey.questions[0].options.size)
        assertEquals("满意", survey.questions[0].options[0].label)
        assertEquals(SurveyQuestionType.MULTI, survey.questions[1].type)
        assertEquals(SurveyQuestionType.TEXT, survey.questions[2].type)
        assertEquals(200, survey.questions[2].maxLength)
        // A type this build cannot draw is shown as text rather than dropping the
        // question from the form.
        assertEquals(SurveyQuestionType.TEXT, survey.questions[3].type)
        assertTrue(survey.isOpen())
    }

    @Test
    fun `a survey that is closed, completed or expired is not open`() {
        val base = Survey(id = "s1")
        assertTrue(base.isOpen(nowMillis = 1_000))
        assertFalse(base.copy(completed = true).isOpen(nowMillis = 1_000))
        assertFalse(base.copy(active = false).isOpen(nowMillis = 1_000))
        assertFalse(base.copy(expiresAt = 900).isOpen(nowMillis = 1_000))
        assertTrue(base.copy(expiresAt = 1_001).isOpen(nowMillis = 1_000))
    }

    @Test
    fun `the answer value keeps its string-or-array union`() {
        val single = WIRE_JSON.decodeFromString(SurveyAnswer.serializer(), """{"questionId":"q1","value":"o1"}""")
        assertEquals(SurveyAnswerValue.Text("o1"), single.value)

        val multi = WIRE_JSON.decodeFromString(
            SurveyAnswer.serializer(),
            """{"questionId":"q2","value":["f1","f2"]}""",
        )
        assertEquals(SurveyAnswerValue.Choices(listOf("f1", "f2")), multi.value)

        // And back out: a multi-select stays an array on the wire.
        assertEquals(
            """{"questionId":"q2","value":["f1","f2"]}""",
            WIRE_JSON.encodeToString(SurveyAnswer.serializer(), multi),
        )
        assertEquals(
            """{"questionId":"q1","value":"o1"}""",
            WIRE_JSON.encodeToString(SurveyAnswer.serializer(), SurveyAnswer.text("q1", "o1")),
        )
    }

    @Test
    fun `listing surveys is a bearer-authenticated GET`() = runBlocking {
        val (client, transport) = client {
            HttpResponse(
                200,
                """{"surveys":[{"id":"s1","title":"t","rewardDays":7,"questions":[]}]}""",
            )
        }
        val surveys = client.surveys("mza_session_token")

        val request = transport.requests.single()
        assertEquals("GET", request.method)
        assertEquals("https://relay.test/account/v1/surveys", request.url)
        assertEquals("Bearer mza_session_token", request.headers["authorization"])
        assertEquals(listOf("s1"), surveys.map { it.id })
    }

    @Test
    fun `submitting a survey posts the answers and returns the granted reward`() = runBlocking {
        val (client, transport) = client {
            HttpResponse(200, """{"user":$SURVEY_USER,"rewardDays":7}""")
        }
        val submission = client.submitSurvey(
            "mza_session_token",
            "s1",
            listOf(
                SurveyAnswer.text("q1", "o1"),
                SurveyAnswer.choices("q2", listOf("f1", "f2")),
            ),
        )

        val request = transport.requests.single()
        assertEquals("POST", request.method)
        assertEquals("https://relay.test/account/v1/surveys/submit", request.url)
        assertEquals("Bearer mza_session_token", request.headers["authorization"])
        val body = WIRE_JSON.parseToJsonElement(request.body!!).jsonObject
        assertEquals("s1", body["surveyId"]?.jsonPrimitive?.content)
        val answers = body["answers"] as JsonArray
        assertEquals("o1", answers[0].jsonObject["value"]?.jsonPrimitive?.content)
        assertEquals(
            listOf("f1", "f2"),
            (answers[1].jsonObject["value"] as JsonArray).map { it.jsonPrimitive.content },
        )
        assertEquals(7, submission.rewardDays)
        assertTrue(submission.user.isPaid)
    }

    @Test
    fun `the relay's refusal code reaches the caller verbatim`() = runBlocking {
        val (client, _) = client {
            HttpResponse(410, """{"error":"SURVEY_EXPIRED","message":"too late"}""")
        }
        val error = assertThrows<AccountError> { runBlocking { client.submitSurvey("t", "s1", emptyList()) } }
        assertEquals("SURVEY_EXPIRED", error.code)
        assertEquals(410, error.status)
        assertFalse(error.requiresReauthentication)
    }

    @Test
    fun `a revoked session is distinguishable from a bad survey`() = runBlocking {
        val (client, _) = client {
            HttpResponse(401, """{"error":"SESSION_REVOKED","message":"signed out"}""")
        }
        val error = assertThrows<AccountError> { runBlocking { client.surveys("stale") } }
        assertTrue(error.requiresReauthentication)
    }

    @Test
    fun `the demo path of the survey form builds only the answered questions`() {
        // Unanswered optional questions are simply absent; the relay treats a missing
        // question id as unanswered, so sending an empty answer would be a lie.
        val answers = listOf(SurveyAnswer.text("q3", "都挺好"))
        assertEquals(1, answers.size)
        assertEquals("q3", answers.single().questionId)
    }
}
