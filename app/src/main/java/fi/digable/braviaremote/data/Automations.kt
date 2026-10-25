package fi.digable.braviaremote.data

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.ZonedDateTime

/** Something the TV can be switched to: a channel or input (setPlayContent) or an app (setActiveApp). */
@Serializable
data class TvTarget(val kind: Kind, val uri: String, val name: String) {
    enum class Kind { CHANNEL, INPUT, APP }
}

/**
 * A one-tap setup such as "PS5 night": the TV on, [target] (if any), [volume] (if any). It can also
 * run by itself at [times] (minutes after midnight) on [days] (ISO, 1 = Monday), but then only
 * while the TV is already on: a schedule never switches the TV on.
 */
@Serializable
data class Scene(
    val id: String,
    val name: String,
    val target: TvTarget? = null,
    val volume: Int? = null,
    val days: Set<Int> = emptySet(),
    val times: List<Int> = emptyList(),
) {
    val scheduled: Boolean get() = days.isNotEmpty() && times.isNotEmpty()

    /** The next scheduled run after [now], or null when it has no schedule. */
    fun nextRunAfter(now: ZonedDateTime): ZonedDateTime? =
        if (!scheduled) null else times.mapNotNull { nextOccurrence(now, days, it / 60, it % 60) }.minOrNull()
}

/**
 * The TV switched on at [hour]:[minute] on [days] (ISO day numbers, 1 = Monday), to [target] and
 * [volume] when given; otherwise to whatever it showed last, at the volume it had.
 */
@Serializable
data class WakeUp(
    val enabled: Boolean = false,
    val hour: Int = 7,
    val minute: Int = 0,
    val days: Set<Int> = setOf(1, 2, 3, 4, 5),
    val target: TvTarget? = null,
    val volume: Int? = null,
) {
    /** The next time after [now] this goes off, or null when it is off or has no days. */
    fun nextAfter(now: ZonedDateTime): ZonedDateTime? = if (enabled) nextOccurrence(now, days, hour, minute) else null

    companion object {
        val WEEK: List<DayOfWeek> = DayOfWeek.entries
    }
}

/** The first [hour]:[minute] after [now] on one of [days] (ISO day numbers), or null without days. */
internal fun nextOccurrence(now: ZonedDateTime, days: Set<Int>, hour: Int, minute: Int): ZonedDateTime? {
    if (days.isEmpty()) return null
    val today = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
    return (0..7).asSequence()
        .map { today.plusDays(it.toLong()) }
        .firstOrNull { it.isAfter(now) && it.dayOfWeek.value in days }
}
