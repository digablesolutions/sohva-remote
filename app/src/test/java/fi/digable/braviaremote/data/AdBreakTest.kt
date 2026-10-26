package fi.digable.braviaremote.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AdBreakTest {
    private val mtv3 = "tv:dvbt?trip=8438.3.784&srvName=MTV3%20HD"
    private val yle1 = "tv:dvbt?trip=8438.1.17&srvName=Yle%20TV1%20HD"
    private val adBreak = AdBreakState(tvId = "tv", endsAt = 0, channelUri = mtv3, channelTitle = "MTV3 HD", mutedByUs = true)

    @Test
    fun `stays put when still on the channel it started on`() {
        assertThat(adBreak.returnTo(mtv3)).isNull()
    }

    @Test
    fun `comes back from another channel watched meanwhile`() {
        assertThat(adBreak.returnTo(yle1)).isEqualTo(mtv3)
    }

    @Test
    fun `does not pull you out of an app you opened meanwhile`() {
        assertThat(adBreak.returnTo("com.sony.dtv.com.netflix.ninja.com.netflix.ninja.MainActivity")).isNull()
    }

    @Test
    fun `does nothing when the TV did not say what it shows`() {
        assertThat(adBreak.returnTo(null)).isNull()
    }

    @Test
    fun `unmutes what it muted`() {
        assertThat(adBreak.shouldUnmute(mutedForCall = false)).isTrue()
    }

    @Test
    fun `leaves a TV that was muted already`() {
        assertThat(adBreak.copy(mutedByUs = false).shouldUnmute(mutedForCall = false)).isFalse()
    }

    @Test
    fun `keeps the TV muted during a call`() {
        assertThat(adBreak.shouldUnmute(mutedForCall = true)).isFalse()
    }
}
