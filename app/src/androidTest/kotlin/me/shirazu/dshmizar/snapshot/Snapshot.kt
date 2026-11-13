package me.shirazu.dshmizar.snapshot

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * Golden images, and the one place they are compared.
 *
 * The goldens live in the test APK's assets so the suite is self-contained: no
 * download, no service, and a baseline is reviewed in the same diff as the change
 * that re-recorded it. The harness is deliberately small and hand-written -- the
 * same decision iOS made for `SnapshotHarness.swift` -- because the comparison it
 * needs is a channel delta plus a differing-pixel fraction, and a snapshot library
 * would add a second rendering/comparison pipeline to keep in step for that.
 *
 * The contract a caller can rely on:
 *
 *  - **compare mode never writes a golden.** It writes the actual image and a
 *    magenta diff next to it on a failure, and fails with the numbers; the only
 *    way to change a baseline is a run with `-e snapshot record`.
 *  - **a missing golden is a failure, not a skip.** A test that quietly passes
 *    because there is nothing to compare proves nothing.
 *  - **a size change fails before any pixel is compared**, because a layout that
 *    moved by a dp reports a pixel-size change and that is the cheapest signal.
 *  - **masks are explicit.** A region that cannot be made deterministic (the home
 *    greeting depends on the hour) is named by the test, not tolerated by a wider
 *    threshold that would hide real changes elsewhere.
 *
 * Recording writes to the app's external files directory rather than `filesDir`:
 * `adb pull` can read an app's external directory without root, and the operator's
 * phone is not rooted. See `docs/android-testing.md` for the two commands.
 */
internal object Snapshot {

    /** The instrumentation argument that switches a run into recording mode. */
    const val ARGUMENT = "snapshot"

    /** The one accepted value: anything else compares. */
    const val RECORD = "record"

    /** How the comparison is allowed to differ. */
    data class Tolerance(
        /** How far one ARGB channel may drift before the pixel counts as different. */
        val channel: Int = 16,
        /** How much of the (unmasked) image may differ: anti-aliasing, not a layout change. */
        val maxDifferentFraction: Double = 0.004,
    ) {
        init {
            require(channel in 0..255) { "channel tolerance must be 0..255, was $channel" }
            require(maxDifferentFraction in 0.0..1.0) {
                "maxDifferentFraction must be 0..1, was $maxDifferentFraction"
            }
        }
    }

    /**
     * A rectangle excluded from the comparison, in pixels of the captured image.
     *
     * Built from semantics bounds by `masksOfText` in `SnapshotScene.kt`, so the
     * mask follows the layout instead of a hand-written guess at where the text is.
     */
    data class Mask(val left: Int, val top: Int, val right: Int, val bottom: Int) {

        /** The same rectangle clipped to an image, or `null` when it misses it entirely. */
        fun clippedTo(width: Int, height: Int): Mask? {
            val l = left.coerceIn(0, width)
            val t = top.coerceIn(0, height)
            val r = right.coerceIn(0, width)
            val b = bottom.coerceIn(0, height)
            return if (r <= l || b <= t) null else Mask(l, t, r, b)
        }
    }

    /** Whether this run records instead of comparing. */
    val isRecording: Boolean
        get() = InstrumentationRegistry.getArguments().getString(ARGUMENT) == RECORD

    private val targetContext
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * Where recordings and failure artefacts are written.
     *
     * External app storage when the device has it (the emulator and every phone do),
     * else internal storage. Both are pulled with `test/pull-snapshots.sh`.
     */
    val outputDir: File
        get() {
            val root = targetContext.getExternalFilesDir(null) ?: targetContext.filesDir
            return File(root, "snapshots")
        }

    /**
     * Record [actual] as the golden for [name], or compare it with the golden.
     *
     * @param name the asset path under `snapshots/`, without the extension.
     */
    fun assertMatches(
        name: String,
        actual: Bitmap,
        tolerance: Tolerance = Tolerance(),
        masks: List<Mask> = emptyList(),
    ) {
        val recorded = File(outputDir, "$name.png")

        if (isRecording) {
            write(actual, recorded)
            println("snapshot: recorded $name (${actual.width}x${actual.height}) -> ${recorded.absolutePath}")
            return
        }

        val golden = readGolden(name)
        if (golden.width != actual.width || golden.height != actual.height) {
            write(actual, recorded)
            throw AssertionError(
                "snapshot: $name is ${actual.width}x${actual.height} but the golden is " +
                    "${golden.width}x${golden.height}. A layout or frame-size change is a " +
                    "failure before pixels are compared. If it was intended, re-record with " +
                    "`-e snapshot record`; the actual image is at ${recorded.absolutePath}",
            )
        }

        val width = golden.width
        val height = golden.height
        val total = width * height
        val masked = BooleanArray(total)
        val appliedMasks = masks.mapNotNull { it.clippedTo(width, height) }
        for (mask in appliedMasks) {
            for (y in mask.top until mask.bottom) {
                val row = y * width
                for (x in mask.left until mask.right) masked[row + x] = true
            }
        }
        val maskedCount = masked.count { it }
        val counted = total - maskedCount
        if (counted == 0) {
            throw AssertionError("snapshot: $name is entirely masked; there is nothing left to compare")
        }

        val goldenPixels = IntArray(total)
        val actualPixels = IntArray(total)
        golden.getPixels(goldenPixels, 0, width, 0, 0, width, height)
        actual.getPixels(actualPixels, 0, width, 0, 0, width, height)

        var different = 0
        var worst = 0
        var firstX = -1
        var firstY = -1
        val diff = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val diffPixels = IntArray(total)
        for (index in 0 until total) {
            if (masked[index]) {
                // Masked pixels are printed as a neutral grey in the diff, so a
                // reviewer can see what was excluded rather than wondering.
                diffPixels[index] = Color.argb(255, 96, 96, 96)
                continue
            }
            val expected = goldenPixels[index]
            val observed = actualPixels[index]
            val delta = channelDelta(expected, observed)
            if (delta > tolerance.channel) {
                different++
                if (delta > worst) worst = delta
                if (firstX < 0) {
                    firstX = index % width
                    firstY = index / width
                }
                diffPixels[index] = Color.MAGENTA
            } else {
                // The actual pixel, slightly dimmed, so the diff reads as a picture.
                diffPixels[index] = (observed and 0x00FFFFFF) or (0x66 shl 24)
            }
        }
        diff.setPixels(diffPixels, 0, width, 0, 0, width, height)

        val fraction = different.toDouble() / counted
        if (fraction > tolerance.maxDifferentFraction) {
            write(actual, recorded)
            val diffFile = File(outputDir, "$name.diff.png")
            write(diff, diffFile)
            throw AssertionError(
                "snapshot: $name differs in ${fraction.percent()} of its pixels (limit " +
                    "${tolerance.maxDifferentFraction.percent()}); $different of $counted " +
                    "compared, worst channel delta $worst at ($firstX, $firstY). " +
                    "Actual: ${recorded.absolutePath} Diff: ${diffFile.absolutePath}. " +
                    "If the change was intended, re-record with `-e snapshot record`.",
            )
        }
        println("snapshot: $name matches (${fraction.percent()} of $counted px differ)")
    }

    /** The golden image for [name], or a failure that says how to record it. */
    private fun readGolden(name: String): Bitmap {
        val stream = try {
            InstrumentationRegistry.getInstrumentation().context.assets.open("snapshots/$name.png")
        } catch (missing: java.io.FileNotFoundException) {
            throw AssertionError(
                "snapshot: there is no golden for $name. Record it with " +
                    "`-e snapshot record`, pull it with `test/pull-snapshots.sh`, and commit " +
                    "`app/src/androidTest/assets/snapshots/$name.png`. Missing is a failure " +
                    "on purpose: a test that passes because it compared nothing proves nothing.",
                missing,
            )
        }
        return stream.use { BitmapFactory.decodeStream(it) }
            ?: throw AssertionError("snapshot: snapshots/$name.png is not a readable image")
    }

    private fun write(bitmap: Bitmap, file: File) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** The largest single-channel difference between two ARGB pixels. */
    private fun channelDelta(expected: Int, actual: Int): Int {
        val da = kotlin.math.abs((expected shr 24 and 0xff) - (actual shr 24 and 0xff))
        val dr = kotlin.math.abs((expected shr 16 and 0xff) - (actual shr 16 and 0xff))
        val dg = kotlin.math.abs((expected shr 8 and 0xff) - (actual shr 8 and 0xff))
        val db = kotlin.math.abs((expected and 0xff) - (actual and 0xff))
        return maxOf(da, dr, dg, db)
    }

    private fun Double.percent(): String = String.format(Locale.US, "%.2f%%", this * 100)
}
