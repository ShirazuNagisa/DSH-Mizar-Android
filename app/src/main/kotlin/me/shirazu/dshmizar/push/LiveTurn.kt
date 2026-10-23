package me.shirazu.dshmizar.push

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import me.shirazu.dshmizar.MainActivity
import me.shirazu.dshmizar.R

/**
 * The running turn on the lock screen and in the status bar.
 *
 * Android 16's Live Updates are the counterpart of the iOS Live Activity: an
 * ongoing, progress-styled notification the system promotes to a chip in the
 * status bar and a card on the lock screen. The elapsed time is the system's own
 * chronometer (`setUsesChronometer`), so it keeps ticking while the app is
 * suspended -- the phone does not have to wake up to redraw a clock.
 *
 * **Indeterminate on purpose.** A turn's total length is not knowable: the host
 * reports steps as they happen, not how many are left. A determinate bar would be
 * an invented number, and a progress bar that reaches 90% and stays there is worse
 * than one that says "still going".
 *
 * The card is withdrawn when the host says the turn settled ([LiveTurn.hide] from
 * the `turn.settled` path) rather than left for the operator to dismiss: a live
 * update that outlives the thing it reports is a notification that lies.
 *
 * Below API 36 there is no Live Updates surface, and the connection service's own
 * notice already says "connected", so this posts nothing at all.
 */
object LiveTurn {

    /** Its own channel: Live Updates must be silent, and the alerts channel is not. */
    const val CHANNEL_ID = "mizar-live"

    /** Clear of the service's 1 and the alerts' 1000+ ranges. */
    const val NOTIFICATION_ID = 2

    /** The stop action's broadcast. */
    const val ACTION_STOP = "me.shirazu.dshmizar.action.LIVE_STOP"

    /** Which session the stop action is about. */
    const val EXTRA_SESSION = "me.shirazu.dshmizar.liveSessionId"

    /** Whether this device has the surface at all. */
    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA

    /**
     * Put the running turn on the lock screen, or bring it up to date.
     *
     * @param sessionId the session the turn belongs to, for the stop action.
     * @param title what the session is called.
     * @param sinceMillis when the turn was first seen running, for the chronometer.
     */
    fun show(context: Context, sessionId: String, title: String, sinceMillis: Long) {
        if (!isSupported()) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel(context)
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return

        val open = PendingIntent.getActivity(
            context,
            sessionId.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_SESSION_ID, sessionId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getBroadcast(
            context,
            sessionId.hashCode() + 1,
            Intent(context, LiveTurnReceiver::class.java).apply {
                action = ACTION_STOP
                putExtra(EXTRA_SESSION, sessionId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = platformNotification(context, sessionId, title, sinceMillis, open, stop)
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    /** Take the card down: the turn is over, or there is no connection to report on. */
    fun hide(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
    }

    /** Create the channel, or bring its text up to date in the current language. */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.live_turn_channel),
                // LOW: a live update is a status surface, not something to buzz for.
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setShowBadge(false)
                description = context.getString(R.string.live_turn_channel_description)
            },
        )
    }

    /**
     * The progress style that makes this a live update.
     *
     * Extracted so a test on an API 36 device can assert the one property that
     * matters: `ProgressStyle` is what the system promotes, and a notification
     * built without it is an ordinary ongoing notice.
     */
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    internal fun progressStyle(context: Context): Notification.ProgressStyle =
        Notification.ProgressStyle()
            // Nothing knows how much of a turn is left; see the class comment.
            .setProgressIndeterminate(true)
            .setProgressTrackerIcon(Icon.createWithResource(context, R.drawable.ic_mizar_notification))

    /**
     * The API 36 notification.
     *
     * Built with the platform builder rather than [NotificationCompat]: `ProgressStyle`
     * is what makes this a live update, and the compat builder has no counterpart
     * for it -- a compat-built notification here would be an ordinary ongoing notice
     * that never gets promoted.
     */
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun platformNotification(
        context: Context,
        sessionId: String,
        title: String,
        sinceMillis: Long,
        open: PendingIntent,
        stop: PendingIntent,
    ): Notification {
        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mizar_notification)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.live_turn_running))
            .setContentIntent(open)
            .setOngoing(true)
            // No `setSilent` here: the platform builder has no such method, and the
            // channel's LOW importance is what keeps this quiet.
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setWhen(sinceMillis)
            .setUsesChronometer(true)
            // The chip's one line, which is all the status bar has room for.
            .setShortCriticalText(context.getString(R.string.live_turn_chip))
            .setStyle(progressStyle(context))
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, android.R.drawable.ic_media_pause),
                    context.getString(R.string.live_turn_stop),
                    stop,
                ).build(),
            )
            .build()
    }
}

/**
 * Where the stop action lands.
 *
 * A broadcast receiver, because the notification outlives the activity: the whole
 * point of a live update is that the operator is not looking at the app. The
 * connection lives in the view model, so the receiver forwards to it through
 * [LiveTurnCommands]; when nothing is listening the process is gone, the socket
 * with it, and the only honest thing left is to open the app on that session.
 */
class LiveTurnReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != LiveTurn.ACTION_STOP) return
        val sessionId = intent.getStringExtra(LiveTurn.EXTRA_SESSION) ?: return
        val handled = LiveTurnCommands.stop(sessionId)
        if (!handled) {
            runCatching {
                context.startActivity(
                    Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        putExtra(EXTRA_SESSION_ID, sessionId)
                    },
                )
            }
        }
        // The card comes down either way: either the cancel was sent, or there is
        // no connection left that could still be running a turn.
        LiveTurn.hide(context)
    }
}

/**
 * The bridge from the notification's stop action to the live view model.
 *
 * A single slot rather than an event bus: there is one app, one connection, and one
 * view model, and the model clears the slot when it goes away so a stale reference
 * cannot outlive it.
 */
object LiveTurnCommands {

    @Volatile
    private var handler: ((String) -> Boolean)? = null

    /** Install the handler while a model exists, or `null` when it is cleared. */
    fun install(handler: ((String) -> Boolean)?) {
        this.handler = handler
    }

    /** Ask the connection to cancel that session's turn; false when nobody is listening. */
    fun stop(sessionId: String): Boolean = handler?.invoke(sessionId) ?: false
}
