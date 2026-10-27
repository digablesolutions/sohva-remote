package com.mothandgravel.sohva.ui.remote

import com.google.common.truth.Truth.assertThat
import com.mothandgravel.sohva.protocol.RemoteKey
import org.junit.Test

class SwipeStepperTest {
    private val stepper = SwipeStepper(firstStep = 30f, step = 50f)

    @Test
    fun `a small move sends nothing`() {
        assertThat(stepper.feed(20f, 5f)).isEmpty()
    }

    @Test
    fun `the first key comes early, then one per step`() {
        assertThat(stepper.feed(30f, 0f)).containsExactly(RemoteKey.RIGHT)
        assertThat(stepper.feed(49f, 0f)).isEmpty()
        assertThat(stepper.feed(1f, 0f)).containsExactly(RemoteKey.RIGHT)
    }

    @Test
    fun `one long move sends several keys`() {
        assertThat(stepper.feed(0f, -180f)).containsExactly(RemoteKey.UP, RemoteKey.UP, RemoteKey.UP, RemoteKey.UP).inOrder()
    }

    @Test
    fun `a slanted swipe stays one direction`() {
        val keys = (1..10).flatMap { stepper.feed(20f, 8f) }
        assertThat(keys.toSet()).containsExactly(RemoteKey.RIGHT)
    }

    @Test
    fun `an L-shaped swipe turns the corner`() {
        val keys = (1..6).flatMap { stepper.feed(-25f, 0f) } + (1..6).flatMap { stepper.feed(0f, 25f) }
        assertThat(keys.first()).isEqualTo(RemoteKey.LEFT)
        assertThat(keys.last()).isEqualTo(RemoteKey.DOWN)
    }

    @Test
    fun `the keys do not depend on how the move was batched`() {
        val oneEvent = SwipeStepper(28f, 52f).feed(180f, 120f)
        val manyEvents = SwipeStepper(28f, 52f).let { s -> (1..90).flatMap { s.feed(2f, 4f / 3) } }
        assertThat(manyEvents).isEqualTo(oneEvent)
    }

    @Test
    fun `a slow swipe of tiny moves still moves`() {
        val keys = (1..100).flatMap { stepper.feed(1f, 0f) }
        assertThat(keys).containsExactly(RemoteKey.RIGHT, RemoteKey.RIGHT)
    }

    @Test
    fun `reset starts a new swipe with the short first step`() {
        stepper.feed(200f, 0f)
        stepper.reset()
        assertThat(stepper.feed(-30f, 0f)).containsExactly(RemoteKey.LEFT)
    }
}
