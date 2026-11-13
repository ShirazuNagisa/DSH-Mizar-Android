package me.shirazu.dshmizar.snapshot

import android.content.Context
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import me.shirazu.dshmizar.ui.MizarTheme
import me.shirazu.dshmizar.ui.ThemeMode
import me.shirazu.dshmizar.ui.design.LocalReduceMotion
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The fixed environment every golden is rendered in, and the crop that is captured.
 *
 * A snapshot only means anything if two runs render the same thing. Everything a
 * device, a locale or an accessibility setting could vary is therefore pinned here,
 * once, instead of by each test:
 *
 *  - **density 2x, font scale 1.** `dp` becomes the same number of pixels on every
 *    device, so a phone at 2.625x and a 420 dpi emulator record the same image.
 *  - **English (`en-US`) resources.** `values/` is English; a Chinese emulator and
 *    an English CI runner must produce the same text, or the goldens would only be
 *    valid on the machine that recorded them.
 *  - **light theme.** `MizarTheme(LIGHT)` rather than the system's setting: the
 *    appearance under test is the app's, not the emulator's.
 *  - **Reduce Motion on.** Ambient loops (the search pulse, the twin star, the
 *    refresh spinner) collapse to their resting frame, so a capture does not race
 *    whichever frame the animation happened to be on. One-shot animations still
 *    run and are settled by `waitForIdle` before the capture.
 *  - **a fixed frame and no window insets.** The page is laid out at 390x844 dp and
 *    the status/navigation bar insets are consumed, so the crop does not depend on
 *    the device's cutout or bar heights.
 *  - **a fixed `LocalConfiguration`.** Popover clamps and the dock's height limit
 *    read `screenWidthDp`/`screenHeightDp`; pinned to the frame above.
 *
 * The scene is *not* edge-to-edge: it is the app's own content, without the system
 * bars, which is what a golden can actually pin. Android system UI (the status bar,
 * the notification shade) is out of scope and is verified with `adb screencap`.
 */
internal const val SNAPSHOT_ROOT_TAG = "snapshot-root"

/** The page frame: a phone's logical size, big enough for the shell and the composer. */
internal val SnapshotPageWidth: Dp = 390.dp
internal val SnapshotPageHeight: Dp = 844.dp

/** Density and font scale are pinned together; both change text metrics. */
internal const val SNAPSHOT_DENSITY = 2f
internal const val SNAPSHOT_FONT_SCALE = 1f

/** The locale the goldens are drawn in. `values/` is this one. */
internal val SNAPSHOT_LOCALE: Locale = Locale.US

/** The window configuration the scene is measured under. */
private const val SNAPSHOT_WIDTH_DP = 390
private const val SNAPSHOT_HEIGHT_DP = 844

/**
 * A page-sized crop: exactly 390x844 dp, whatever the device.
 *
 * Draw full screens inside this. The [SNAPSHOT_ROOT_TAG] node is the capture target,
 * so the image never includes the host activity's window or the system bars.
 */
@Composable
internal fun SnapshotPage(
    width: Dp = SnapshotPageWidth,
    height: Dp = SnapshotPageHeight,
    dark: Boolean = false,
    content: @Composable () -> Unit,
) {
    SnapshotScene(dark = dark) {
        Box(
            modifier = Modifier
                .requiredSize(width, height)
                .testTag(SNAPSHOT_ROOT_TAG),
        ) {
            content()
        }
    }
}

/**
 * A content-sized crop: the same environment, but the image is exactly as tall as
 * what was drawn.
 *
 * For a card or a dock this makes a small golden whose height change is a dimension
 * failure rather than a percentage difference.
 */
@Composable
internal fun SnapshotCrop(
    width: Dp = SnapshotPageWidth,
    dark: Boolean = false,
    content: @Composable () -> Unit,
) {
    SnapshotScene(dark = dark) {
        Box(modifier = Modifier.width(width).testTag(SNAPSHOT_ROOT_TAG)) {
            content()
        }
    }
}

/**
 * The shared part of both crops: pinned density, locale, theme, motion and insets.
 */
@Composable
internal fun SnapshotScene(dark: Boolean = false, content: @Composable () -> Unit) {
    val base = LocalContext.current
    val localized = remember(base) { localizedSnapshotContext(base) }
    val density = Density(SNAPSHOT_DENSITY, fontScale = SNAPSHOT_FONT_SCALE)
    // A page can use an activity-result launcher (the session screen's photo and file
    // pickers) or a BackHandler. `ComponentActivity` is both owners, but a test rule's
    // `setContent` does not necessarily publish them as composition locals, so the
    // scene republishes the activity it is running in. Nothing is launched in a
    // snapshot; the owner only has to exist.
    val lifecycleOwner = LocalLifecycleOwner.current
    val backOwner = lifecycleOwner as? OnBackPressedDispatcherOwner
        ?: remember(lifecycleOwner) {
            object : OnBackPressedDispatcherOwner {
                override val onBackPressedDispatcher = OnBackPressedDispatcher()
                override val lifecycle: Lifecycle get() = lifecycleOwner.lifecycle
            }
        }
    // `LocalActivityResultRegistryOwner` is non-nullable and its default throws, so the
    // host activity itself is the owner -- it implements the interface, and the test
    // rule's composition is hosted by it (`LocalLifecycleOwner` is the activity).
    val resultOwner = lifecycleOwner as? ComponentActivity
        ?: error("snapshot: the compose test host is not a ComponentActivity")
    CompositionLocalProvider(
        LocalDensity provides density,
        LocalContext provides localized,
        LocalConfiguration provides localized.resources.configuration,
        LocalActivityResultRegistryOwner provides resultOwner,
        LocalOnBackPressedDispatcherOwner provides backOwner,
    ) {
        MizarTheme(mode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT) {
            // Inside the theme: MizarTheme installs the system's Reduce Motion value
            // from Settings.Global, and an inner provider is what overrides it.
            CompositionLocalProvider(LocalReduceMotion provides true) {
                Box(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.background)
                        .consumeWindowInsets(WindowInsets.systemBars)
                        .consumeWindowInsets(WindowInsets.safeDrawing),
                ) {
                    content()
                }
            }
        }
    }
}

/**
 * The context a golden is drawn in: `en-US`, density 2, a 390x844 window, light.
 *
 * `stringResource` reads `LocalContext.current.resources` (with `LocalConfiguration`
 * as the invalidation key), `LocalContext.current.getString` reads the context, and
 * the configuration is what popovers and the dock size themselves against; all three
 * must agree or the pinned numbers only half apply.
 */
internal fun localizedSnapshotContext(base: Context): Context {
    val configuration = Configuration(base.resources.configuration).apply {
        setLocale(SNAPSHOT_LOCALE)
        fontScale = SNAPSHOT_FONT_SCALE
        densityDpi = (SNAPSHOT_DENSITY * 160f).toInt()
        screenWidthDp = SNAPSHOT_WIDTH_DP
        screenHeightDp = SNAPSHOT_HEIGHT_DP
        smallestScreenWidthDp = SNAPSHOT_WIDTH_DP
        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_NO
    }
    return base.createConfigurationContext(configuration)
}

/** A string in the snapshot locale, for fixtures and for finding the nodes to mask. */
internal fun snapshotText(@StringRes id: Int): String =
    localizedSnapshotContext(InstrumentationRegistry.getInstrumentation().targetContext).getString(id)

/**
 * Capture the tagged crop and compare it with (or record it as) [name]'s golden.
 *
 * `waitForIdle` runs first so one-shot animations settle at their end state; captures
 * that must not wait for an animation take the bitmap themselves instead.
 */
internal fun ComposeTestRule.captureSnapshot(
    name: String,
    tolerance: Snapshot.Tolerance = Snapshot.Tolerance(),
    masks: List<Snapshot.Mask> = emptyList(),
) {
    waitForIdle()
    val image = onNodeWithTag(SNAPSHOT_ROOT_TAG).captureToImage().asAndroidBitmap()
    Snapshot.assertMatches(name, image, tolerance, masks)
}

/**
 * Masks covering every node whose text is one of [texts].
 *
 * The rectangle comes from the semantics tree, so it follows the layout instead of
 * naming a guessed coordinate: if a page is restyled and the masked line moves, the
 * mask moves with it. Used for the one region on these pages that cannot be frozen --
 * the home view's greeting, which is chosen from the phone's current hour.
 */
internal fun ComposeTestRule.masksOfText(vararg texts: String): List<Snapshot.Mask> {
    val frame: Rect = onNodeWithTag(SNAPSHOT_ROOT_TAG).fetchSemanticsNode().boundsInRoot
    return texts.flatMap { text ->
        onAllNodesWithText(text).fetchSemanticsNodes().map { node ->
            maskOf(node.boundsInRoot, frame)
        }
    }
}

/**
 * Masks the whole horizontal band a line of text sits in.
 *
 * The greeting's *bounds* depend on its wording: "Good morning" and "Good afternoon"
 * are different widths, so a mask taken from the current node would leave the longer
 * golden's tails comparable and fail for the wrong reason. The band is the node's
 * vertical extent across the full frame width -- the line is centred, so nothing else
 * shares it.
 */
internal fun ComposeTestRule.masksOfTextLine(vararg texts: String): List<Snapshot.Mask> {
    val frame: Rect = onNodeWithTag(SNAPSHOT_ROOT_TAG).fetchSemanticsNode().boundsInRoot
    return texts.flatMap { text ->
        onAllNodesWithText(text).fetchSemanticsNodes().map { node ->
            val bounds = node.boundsInRoot
            Snapshot.Mask(
                left = 0,
                top = floor(bounds.top - frame.top).toInt() - TEXT_MASK_PAD_PX,
                right = ceil(frame.right - frame.left).toInt(),
                bottom = ceil(bounds.bottom - frame.top).toInt() + TEXT_MASK_PAD_PX,
            )
        }
    }
}

/** Room for the anti-aliased edge of a masked glyph line. */
private const val TEXT_MASK_PAD_PX = 2

/** Convert a node's bounds into a padded rectangle of the captured image. */
private fun maskOf(bounds: Rect, frame: Rect, padPx: Int = TEXT_MASK_PAD_PX): Snapshot.Mask =
    Snapshot.Mask(
        left = floor(bounds.left - frame.left).toInt() - padPx,
        top = floor(bounds.top - frame.top).toInt() - padPx,
        right = ceil(bounds.right - frame.left).toInt() + padPx,
        bottom = ceil(bounds.bottom - frame.top).toInt() + padPx,
    )
