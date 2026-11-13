package me.shirazu.dshmizar.ui.conversation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

/**
 * The app half of an inline picture: ask the host what the path is, read its bytes,
 * decode them off the main thread inside the budgets, and keep a few decoded bitmaps
 * so scrolling back does not re-read the computer.
 *
 * The policy and the wire order live in `ConversationImage.kt` (JVM-testable); this
 * file is Android-only: `ImageDecoder`/`Bitmap`, and the LRU of decoded pictures.
 */

/** What `file.stat` says about a picture. */
data class PictureFacts(
    val path: String,
    val name: String,
    val byteSize: Long,
    val mediaType: String,
    val modifiedAt: Double,
)

/**
 * The host calls a picture needs, injected so the store needs no socket.
 *
 * Implemented over `MizarConnection.fileStat` / `readFileSlice` by AppViewModel; the
 * gate ([isUnlocked]) is read from the same `advancedFeaturesUnlocked` the delivered
 * file preview uses.
 */
interface ConversationImageBridge {
    /** Whether this link/account may read a picture at all. */
    val isUnlocked: Boolean

    /** Whether this session runs on the phone's own kernel (it can serve no files). */
    val isLocalAgent: Boolean

    /** `file.stat`, or `null` when this host predates the method. */
    suspend fun stat(sessionId: String, path: String): PictureFacts?

    /** `file.read` in 256 KiB slices, or `null` when the host answered nothing usable. */
    suspend fun read(sessionId: String, path: String, expectedBytes: Long): ByteArray?
}

/** What an inline picture currently is. */
sealed interface ConversationPictureState {
    data object Locked : ConversationPictureState
    data object Loading : ConversationPictureState
    data class Loaded(
        val bitmap: Bitmap,
        val name: String,
    ) : ConversationPictureState
    data class Failed(val failure: ConversationImageFailure) : ConversationPictureState
}

/**
 * Decoded inline pictures, held across recompositions.
 *
 * Two bounds, both from the iOS store: at most [cacheCount] pictures and at most
 * [cacheBytes] decoded bytes, whichever is hit first. A failure is remembered too --
 * without it a row rebuilt while its load was in flight restarted the read, and a
 * row whose load failed asked the computer again on every recomposition.
 */
class ConversationImagePresenter(
    private val bridge: ConversationImageBridge,
    private val cacheCount: Int = 24,
    private val cacheBytes: Long = 96L * 1_048_576,
) {
    private val states: SnapshotStateMap<String, ConversationPictureState> = mutableStateMapOf()
    private val cache = object : android.util.LruCache<String, Bitmap>(cacheCount) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val inFlight = mutableMapOf<String, Job>()
    private val decodedBytes = HashMap<String, Long>()
    private val insertionOrder = ArrayDeque<String>()
    private var decodedTotal: Long = 0L

    val unlocked: Boolean get() = bridge.isUnlocked && !bridge.isLocalAgent

    /** The state for a picture, before anything is asked of the host. */
    fun state(sessionId: String, target: ConversationImageTarget): ConversationPictureState {
        if (!unlocked) return ConversationPictureState.Locked
        return states[key(sessionId, target)] ?: ConversationPictureState.Loading
    }

    /**
     * Ensure a picture is being loaded. Idempotent: a second call while the same
     * picture is in flight is a no-op, and a failure stays until [retry].
     */
    fun ensure(sessionId: String, target: ConversationImageTarget, scope: CoroutineScope) {
        if (!unlocked) return
        val key = key(sessionId, target)
        if (states[key] is ConversationPictureState.Failed) return
        if (states[key] is ConversationPictureState.Loaded) return
        if (inFlight.containsKey(key)) return

        cache.get(key)?.let { bitmap ->
            states[key] = ConversationPictureState.Loaded(bitmap, target.lookupPath.substringAfterLast('/'))
            return
        }

        states[key] = ConversationPictureState.Loading
        inFlight[key] = scope.launch {
            try {
                val loaded = readAndDecode(sessionId, target, key)
                states[key] = loaded
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                // A rebuild, not a verdict on the picture: the next instance asks again.
                states.remove(key)
            } catch (failure: ConversationImageFailure) {
                states[key] = ConversationPictureState.Failed(failure)
            } catch (_: Throwable) {
                states[key] = ConversationPictureState.Failed(ConversationImageFailure.UNREADABLE)
            } finally {
                inFlight.remove(key)
            }
        }
    }

    /** Forget a failure so the row asks again; the bytes cache is left alone. */
    fun retry(sessionId: String, target: ConversationImageTarget) {
        states.remove(key(sessionId, target))
    }

    private suspend fun readAndDecode(
        sessionId: String,
        target: ConversationImageTarget,
        key: String,
    ): ConversationPictureState {
        if (!target.isLocal) throw ConversationImageFailure.NOT_AN_IMAGE
        val path = target.lookupPath
        val facts = try {
            bridge.stat(sessionId, path)
        } catch (error: Throwable) {
            throw failureOf(error)
        } ?: throw ConversationImageFailure.OLD_PLUGIN

        if (!ConversationImagePolicy.accepts(path) &&
            !ConversationImagePolicy.acceptsMediaType(facts.mediaType)
        ) {
            throw ConversationImageFailure.NOT_AN_IMAGE
        }
        if (!ConversationImagePolicy.acceptsBytes(facts.byteSize)) {
            throw ConversationImageFailure.TOO_LARGE
        }

        val bytes = try {
            bridge.read(sessionId, path, facts.byteSize)
        } catch (error: Throwable) {
            throw failureOf(error)
        } ?: throw ConversationImageFailure.OFFLINE
        if (bytes.isEmpty() || bytes.size.toLong() != facts.byteSize) {
            throw ConversationImageFailure.UNREADABLE
        }

        val bitmap = withContext(Dispatchers.Default) {
            decode(bytes, facts.byteSize)
        } ?: throw ConversationImageFailure.UNREADABLE

        cache.put(key, bitmap)
        decodedBytes[key] = bitmap.byteCount.toLong()
        decodedTotal += bitmap.byteCount.toLong()
        insertionOrder.remove(key)
        insertionOrder.addLast(key)
        trimCache()
        return ConversationPictureState.Loaded(bitmap, facts.name.ifBlank {
            target.lookupPath.substringAfterLast('/')
        })
    }

    /** Decode with both budgets: the pixel ceiling, then the long-edge cap. */
    private fun decode(bytes: ByteArray, expectedBytes: Long): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return runCatching {
                val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val size = ConversationImagePolicy.targetSize(info.size.width, info.size.height)
                        ?: throw IllegalArgumentException("picture is past the pixel budget")
                    decoder.setTargetSize(size.width, size.height)
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            }.getOrNull()
        }
        // minSdk 26: BitmapFactory with a sampled decode, then a bounded rescale.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val size = ConversationImagePolicy.targetSize(bounds.outWidth, bounds.outHeight) ?: return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= size.width && bounds.outHeight / (sample * 2) >= size.height) {
            sample *= 2
        }
        val decoded = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null
        if (decoded.width <= ConversationImagePolicy.MAXIMUM_EDGE &&
            decoded.height <= ConversationImagePolicy.MAXIMUM_EDGE
        ) {
            return decoded
        }
        val scaled = Bitmap.createScaledBitmap(decoded, size.width, size.height, true)
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    /** Evict oldest-first until both bounds hold: 24 pictures and 96 MiB decoded. */
    private fun trimCache() {
        while (cache.size() > cacheCount || decodedTotal > cacheBytes) {
            val oldest = insertionOrder.removeFirstOrNull() ?: break
            val bytes = decodedBytes.remove(oldest) ?: continue
            cache.remove(oldest)
            decodedTotal -= bytes
        }
    }

    private fun key(sessionId: String, target: ConversationImageTarget): String =
        "$sessionId\u0000${target.lookupPath}"

    private fun failureOf(error: Throwable): ConversationImageFailure =
        (error as? me.shirazu.mizar.kit.MizarError)?.let {
            ConversationImageFailure.fromCode(it.code)
        } ?: ConversationImageFailure.OFFLINE

    /** For tests and diagnostics: how many pictures are held. */
    val cachedCount: Int get() = cache.size()
}
