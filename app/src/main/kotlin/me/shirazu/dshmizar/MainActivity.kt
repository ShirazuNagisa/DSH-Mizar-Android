package me.shirazu.dshmizar

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import me.shirazu.dshmizar.push.EXTRA_SESSION_ID
import me.shirazu.dshmizar.push.Notifications
import me.shirazu.dshmizar.ui.MizarTheme
import me.shirazu.dshmizar.ui.RootScreen

/**
 * The single activity.
 *
 * Three entry points land here besides the launcher: a `mizar://pair?...` deep
 * link, a `mizar://auth?...` link from the GitHub landing page, and a
 * notification tap carrying a session id.
 */
class MainActivity : ComponentActivity() {

    private val model: AppViewModel by viewModels()

    /** A pairing or sign-in URI the UI has not consumed yet. */
    private var incomingUri by mutableStateOf<String?>(null)

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* refused is fine */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Notifications.ensureChannel(this)
        askForNotificationPermission()

        // Only on a cold start: a rotation must not reconnect a second time.
        if (savedInstanceState == null) {
            model.start()
            readIntent(intent)
        }

        setContent {
            val state by model.state.collectAsState()
            MizarTheme(mode = state.themeMode) {
                RootScreen(
                    model = model,
                    incomingUri = incomingUri,
                    onUriHandled = { incomingUri = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readIntent(intent)
    }

    /** Foreground, so the host stops notifying this device about what it is looking at. */
    override fun onStart() {
        super.onStart()
        model.setPresence(true); me.shirazu.dshmizar.push.ConnectionService.start(this, getString(R.string.link_connecting))
        model.refreshAccountOnForeground()
    }

    override fun onStop() {
        // The service deliberately survives this: going to the background is the
        // case it exists for. It stops in onDestroy, when the task really ends.
        model.setPresence(false)
        super.onStop()
    }

    override fun onDestroy() {
        // Only a finishing activity means the connection is going away; a rotation
        // or a configuration change must not drop the keep-alive.
        if (isFinishing) me.shirazu.dshmizar.push.ConnectionService.stop(this)
        super.onDestroy()
    }

    private fun readIntent(intent: Intent?) {
        val uri = intent?.data
        when {
            // Split by host: every mizar:// link used to be treated as a pairing
            // code, which silently swallowed the sign-in callback. `github` is the
            // relay 0.3.x spelling; `auth` is the older one, kept so an app that
            // was already on a phone during the upgrade still finishes its flow.
            uri?.scheme == "mizar" &&
                (uri.host == "pair" || uri.host == "auth" || uri.host == "account" || uri.host == "github") ->
                incomingUri = uri.toString()
            else -> intent?.getStringExtra(EXTRA_SESSION_ID)?.let { model.selectSession(it) }
        }
    }

    /**
     * Ask for notification permission once, where the operator can see why.
     *
     * Below Android 13 there is nothing to ask: the permission is granted at install
     * time, and the app already declares it.
     */
    private fun askForNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
