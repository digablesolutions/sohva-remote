package fi.digable.braviaremote.protocol

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.OffsetDateTime

class PlayingContentTest {
    private fun at(text: String) = OffsetDateTime.parse(text).toInstant().toEpochMilli()

    @Test
    fun `the tv's time with an offset without a colon parses`() {
        assertThat(parseTvTime("2026-10-25T21:05:00+0200")).isEqualTo(at("2026-10-25T21:05:00+02:00"))
        assertThat(parseTvTime("2026-10-25T21:05:00+02:00")).isEqualTo(at("2026-10-25T21:05:00+02:00"))
        assertThat(parseTvTime("not a time")).isNull()
    }

    @Test
    fun `the programme window runs from the start for its length`() {
        // As the KD-65XF9005 reported Nelonen on 25 October 2026.
        val playing = PlayingContent(
            uri = "tv:dvbt?trip=8438.8193.441&srvName=Nelonen HD", title = "Nelonen HD", source = "tv:dvbt",
            programTitle = "Elokuva: Bad Boys for Life (16)", programStart = "2026-10-25T21:05:00+0200", programSeconds = 9000,
        )
        assertThat(playing.programWindow).isEqualTo(at("2026-10-25T21:05:00+02:00") to at("2026-10-25T23:35:00+02:00"))
    }

    @Test
    fun `no window without a start or a length`() {
        assertThat(PlayingContent("u", "t", "s", programTitle = "x").programWindow).isNull()
        assertThat(PlayingContent("u", "t", "s", programStart = "2026-10-25T21:05:00+0200", programSeconds = 0).programWindow).isNull()
    }
}
