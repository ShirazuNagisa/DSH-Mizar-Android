package me.shirazu.dshmizar.domain

/**
 * Where a first-run answer lives on the phone, and which guide this build wants
 * walked.
 *
 * Kept in `domain` rather than beside the screen because the store and the view
 * model read it too: a key spelled twice is how "the guide was finished" and "the
 * guide was dismissed" become two different facts about the same phone.
 *
 * The keys are the Android app's own (`SharedPreferences`, through
 * `ActivityStore`), not iOS's `@AppStorage` names: the two platforms remember
 * different guides, and a phone migrating between them has nothing to carry over.
 */
object OnboardingStorageKeys {
    /** The guide was walked to its end. */
    const val DONE = "mizar.onboarding.done"

    /** It was started, which is what "skip" leaves behind. */
    const val STARTED = "mizar.onboarding.started"

    /** The guide's version this phone last finished. */
    const val VERSION = "mizar.onboarding.version"
}

/**
 * Which guide this build wants walked.
 *
 * A phone that was carrying an older guide has a lower version here, walks this one
 * again, and keeps the account and the settings it already has. A fresh install has
 * no value and walks it for the first time.
 *
 * The version is compared numerically, not lexicographically: `"0.10.10"` is newer
 * than `"0.9.9"`, and a string comparison would say the opposite.
 */
object OnboardingVersion {

    /**
     * The guide this build wants walked.
     *
     * Bumped whenever a page is added or its meaning changes -- the iOS app's
     * 0.9.9 bump was the metadata reload. The Android guide arrives with the
     * 0.10.7 build.
     */
    const val REQUIRED = "0.10.7"

    /** The desktop application's release page, where both platforms start. */
    const val DESKTOP_RELEASE_URL = "https://github.com/ShirazuNagisa/AGI-Mizar/releases"

    /** The project's page, for the community shelf and the welcome's fine print. */
    const val PROJECT_URL = "https://dsh-mizar.shirazu-nagisa.com"

    /**
     * Whether the guide has been walked in the version this build wants.
     *
     * @param stored the version written when the guide last finished, or `null`.
     */
    fun isComplete(stored: String?): Boolean {
        if (stored.isNullOrBlank()) return false
        return compareVersions(stored, REQUIRED) >= 0
    }

    /**
     * Whether an upgrade re-walks the guide.
     *
     * @param done whether the guide was ever finished.
     * @param stored the guide version the phone finished, or `null`.
     */
    fun isUpdate(done: Boolean, stored: String?): Boolean = done && !isComplete(stored)

    /**
     * Numeric, segment-wise comparison of two dotted versions.
     *
     * `"0.10.7"` is greater than `"0.9.9"`, and a segment that is not a number
     * falls back to a case-sensitive string comparison rather than throwing: a
     * half-written value on disk must not crash the first screen of the app.
     *
     * @return negative, zero, or positive as [left] is older, equal, or newer.
     */
    fun compareVersions(left: String, right: String): Int {
        val a = left.trim().split('.')
        val b = right.trim().split('.')
        for (index in 0 until maxOf(a.size, b.size)) {
            val l = a.getOrNull(index) ?: "0"
            val r = b.getOrNull(index) ?: "0"
            val ln = l.toIntOrNull()
            val rn = r.toIntOrNull()
            val comparison = if (ln != null && rn != null) ln.compareTo(rn) else l.compareTo(r)
            if (comparison != 0) return comparison
        }
        return 0
    }
}
