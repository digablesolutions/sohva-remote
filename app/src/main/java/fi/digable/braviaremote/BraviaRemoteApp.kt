package fi.digable.braviaremote

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.util.DebugLogger
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import fi.digable.braviaremote.data.CredentialStore
import fi.digable.braviaremote.data.SleepTimer
import fi.digable.braviaremote.data.TvController
import fi.digable.braviaremote.data.SceneSchedule
import fi.digable.braviaremote.data.WakeUpAlarm
import fi.digable.braviaremote.di.ApplicationScope
import fi.digable.braviaremote.shortcuts.WidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import javax.inject.Inject

@HiltAndroidApp
class BraviaRemoteApp : Application(), ImageLoaderFactory {
    @Inject lateinit var httpClient: Lazy<OkHttpClient>
    @Inject lateinit var credentials: Lazy<CredentialStore>
    @Inject lateinit var tvController: Lazy<TvController>
    @Inject lateinit var widgets: Lazy<WidgetUpdater>
    @Inject lateinit var sleepTimer: Lazy<SleepTimer>
    @Inject lateinit var wakeUp: Lazy<WakeUpAlarm>
    @Inject lateinit var sceneSchedule: Lazy<SceneSchedule>
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        // Both are needed before the first TV call and both are slow: building the HTTP client
        // takes ~17 ms (TLS defaults) and opening the encrypted store ~140 ms (Keystore). Start
        // them now, off the main thread, so neither delays the first frame or the connect.
        scope.launch(Dispatchers.IO) { httpClient.get() }
        scope.launch(Dispatchers.IO) { credentials.get().open() }
        // The session connects as soon as it exists; create it now rather than in the first composition.
        scope.launch(Dispatchers.IO) { tvController.get() }
        // Widgets follow the session while the process runs; a reboot dropped any sleep timer alarm.
        scope.launch(Dispatchers.IO) {
            widgets.get().start()
            sleepTimer.get().restore()
            wakeUp.get().reschedule()
            sceneSchedule.get().reschedule()
        }
    }

    /**
     * App icons come from the TV, so they load through the same (traced) client as everything else.
     * The TV serves them with no caching headers at all, which by HTTP rules means "fetch again", so
     * every icon came over the network on each start (0.4-1 s each). They only change when an app
     * updates, so the disk cache keeps them regardless of headers.
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient { httpClient.get() }
        .respectCacheHeaders(false)
        .apply { if (BuildConfig.DEBUG) logger(DebugLogger()) }
        .build()
}
