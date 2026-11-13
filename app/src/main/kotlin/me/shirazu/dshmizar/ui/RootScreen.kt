package me.shirazu.dshmizar.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import android.content.Context
import android.content.Intent
import android.provider.Settings
import me.shirazu.dshmizar.AccountMode
import me.shirazu.dshmizar.AppViewModel
import me.shirazu.dshmizar.PairingStage
import me.shirazu.dshmizar.Phase
import me.shirazu.dshmizar.R
import me.shirazu.dshmizar.domain.OnboardingVersion
import me.shirazu.dshmizar.ui.onboarding.OnboardingAccount
import me.shirazu.dshmizar.ui.onboarding.OnboardingActions
import me.shirazu.dshmizar.ui.onboarding.OnboardingRefresh
import me.shirazu.dshmizar.ui.onboarding.OnboardingRefreshState
import me.shirazu.dshmizar.ui.onboarding.OnboardingScreen
import me.shirazu.dshmizar.ui.onboarding.OnboardingState
import me.shirazu.dshmizar.ui.onboarding.OnboardingStep
import me.shirazu.dshmizar.ui.pairing.FailedScreen
import me.shirazu.dshmizar.ui.pairing.UnpairedScreen
import me.shirazu.dshmizar.ui.shell.AnnouncementBannerHost
import me.shirazu.dshmizar.ui.design.RefreshSpinner
import me.shirazu.dshmizar.ui.design.LocalHapticsEnabled
import me.shirazu.dshmizar.ui.design.MizarHapticsState
import me.shirazu.dshmizar.ui.design.Transitions
import me.shirazu.dshmizar.ui.shell.MizarShell
import me.shirazu.mizar.kit.PairingUri
import me.shirazu.mizar.kit.account.AuthUri

/**
 * The root: a switch on the connection's phase.
 *
 * There is no navigation graph behind this, deliberately. Which screen the operator
 * is on *is* a property of the connection — the app can be unpaired, connecting, or
 * unable to reach a Mac it knows — and modelling that as a route made the two
 * disagree the first time the socket dropped.
 *
 * Once the connection is READY the app lives in the shell: a sidebar of sessions
 * underneath, the conversation as a card on top (shell/MizarShell.kt).
 */
@Composable
fun RootScreen(model: AppViewModel, incomingUri: String?, onUriHandled: () -> Unit) {
    val state by model.state.collectAsState()

    // An effect, not an `if` in the composition. Pairing is a side effect that spends
    // a single-use code, and running it inline meant every recomposition between the
    // scan and the state update could spend it again. Keyed on the URI so a second
    // scan of the same code is still one pairing. A sign-in callback is the same
    // shape: one handoff, spent once.
    LaunchedEffect(incomingUri) {
        val uri = incomingUri ?: return@LaunchedEffect
        if (AuthUri.isAuth(uri)) {
            model.handleAuthUri(uri)
        } else if (uri.startsWith("mizar://account")) {
            model.openAccountScreen()
        } else {
            PairingUri.parse(uri)?.let { target -> model.pair(target, "Android") }
        }
        onUriHandled()
    }

    SideEffect { MizarHapticsState.enabled = state.hapticsEnabled }
    CompositionLocalProvider(
        LocalHapticsEnabled provides state.hapticsEnabled,
        me.shirazu.dshmizar.ui.design.LocalSlowTypewriter provides state.slowTypewriter,
    ) {
    Box(Modifier.fillMaxSize()) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // Above both screens on purpose: a lost connection matters whether the
            // operator is reading a conversation or looking at the list, and it is
            // shown rather than navigated to so nothing on screen is thrown away.
            // The 2 s delay lives in the view model's `reconnecting` flag; a one-second
            // network flap must not flash this.
            // It takes its height in one step and arrives by fading and rising its
            // last few points; leaving, it fades, then gives its height back. The
            // conversation below moves once each way and never animates.
            AnimatedVisibility(
                visible = state.reconnecting,
                enter = Transitions.riseIn(distance = 6.dp),
                exit = fadeOut(tween(160)),
            ) {
                ReconnectingBanner(state.disconnectReason)
            }
            Box(Modifier.weight(1f)) {
                when (state.phase) {
                    Phase.LAUNCHING -> Centered { CircularProgressIndicator() }
                    Phase.UNPAIRED -> UnpairedScreen(model, state.account)
                    Phase.CONNECTING -> Centered {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(12.dp))
                            Text(
                                stringResource(
                                    when (state.pairingStage) {
                                        PairingStage.PAIRING -> R.string.pairing_stage_pairing
                                        PairingStage.SYNCING -> R.string.pairing_stage_syncing
                                        null -> R.string.connecting
                                    },
                                ),
                            )
                            // The first pairing reads every session's title, which is
                            // the only part of it that takes visible time. A count and
                            // an estimate say whether to wait or put the phone down.
                            state.syncProgress?.let { progress ->
                                Spacer(Modifier.height(14.dp))
                                LinearProgressIndicator(
                                    progress = { progress.fraction },
                                    modifier = Modifier.width(180.dp),
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = stringResource(
                                        R.string.pairing_sync_count,
                                        progress.done,
                                        progress.total,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                state.syncEtaMillis?.let { eta ->
                                    Text(
                                        text = etaLabel(eta),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }

                    Phase.READY -> MizarShell(model, state)
                    Phase.FAILED -> if (state.offlineBrowser) {
                        Column(Modifier.fillMaxSize()) {
                            OfflineBanner(onRetry = { model.retry() })
                            Box(Modifier.weight(1f)) { MizarShell(model, state) }
                        }
                    } else {
                        FailedScreen(
                            state,
                            onRetry = { model.retry() },
                            onPairAgain = { model.startPairing() },
                            onBrowseOffline = { model.browseOffline() },
                        )
                    }
                }
            }
        }
    }
        // The first-run guide covers everything, including the unpaired screen: a
        // phone holding no pairing code is exactly who it is written for. It is a
        // cover rather than a phase, like the account screen, because it must be
        // able to explain the computer before any connection exists.
        if (state.onboardingOpen) {
            val context = LocalContext.current
            val clipboard = LocalClipboardManager.current
            var page by rememberSaveable { mutableIntStateOf(0) }
            var refresh by remember { mutableStateOf(OnboardingRefresh()) }
            // The local-agent step needs the kernel's own answer; it is read when the
            // guide opens rather than at launch, because booting it costs a kernel.
            LaunchedEffect(Unit) { model.refreshLocalAgentStatus() }
            val runRefresh: () -> Unit = {
                refresh = OnboardingRefresh(OnboardingRefreshState.RUNNING)
                model.refreshSessionMetadata { sessions, reached ->
                    refresh = OnboardingRefresh(
                        state = if (reached) OnboardingRefreshState.DONE else OnboardingRefreshState.FAILED,
                        sessions = sessions,
                    )
                }
            }
            val pages = OnboardingStep.entries
            val guide = OnboardingState(
                step = pages[page.coerceIn(0, pages.lastIndex)],
                isUpdate = state.onboardingUpdate,
                account = OnboardingAccount(
                    signedIn = state.account.session != null,
                    displayName = state.account.profile?.username,
                    isPaid = state.account.isPaid,
                ),
                localNetworkFirst = state.localNetworkFirst,
                // The guide says what this build can honestly do: a PoC kernel that
                // has not been given a provider is available but unconfigured.
                wantsLocalAgent = state.localAgentActive,
                localAgentAvailable = state.localAgentStatus?.available == true,
                localAgentConfigured = state.localAgentStatus?.configured == true,
                notificationsEnabled = state.notifyCompleted && state.notifyInterrupted &&
                    state.notifyQuestions && state.notifyApprovals,
                liveUpdatesSupported = android.os.Build.VERSION.SDK_INT >= 36,
                refresh = refresh,
            )
            OnboardingScreen(
                state = guide,
                actions = OnboardingActions(
                    onNext = {
                        if (page >= pages.lastIndex) model.finishOnboarding() else page += 1
                    },
                    onBack = { if (page > 0) page -= 1 },
                    onFinish = { model.finishOnboarding() },
                    onOpenDesktopDownload = { openUrl(context, OnboardingVersion.DESKTOP_RELEASE_URL) },
                    onCopyDesktopLink = {
                        clipboard.setText(AnnotatedString(OnboardingVersion.DESKTOP_RELEASE_URL))
                    },
                    onOpenAccount = { model.openAccountScreen() },
                    onCreateAccount = {
                        model.setAccountMode(AccountMode.REGISTER)
                        model.openAccountScreen()
                    },
                    onLocalNetworkFirst = { model.setLocalNetworkFirst(it) },
                    onNotificationsEnabled = { on ->
                        model.setNotifyCompleted(on)
                        model.setNotifyInterrupted(on)
                        model.setNotifyQuestions(on)
                        model.setNotifyApprovals(on)
                    },
                    onOpenSystemNotificationSettings = {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    },
                    onRedeemCode = { model.openRedeem() },
                    onOpenBalance = { model.refreshBalance() },
                    onRetryRefresh = runRefresh,
                    onRefreshShown = runRefresh,
                ),
            )
        }
        // Banners sit above the shell and below modal sheets: an account screen or a
        // survey covers them rather than letting them float over a modal.
        val banners by model.announcementBanner.collectAsState()
        AnnouncementBannerHost(
            state = banners,
            onAction = { action -> model.handleAnnouncementAction(action) },
            onDismiss = { model.dismissAnnouncement() },
            modifier = Modifier.align(Alignment.TopCenter).zIndex(1f),
        )
        // A sheet over everything, not a phase: the account works while the host
        // is unreachable, and the sign-in callback can arrive in any phase.
        if (state.account.screenOpen) {
            me.shirazu.dshmizar.ui.account.AccountScreen(
                model = model,
                account = state.account,
                onClose = { model.closeAccountScreen() },
            )
        }
        // Above the shell and independent of the account screen: the figure is the
        // computer's, so it opens whether or not this phone is signed in.
        if (state.account.redeemOpen) {
            me.shirazu.dshmizar.ui.account.RedeemDialog(model = model, account = state.account)
        }
        // The surveys sit above everything else: they are opened from settings or
        // from a banner, and they are a modal flow of their own.
        val surveyUi by model.surveyState.collectAsState()
        if (surveyUi.open) {
            me.shirazu.dshmizar.ui.account.surveys.SurveysRoute(
                coordinator = model.surveysCoordinator,
                onClose = { model.closeSurveys() },
                onSignIn = {
                    model.closeSurveys()
                    model.openAccountScreen()
                },
            )
        }
    }
    }
}

/**
 * A quiet strip over the shell while it shows cached data, with the one action that
 * matters: try the host again.
 */
@Composable
private fun OfflineBanner(onRetry: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.offline_banner),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
    }
}

/**
 * One narrow line saying the socket is being dialled again.
 *
 * Kept to a line because the interesting part is that it goes away: the screen below it
 * is still readable, which is the whole point of retrying instead of failing.
 */
@Composable
private fun ReconnectingBanner(reason: String?) {
    // The console's own neutral, with a hairline under it: a note on top of the
    // screen, not a coloured alarm. The three-dash spinner is the app's own sign
    // of work in progress.
    val hairline = MaterialTheme.colorScheme.outlineVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .drawBehind {
                val h = 1.dp.toPx()
                drawRect(hairline, topLeft = Offset(0f, size.height - h), size = Size(size.width, h))
            }
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RefreshSpinner(size = 16.dp, spinning = true)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                text = stringResource(R.string.reconnecting),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (reason != null) {
                Text(
                    text = reason,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

/**
 * Open a link in whatever the system uses for it.
 *
 * Wrapped because this is a side effect on someone else's app: a device with no
 * browser must not crash the guide, so a failure is swallowed.
 */
private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/**
 * "About 1 min 12 s left".
 *
 * Rounded up, because an estimate that says "0 s left" and then takes two more
 * seconds is the one that makes an operator stop believing the rest of the screen.
 */
@Composable
private fun etaLabel(millis: Long): String {
    val seconds = ((millis + 999) / 1000).coerceAtLeast(0)
    val span = if (seconds < 60) {
        stringResource(R.string.sync_eta_seconds, seconds)
    } else {
        stringResource(R.string.sync_eta_minutes, seconds / 60, seconds % 60)
    }
    return stringResource(R.string.pairing_sync_eta, span)
}
