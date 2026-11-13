package me.shirazu.dshmizar.localagent

import org.json.JSONObject

/**
 * The one JSON envelope the JavaScript host parses.
 *
 * The JNI bridge ([HostDispatcher] → `jni_bridge.c` → `__mizarNativeCall`) can carry
 * only strings, and a host answer has three shapes: a value, no value, or a failure.
 * `{ok:true,value:…}` and `{ok:false,error:"…"}` are the whole vocabulary;
 * `assets/localagent-host.js` turns them back into the kernel's `cb.ok/cb.fail`.
 */
internal object Envelopes {
    fun ok(value: Any? = null): String =
        JSONObject().put("ok", true).put("value", value ?: JSONObject.NULL).toString()

    fun fail(message: String): String =
        JSONObject().put("ok", false).put("error", message).toString()
}
