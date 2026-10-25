package fi.digable.braviaremote.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PowerGuardTest {
    private var clock = 100_000L
    private val guard = PowerGuard(windowMs = 4_000L, now = { clock })

    @Test
    fun `the first press goes through`() {
        assertThat(guard.tryAcquire()).isTrue()
    }

    @Test
    fun `a double tap is ignored`() {
        guard.tryAcquire()
        clock += 300
        assertThat(guard.tryAcquire()).isFalse()
    }

    @Test
    fun `a press after the window goes through again`() {
        guard.tryAcquire()
        clock += 4_000
        assertThat(guard.tryAcquire()).isTrue()
    }

    @Test
    fun `ignored presses do not extend the window`() {
        guard.tryAcquire()
        clock += 3_000
        guard.tryAcquire()
        clock += 1_000
        assertThat(guard.tryAcquire()).isTrue()
    }
}
