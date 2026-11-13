package me.shirazu.dshmizar.localagent

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Host work that finished off the JS thread, waiting for the engine thread to hand
 * it to JavaScript.
 *
 * The kernel's host API is callback-style and expects its callbacks "later", but
 * QuickJS has no event loop: nothing runs in the context between two `evaluate`
 * calls. HTTP streaming is the first host function that genuinely finishes on
 * another thread (OkHttp's), so its chunks land here from that thread; the engine
 * thread drains this queue into `__mizarDrainHostEvents()` (see
 * `assets/localagent-host.js`) and the JS host invokes the waiting `onChunk` /
 * `onDone` / `onError`.
 *
 * Order matters and is preserved: the kernel's SSE parser appends chunks to a
 * buffer, and `onDone` must arrive after every chunk of the response.
 */
internal class HostEventQueue {
    private val queue = ConcurrentLinkedQueue<JSONObject>()

    fun addChunk(handle: String, text: String) {
        queue.add(JSONObject().put("op", "chunk").put("handle", handle).put("text", text))
    }

    fun addDone(handle: String, status: Int) {
        queue.add(JSONObject().put("op", "done").put("handle", handle).put("status", status))
    }

    fun addError(handle: String, message: String) {
        queue.add(JSONObject().put("op", "error").put("handle", handle).put("message", message))
    }

    fun hasPending(): Boolean = queue.isNotEmpty()

    /**
     * Take up to [limit] events, oldest first, as the JS host reads them.
     *
     * The cap is what keeps one `evaluate` bounded: a fast model stream produces
     * hundreds of lines a second, and draining all of them in one gulp would hold
     * the JS thread while the rest of the UI's requests wait behind it. Whatever is
     * left stays queued for the next pump.
     */
    fun poll(limit: Int = 64): String {
        val events = JSONArray()
        var taken = 0
        while (taken < limit) {
            val event = queue.poll() ?: break
            events.put(event)
            taken += 1
        }
        return Envelopes.ok(events)
    }
}
