package fi.digable.braviaremote.data

import android.os.SystemClock

/**
 * Lets one power toggle through per [windowMs]. The TV takes seconds to switch, so a second press
 * soon after the first is almost always a double tap or an impatient retry, and toggling again
 * would undo the first press.
 */
class PowerGuard(
    private val windowMs: Long = 4_000L,
    private val now: () -> Long = SystemClock::elapsedRealtime,
) {
    private var last = Long.MIN_VALUE

    @Synchronized
    fun tryAcquire(): Boolean {
        val t = now()
        if (last != Long.MIN_VALUE && t - last < windowMs) return false
        last = t
        return true
    }
}
