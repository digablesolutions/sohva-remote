package fi.digable.braviaremote.ui.remote

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VolumeBarTest {
    @Test
    fun `dragging the whole bar sweeps the whole range`() {
        assertThat(draggedVolume(start = 0, dx = 1000f, width = 1000f, min = 0, max = 100)).isEqualTo(100)
        assertThat(draggedVolume(start = 100, dx = -1000f, width = 1000f, min = 0, max = 100)).isEqualTo(0)
    }

    @Test
    fun `the level moves from where it was, not from where the finger landed`() {
        assertThat(draggedVolume(start = 30, dx = 50f, width = 1000f, min = 0, max = 100)).isEqualTo(35)
        assertThat(draggedVolume(start = 30, dx = -20f, width = 1000f, min = 0, max = 100)).isEqualTo(28)
        assertThat(draggedVolume(start = 30, dx = 0f, width = 1000f, min = 0, max = 100)).isEqualTo(30)
    }

    @Test
    fun `the level stays within the TV's range`() {
        assertThat(draggedVolume(start = 95, dx = 500f, width = 1000f, min = 0, max = 100)).isEqualTo(100)
        assertThat(draggedVolume(start = 3, dx = -500f, width = 1000f, min = 0, max = 100)).isEqualTo(0)
    }

    @Test
    fun `a bar with no width leaves the level alone`() {
        assertThat(draggedVolume(start = 30, dx = 50f, width = 0f, min = 0, max = 100)).isEqualTo(30)
    }
}
