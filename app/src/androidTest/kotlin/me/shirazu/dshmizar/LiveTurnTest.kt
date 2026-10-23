package me.shirazu.dshmizar

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.shirazu.dshmizar.push.LiveTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The live update, on a device that has the surface.
 *
 * This is the one part of the feature a JVM test cannot see and a screenshot cannot
 * prove: whether the notification the app posts is the *kind* the system promotes
 * to a Live Update. The assertions are therefore about the notification's contract
 * -- progress-styled, ongoing, counting up from the turn's start, with a stop action
 * -- rather than about pixels.
 *
 * Skipped below API 36, where `LiveTurn` posts nothing at all.
 */
@RunWith(AndroidJUnit4::class)
class LiveTurnTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val manager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    @Before
    fun requireLiveUpdatesAndNotifications() {
        assumeTrue("Live Updates need API 36", Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA)
        // A debug build can grant itself this; on a real phone the operator does.
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
            .close()
    }

    private fun active(): android.service.notification.StatusBarNotification? =
        manager.activeNotifications.firstOrNull { it.id == LiveTurn.NOTIFICATION_ID }

    /**
     * The card, once the notification service has published it.
     *
     * `notify` is a request too: the posting and the `activeNotifications` view of
     * it are both asynchronous, so an immediate read races in both directions.
     */
    private fun awaitCard(timeoutMillis: Long = 2_000): android.service.notification.StatusBarNotification? {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            active()?.let { return it }
            Thread.sleep(25)
        }
        return null
    }

    /**
     * The card, once the system has caught up with the cancel.
     *
     * `cancel` is a request to the notification service, not a synchronous removal:
     * asserting immediately is a race that passes on a fast device and fails on a
     * busy one, which is exactly what this cost the first time.
     */
    private fun awaitNoCard(timeoutMillis: Long = 2_000): android.service.notification.StatusBarNotification? {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (active() == null) return null
            Thread.sleep(25)
        }
        return active()
    }

    @Test
    fun theStyleIsAnIndeterminateProgressStyle() {
        val style = LiveTurn.progressStyle(context)

        // Indeterminate on purpose: a turn's total length is not knowable, and a bar
        // that reaches 90% and stops is worse than one that says "still going".
        assertTrue(style.isProgressIndeterminate)
        assertNotNull(style.progressTrackerIcon)
    }

    @Test
    fun showingPostsAnOngoingChronometerWithAStopAction() {
        val since = System.currentTimeMillis() - 65_000
        LiveTurn.show(context, sessionId = "session-live", title = "Fix the test", sinceMillis = since)

        val posted = awaitCard()
        assertNotNull("no live update was posted", posted)
        assertTrue("the card must be ongoing", posted!!.isOngoing)
        val notification = posted.notification
        assertEquals(Notification.CATEGORY_PROGRESS, notification.category)
        // The clock is the system's: it keeps ticking while the app is suspended.
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertEquals(since, notification.`when`)
        assertTrue("a live update with no stop action cannot be acted on", notification.actions.isNotEmpty())
        assertEquals(
            context.getString(R.string.live_turn_stop),
            notification.actions.first().title.toString(),
        )

        // Manual verification: `-e livehold 1` keeps the card up long enough to look
        // at the shade and the lock screen, which no assertion can judge.
        if (InstrumentationRegistry.getArguments().getString("livehold") != null) {
            Thread.sleep(20_000)
        }

        LiveTurn.hide(context)
        assertNull("the card outlived its turn", awaitNoCard())
    }

    @Test
    fun theChannelIsQuiet() {
        LiveTurn.ensureChannel(context)

        val channel = manager.getNotificationChannel(LiveTurn.CHANNEL_ID)
        assertNotNull(channel)
        // LOW, not MIN: the card has to be visible, but a status surface must not
        // buzz for every turn.
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel!!.importance)
    }
}
