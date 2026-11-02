package me.shirazu.mizar.kit.replay

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.shirazu.mizar.kit.HttpRequest
import me.shirazu.mizar.kit.HttpResponse
import me.shirazu.mizar.kit.HttpTransport
import java.io.File

/**
 * One HTTP exchange, as it happened.
 *
 * @property method the request method, upper case.
 * @property path the path and query, without the host: a recording made against
 *   `127.0.0.1:19390` has to replay against `10.0.2.2:19390`, which is the same
 *   server seen from two places.
 * @property status the status the host answered.
 * @property body the answer's body, verbatim.
 * @property note why this exchange matters, when the recording is hand-annotated.
 * @property requestBody the body the client sent, when the scenario depends on it.
 *   Optional so older recordings still load; when present, a request with any
 *   other body does not match, which is what makes "the same path, a different
 *   payload" a failure instead of a silent pass.
 */
@Serializable
data class RecordedExchange(
    val method: String,
    val path: String,
    val status: Int,
    val body: String = "",
    val headers: Map<String, String> = emptyMap(),
    val note: String? = null,
    val requestBody: String? = null,
)

/** One request a replay was actually asked for, in the order it arrived. */
data class AskedRequest(
    val method: String,
    val path: String,
    val body: String?,
)

/** A named set of exchanges: one scenario a client can be driven through. */
@Serializable
data class Recording(
    val name: String,
    val recordedAt: String? = null,
    val exchanges: List<RecordedExchange> = emptyList(),
)

/**
 * A transport that answers from a recording, and never touches the network.
 *
 * The point is the pairing and handshake paths: they are the ones that fail in the
 * field and the ones a unit test cannot reach, because they are HTTP rather than
 * protocol frames. A recorded scenario runs in milliseconds, needs no host, and
 * fails loudly -- a missing exchange is an [AssertionError] naming the request,
 * because a replay that silently invents a 500 turns a drifted fixture into a
 * confusing product bug.
 */
class HttpReplay(private val recording: Recording) : HttpTransport {

    /** How many exchanges this replay has served, so a test can assert the path taken. */
    var served: Int = 0
        private set

    /**
     * Every request this replay was asked for, in arrival order.
     *
     * A test that only counts answers cannot tell "asked once, correctly" from
     * "asked twice, once wrong". This is the half the replay used to keep to itself.
     */
    private val asked = mutableListOf<AskedRequest>()

    /** The requests so far, in order. */
    val requests: List<AskedRequest> get() = asked.toList()

    override suspend fun send(request: HttpRequest): HttpResponse {
        val path = pathOf(request.url)
        val method = request.method.uppercase()
        asked += AskedRequest(method, path, request.body)

        val match = recording.exchanges.lastOrNull {
            it.method.equals(method, ignoreCase = true) &&
                it.path == path &&
                // A recording that names a body means it: the same endpoint called
                // with a different payload is a different scenario.
                (it.requestBody == null || it.requestBody == request.body)
        } ?: throw AssertionError(
            "no recorded exchange for $method $path" +
                (request.body?.let { " with body ${it.take(120)}" } ?: "") +
                " in \"${recording.name}\" " +
                "(recorded: ${recording.exchanges.joinToString { "${it.method} ${it.path}" }})",
        )
        served += 1
        return HttpResponse(match.status, match.body, match.headers)
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; prettyPrint = true }

        /** The path and query of a URL, which is what a recording keys on. */
        fun pathOf(url: String): String {
            val withoutScheme = url.substringAfter("://", url)
            val slash = withoutScheme.indexOf('/')
            return if (slash < 0) "/" else withoutScheme.substring(slash)
        }

        /** Read a recording, or throw with the file named. */
        fun load(file: File): HttpReplay {
            require(file.isFile) { "no recording at ${file.absolutePath}" }
            return HttpReplay(JSON.decodeFromString(Recording.serializer(), file.readText()))
        }

        /** Write a recording, creating its directory. */
        fun save(file: File, recording: Recording) {
            file.parentFile?.mkdirs()
            file.writeText(JSON.encodeToString(Recording.serializer(), recording))
        }
    }
}

/**
 * A transport that forwards to the real network and writes down what came back.
 *
 * Wrapping rather than instrumenting the client means the recorded bytes are the
 * bytes the client actually consumed, not a reconstruction: a fixture that
 * disagrees with the decoder fails the same way the app would.
 */
class RecordingTransport(
    private val wrapped: HttpTransport,
    private val exchanges: MutableList<RecordedExchange> = mutableListOf(),
) : HttpTransport {

    override suspend fun send(request: HttpRequest): HttpResponse {
        val response = wrapped.send(request)
        exchanges += RecordedExchange(
            method = request.method.uppercase(),
            path = HttpReplay.pathOf(request.url),
            status = response.status,
            body = response.body,
            headers = response.headers,
            // Written down so the next replay can insist on it: a pairing body
            // carries the code and the device name, and a recording that ignored
            // them would replay happily against a client that stopped sending them.
            requestBody = request.body,
        )
        return response
    }

    /** What has been recorded so far. */
    fun snapshot(name: String, recordedAt: String? = null): Recording =
        Recording(name = name, recordedAt = recordedAt, exchanges = exchanges.toList())
}
