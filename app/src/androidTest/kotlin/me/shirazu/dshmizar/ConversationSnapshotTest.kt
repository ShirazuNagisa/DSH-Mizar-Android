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
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.shirazu.dshmizar.ui.MizarTheme
import me.shirazu.dshmizar.ui.conversation.ConversationItemView
import me.shirazu.mizar.kit.ConversationItem
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * A pixel-level check on the one surface every release touches: the answer card.
 *
 * This is the substitute for "take a screenshot and look at it", which is how the
 * design tokens were checked until now and which catches a change only when
 * somebody remembers to look. The golden is a PNG in `androidTest/assets`, and the
 * comparison tolerates anti-aliasing noise rather than demanding identical bytes.
 *
 * Two things make it portable enough to keep in the suite:
 *
 *  - the content is laid out under a **fixed density**, so a phone and an emulator
 *    at a different DPI produce the same pixel size;
 *  - a failure reports how far off it was and where the new image was written, so
 *    re-recording is one command: `-e snapshot record`.
 *
 * A deliberate visual change therefore fails once, is looked at, and is re-recorded.
 * That is the point: the diff is the review.
 */
@RunWith(AndroidJUnit4::class)
class ConversationSnapshotTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun theAnswerCardMatchesItsGolden() {
        rule.setContent {
            // Fixed density: dp becomes px the same way on every device, so the
            // golden does not depend on which phone is running the suite.
            CompositionLocalProvider(LocalDensity provides Density(2f, fontScale = 1f)) {
                MizarTheme {
                    Box(
                        modifier = Modifier
                            .width(360.dp)
                            .background(MaterialTheme.colorScheme.background)
                            .padding(16.dp),
                    ) {
                        ConversationItemView(
                            item = ConversationItem.Message(
                                id = "snapshot",
                                role = ConversationItem.Role.ASSISTANT,
                                text = "The card is the page: a heading, a line of text, " +
                                    "and the quiet spacing between them.",
                                atMillis = null,
                            ),
                            isLastPrompt = false,
                            onCopy = {},
                            typing = false,
                        )
                    }
                }
            }
        }
        rule.waitForIdle()

        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        Snapshot.assertMatches("answer-card", bitmap)
    }
}

/**
 * Golden images, and the one place they are compared.
 *
 * Kept in the test APK's assets so the suite is self-contained: no download, no
 * service, and the goldens are reviewed in the same diff as the change that
 * re-recorded them.
 */
internal object Snapshot {

    /** How far one channel may drift before a pixel counts as different. */
    private const val CHANNEL_TOLERANCE = 16

    /** How much of the image may differ: anti-aliasing, not a layout change. */
    private const val MAX_DIFFERENT_FRACTION = 0.004

    fun assertMatches(name: String, actual: Bitmap) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val recording = InstrumentationRegistry.getArguments().getString("snapshot") == "record"
        val file = File(instrumentation.targetContext.filesDir, "snapshots/$name.png")

        if (recording) {
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { actual.compress(Bitmap.CompressFormat.PNG, 100, it) }
            // Printed rather than asserted: a record run is a setup step, and the
            // operator pulls this file into androidTest/assets.
            println("snapshot: recorded ${actual.width}x${actual.height} → ${file.absolutePath}")
            return
        }

        val golden = instrumentation.context.assets.open("snapshots/$name.png").use { stream ->
            android.graphics.BitmapFactory.decodeStream(stream)
        } ?: throw AssertionError("snapshot: $name.png is not a readable image")

        if (golden.width != actual.width || golden.height != actual.height) {
            save(actual, file)
            throw AssertionError(
                "snapshot: $name is ${actual.width}x${actual.height}, the golden is " +
                    "${golden.width}x${golden.height}. If the layout change was intended, " +
                    "re-record with -e snapshot record; the new image is at $file",
            )
        }

        var different = 0
        val total = golden.width * golden.height
        val goldenPixels = IntArray(total)
        val actualPixels = IntArray(total)
        golden.getPixels(goldenPixels, 0, golden.width, 0, 0, golden.width, golden.height)
        actual.getPixels(actualPixels, 0, golden.width, 0, 0, golden.width, golden.height)
        for (index in 0 until total) {
            if (!close(goldenPixels[index], actualPixels[index])) different++
        }

        val fraction = different.toDouble() / total
        if (fraction > MAX_DIFFERENT_FRACTION) {
            save(actual, file)
            throw AssertionError(
                "snapshot: $name differs in ${(fraction * 100).round2()}% of its pixels " +
                    "(limit ${(MAX_DIFFERENT_FRACTION * 100).round2()}%). New image: $file",
            )
        }
        assertTrue(true)
    }

    private fun save(bitmap: Bitmap, file: File) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Whether two ARGB pixels are within the channel tolerance. */
    private fun close(expected: Int, actual: Int): Boolean {
        val da = kotlin.math.abs((expected shr 24 and 0xff) - (actual shr 24 and 0xff))
        val dr = kotlin.math.abs((expected shr 16 and 0xff) - (actual shr 16 and 0xff))
        val dg = kotlin.math.abs((expected shr 8 and 0xff) - (actual shr 8 and 0xff))
        val db = kotlin.math.abs((expected and 0xff) - (actual and 0xff))
        return da <= CHANNEL_TOLERANCE && dr <= CHANNEL_TOLERANCE &&
            dg <= CHANNEL_TOLERANCE && db <= CHANNEL_TOLERANCE
    }

    private fun Double.round2(): String = String.format("%.2f", this)
}
