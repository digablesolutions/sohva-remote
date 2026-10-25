package fi.digable.braviaremote.ui.remote

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StableInputUriTest {
    @Test
    fun `a CEC device is saved as its HDMI port, other inputs as they are`() {
        assertThat(stableInputUri("extInput:cec?type=player&port=3&logicalAddr=4")).isEqualTo("extInput:hdmi?port=3")
        assertThat(stableInputUri("extInput:hdmi?port=1")).isEqualTo("extInput:hdmi?port=1")
        assertThat(stableInputUri("extInput:widi?port=1")).isEqualTo("extInput:widi?port=1")
    }
}
