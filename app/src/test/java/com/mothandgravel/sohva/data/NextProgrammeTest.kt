package com.mothandgravel.sohva.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NextProgrammeTest {
    private val now = 1_000_000_000L
    private val minute = 60_000L

    @Test
    fun `asks just after a programme that is still on ends`() {
        assertThat(nextProgrammeCheckIn(end = now + 10 * minute, now = now)).isEqualTo(10 * minute + 5_000)
    }

    @Test
    fun `asks again soon when the TV still reports an ended programme`() {
        assertThat(nextProgrammeCheckIn(end = now - 11 * minute, now = now)).isEqualTo(20_000)
    }

    @Test
    fun `stops asking a quarter of an hour after the end`() {
        assertThat(nextProgrammeCheckIn(end = now - 15 * minute, now = now)).isNull()
    }

    @Test
    fun `does not wait for a programme ending more than six hours away`() {
        assertThat(nextProgrammeCheckIn(end = now + 7 * 60 * minute, now = now)).isNull()
    }
}
