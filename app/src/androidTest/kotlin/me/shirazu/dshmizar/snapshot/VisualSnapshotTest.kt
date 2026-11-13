package me.shirazu.dshmizar.snapshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.shirazu.dshmizar.R
import me.shirazu.dshmizar.ui.account.AccountScreen
import me.shirazu.dshmizar.ui.conversation.ContextContent
import me.shirazu.dshmizar.ui.conversation.ConversationItemView
import me.shirazu.dshmizar.ui.conversation.InteractionsDock
import me.shirazu.dshmizar.ui.conversation.SessionScreen
import me.shirazu.dshmizar.ui.design.PopoverSize
import me.shirazu.dshmizar.ui.settings.SettingsDialog
import me.shirazu.dshmizar.ui.shell.HomeView
import me.shirazu.dshmizar.ui.shell.ShellState
import me.shirazu.dshmizar.ui.shell.Sidebar
import me.shirazu.mizar.kit.ConversationItem
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import java.util.TimeZone

/**
 * The visual suite: every page the app owns, rendered in one fixed environment and
 * compared with a committed golden.
 *
 * One class on purpose. A snapshot class is a unit of work for the test runner: it
 * can be sharded onto a device as-is, and nothing here shares mutable state with
 * anything else, so the class can run beside the behavioural tests without either
 * side having to be ordered around the other. Adding a page is adding a `@Test`
 * here (or a new class with the same three lines), recording it once, reviewing the
 * PNG and committing it. The frames are `SnapshotPage` (a 390x844 phone) and
 * `SnapshotCrop` (as tall as the content) -- see `SnapshotScene.kt` for what is
 * pinned and why.
 *
 * What the suite is for: catching the change nobody looked at -- a spacing token,
 * a colour, an icon that fell to the wrong tint, a label that lost its figure. What
 * it is not for: behaviour (the other instrumentation tests) or system UI (Live
 * Updates is a notification; `adb screencap` and `LiveTurnTest` cover it, and see
 * `docs/android-testing.md` for that boundary).
 */
@RunWith(AndroidJUnit4::class)
class VisualSnapshotTest {

    @get:Rule
    val rule = createComposeRule()

    /**
     * Pin the process-wide formatting state too.
     *
     * The resources are pinned per-composition in `SnapshotScene`, but `DateUtils`
     * (the session list's "0 min. ago") and `DateFormat` read `Locale`/`TimeZone`
     * from the process. A Chinese emulator and an English CI runner must record the
     * same golden, so both are pinned for the run and restored afterwards.
     */
    companion object {
        private var savedLocale: Locale? = null
        private var savedTimeZone: TimeZone? = null

        @JvmStatic
        @BeforeClass
        fun pinFormatting() {
            savedLocale = Locale.getDefault()
            savedTimeZone = TimeZone.getDefault()
            Locale.setDefault(Locale.US)
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        }

        @JvmStatic
        @AfterClass
        fun restoreFormatting() {
            savedLocale?.let(Locale::setDefault)
            savedTimeZone?.let(TimeZone::setDefault)
        }
    }

    // ── the answer card (the one baseline that predates this suite) ────────────

    /**
     * The card every release touches, kept exactly as it was recorded: 360 dp wide,
     * fixed density, one assistant answer. This is the port of the original
     * `ConversationSnapshotTest`, so its golden (`answer-card.png`) stayed valid.
     */
    @Test
    fun theAnswerCardMatchesItsGolden() {
        rule.setContent {
            SnapshotCrop(width = 360.dp) {
                Box(
                    modifier = Modifier
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
        rule.captureSnapshot("answer-card")
    }

    // ── the pages ──────────────────────────────────────────────────────────────

    /**
     * Home, with a host and two workspaces and the composer ready.
     *
     * The greeting above the machine's name is picked from the phone's current hour
     * (`greeting_morning` .. `greeting_night`), so it cannot be recorded: the region
     * is masked by the semantics bounds of whichever greeting is on screen. Everything
     * else on the page -- the mark, the name, the composer, the workspace and model
     * chips -- is compared.
     */
    @Test
    fun theHomePageMatchesItsGolden() {
        rule.setContent {
            SnapshotPage {
                HomeView(
                    model = SnapshotFixtures.model,
                    state = SnapshotFixtures.homeState(),
                    onOpenSidebar = {},
                )
            }
        }
        val greetings = listOf(
            R.string.greeting_morning,
            R.string.greeting_afternoon,
            R.string.greeting_evening,
            R.string.greeting_night,
        ).map(::snapshotText)
        rule.captureSnapshot("home", masks = rule.masksOfTextLine(*greetings.toTypedArray()))
    }

    /**
     * The session page: the app bar with the session's status, a finished turn with
     * its folded tool step and delivered file, the composer and the figures above it.
     */
    @Test
    fun theSessionPageMatchesItsGolden() {
        rule.setContent {
            SnapshotPage {
                SessionScreen(
                    model = SnapshotFixtures.model,
                    state = SnapshotFixtures.sessionState(),
                )
            }
        }
        rule.captureSnapshot("session")
    }

    /**
     * The approval dock on its own: a deck of three, so the card behind the front
     * one, the position marker and the Next pill are all in the golden.
     */
    @Test
    fun theApprovalDockMatchesItsGolden() {
        rule.setContent {
            SnapshotCrop {
                Box(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.background)
                        .padding(vertical = 12.dp),
                ) {
                    InteractionsDock(
                        pending = SnapshotFixtures.pending(),
                        titles = SnapshotFixtures.titles(),
                        selectedSessionId = SnapshotFixtures.OPEN_SESSION_ID,
                        composerFocused = false,
                        onExpand = {},
                        onApproval = { _, _ -> },
                        onQuestions = { _, _ -> },
                    )
                }
            }
        }
        rule.captureSnapshot("approval-dock")
    }

    /**
     * The sidebar: header, search, the new-session key, both workspaces expanded with
     * their rows, and the connection footer.
     */
    @Test
    fun theSidebarMatchesItsGolden() {
        rule.setContent {
            SnapshotPage {
                val scope = rememberCoroutineScope()
                val shell = remember {
                    ShellState(
                        scope = scope,
                        reduceMotion = { true },
                        dismissKeyboard = {},
                    ).apply {
                        setExpanded(SnapshotFixtures.expandedWorkspaces(), SnapshotFixtures.HOST_ID)
                    }
                }
                Sidebar(
                    model = SnapshotFixtures.model,
                    state = SnapshotFixtures.sidebarState(),
                    shell = shell,
                )
            }
        }
        rule.captureSnapshot("sidebar")
    }

    /**
     * The composer's context sheet, with the compact command in it.
     *
     * A popover is its own window, so it cannot be captured as part of the page
     * crop; its content is snapshot-able on its own, and that is the half that
     * matters: the figures, the em dashes for what the host did not send, and the
     * "Compact the context" row that appears only when compacting is available.
     */
    @Test
    fun theContextSheetMatchesItsGolden() {
        rule.setContent {
            SnapshotCrop(width = PopoverSize.COMPACT_WIDTH) {
                Box(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
                    ContextContent(
                        context = SnapshotFixtures.stats().context,
                        onCompact = {},
                    )
                }
            }
        }
        rule.captureSnapshot("context-sheet")
    }

    /**
     * The settings directory -- the front page of the shelves, not a page behind one.
     *
     * Recorded at the width the sidebar's popover gives it (272 dp) so the row copy
     * is compared at the size it is read at.
     */
    @Test
    fun theSettingsDirectoryMatchesItsGolden() {
        rule.setContent {
            SnapshotCrop(width = 272.dp) {
                Box(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
                    val state = SnapshotFixtures.sidebarState()
                    SettingsDialog(
                        state = state,
                        onDismiss = {},
                        onOpenAccount = {},
                        onOpenBalance = {},
                        onOpenRedeem = {},
                        onForget = {},
                        onAddHost = {},
                        onTheme = {},
                        onQuietHours = {},
                        onNotifyCompleted = {},
                        onNotifyInterrupted = {},
                        onNotifyQuestions = {},
                        onNotifyApprovals = {},
                        onHaptics = {},
                        onSlowTypewriter = {},
                        onLocalNetworkFirst = {},
                        onExportLogs = {},
                    )
                }
            }
        }
        rule.captureSnapshot("settings")
    }

    /**
     * The account screen, signed out: the two-halves control, the two fields and the
     * three ways in.
     */
    @Test
    fun theSignedOutAccountPageMatchesItsGolden() {
        rule.setContent {
            SnapshotPage {
                AccountScreen(
                    model = SnapshotFixtures.model,
                    account = SnapshotFixtures.accountSignedOut(),
                    onClose = {},
                )
            }
        }
        rule.captureSnapshot("account-signed-out")
    }

    /**
     * The account screen, signed in: the profile, the plan, the devices and the invite
     * section. No end date on the plan: a date would be formatted in the device's
     * time zone, and a golden that only holds in some zones is not a golden.
     */
    @Test
    fun theSignedInAccountPageMatchesItsGolden() {
        rule.setContent {
            SnapshotPage {
                AccountScreen(
                    model = SnapshotFixtures.model,
                    account = SnapshotFixtures.accountSignedIn(),
                    onClose = {},
                )
            }
        }
        rule.captureSnapshot("account-signed-in")
    }
}
