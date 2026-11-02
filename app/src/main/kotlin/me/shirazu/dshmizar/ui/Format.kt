package me.shirazu.dshmizar.ui

import android.text.format.DateUtils
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Formatting, in one place.
 *
 * Two rules from the protocol land here. A duration is spelled the way the iOS
 * client spells it — seconds under a minute, then minutes and seconds — because the
 * running-turn clock and the sync estimate were tuned against that shape. And a
 * relative timestamp is the system's own, so the phone's locale decides the words.
 */
object Format {

    /**
     * "just now", "21 min. ago", "2 hr. ago".
     *
     * The platform's own formatter, not a table of English strings: the first version
     * hardcoded `"${minutes}m ago"` and drew "21m ago" underneath a Chinese session
     * list. `DateUtils` is locale-aware and needs no catalogue of its own.
     */
    fun relativeTime(millis: Long, now: Long = System.currentTimeMillis()): String =
        DateUtils.getRelativeTimeSpanString(
            millis,
            now,
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE,
        ).toString()

    /** "12 s", "1 min 12 s". Never negative, always rounded. */
    fun duration(millis: Long?): String? {
        if (millis == null) return null
        val seconds = (millis / 1000.0).roundToLong().coerceAtLeast(0)
        return if (seconds < 60) "$seconds s" else "${seconds / 60} min ${seconds % 60} s"
    }

    /** A clock time for one activity row. */
    fun clockTime(millis: Long): String =
        DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(millis))

    /** A grouped integer, which is what the usage figures are read at. */
    fun count(value: Long): String = String.format(Locale.getDefault(Locale.Category.FORMAT), "%,d", value)

    /** `12,345` or an em dash — **never** a zero for a figure the host did not send. */
    fun countOrDash(value: Long?): String = value?.let { count(it) } ?: "—"

    /**
     * Bytes in the units a person reads: raw under a kilobyte, whole kilobytes
     * under a megabyte, one decimal above.
     *
     * There is deliberately no gigabyte unit: a 5 GiB file reads "5120.0 MB", which
     * is what both the attachment chip and the file preview have always shown. The
     * kilobytes truncate rather than round, so 1023.9 KB reads "1023 KB" and a file
     * never looks smaller than it is.
     */
    fun bytes(value: Long): String = when {
        value < 1024 -> "$value B"
        value < 1024 * 1024 -> "${value / 1024} KB"
        else -> String.format(Locale.getDefault(Locale.Category.FORMAT), "%.1f MB", value / 1024.0 / 1024.0)
    }

    /**
     * The cache-hit figure, exactly as the host spelled it.
     *
     * A string, not a re-rounded number: turning 99.96% into "100%" is the one claim
     * a cache report must not make.
     */
    fun percentOrDash(display: String?, ratio: Double?): String =
        display ?: ratio?.let { "${(it * 100).roundToLong()}%" } ?: "—"
}
