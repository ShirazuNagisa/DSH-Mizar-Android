package me.shirazu.dshmizar.localagent

import kotlinx.coroutines.channels.ReceiveChannel

/**
 * The narrow engine surface a transport needs.
 *
 * [LocalKernelTransport] talks to the kernel through this interface rather than
 * [LocalKernelEngine] directly so the transport's framing rules — connect once,
 * frames in order, close ends the read loop — can be unit-tested on the JVM with a
 * scripted fake, no emulator and no QuickJS library. The real implementation is
 * [LocalKernelEngine], which owns the single JS thread.
 */
fun interface LocalKernelLink {
    /** Open one protocol connection to the kernel; throws when the kernel is not booted. */
    fun openConnection(): LocalLinkConnection
}

/**
 * One open connection to the local kernel, seen from the transport.
 *
 * This is the in-process equivalent of the socket the remote transport dials: the
 * kernel pushes whole JSON frames, the client sends whole JSON frames, and the
 * order is preserved in both directions. Binary frames never appear (the local
 * kernel does not speak the desktop state channel).
 */
interface LocalLinkConnection {
    /** The kernel identity's device id from `welcome`; useful for logs. */
    val deviceId: String?

    /**
     * Frames the kernel pushed for this connection, in order.
     *
     * Closed when [close] runs; a collector then sees the end rather than a stall.
     */
    val frames: ReceiveChannel<String>

    /** Deliver one encoded client frame. Throws once the connection is closed. */
    fun send(text: String)

    /** Close the kernel-side connection and end [frames]. Idempotent. */
    fun close()
}
