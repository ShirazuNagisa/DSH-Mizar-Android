package me.shirazu.dshmizar

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.shirazu.dshmizar.domain.LiveTurnView
import me.shirazu.dshmizar.domain.toUi
import me.shirazu.dshmizar.ui.MizarTheme
import me.shirazu.dshmizar.ui.conversation.ConversationImageBlock
import me.shirazu.dshmizar.ui.conversation.ConversationImageBridge
import me.shirazu.dshmizar.ui.conversation.ConversationImagePresenter
import me.shirazu.dshmizar.ui.conversation.ConversationImageTarget
import me.shirazu.dshmizar.ui.conversation.ConversationPictureState
import me.shirazu.dshmizar.ui.conversation.ConversationStream
import me.shirazu.dshmizar.ui.conversation.LocalConversationImages
import me.shirazu.dshmizar.ui.conversation.LocalConversationSessionId
import me.shirazu.dshmizar.ui.conversation.PictureFacts
import me.shirazu.mizar.kit.SessionState
import me.shirazu.mizar.kit.StateFrame
import me.shirazu.mizar.kit.StateFrameFlags
import me.shirazu.mizar.kit.TurnStream
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Produces the evidence PNGs for the live stream: running, stale, folded and
 * unfolded, and the locked picture.
 *
 * This is a screenshot *producer*, not a comparison: it writes PNGs under the
 * app's external files dir so `adb pull` can collect them, and it never touches
 * the visual-qa baseline assets. Fixed density and a fixed clock keep the output
 * deterministic.
 */
@RunWith(AndroidJUnit4::class)
class ConversationStreamScreenshotTest {

    @get:Rule
    val rule = createComposeRule()

    private val outputDirectory: File by lazy {
        File(
            InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
            "screenshots",
        ).apply { mkdirs() }
    }

    private fun runningView(startedAgoMillis: Long = 5_000L): LiveTurnView {
        val stream = TurnStream()
        stream.apply(StateFrame(SessionState.THINKING, interval = 1))
        stream.apply(StateFrame(SessionState.TOOL, interval = 2))
        stream.apply(
            StateFrame(
                state = SessionState.STREAM_FINAL,
                interval = 3,
                flags = StateFrameFlags.SNAPSHOT or StateFrameFlags.APPEND,
                text = "先看结论：这次改动把会话页的实时状态接上了，过程行内联显示，不打断阅读。",
            ),
        )
        return stream.snapshot().toUi(
            sessionId = "s1",
            startedAtMillis = System.currentTimeMillis() - startedAgoMillis,
            running = true,
            typing = false,
            lastFrameAtMillis = System.currentTimeMillis() - startedAgoMillis,
        )
    }

    private fun settledView(): LiveTurnView {
        val stream = TurnStream()
        stream.apply(StateFrame(SessionState.THINKING, interval = 1))
        stream.apply(StateFrame(SessionState.TOOL, interval = 2))
        stream.apply(
            StateFrame(
                state = SessionState.STREAM_FINAL,
                interval = 3,
                flags = StateFrameFlags.SNAPSHOT or StateFrameFlags.APPEND,
                text = "结论：状态通道已经接进会话页。",
            ),
        )
        stream.apply(
            StateFrame(
                state = SessionState.STREAM_FINAL,
                interval = 3,
                textSeq = "结论：状态通道已经接进会话页。".encodeToByteArray().size.toLong(),
                flags = StateFrameFlags.FINAL,
            ),
        )
        stream.apply(StateFrame(SessionState.COMPLETED, interval = 4))
        return stream.snapshot().toUi(
            sessionId = "s1",
            startedAtMillis = 1_000L,
            endedAtMillis = 135_000L,
            running = false,
            typing = false,
        )
    }

    private fun capture(name: String) {
        rule.waitForIdle()
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        val file = File(outputDirectory, name)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun runningStream() {
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f, fontScale = 1f)) {
                MizarTheme {
                    Box(
                        Modifier
                            .width(360.dp)
                            .background(MaterialTheme.colorScheme.background)
                            .padding(16.dp),
                    ) {
                        ConversationStream(view = runningView(), onCopy = {})
                    }
                }
            }
        }
        capture("conversation-stream-running.png")
    }

    @Test
    fun staleStream() {
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f, fontScale = 1f)) {
                MizarTheme {
                    Box(
                        Modifier
                            .width(360.dp)
                            .background(MaterialTheme.colorScheme.background)
                            .padding(16.dp),
                    ) {
                        ConversationStream(view = runningView(startedAgoMillis = 30_000L), onCopy = {})
                    }
                }
            }
        }
        capture("conversation-stream-stale.png")
    }

    @Test
    fun settledFolded() {
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f, fontScale = 1f)) {
                MizarTheme {
                    Box(
                        Modifier
                            .width(360.dp)
                            .background(MaterialTheme.colorScheme.background)
                            .padding(16.dp),
                    ) {
                        ConversationStream(view = settledView(), onCopy = {})
                    }
                }
            }
        }
        capture("conversation-stream-settled-folded.png")
    }

    @Test
    fun pictureLocked() {
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f, fontScale = 1f)) {
                MizarTheme {
                    Box(
                        Modifier
                            .width(360.dp)
                            .background(MaterialTheme.colorScheme.background)
                            .padding(16.dp),
                    ) {
                        ConversationImageBlock(
                            target = ConversationImageTarget(
                                path = "docs/evidence/android-live-updates.png",
                                alt = "Live Updates card",
                            ),
                        )
                    }
                }
            }
        }
        capture("conversation-image-locked.png")
    }

    @Test
    fun settledUnfolded() {
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f, fontScale = 1f)) {
                MizarTheme {
                    Box(
                        Modifier
                            .width(360.dp)
                            .background(MaterialTheme.colorScheme.background)
                            .padding(16.dp),
                    ) {
                        ConversationStream(view = settledView(), onCopy = {})
                    }
                }
            }
        }
        rule.onNodeWithTag("conv.stream.header").performClick()
        capture("conversation-stream-settled-unfolded.png")
    }

    @Test
    fun pictureUnlocked() {
        val bitmap = Bitmap.createBitmap(320, 200, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.rgb(14, 124, 134))
        }
        val png = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
        val path = "docs/evidence/shot.png"
        val bridge = object : ConversationImageBridge {
            override val isUnlocked: Boolean = true
            override val isLocalAgent: Boolean = false
            override suspend fun stat(sessionId: String, path: String): PictureFacts =
                PictureFacts(path, "shot.png", png.size.toLong(), "image/png", 0.0)
            override suspend fun read(sessionId: String, path: String, expectedBytes: Long): ByteArray = png
        }
        val presenter = ConversationImagePresenter(bridge)
        val target = ConversationImageTarget(path = path, alt = "Screenshot evidence")
        rule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(2f, fontScale = 1f),
                LocalConversationImages provides presenter,
                LocalConversationSessionId provides "s1",
            ) {
                MizarTheme {
                    Box(
                        Modifier
                            .width(360.dp)
                            .background(MaterialTheme.colorScheme.background)
                            .padding(16.dp),
                    ) {
                        ConversationImageBlock(target = target)
                    }
                }
            }
        }
        rule.waitUntil(5_000) {
            presenter.state("s1", target) is ConversationPictureState.Loaded
        }
        capture("conversation-image-unlocked.png")
    }
}


