package me.shirazu.dshmizar.push

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import me.shirazu.dshmizar.MainActivity
import me.shirazu.dshmizar.R

/**
 * Keeps the process alive while the app is in the background, so the socket
 * stays connected and a pending approval or question can be announced.
 *
 * Android lets a foreground service's host process run without being frozen or
 * killed for being idle; without one, a backgrounded WebSocket is closed within
 * seconds on most ROMs — which means an agent blocked on an approval could sit
 * there, visible to the desktop, with the phone showing nothing.
 *
 * The service does not own the connection. The connection lives in the app's
 * view model, and this service exists to tell the system "do not reap this
 * process". When the task leaves the recents list the activity is finished, the
 * view model is cleared, the socket closes, and [stop] is called — a foreground
 * service with nothing behind it would just be a notification that lies.
 */
class ConnectionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        running = true
        startForeground(NOTIFICATION_ID, ongoingNotification(intent?.getStringExtra(EXTRA_TEXT)))
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    /** The permanent notification that keeps the process alive, and says so. */
    private fun ongoingNotification(text: String? = null): Notification {
        ensureChannel(this)
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text ?: getString(R.string.connection_service_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .build()
    }

    companion object {
        /**
         * Its own channel, and a low-importance one on purpose: this notification is
         * a "still connected" indicator, not something the operator should be nagged
         * by. Approvals keep using [MIZAR_CHANNEL_ID], which does make noise.
         */
        const val CHANNEL_ID = "mizar-connection"

        /** One id, so a restart replaces the notice instead of stacking another. */
        const val NOTIFICATION_ID = 1

        /** Extra the model uses to say what the notice should read before the first update. */
        internal const val EXTRA_TEXT = "mizar.connection.text"

        /** Whether the keep-alive is actually running, so an update never creates one. */
        @Volatile
        var running = false
            private set

        /** Start the keep-alive, or refresh the notification if it is already up. */
        fun start(context: Context, text: String? = null) {
            val intent = Intent(context, ConnectionService::class.java).apply {
                if (text != null) putExtra(EXTRA_TEXT, text)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Stop the keep-alive; the connection is gone, so should the notice be. */
        fun stop(context: Context) {
            context.stopService(Intent(context, ConnectionService::class.java))
        }

        /**
         * Say something else in the notice: the host and how far away it is while
         * connected, and which half of the link is down while it is not.
         *
         * Only ever refreshes a notice that exists. A failed connect with the service
         * stopped must not leave a permanent notification behind — that would be a
         * keep-alive claiming to keep alive a process with no connection.
         */
        fun update(context: Context, text: String) {
            if (!running) return
            ensureChannel(context)
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(text)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .setSilent(true)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setContentIntent(open)
                .build()
            runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
        }

        /**
         * Create the channel, or bring its name and description up to date.
         *
         * Deliberately not "create once and return early": the channel outlives the
         * app's version, and a build that renames it in another language would keep
         * the English name it was first created with forever. Android updates a
         * channel's name and description on every call, and refuses to raise its
         * importance — which is the only part a user owns.
         */
        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.connection_service_channel),
                    // MIN: no sound, no heads-up. It sits in the shade.
                    NotificationManager.IMPORTANCE_MIN,
                ).apply {
                    setShowBadge(false)
                    description = context.getString(R.string.connection_service_text)
                },
            )
        }
    }
}
