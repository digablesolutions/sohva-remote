package com.mothandgravel.sohva.ui.remote

import com.google.common.truth.Truth.assertThat
import com.mothandgravel.sohva.protocol.RemoteKey
import org.junit.Test

class RailTest {
    @Test
    fun `the edges are rails and the middle is not`() {
        assertThat(Rail.at(x = 20f, width = 1000f, railWidth = 140f)).isEqualTo(Rail.VOLUME)
        assertThat(Rail.at(x = 500f, width = 1000f, railWidth = 140f)).isNull()
        assertThat(Rail.at(x = 900f, width = 1000f, railWidth = 140f)).isEqualTo(Rail.CHANNEL)
    }

    @Test
    fun `up and down become volume or channel keys, sideways becomes nothing`() {
        assertThat(Rail.VOLUME.keyFor(RemoteKey.UP)).isEqualTo(RemoteKey.VOLUME_UP)
        assertThat(Rail.VOLUME.keyFor(RemoteKey.DOWN)).isEqualTo(RemoteKey.VOLUME_DOWN)
        assertThat(Rail.CHANNEL.keyFor(RemoteKey.UP)).isEqualTo(RemoteKey.CHANNEL_UP)
        assertThat(Rail.CHANNEL.keyFor(RemoteKey.DOWN)).isEqualTo(RemoteKey.CHANNEL_DOWN)
        assertThat(Rail.CHANNEL.keyFor(RemoteKey.LEFT)).isNull()
    }

    @Test
    fun `a vertical swipe on a rail ignores sideways drift`() {
        val stepper = SwipeStepper(firstStep = 60f, step = 60f)
        val keys = (1..10).flatMap { stepper.feed(0f, -20f) }.mapNotNull(Rail.CHANNEL::keyFor)
        assertThat(keys).containsExactly(RemoteKey.CHANNEL_UP, RemoteKey.CHANNEL_UP, RemoteKey.CHANNEL_UP)
    }
}
