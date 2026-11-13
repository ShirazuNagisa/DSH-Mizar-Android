package me.shirazu.dshmizar.ui.conversation

/**
 * The policy half of an inline picture: what may be read, and what may be decoded.
 *
 * Ported from iOS `MizarKit/Sources/MizarKit/ConversationImage.swift`. No Android
 * type appears here: the gate and the budgets are arithmetic, and arithmetic is
 * the part that must be pinned by a JVM test. The bytes themselves are read and
 * decoded in `ConversationImageStore.kt`.
 */

/** A picture a message asked for: `![alt](path)`. */
data class ConversationImageTarget(
    /** The path exactly as the model wrote it; the host resolves it, the phone never guesses. */
    val path: String,
    /** What the model meant the picture to say, for the caption and a screen reader. */
    val alt: String? = null,
) {
    /** The path without a Markdown anchor or fragment (`shot.png#L3` is still a picture). */
    val lookupPath: String
        get() {
            val hash = path.indexOf('#')
            return if (hash <= 0) path else path.substring(0, hash)
        }

    /** Whether the path names a file on the computer rather than the network. */
    val isLocal: Boolean
        get() {
            val colon = lookupPath.indexOf(':')
            if (colon < 0) return true
            val scheme = lookupPath.substring(0, colon).lowercase()
            // A Windows drive letter is a path, not a scheme.
            if (scheme.length == 1) return true
            return scheme == "file"
        }
}

/**
 * Why a picture is not on the page. Semantics, not sentences: the app owns the wording.
 *
 * A failure is an exception so the store can throw one across its suspend boundary
 * and catch the same value; it is never allowed to escape to the UI.
 */
sealed class ConversationImageFailure(message: String) : Exception(message) {
    data object MISSING : ConversationImageFailure("missing")
    data object FORBIDDEN : ConversationImageFailure("forbidden")
    data object TOO_LARGE : ConversationImageFailure("too large")
    data object OLD_PLUGIN : ConversationImageFailure("host predates file.stat")
    data object LOCKED : ConversationImageFailure("locked")
    data object OFFLINE : ConversationImageFailure("offline")
    data object NOT_AN_IMAGE : ConversationImageFailure("not an image")
    data object UNREADABLE : ConversationImageFailure("unreadable")

    companion object {
        /** The case a host error code maps to (the iOS table, verbatim). */
        fun fromCode(code: String): ConversationImageFailure = when (code) {
            "FILE_UNKNOWN", "NOT_FOUND" -> MISSING
            "FILE_FORBIDDEN", "PATH_ESCAPES_SANDBOX" -> FORBIDDEN
            "FILE_TOO_LARGE", "RESPONSE_TOO_LARGE" -> TOO_LARGE
            "NOT_IMPLEMENTED" -> OLD_PLUGIN
            "PREVIEW_LOCKED", "PERMISSION_DENIED", "UNAUTHENTICATED", "PAIRING_REQUIRED" -> LOCKED
            "NO_CONNECTION", "SOCKET_CLOSED", "CONNECTION_LOST", "HOST_OFFLINE" -> OFFLINE
            else -> UNREADABLE
        }
    }
}

/**
 * Who may see a picture that lives on the computer.
 *
 * A picture's bytes cross the link the phone is on. On the operator's own network
 * they cost the project nothing, so every account may have them; over the relay
 * they are the bandwidth the paid plan pays for. The rule is therefore about the
 * *link* as much as the account: `isPaid || isLocalNetwork`.
 */
object InlinePictureAccess {
    fun isEntitled(isPaid: Boolean, isLocalNetwork: Boolean, isLocalAgent: Boolean = false): Boolean {
        if (isLocalAgent) return false
        return isPaid || isLocalNetwork
    }
}

/** What an inline picture will agree to load. */
object ConversationImagePolicy {
    /** The most bytes one inline picture may be (the host's own document ceiling is 32 MiB). */
    const val MAXIMUM_BYTES: Long = 12L * 1_048_576

    /** The longest edge the decoded bitmap is allowed to keep. */
    const val MAXIMUM_EDGE: Int = 2048

    /** The pixel budget: 40 MP, the same ceiling the delivered-file preview uses. */
    const val MAXIMUM_PIXELS: Long = 40_000_000L

    /** The extensions this client draws (ImageDecoder on the app's minSdk reads these). */
    val extensions: Set<String> = setOf(
        "png", "jpg", "jpeg", "jpe", "webp", "gif", "bmp", "heic", "heif", "tiff", "tif",
    )

    /** Whether a path's extension is one this client draws. */
    fun accepts(path: String): Boolean {
        val name = path.substringAfterLast('/').substringAfterLast('\\')
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in extensions
    }

    /** Whether a host-reported media type is one this client draws. */
    fun acceptsMediaType(mediaType: String): Boolean = mediaType.lowercase().startsWith("image/")

    /** Whether a size may be read. Zero bytes is "unreadable", not "too large". */
    fun acceptsBytes(bytes: Long): Boolean = bytes in 0L..MAXIMUM_BYTES

    /** Whether the pixel budget allows a bitmap of this size. */
    fun acceptsPixels(width: Int, height: Int): Boolean =
        width > 0 && height > 0 && width.toLong() * height.toLong() <= MAXIMUM_PIXELS

    /**
     * The size to decode at: inside the pixel budget already, nothing is scaled; past
     * the edge ceiling, the long edge is capped and the other follows.
     *
     * @returns the target size, or `null` when the source is not a drawable size at all.
     */
    fun targetSize(width: Int, height: Int): PictureSize? {
        if (!acceptsPixels(width, height)) return null
        val longEdge = maxOf(width, height)
        if (longEdge <= MAXIMUM_EDGE) return PictureSize(width, height)
        val scale = MAXIMUM_EDGE.toDouble() / longEdge.toDouble()
        val targetWidth = (width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (height * scale).toInt().coerceAtLeast(1)
        return PictureSize(targetWidth, targetHeight)
    }
}

/** A decoded target size, in pixels. */
data class PictureSize(val width: Int, val height: Int)
