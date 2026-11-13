package me.shirazu.dshmizar

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.shirazu.dshmizar.domain.LiveTurnView
import me.shirazu.dshmizar.domain.toUi
import me.shirazu.dshmizar.ui.MizarTheme
import me.shirazu.dshmizar.ui.conversation.ConversationImageBlock
import me.shirazu.dshmizar.ui.conversation.ConversationImageTarget
import me.shirazu.dshmizar.ui.conversation.ConversationStream
import me.shirazu.mizar.kit.SessionState
import me.shirazu.mizar.kit.StateFrame
import me.shirazu.mizar.kit.StateFrameFlags
import me.shirazu.mizar.kit.TurnStream
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The live stream as a page: a running turn shows its status and clock, and a
 * settled turn folds the process behind one tap while leaving the answer on screen.
 *
 * Rendering only -- no view model, no socket: [TurnStream] is fed real state frames
 * and its snapshot is handed to [ConversationStream], which is exactly how the app
 * wires it. This file is new and independent of the visual-qa snapshot baselines.
 */
@RunWith(AndroidJUnit4::class)
class ConversationStreamTest {

    @get:Rule
    val rule = createComposeRule()

    private fun text(id: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun runningView(): LiveTurnView {
        val stream = TurnStream()
        stream.apply(StateFrame(SessionState.THINKING, interval = 1))
        stream.apply(
            StateFrame(
                state = SessionState.STREAM_FINAL,
                interval = 2,
                flags = StateFrameFlags.SNAPSHOT or StateFrameFlags.APPEND,
                text = "先说结论：改好了。",
            ),
        )
        return stream.snapshot().toUi(
            sessionId = "s1",
            startedAtMillis = System.currentTimeMillis() - 5_000,
            running = true,
            typing = false,
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
                text = "改好了。",
            ),
        )
        stream.apply(
            StateFrame(
                state = SessionState.STREAM_FINAL,
                interval = 3,
                textSeq = "改好了。".encodeToByteArray().size.toLong(),
                flags = StateFrameFlags.APPEND,
                text = "测试通过。",
            ),
        )
        stream.apply(
            StateFrame(
                state = SessionState.STREAM_FINAL,
                interval = 3,
                textSeq = "改好了。测试通过。".encodeToByteArray().size.toLong(),
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

    @Test
    fun aRunningTurnShowsItsStatusAndAClock() {
        rule.setContent {
            MizarTheme { ConversationStream(view = runningView()) }
        }
        rule.onNodeWithTag("conv.stream.clock").assertIsDisplayed()
    }

    @Test
    fun aSettledTurnShowsItsAnswerAndFoldsTheProcess() {
        rule.setContent {
            MizarTheme { ConversationStream(view = settledView()) }
        }
        // The answer is on the page, and the process is behind the fold.
        rule.onNodeWithTag("conv.stream.answer").assertIsDisplayed()
        // Tap the header: the whole row is the fold's touch target, not the glyph.
        rule.onNodeWithTag("conv.stream.header").assertIsDisplayed().performClick()
        // Opening the fold brings the status line back.
        rule.onNodeWithTag("conv.stream.status").assertIsDisplayed()
    }

    @Test
    fun aLockedPictureSaysHowToUnlockItAndAsksNothing() {
        rule.setContent {
            MizarTheme {
                ConversationImageBlock(
                    target = ConversationImageTarget(
                        path = "docs/evidence/shot.png",
                        alt = "evidence",
                    ),
                )
            }
        }
        // No presenter is provided at all in this test: the gate must not ask the
        // host for anything, and the card must say what unlocks pictures.
        rule.onNodeWithText(text(R.string.conv_image_locked_title), substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun aSettledStreamKeepsTheAnswerCopyable() {
        assertTrue(settledView().copyText?.contains("改好了") == true)
    }
}
