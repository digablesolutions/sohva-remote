package fi.digable.braviaremote.protocol

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TvAppTest {
    // URIs as the KD-65XF9005 lists them.
    private val youtube = TvApp("com.sony.dtv.com.google.android.youtube.tv.com.google.android.apps.youtube.tv.activity.ShellActivity", "YouTube")
    private val youtubeMusic = TvApp("com.sony.dtv.com.google.android.youtube.tvmusic.com.google.android.apps.youtube.tvmusic.activity.MainActivity", "YouTube Music")
    private val liveTv = TvApp("com.sony.dtv.com.sony.dtv.tvx.com.sony.dtv.tvx.MainActivity", "TV")
    private val guide = TvApp("com.sony.dtv.com.sony.dtv.tvxlauncher.programguide.com.sony.dtv.tvxlauncher.programguide.MainActivity", "Programme Guide")
    private val prime = TvApp("com.sony.dtv.com.amazon.amazonvideo.livingroom.com.amazon.ignition.IgnitionActivity", "Prime Video")
    private val apps = listOf(guide, liveTv, prime, youtube, youtubeMusic)

    private fun titleOf(packageName: String) = apps.firstOrNull { it.isPackage(packageName) }?.title

    @Test
    fun `package names find their app`() {
        assertThat(titleOf("com.amazon.amazonvideo.livingroom")).isEqualTo("Prime Video")
        assertThat(titleOf("com.sony.dtv.tvx")).isEqualTo("TV")
    }

    @Test
    fun `a package that is a prefix of another does not match it`() {
        assertThat(titleOf("com.google.android.youtube.tv")).isEqualTo("YouTube")
        assertThat(titleOf("com.google.android.youtube.tvmusic")).isEqualTo("YouTube Music")
        assertThat(guide.isPackage("com.sony.dtv.tvx")).isFalse()
    }

    @Test
    fun `an unknown package has no title`() {
        assertThat(titleOf("com.netflix.ninja")).isNull()
    }
}
