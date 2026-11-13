package me.shirazu.mizar.kit.account

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * The questionnaires the operator dispatches, as the account service returns them.
 *
 * The relay (主力1) is what the phone talks to, because it is the one that knows who
 * is asking and who has already been paid. A survey arrives with the reward it
 * carries and whether this account already finished it, and the submission is a list
 * of `(questionId, value)` pairs in the same order the form drew them.
 *
 * The answer value is the one shape the wire does not make easy: a single choice is
 * a string, a multi-select is an array, and a text answer is a string again.
 * [SurveyAnswerValue] encodes that union rather than making every caller carry an
 * `Any`.
 *
 * DTO shape mirrors `iOS/MizarKit/Sources/MizarKit/AccountSurveys.swift`; every
 * timestamp is epoch milliseconds, like the rest of the account API.
 */

/** What kind of control one question needs. */
@Serializable(with = SurveyQuestionTypeSerializer::class)
enum class SurveyQuestionType(val wire: String) {
    SINGLE("single"),
    MULTI("multi"),
    TEXT("text"),
    ;

    companion object {
        /**
         * Decode a wire value.
         *
         * An unknown type reads as [TEXT]: a question the phone cannot draw as a
         * choice is better shown as a free-form answer than dropped from the form.
         */
        fun of(wire: String): SurveyQuestionType = entries.firstOrNull { it.wire == wire } ?: TEXT
    }
}

/** Lenient `SurveyQuestionType` codec: unknown values become [SurveyQuestionType.TEXT]. */
object SurveyQuestionTypeSerializer : KSerializer<SurveyQuestionType> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("me.shirazu.mizar.kit.account.SurveyQuestionType", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): SurveyQuestionType =
        SurveyQuestionType.of(decoder.decodeString())

    override fun serialize(encoder: Encoder, value: SurveyQuestionType) {
        encoder.encodeString(value.wire)
    }
}

/** One choice in a single- or multi-select question. */
@Serializable
data class SurveyOption(
    val id: String,
    val label: String = "",
)

/** One question. */
@Serializable
data class SurveyQuestion(
    val id: String,
    val type: SurveyQuestionType = SurveyQuestionType.TEXT,
    val prompt: String = "",
    val options: List<SurveyOption> = emptyList(),
    val required: Boolean = false,
    val maxLength: Int? = null,
)

/**
 * One questionnaire, plus this account's state.
 *
 * @property rewardDays how many days of paid access answering it grants.
 * @property active whether the relay still serves it at all.
 * @property expiresAt last moment it can be answered, epoch milliseconds.
 * @property completed whether this account has already been paid for it.
 */
@Serializable
data class Survey(
    val id: String,
    val title: String = "",
    val summary: String = "",
    val rewardDays: Int = 7,
    val questions: List<SurveyQuestion> = emptyList(),
    val active: Boolean = true,
    val expiresAt: Long? = null,
    val completed: Boolean = false,
    val completedAt: Long? = null,
) {
    /** Whether the form can still be filled in. */
    fun isOpen(nowMillis: Long = System.currentTimeMillis()): Boolean =
        active && !completed && (expiresAt == null || expiresAt > nowMillis)
}

/** One answer, in the wire's union shape. */
@Serializable(with = SurveyAnswerValueSerializer::class)
sealed interface SurveyAnswerValue {
    /** A text answer, or the id of the one chosen option. */
    data class Text(val value: String) : SurveyAnswerValue

    /** The chosen option ids of a multi-select question. */
    data class Choices(val value: List<String>) : SurveyAnswerValue
}

/**
 * `SurveyAnswerValue` codec: a JSON string is [SurveyAnswerValue.Text], a JSON array
 * is [SurveyAnswerValue.Choices].
 */
object SurveyAnswerValueSerializer : KSerializer<SurveyAnswerValue> {
    /** The wire shape is a JSON string or array, so the element descriptor is the honest one. */
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun deserialize(decoder: Decoder): SurveyAnswerValue {
        val json = (decoder as JsonDecoder).decodeJsonElement()
        return when (json) {
            is JsonArray -> SurveyAnswerValue.Choices(json.map { (it as JsonPrimitive).content })
            else -> SurveyAnswerValue.Text((json as? JsonPrimitive)?.content ?: "")
        }
    }

    override fun serialize(encoder: Encoder, value: SurveyAnswerValue) {
        when (value) {
            is SurveyAnswerValue.Text -> encoder.encodeString(value.value)
            is SurveyAnswerValue.Choices ->
                encoder.encodeSerializableValue(ListSerializer(String.serializer()), value.value)
        }
    }
}

/** One question's answer. */
@Serializable
data class SurveyAnswer(
    val questionId: String,
    val value: SurveyAnswerValue,
) {
    companion object {
        /** A single-choice or text answer. */
        fun text(questionId: String, value: String): SurveyAnswer =
            SurveyAnswer(questionId, SurveyAnswerValue.Text(value))

        /** A multi-select answer. */
        fun choices(questionId: String, values: List<String>): SurveyAnswer =
            SurveyAnswer(questionId, SurveyAnswerValue.Choices(values))
    }
}

/** The answer to a successful submission. */
@Serializable
data class SurveySubmission(
    /** The updated profile: the reward already applied to `plan` / `paidUntil`. */
    val user: AccountUser,
    val rewardDays: Int = 0,
)

/** The `POST /surveys/submit` body. */
@Serializable
internal data class SurveySubmitBody(
    val surveyId: String,
    val answers: List<SurveyAnswer>,
)

/**
 * The questionnaire half of the account API.
 *
 * A separate interface rather than three more methods on [AccountApi]: the sign-in
 * coordinator's test double has no reason to grow methods it never calls, and a
 * caller that only draws surveys should not have to hold the whole account surface.
 */
interface AccountSurveysApi {

    /**
     * The questionnaires this account may fill in.
     *
     * Each one carries whether this account already answered it and the reward it
     * pays. A relay that predates surveys answers 404, which the caller reports as
     * "not available on this account relay" rather than a failure.
     */
    suspend fun surveys(token: String): List<Survey>

    /** Submit one filled-in questionnaire; the answer carries the reward already granted. */
    suspend fun submitSurvey(token: String, surveyId: String, answers: List<SurveyAnswer>): SurveySubmission
}

/** The `GET /surveys` envelope. */
@Serializable
internal data class SurveyListEnvelope(
    val surveys: List<Survey> = emptyList(),
)
