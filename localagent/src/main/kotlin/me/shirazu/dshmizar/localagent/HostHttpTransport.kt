package me.shirazu.dshmizar.localagent

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The kernel's `host.http` surface: one streaming request at a time per handle.
 *
 * The contract is the iOS one (`URLSessionKernelHTTPHandler`): `onChunk*` with
 * decoded UTF-8 text, then **exactly one** of `onDone(status)` / `onError(message)`,
 * and `handle.abort()` cancels. The kernel's SSE parser consumes lines, so chunks
 * are whole lines including the newline, decoded only at a line boundary — a byte
 * split inside a multi-byte character would otherwise corrupt model text.
 *
 * Everything here happens on OkHttp's threads. Chunks do not touch JavaScript
 * directly: they go through [HostEventQueue] and [onWork] posts a drain task to the
 * engine thread, which is the only thread allowed into the QuickJS context.
 */
internal class HostHttpTransport(
    private val client: OkHttpClient,
    private val events: HostEventQueue,
    private val onWork: () -> Unit,
) {
    private val sequence = AtomicInteger(0)
    private val calls = ConcurrentHashMap<String, Call>()
    private val settled = ConcurrentHashMap.newKeySet<String>()

    /**
     * Start one request; returns the handle the JS side stores.
     *
     * The answer is an envelope: `{ok:true,value:"http-N"}` or `{ok:false,error}`.
     * A malformed URL is a `fail` rather than a thrown exception for the same reason
     * iOS returns `onError` there: the kernel checks a URL's shape, not whether it
     * parses, and a bad URL from the model must look like any other request failure.
     */
    fun start(requestJson: String): String {
        val request = try {
            buildRequest(requestJson)
        } catch (error: Exception) {
            return Envelopes.fail("BAD_REQUEST: ${error.message}")
        }
        val handle = "http-${sequence.incrementAndGet()}"
        val call = client.newCall(request)
        calls[handle] = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                finish(handle, status = null, error = error.message ?: "the request failed")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { answered ->
                    val status = answered.code
                    try {
                        // Reading on the callback thread is deliberate: a stream lasts
                        // as long as the model answer, and moving it to another thread
                        // would buy nothing but one more thread per open request.
                        answered.body?.source()?.use { source ->
                            while (true) {
                                val line = source.readUtf8Line() ?: break
                                events.addChunk(handle, "$line\n")
                                onWork()
                            }
                        }
                        finish(handle, status = status, error = null)
                    } catch (error: Exception) {
                        finish(handle, status = null, error = error.message ?: "the response stream failed")
                    }
                }
            }
        })
        return Envelopes.ok(handle)
    }

    /**
     * Abort one request.
     *
     * The kernel still expects one terminal callback after an abort (its engine
     * checks its abort signal on delivery), so this only cancels the HTTP call; the
     * `onFailure` that follows reports the terminal event exactly like a network
     * error would.
     */
    fun abort(handle: String) {
        calls.remove(handle)?.cancel()
    }

    /** Cancel everything — engine shutdown must not leave a stream running. */
    fun cancelAll() {
        for ((_, call) in calls) call.cancel()
        calls.clear()
    }

    private fun finish(handle: String, status: Int?, error: String?) {
        // A cancelled call can race the response callback; the first terminal wins,
        // because the kernel's contract is exactly one.
        if (!settled.add(handle)) return
        calls.remove(handle)
        if (error != null) events.addError(handle, error) else events.addDone(handle, status ?: 0)
        onWork()
    }

    private fun buildRequest(requestJson: String): Request {
        val fields = JSONObject(requestJson)
        val url = fields.optString("url")
        if (url.isEmpty()) throw IllegalArgumentException("the request has no URL")
        val builder = Request.Builder().url(url)
        val headers = fields.optJSONObject("headers")
        if (headers != null) {
            val names = headers.keys()
            while (names.hasNext()) {
                val name = names.next()
                builder.header(name, headers.optString(name))
            }
        }
        val method = fields.optString("method", "GET").uppercase(Locale.US)
        val body = if (fields.has("body") && !fields.isNull("body")) {
            fields.optString("body").toRequestBody(JSON_MEDIA_TYPE)
        } else {
            null
        }
        return when (method) {
            "GET", "HEAD" -> builder.method(method, null).build()
            // The kernel only issues GET and POST today, but a host that refuses
            // anything else would be a host its own engine cannot use later.
            else -> builder.method(method, body ?: "".toRequestBody(JSON_MEDIA_TYPE)).build()
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
