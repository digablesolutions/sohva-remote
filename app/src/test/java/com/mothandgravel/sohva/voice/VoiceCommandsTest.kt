package com.mothandgravel.sohva.voice

import com.google.common.truth.Truth.assertThat
import com.mothandgravel.sohva.protocol.ExternalInput
import com.mothandgravel.sohva.protocol.TvApp
import com.mothandgravel.sohva.protocol.TvChannel
import org.junit.Test

class VoiceCommandsTest {
    // Names as the KD-65XF9005 lists them.
    private val yle1 = TvChannel("tv:dvbt?srvName=Yle TV1 HD", "Yle TV1 HD", "001")
    private val yle2 = TvChannel("tv:dvbt?srvName=Yle TV2 HD", "Yle TV2 HD", "002")
    private val mtv3 = TvChannel("tv:dvbt?srvName=MTV3 HD", "MTV3 HD", "003")
    private val nelonen = TvChannel("tv:dvbt?srvName=Nelonen HD", "Nelonen HD", "004")
    private val kutonen = TvChannel("tv:dvbt?srvName=Kutonen HD", "Kutonen HD", "006")
    private val channels = listOf(yle1, yle2, mtv3, nelonen, kutonen)
    private val netflix = TvApp("com.sony.dtv.com.netflix.ninja.MainActivity", "Netflix")
    private val areena = TvApp("com.sony.dtv.fi.yle.areena.MainActivity", "Yle Areena")
    private val apps = listOf(netflix, areena, TvApp("com.sony.dtv.youtube", "YouTube"))
    private val ps5 = ExternalInput("extInput:cec?port=3", "HDMI 3", label = "PlayStation 5")
    private val inputs = listOf(ps5, ExternalInput("extInput:hdmi?port=1", "HDMI 1"))

    private fun parse(heard: String) = VoiceCommands.parse(heard, channels, apps, inputs)

    @Test
    fun `channels by name, also with Finnish endings and split numbers`() {
        assertThat(parse("Yle TV2")).isEqualTo(VoiceAction.Channel(yle2))
        assertThat(parse("laita Yle TV1:lle")).isEqualTo(VoiceAction.Channel(yle1))
        assertThat(parse("MTV3")).isEqualTo(VoiceAction.Channel(mtv3))
        assertThat(parse("Nelonen")).isEqualTo(VoiceAction.Channel(nelonen))
        assertThat(parse("switch to Kutonen")).isEqualTo(VoiceAction.Channel(kutonen))
        assertThat(parse("Kutoselle")).isEqualTo(VoiceAction.Channel(kutonen))
    }

    @Test
    fun `channels by number`() {
        assertThat(parse("kanava 3")).isEqualTo(VoiceAction.Channel(mtv3))
        assertThat(parse("Channel 4.")).isEqualTo(VoiceAction.Channel(nelonen))
        assertThat(parse("6")).isEqualTo(VoiceAction.Channel(kutonen))
    }

    @Test
    fun `apps and inputs by the names the TV gives them`() {
        assertThat(parse("Netflix")).isEqualTo(VoiceAction.App(netflix))
        assertThat(parse("avaa Netflixiin")).isEqualTo(VoiceAction.App(netflix))
        assertThat(parse("Yle Areena")).isEqualTo(VoiceAction.App(areena))
        assertThat(parse("Areenaan")).isEqualTo(VoiceAction.App(areena))
        assertThat(parse("PlayStation")).isEqualTo(VoiceAction.Input(ps5))
        assertThat(parse("Open PlayStation 5.")).isEqualTo(VoiceAction.Input(ps5))
    }

    @Test
    fun `volume, mute and power in Finnish and English`() {
        assertThat(parse("Volume 15")).isEqualTo(VoiceAction.SetVolume(15))
        assertThat(parse("äänenvoimakkuus 20")).isEqualTo(VoiceAction.SetVolume(20))
        assertThat(parse("ääni 8")).isEqualTo(VoiceAction.SetVolume(8))
        assertThat(parse("Mute")).isEqualTo(VoiceAction.Mute(true))
        assertThat(parse("mykistä")).isEqualTo(VoiceAction.Mute(true))
        assertThat(parse("Unmute.")).isEqualTo(VoiceAction.Mute(false))
        assertThat(parse("äänet päälle")).isEqualTo(VoiceAction.Mute(false))
        assertThat(parse("Louder")).isEqualTo(VoiceAction.StepVolume(up = true))
        assertThat(parse("hiljemmalle")).isEqualTo(VoiceAction.StepVolume(up = false))
        assertThat(parse("Sammuta")).isEqualTo(VoiceAction.PowerOff)
        assertThat(parse("turn off the TV")).isEqualTo(VoiceAction.PowerOff)
    }

    @Test
    fun `nothing for what names nothing the TV has`() {
        assertThat(parse("")).isNull()
        assertThat(parse("what's the weather")).isNull()
        assertThat(parse("kanava 99")).isNull()
        assertThat(parse("Yle")).isNull()
    }
}
