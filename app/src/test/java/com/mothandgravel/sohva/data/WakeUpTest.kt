package com.mothandgravel.sohva.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class WakeUpTest {
    private val helsinki = ZoneId.of("Europe/Helsinki")

    // Saturday 24 October 2026, the evening this was written.
    private fun at(day: Int, hour: Int, minute: Int) = ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, helsinki)

    @Test
    fun `weekdays at seven skip the weekend`() {
        val wakeUp = WakeUp(enabled = true, hour = 7, minute = 0, days = setOf(1, 2, 3, 4, 5))
        assertThat(wakeUp.nextAfter(at(24, 22, 0))).isEqualTo(at(26, 7, 0))
    }

    @Test
    fun `later today when the time has not come yet, else the next chosen day`() {
        val daily = WakeUp(enabled = true, hour = 7, minute = 30, days = (1..7).toSet())
        assertThat(daily.nextAfter(at(24, 6, 0))).isEqualTo(at(24, 7, 30))
        assertThat(daily.nextAfter(at(24, 7, 30))).isEqualTo(at(25, 7, 30))
    }

    @Test
    fun `the same weekday a week later when it is the only day and has passed`() {
        val saturdays = WakeUp(enabled = true, hour = 9, minute = 0, days = setOf(6))
        assertThat(saturdays.nextAfter(at(24, 10, 0))).isEqualTo(at(31, 9, 0))
    }

    @Test
    fun `daylight saving ends overnight on 25 October and seven stays seven`() {
        val sunday = WakeUp(enabled = true, hour = 7, minute = 0, days = setOf(7))
        val next = sunday.nextAfter(at(24, 22, 0))!!
        assertThat(next.hour).isEqualTo(7)
        assertThat(next.dayOfMonth).isEqualTo(25)
        assertThat(next.offset.totalSeconds).isEqualTo(2 * 3600)
    }

    @Test
    fun `a scheduled scene runs at its soonest time on a chosen day`() {
        // Evening news: MTV3 at 19.00 and 21.00 on weekdays.
        val news = Scene("n", "MTV3 news", days = setOf(1, 2, 3, 4, 5), times = listOf(21 * 60, 19 * 60))
        assertThat(news.nextRunAfter(at(26, 18, 0))).isEqualTo(at(26, 19, 0))
        assertThat(news.nextRunAfter(at(26, 19, 0))).isEqualTo(at(26, 21, 0))
        assertThat(news.nextRunAfter(at(23, 21, 30))).isEqualTo(at(26, 19, 0))
    }

    @Test
    fun `a scene without days or times has no schedule`() {
        assertThat(Scene("a", "PS5").nextRunAfter(at(24, 6, 0))).isNull()
        assertThat(Scene("b", "x", days = setOf(1), times = emptyList()).nextRunAfter(at(24, 6, 0))).isNull()
        assertThat(Scene("c", "x", days = emptySet(), times = listOf(600)).nextRunAfter(at(24, 6, 0))).isNull()
    }

    @Test
    fun `off or without days it never goes off`() {
        assertThat(WakeUp(enabled = false).nextAfter(at(24, 6, 0))).isNull()
        assertThat(WakeUp(enabled = true, days = emptySet()).nextAfter(at(24, 6, 0))).isNull()
    }
}
