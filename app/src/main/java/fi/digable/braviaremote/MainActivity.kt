package fi.digable.braviaremote

import android.content.Intent
import android.os.Bundle
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import dagger.hilt.android.AndroidEntryPoint
import fi.digable.braviaremote.data.HardwareKeys
import fi.digable.braviaremote.protocol.RemoteKey
import fi.digable.braviaremote.ui.AppNav
import fi.digable.braviaremote.ui.theme.BraviaRemoteTheme
import fi.digable.braviaremote.voice.VoiceInbox
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var hardwareKeys: HardwareKeys
    @Inject lateinit var voiceInbox: VoiceInbox

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        takeDebugVoice(intent)
        setContent {
            BraviaRemoteTheme {
                Surface(Modifier.fillMaxSize()) { AppNav() }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeDebugVoice(intent)
    }

    private fun takeDebugVoice(intent: Intent?) {
        if (BuildConfig.DEBUG) intent?.getStringExtra("voice")?.let(voiceInbox::offer)
    }

    /** Phone volume rocker controls the TV while the remote screen is listening (F15). */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val key = volumeKey(keyCode) ?: return super.onKeyDown(keyCode, event)
        return hardwareKeys.offer(key, repeat = event.repeatCount > 0) || super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (volumeKey(keyCode) != null && hardwareKeys.hasListener) return true
        return super.onKeyUp(keyCode, event)
    }

    private fun volumeKey(keyCode: Int): RemoteKey? = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP -> RemoteKey.VOLUME_UP
        KeyEvent.KEYCODE_VOLUME_DOWN -> RemoteKey.VOLUME_DOWN
        else -> null
    }
}
