package me.shirazu.dshmizar.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import me.shirazu.dshmizar.R
import me.shirazu.dshmizar.domain.OnboardingVersion
import me.shirazu.dshmizar.ui.AccentDark
import me.shirazu.dshmizar.ui.MizarColors
import me.shirazu.dshmizar.ui.design.ContinuousCornerShape
import me.shirazu.dshmizar.ui.design.Corner
import me.shirazu.dshmizar.ui.design.LocalReduceMotion
import me.shirazu.dshmizar.ui.design.MizarMarkConnecting
import me.shirazu.dshmizar.ui.design.Space

/**
 * The first-run guide: nine pages, walked once and re-walked once per guide
 * version, with the way out on every page.
 *
 * Ported from iOS `Views/Onboarding/OnboardingView.swift`, with the differences
 * Android's product shape demands (no sign-in gate, no store purchase, no Apple
 * sign-in) recorded in [OnboardingModel].
 *
 * Stateless: it draws [OnboardingState] and reports through [OnboardingActions].
 * The screen keeps exactly one piece of its own state -- whether the "link copied"
 * tick is showing -- because that is feedback for the tap itself, not a fact about
 * the app.
 *
 * The whole guide is drawn on the brand navy in both themes, as iOS forces the
 * dark appearance: the first screen is the product's face, not the operator's
 * preference, and every page after it is drawn over the same ground.
 */
@Composable
fun OnboardingScreen(
    state: OnboardingState,
    actions: OnboardingActions,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = LocalReduceMotion.current
    BackHandler(enabled = state.canGoBack) { actions.onBack() }

    Box(modifier.fillMaxSize()) {
        Background()
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Progress(state = state, onBack = actions.onBack)
            AnimatedContent(
                targetState = state.step,
                transitionSpec = {
                    val forward = targetState.ordinal > initialState.ordinal
                    if (reduceMotion) {
                        fadeIn(tween(220)) togetherWith fadeOut(tween(160))
                    } else {
                        val enter = slideInHorizontally(tween(260)) { width -> if (forward) width / 5 else -width / 5 } +
                            fadeIn(tween(220))
                        val exit = slideOutHorizontally(tween(200)) { width -> if (forward) -width / 5 else width / 5 } +
                            fadeOut(tween(160))
                        enter togetherWith exit
                    }
                },
                modifier = Modifier.weight(1f).fillMaxWidth(),
                label = "onboardingPage",
            ) { step ->
                PageContent(step = step, state = state, actions = actions)
            }
            Footer(state = state, actions = actions)
        }
    }
}

/** The navy ground, and one glow behind where the mark sits. */
@Composable
private fun Background() {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(MizarColors.Navy, MizarColors.NavyDeep))),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(420.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(AccentDark.copy(alpha = 0.20f), Color.Transparent),
                    ),
                ),
        )
    }
}

/**
 * The top line: back, one dot per page, and "3 / 9".
 *
 * The dots are the progress and the number is the exact page; both are drawn
 * because a dot a thumb away from its neighbour says how far along this is and the
 * number says how much is left.
 */
@Composable
private fun Progress(state: OnboardingState, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.xxl, vertical = Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.canGoBack) {
            Box(
                Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.10f))
                    .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape)
                    .clickable(onClick = onBack)
                    .testTag("onboarding.back"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.ChevronLeft,
                    contentDescription = stringResource(R.string.shell_onboarding_back),
                    modifier = Modifier.size(20.dp),
                    tint = Color.White,
                )
            }
        } else {
            Spacer(Modifier.size(38.dp))
        }
        Spacer(Modifier.width(Space.md))
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (step in OnboardingStep.entries) {
                val reached = step.ordinal <= state.step.ordinal
                Box(
                    Modifier
                        .height(4.dp)
                        .width(if (step == state.step) 22.dp else 6.dp)
                        .clip(CircleShape)
                        .background(
                            if (reached) AccentDark else Color.White.copy(alpha = 0.16f),
                        ),
                )
            }
        }
        Spacer(Modifier.width(Space.md))
        Text(
            text = "${state.number} / ${state.count}",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
            color = Color.White.copy(alpha = 0.55f),
            modifier = Modifier.testTag("onboarding.step"),
        )
    }
}

/** The footer's one button: Continue, and Start on the last page. */
@Composable
private fun Footer(state: OnboardingState, actions: OnboardingActions) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.xxl)
            .padding(top = Space.md, bottom = Space.lg),
    ) {
        if (state.step == OnboardingStep.ACCOUNT && !state.account.signedIn) {
            Text(
                text = stringResource(
                    if (state.isUpdate) {
                        R.string.shell_onboarding_account_keep_note
                    } else {
                        R.string.shell_onboarding_account_optional
                    },
                ),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.55f),
                modifier = Modifier.padding(bottom = Space.sm),
            )
        }
        val label = if (state.isLastStep) {
            stringResource(R.string.shell_onboarding_start)
        } else {
            stringResource(R.string.shell_onboarding_continue)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .background(AccentDark, ContinuousCornerShape(16.dp))
                .clickable(enabled = state.canAdvance) {
                    if (state.isLastStep) actions.onFinish() else actions.onNext()
                }
                .testTag(if (state.isLastStep) "onboarding.done" else "onboarding.next"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MizarColors.Navy,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * One page, scrolled as a whole: these pages grow with their copy, and a page that
 * clipped its last paragraph on a small phone would be a guide with a hole in it.
 */
@Composable
private fun PageContent(step: OnboardingStep, state: OnboardingState, actions: OnboardingActions) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Space.xxl)
            .padding(top = Space.sm, bottom = Space.xxl)
            .testTag("onboarding.page.${step.name.lowercase()}"),
    ) {
        when (step) {
            OnboardingStep.WELCOME -> WelcomePage()
            OnboardingStep.DESKTOP -> DesktopPage(actions)
            OnboardingStep.ACCOUNT -> AccountPage(state, actions)
            OnboardingStep.NETWORK -> NetworkPage(state, actions)
            OnboardingStep.AGENT -> AgentPage(state, actions)
            OnboardingStep.ALERTS -> AlertsPage(state, actions)
            OnboardingStep.PLAN -> PlanPage(state, actions)
            OnboardingStep.REFRESH -> RefreshPage(state, actions)
            OnboardingStep.SUMMARY -> SummaryPage(state)
        }
    }
}

// MARK: - Welcome

@Composable
private fun WelcomePage() {
    MizarMarkConnecting(
        modifier = Modifier.width(88.dp),
        accent = AccentDark,
        ink = Color.White,
    )
    Spacer(Modifier.height(Space.xl))
    PageHeader(
        title = stringResource(R.string.shell_onboarding_welcome_title),
        subtitle = stringResource(R.string.shell_onboarding_welcome_subtitle),
    )
    Spacer(Modifier.height(Space.lg))
    FeatureRow(
        icon = Icons.Default.Computer,
        title = stringResource(R.string.shell_onboarding_feature_computer_title),
        detail = stringResource(R.string.shell_onboarding_feature_computer_detail),
    )
    Spacer(Modifier.height(Space.md))
    FeatureRow(
        icon = Icons.Default.Person,
        title = stringResource(R.string.shell_onboarding_feature_phone_title),
        detail = stringResource(R.string.shell_onboarding_feature_phone_detail),
    )
    Spacer(Modifier.height(Space.md))
    FeatureRow(
        icon = Icons.Default.AccountBalanceWallet,
        title = stringResource(R.string.shell_onboarding_feature_account_title),
        detail = stringResource(R.string.shell_onboarding_feature_account_detail),
    )
}

// MARK: - Desktop

@Composable
private fun DesktopPage(actions: OnboardingActions) {
    PageHeader(
        icon = Icons.Default.Computer,
        title = stringResource(R.string.shell_onboarding_desktop_title),
        subtitle = stringResource(R.string.shell_onboarding_desktop_subtitle),
    )
    Spacer(Modifier.height(Space.lg))
    OnboardingCard {
        NumberedStep(
            1,
            stringResource(R.string.shell_onboarding_desktop_step_download_title),
            stringResource(R.string.shell_onboarding_desktop_step_download_detail),
        )
        Spacer(Modifier.height(Space.md))
        NumberedStep(
            2,
            stringResource(R.string.shell_onboarding_desktop_step_install_title),
            stringResource(R.string.shell_onboarding_desktop_step_install_detail),
        )
        Spacer(Modifier.height(Space.md))
        NumberedStep(
            3,
            stringResource(R.string.shell_onboarding_desktop_step_return_title),
            stringResource(R.string.shell_onboarding_desktop_step_return_detail),
        )
    }
    Spacer(Modifier.height(Space.lg))
    Text(
        text = stringResource(R.string.shell_onboarding_desktop_url_label),
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
        color = Color.White.copy(alpha = 0.78f),
    )
    Spacer(Modifier.height(Space.xs))
    Text(
        text = OnboardingVersion.DESKTOP_RELEASE_URL,
        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
        color = Color.White.copy(alpha = 0.62f),
        modifier = Modifier.testTag("onboarding.desktop.url"),
    )
    Spacer(Modifier.height(Space.md))
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2_000)
            copied = false
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        SecondaryButton(
            label = stringResource(R.string.shell_onboarding_desktop_open),
            icon = Icons.Default.OpenInNew,
            onClick = actions.onOpenDesktopDownload,
            modifier = Modifier.weight(1f).testTag("onboarding.desktop.open"),
        )
        SecondaryButton(
            label = stringResource(
                if (copied) R.string.shell_onboarding_desktop_copied else R.string.shell_onboarding_desktop_copy,
            ),
            icon = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
            onClick = {
                actions.onCopyDesktopLink()
                copied = true
            },
            modifier = Modifier.weight(1f).testTag("onboarding.desktop.copy"),
        )
    }
    Spacer(Modifier.height(Space.md))
    Note(stringResource(R.string.shell_onboarding_desktop_note))
}

// MARK: - Account

@Composable
private fun AccountPage(state: OnboardingState, actions: OnboardingActions) {
    PageHeader(
        icon = Icons.Default.Person,
        title = stringResource(R.string.shell_onboarding_account_title),
        subtitle = stringResource(R.string.shell_onboarding_account_subtitle),
    )
    Spacer(Modifier.height(Space.lg))
    if (state.account.signedIn) {
        OnboardingCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = AccentDark,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(Space.md))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = state.account.displayName
                            ?: stringResource(R.string.shell_onboarding_account_signed_in),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(
                            when (state.account.plan) {
                                PlanLabel.PAID -> R.string.shell_onboarding_plan_paid
                                PlanLabel.FREE -> R.string.shell_onboarding_plan_free
                            },
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.62f),
                    )
                }
            }
        }
        Spacer(Modifier.height(Space.md))
        SecondaryButton(
            label = stringResource(R.string.shell_onboarding_account_manage),
            icon = null,
            onClick = actions.onOpenAccount,
            modifier = Modifier.fillMaxWidth().testTag("onboarding.account.manage"),
        )
    } else {
        OnboardingCard {
            PrimaryButton(
                label = stringResource(R.string.shell_onboarding_account_sign_in),
                onClick = actions.onOpenAccount,
                modifier = Modifier.fillMaxWidth().testTag("onboarding.account.signIn"),
            )
            Spacer(Modifier.height(Space.sm))
            SecondaryButton(
                label = stringResource(R.string.shell_onboarding_account_create),
                icon = null,
                onClick = actions.onCreateAccount,
                modifier = Modifier.fillMaxWidth().testTag("onboarding.account.register"),
            )
        }
        Spacer(Modifier.height(Space.md))
        Note(stringResource(R.string.shell_onboarding_account_body))
    }
}

// MARK: - Network

@Composable
private fun NetworkPage(state: OnboardingState, actions: OnboardingActions) {
    PageHeader(
        icon = Icons.Default.Wifi,
        title = stringResource(R.string.shell_onboarding_network_title),
        subtitle = stringResource(R.string.shell_onboarding_network_subtitle),
    )
    Spacer(Modifier.height(Space.lg))
    ToggleCard(
        icon = Icons.Default.Wifi,
        title = stringResource(R.string.shell_onboarding_network_lan_title),
        detail = stringResource(R.string.shell_onboarding_network_lan_detail),
        checked = state.localNetworkFirst,
        onCheckedChange = actions.onLocalNetworkFirst,
        modifier = Modifier.testTag("onboarding.network.lan"),
    )
    Spacer(Modifier.height(Space.md))
    Note(stringResource(R.string.shell_onboarding_network_note))
}

// MARK: - Agent

@Composable
private fun AgentPage(state: OnboardingState, actions: OnboardingActions) {
    PageHeader(
        icon = Icons.Default.Memory,
        title = stringResource(R.string.shell_onboarding_agent_title),
        subtitle = stringResource(R.string.shell_onboarding_agent_subtitle),
    )
    Spacer(Modifier.height(Space.lg))
    ChoiceCard(
        selected = !state.wantsLocalAgent,
        icon = Icons.Default.Computer,
        title = stringResource(R.string.shell_onboarding_agent_computer_title),
        detail = stringResource(R.string.shell_onboarding_agent_computer_detail),
        onClick = { actions.onWantsLocalAgent(false) },
        modifier = Modifier.testTag("onboarding.agent.computer"),
    )
    Spacer(Modifier.height(Space.sm))
    ChoiceCard(
        selected = state.wantsLocalAgent,
        icon = Icons.Default.Memory,
        title = stringResource(R.string.shell_onboarding_agent_local_title),
        detail = stringResource(R.string.shell_onboarding_agent_local_detail),
        enabled = state.localAgentAvailable,
        onClick = { actions.onWantsLocalAgent(true) },
        modifier = Modifier.testTag("onboarding.agent.local"),
    )
    if (state.wantsLocalAgent && state.localAgentAvailable) {
        Spacer(Modifier.height(Space.md))
        OnboardingCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (state.localAgentConfigured) Icons.Default.CheckCircle else Icons.Default.Memory,
                    contentDescription = null,
                    tint = if (state.localAgentConfigured) AccentDark else Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(Space.md))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(
                            if (state.localAgentConfigured) {
                                R.string.shell_onboarding_agent_configured
                            } else {
                                R.string.shell_onboarding_agent_not_configured
                            },
                        ),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = Color.White,
                    )
                    Text(
                        text = stringResource(R.string.shell_onboarding_agent_configure_detail),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.62f),
                    )
                }
                Spacer(Modifier.width(Space.sm))
                SecondaryButton(
                    label = stringResource(R.string.shell_onboarding_agent_configure),
                    icon = null,
                    onClick = actions.onConfigureLocalAgent,
                    modifier = Modifier.testTag("onboarding.agent.configure"),
                )
            }
        }
    }
    Spacer(Modifier.height(Space.md))
    if (!state.localAgentAvailable) {
        Note(stringResource(R.string.shell_onboarding_agent_unavailable))
        Spacer(Modifier.height(Space.sm))
    }
    Note(stringResource(R.string.shell_onboarding_agent_note))
}

// MARK: - Alerts

@Composable
private fun AlertsPage(state: OnboardingState, actions: OnboardingActions) {
    PageHeader(
        icon = Icons.Default.Notifications,
        title = stringResource(R.string.shell_onboarding_alerts_title),
        subtitle = stringResource(R.string.shell_onboarding_alerts_subtitle),
    )
    Spacer(Modifier.height(Space.lg))
    ToggleCard(
        icon = Icons.Default.Notifications,
        title = stringResource(R.string.shell_onboarding_alerts_notifications_title),
        detail = stringResource(R.string.shell_onboarding_alerts_notifications_detail),
        checked = state.notificationsEnabled,
        onCheckedChange = actions.onNotificationsEnabled,
        modifier = Modifier.testTag("onboarding.alerts.notifications"),
    )
    if (state.liveUpdatesSupported) {
        Spacer(Modifier.height(Space.sm))
        ToggleCard(
            icon = Icons.Default.Notifications,
            title = stringResource(R.string.shell_onboarding_alerts_live_title),
            detail = stringResource(R.string.shell_onboarding_alerts_live_detail),
            checked = state.liveUpdatesEnabled,
            onCheckedChange = actions.onLiveUpdatesEnabled,
            modifier = Modifier.testTag("onboarding.alerts.liveUpdates"),
        )
    }
    Spacer(Modifier.height(Space.md))
    SecondaryButton(
        label = stringResource(R.string.shell_onboarding_alerts_system),
        icon = Icons.Default.OpenInNew,
        onClick = actions.onOpenSystemNotificationSettings,
        modifier = Modifier.fillMaxWidth().testTag("onboarding.alerts.system"),
    )
    Spacer(Modifier.height(Space.md))
    Note(stringResource(R.string.shell_onboarding_alerts_note))
}

// MARK: - Plan

@Composable
private fun PlanPage(state: OnboardingState, actions: OnboardingActions) {
    PageHeader(
        icon = Icons.Default.Star,
        title = stringResource(R.string.shell_onboarding_plan_title),
        subtitle = stringResource(R.string.shell_onboarding_plan_subtitle),
    )
    Spacer(Modifier.height(Space.lg))
    PlanCard(
        title = stringResource(R.string.shell_onboarding_plan_free),
        detail = stringResource(R.string.shell_onboarding_plan_free_detail),
        selected = !state.account.isPaid,
        modifier = Modifier.testTag("onboarding.plan.free"),
    )
    Spacer(Modifier.height(Space.sm))
    PlanCard(
        title = stringResource(R.string.shell_onboarding_plan_paid),
        detail = stringResource(R.string.shell_onboarding_plan_paid_detail),
        selected = state.account.isPaid,
        modifier = Modifier.testTag("onboarding.plan.paid"),
    )
    Spacer(Modifier.height(Space.md))
    OnboardingCard {
        if (state.account.isPaid && state.paidUntilLabel != null) {
            InfoRow(
                icon = Icons.Default.Star,
                title = stringResource(R.string.shell_onboarding_plan_paid_until),
                value = state.paidUntilLabel,
            )
        }
        if (state.balanceLabel != null) {
            InfoRow(
                icon = Icons.Default.AccountBalanceWallet,
                title = stringResource(R.string.shell_onboarding_plan_balance),
                value = state.balanceLabel,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            SecondaryButton(
                label = stringResource(R.string.shell_onboarding_plan_redeem),
                icon = Icons.Default.ConfirmationNumber,
                onClick = actions.onRedeemCode,
                modifier = Modifier.weight(1f).testTag("onboarding.plan.redeem"),
            )
            SecondaryButton(
                label = stringResource(R.string.shell_onboarding_plan_open_balance),
                icon = Icons.Default.AccountBalanceWallet,
                onClick = actions.onOpenBalance,
                modifier = Modifier.weight(1f).testTag("onboarding.plan.balance"),
            )
        }
    }
    Spacer(Modifier.height(Space.md))
    Note(stringResource(R.string.shell_onboarding_plan_note))
}

// MARK: - Refresh

@Composable
private fun RefreshPage(state: OnboardingState, actions: OnboardingActions) {
    // The read is a side effect of arriving here, not a state the screen can invent.
    LaunchedEffect(Unit) { actions.onRefreshShown() }
    PageHeader(
        icon = Icons.Default.Sync,
        title = stringResource(R.string.shell_onboarding_refresh_title),
        subtitle = stringResource(R.string.shell_onboarding_refresh_subtitle),
    )
    Spacer(Modifier.height(Space.lg))
    OnboardingCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            when (state.refresh.state) {
                OnboardingRefreshState.IDLE, OnboardingRefreshState.RUNNING -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = AccentDark,
                        strokeWidth = 2.dp,
                    )
                }
                OnboardingRefreshState.DONE -> Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = AccentDark,
                    modifier = Modifier.size(24.dp),
                )
                OnboardingRefreshState.FAILED -> Icon(
                    Icons.Default.Sync,
                    contentDescription = null,
                    tint = MizarColors.Warning,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.width(Space.md))
            Column(Modifier.weight(1f)) {
                Text(
                    text = when (state.refresh.state) {
                        OnboardingRefreshState.DONE -> stringResource(
                            R.string.shell_onboarding_refresh_done,
                            state.refresh.sessions,
                        )
                        OnboardingRefreshState.FAILED -> stringResource(R.string.shell_onboarding_refresh_failed)
                        else -> stringResource(R.string.shell_onboarding_refresh_running)
                    },
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = Color.White,
                )
                Text(
                    text = when (state.refresh.state) {
                        OnboardingRefreshState.DONE, OnboardingRefreshState.FAILED ->
                            stringResource(R.string.shell_onboarding_refresh_kept)
                        else -> stringResource(R.string.shell_onboarding_refresh_running_detail)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.62f),
                )
            }
        }
        if (state.refresh.state == OnboardingRefreshState.FAILED) {
            Spacer(Modifier.height(Space.md))
            SecondaryButton(
                label = stringResource(R.string.shell_onboarding_refresh_retry),
                icon = Icons.Default.Sync,
                onClick = actions.onRetryRefresh,
                modifier = Modifier.fillMaxWidth().testTag("onboarding.refresh.retry"),
            )
        }
    }
    Spacer(Modifier.height(Space.md))
    Note(stringResource(R.string.shell_onboarding_refresh_note))
}

// MARK: - Summary

@Composable
private fun SummaryPage(state: OnboardingState) {
    PageHeader(
        icon = Icons.Default.CheckCircle,
        title = stringResource(R.string.shell_onboarding_summary_title),
        subtitle = stringResource(R.string.shell_onboarding_summary_subtitle),
    )
    Spacer(Modifier.height(Space.lg))
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.07f), Corner.card)
            .border(1.dp, Color.White.copy(alpha = 0.10f), Corner.card)
            .padding(vertical = Space.xs)
            .testTag("onboarding.summary"),
    ) {
        SummaryRow(
            icon = Icons.Default.Person,
            title = stringResource(R.string.shell_onboarding_summary_account),
            value = state.account.displayName
                ?: stringResource(
                    if (state.account.signedIn) {
                        R.string.shell_onboarding_account_signed_in
                    } else {
                        R.string.shell_onboarding_summary_no_account
                    },
                ),
        )
        SummaryDivider()
        SummaryRow(
            icon = Icons.Default.Wifi,
            title = stringResource(R.string.shell_onboarding_summary_network),
            value = stringResource(
                if (state.localNetworkFirst) {
                    R.string.shell_onboarding_summary_lan_on
                } else {
                    R.string.shell_onboarding_summary_lan_off
                },
            ),
        )
        SummaryDivider()
        SummaryRow(
            icon = Icons.Default.Memory,
            title = stringResource(R.string.shell_onboarding_summary_agent),
            value = stringResource(
                when {
                    !state.wantsLocalAgent -> R.string.shell_onboarding_summary_agent_computer
                    state.localAgentConfigured -> R.string.shell_onboarding_summary_agent_local_ready
                    else -> R.string.shell_onboarding_summary_agent_local
                },
            ),
        )
        SummaryDivider()
        SummaryRow(
            icon = Icons.Default.Notifications,
            title = stringResource(R.string.shell_onboarding_summary_alerts),
            value = stringResource(
                if (state.notificationsEnabled) {
                    R.string.shell_onboarding_summary_on
                } else {
                    R.string.shell_onboarding_summary_off
                },
            ),
        )
        SummaryDivider()
        SummaryRow(
            icon = Icons.Default.Star,
            title = stringResource(R.string.shell_onboarding_summary_plan),
            value = stringResource(
                when (state.account.plan) {
                    PlanLabel.PAID -> R.string.shell_onboarding_plan_paid
                    PlanLabel.FREE -> R.string.shell_onboarding_plan_free
                },
            ),
        )
    }
}

// MARK: - Building blocks

/** A page's title block: the symbol, the sentence, and the sentence under it. */
@Composable
private fun PageHeader(icon: ImageVector? = null, title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth()) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = AccentDark,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.height(Space.sm))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
            color = Color.White,
        )
        Spacer(Modifier.height(Space.sm))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.62f),
        )
    }
}

/** One feature on the welcome page: a symbol in a tile, and two lines. */
@Composable
private fun FeatureRow(icon: ImageVector, title: String, detail: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .size(28.dp)
                .background(AccentDark.copy(alpha = 0.12f), ContinuousCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = AccentDark, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White,
            )
            Spacer(Modifier.height(1.dp))
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.55f),
            )
        }
    }
}

/** The card every page's content sits in: white at 7% on the navy, one hairline. */
@Composable
private fun OnboardingCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.07f), Corner.card)
            .border(1.dp, Color.White.copy(alpha = 0.10f), Corner.card)
            .padding(Space.lg),
        content = content,
    )
}

/** One numbered instruction: the number in an accent disc, the words beside it. */
@Composable
private fun NumberedStep(number: Int, title: String, detail: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .size(22.dp)
                .background(AccentDark, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = number.toString(),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = MizarColors.Navy,
            )
        }
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.55f),
            )
        }
    }
}

/** A switch with a symbol and two lines; the whole card answers the track. */
@Composable
private fun ToggleCard(
    icon: ImageVector,
    title: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.07f), Corner.card)
            .border(1.dp, Color.White.copy(alpha = 0.10f), Corner.card)
            .padding(Space.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = AccentDark, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.55f),
            )
        }
        Spacer(Modifier.width(Space.sm))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MizarColors.Navy,
                checkedTrackColor = AccentDark,
                uncheckedThumbColor = Color.White.copy(alpha = 0.75f),
                uncheckedTrackColor = Color.White.copy(alpha = 0.14f),
                uncheckedBorderColor = Color.White.copy(alpha = 0.22f),
            ),
        )
    }
}

/** A two-way choice: the card is the radio, the tick says which way it is set. */
@Composable
private fun ChoiceCard(
    selected: Boolean,
    icon: ImageVector,
    title: String,
    detail: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Color.White.copy(alpha = if (selected) 0.10f else 0.06f),
                Corner.card,
            )
            .border(
                1.dp,
                if (selected) AccentDark.copy(alpha = 0.75f) else Color.White.copy(alpha = 0.10f),
                Corner.card,
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(Space.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(38.dp)
                .background(
                    if (selected) AccentDark else AccentDark.copy(alpha = 0.12f),
                    ContinuousCornerShape(11.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected) MizarColors.Navy else AccentDark,
                modifier = Modifier.size(19.dp),
            )
        }
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White.copy(alpha = if (enabled) 1f else 0.5f),
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = if (enabled) 0.55f else 0.35f),
            )
        }
        Spacer(Modifier.width(Space.sm))
        Icon(
            if (selected) Icons.Default.CheckCircle else Icons.Default.ChevronRight,
            contentDescription = null,
            tint = if (selected) AccentDark else Color.White.copy(alpha = 0.28f),
            modifier = Modifier.size(20.dp),
        )
    }
}

/** A plan, named and explained; the current one is ticked. */
@Composable
private fun PlanCard(
    title: String,
    detail: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Color.White.copy(alpha = if (selected) 0.10f else 0.055f),
                Corner.card,
            )
            .border(
                1.dp,
                if (selected) AccentDark.copy(alpha = 0.75f) else Color.White.copy(alpha = 0.10f),
                Corner.card,
            )
            .padding(Space.lg),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            if (selected) Icons.Default.CheckCircle else Icons.Default.ChevronRight,
            contentDescription = null,
            tint = if (selected) AccentDark else Color.White.copy(alpha = 0.28f),
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.55f),
            )
        }
    }
}

/** One line of the summary card: what it is, and what was chosen. */
@Composable
private fun SummaryRow(icon: ImageVector, title: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = AccentDark, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(Space.md))
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.62f),
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
            color = Color.White,
            maxLines = 2,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun SummaryDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 46.dp)
            .height(1.dp)
            .background(Color.White.copy(alpha = 0.08f)),
    )
}

/** One quiet line under a page: the fine print, said once. */
@Composable
private fun Note(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = Color.White.copy(alpha = 0.48f),
    )
}

/** The guide's filled call to action, and its quiet sibling. */
@Composable
private fun PrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .height(48.dp)
            .background(AccentDark, ContinuousCornerShape(14.dp))
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MizarColors.Navy,
        )
    }
}

@Composable
private fun SecondaryButton(
    label: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .height(44.dp)
            .background(Color.White.copy(alpha = 0.08f), ContinuousCornerShape(12.dp))
            .border(1.dp, Color.White.copy(alpha = 0.14f), ContinuousCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = Space.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(Space.xs))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            letterSpacing = 0.2.sp,
        )
    }
}

/** One labelled value inside a card. */
@Composable
private fun InfoRow(icon: ImageVector, title: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = AccentDark, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(Space.md))
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.62f),
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
