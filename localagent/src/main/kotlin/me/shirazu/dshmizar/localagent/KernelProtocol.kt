package me.shirazu.dshmizar.localagent

import org.json.JSONException
import org.json.JSONObject

/**
 * The Mizar wire protocol, version 1 — the client half the PoC needs.
 *
 * The kernel bundle carries the server half (`encodeFrame`, `parseClientFrame`
 * and `PROTOCOL_VERSION` in `LocalKernel/kernel/src/protocol/frames.ts`); the
 * client frames below are the same shapes the iOS and Android apps already
 * send over the network transport, so a local connection is indistinguishable
 * from a paired host one.
 *
 * A request is `{v, id, type, payload?}` and is answered by exactly one
 * `{v, id, type: "ok"|"error", ...}`; `welcome` is the kernel's answer to the
 * `hello` handshake and carries no `id`. Keep this object boring: every byte
 * here is the contract.
 */
object KernelProtocol {
    /** The protocol version this build speaks; must match the bundle's. */
    const val PROTOCOL_VERSION = 1

    /**
     * The first frame a client must send on a connection.
     *
     * The `token` is required by the envelope even for a local kernel, which
     * accepts any value — a local kernel has nothing to pair with. `appVersion`
     * and `deviceModel` are logs-only fields on the kernel side.
     */
    fun helloFrame(
        token: String,
        appVersion: String? = null,
        deviceModel: String? = null,
    ): String {
        val frame = JSONObject()
            .put("v", PROTOCOL_VERSION)
            .put("type", "hello")
            .put("token", token)
            .put("protocolVersion", PROTOCOL_VERSION)
        if (appVersion != null) frame.put("appVersion", appVersion)
        if (deviceModel != null) frame.put("deviceModel", deviceModel)
        return frame.toString()
    }

    /**
     * One correlated request.
     *
     * [payloadJson] must be a complete JSON object; the protocol keeps arguments
     * inside `payload` so the envelope stays closed. `null` means "no payload",
     * which is what `kernel.status` and `kernel.config.get` take.
     */
    fun requestFrame(id: String, method: String, payloadJson: String? = null): String {
        val frame = JSONObject()
            .put("v", PROTOCOL_VERSION)
            .put("id", id)
            .put("type", method)
        if (payloadJson != null) {
            val payload = try {
                JSONObject(payloadJson)
            } catch (error: JSONException) {
                throw IllegalArgumentException("payload for $method is not a JSON object: $payloadJson", error)
            }
            frame.put("payload", payload)
        }
        return frame.toString()
    }

    /** The frame's `type`, or `null` when it is not a JSON object with one. */
    fun typeOf(frameText: String): String? = try {
        JSONObject(frameText).optString("type", "").takeIf { it.isNotEmpty() }
    } catch (error: JSONException) {
        null
    }

    /** The frame's `id`, or `null` when it has none. */
    fun idOf(frameText: String): String? = try {
        val id = JSONObject(frameText).optString("id", "")
        id.takeIf { it.isNotEmpty() }
    } catch (error: JSONException) {
        null
    }

    /** Whether [frameText] is the `ok` or `error` answer to request [id]. */
    fun isAnswerTo(frameText: String, id: String): Boolean {
        val type = typeOf(frameText) ?: return false
        if (type != "ok" && type != "error") return false
        return idOf(frameText) == id
    }

    /**
     * The `result` of an `ok` frame as JSON text, or `null`.
     *
     * Methods without a result omit the field entirely; those answer `null`
     * here, which is a legitimate success.
     */
    fun resultJson(frameText: String): String? = try {
        val frame = JSONObject(frameText)
        if (frame.optString("type") != "ok") null else frame.opt("result")?.toString()
    } catch (error: JSONException) {
        null
    }

    /** The human-readable `message` of an `error` frame, if this is one. */
    fun errorMessage(frameText: String): String? = try {
        val frame = JSONObject(frameText)
        if (frame.optString("type") != "error") {
            null
        } else {
            val code = frame.optString("code", "")
            val message = frame.optString("message", "")
            if (code.isEmpty()) message else "$code: $message"
        }
    } catch (error: JSONException) {
        null
    }
}
