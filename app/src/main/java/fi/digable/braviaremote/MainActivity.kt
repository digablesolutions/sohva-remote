package fi.digable.braviaremote

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dagger.hilt.android.AndroidEntryPoint
import fi.digable.braviaremote.data.AppShortcuts
import fi.digable.braviaremote.data.ConnectionState
import fi.digable.braviaremote.data.HardwareKeys
import fi.digable.braviaremote.data.TvController
import fi.digable.braviaremote.di.ApplicationScope
import fi.digable.braviaremote.protocol.RemoteKey
import fi.digable.braviaremote.ui.AppNav
import fi.digable.braviaremote.ui.theme.BraviaRemoteTheme
import fi.digable.braviaremote.voice.VoiceInbox
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var hardwareKeys: HardwareKeys
    @Inject lateinit var voiceInbox: VoiceInbox
    @Inject lateinit var controller: TvController
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        takeDebugVoice(intent)
        openTvApp(intent)
        setContent {
            BraviaRemoteTheme {
                Surface(Modifier.fillMaxSize()) { AppNav() }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeDebugVoice(intent)
        openTvApp(intent)
    }

    /** A launcher shortcut for a pinned app: open it on the TV once the session is up. */
    private fun openTvApp(intent: Intent?) {
        if (intent?.action != AppShortcuts.ACTION_OPEN_TV_APP) return
        val uri = intent.getStringExtra(AppShortcuts.EXTRA_URI) ?: return
        scope.launch {
            if (controller.awaitSession() is ConnectionState.Connected) controller.launchApp(uri)
        }
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
