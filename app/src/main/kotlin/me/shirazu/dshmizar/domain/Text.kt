package me.shirazu.dshmizar.domain

/**
 * One string resource, resolved.
 *
 * A coordinator takes this instead of an `Application`: a resource id is the only
 * thing it needs from the app, and handing over the app itself would make every
 * coordinator impossible to run off a device -- which is where the account flows
 * have to be tested, since the screen's own tests only draw a state.
 */
internal interface Text {

    fun get(id: Int): String

    /** Formatted, as `Context.getString` formats: the notice bodies carry a name, a
     *  duration and a count, and the counts arrive as numbers, not strings. */
    fun get(id: Int, vararg args: Any): String
}
