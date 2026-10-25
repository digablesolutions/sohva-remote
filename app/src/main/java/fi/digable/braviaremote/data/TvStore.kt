package fi.digable.braviaremote.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import fi.digable.braviaremote.protocol.TvApp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Saved TVs, the active one, and non-secret settings, in Preferences DataStore. */
@Singleton
class TvStore @Inject constructor(private val dataStore: DataStore<Preferences>) {

    val savedTvs: Flow<List<SavedTv>> = dataStore.data.map { it.readTvs() }
    val activeTvId: Flow<String?> = dataStore.data.map { it[ACTIVE_TV] }
    val hapticsEnabled: Flow<Boolean> = dataStore.data.map { it[HAPTICS] ?: true }

    /** The remote keys as an ongoing notification, also on the lock screen. */
    val notificationRemote: Flow<Boolean> = dataStore.data.map { it[NOTIFICATION_REMOTE] ?: false }

    suspend fun setNotificationRemote(enabled: Boolean) {
        dataStore.edit { it[NOTIFICATION_REMOTE] = enabled }
    }

    /** The remote shows the touchpad instead of the D-pad ring. */
    val touchpad: Flow<Boolean> = dataStore.data.map { it[TOUCHPAD] ?: false }

    /** Mute the TV while a phone call rings or is on (needs the phone-state permission). */
    val muteOnCalls: Flow<Boolean> = dataStore.data.map { it[MUTE_ON_CALLS] ?: false }

    /** The app muted the TV for a call and unmutes it when the call ends. */
    val mutedForCall: Flow<Boolean> = dataStore.data.map { it[MUTED_FOR_CALL] ?: false }

    /**
     * After a sleep-timer fade: the TV ([VolumeRestore.tvId]) went off at [VolumeRestore.faded] and
     * should get [VolumeRestore.level] back the next time the app sees it on.
     */
    val volumeRestore: Flow<VolumeRestore?> = dataStore.data.map { prefs ->
        prefs[VOLUME_RESTORE]?.split('|')?.takeIf { it.size == 3 }?.let { (id, level, faded) ->
            VolumeRestore(id, level.toIntOrNull() ?: return@let null, faded.toIntOrNull() ?: return@let null)
        }
    }

    suspend fun setVolumeRestore(restore: VolumeRestore?) {
        dataStore.edit {
            if (restore == null) it.remove(VOLUME_RESTORE) else it[VOLUME_RESTORE] = "${restore.tvId}|${restore.level}|${restore.faded}"
        }
    }

    data class VolumeRestore(val tvId: String, val level: Int, val faded: Int)

    /** When the sleep timer turns the TV off (epoch ms), or null when no timer is set. */
    val sleepAt: Flow<Long?> = dataStore.data.map { it[SLEEP_AT] }

    suspend fun upsert(tv: SavedTv, makeActive: Boolean = true) {
        dataStore.edit { prefs ->
            val list = prefs.readTvs().filterNot { it.id == tv.id } + tv
            prefs[TVS] = json.encodeToString(listSerializer, list)
            if (makeActive) prefs[ACTIVE_TV] = tv.id
        }
    }

    suspend fun updateHost(id: String, host: String) {
        dataStore.edit { prefs ->
            val list = prefs.readTvs().map { if (it.id == id) it.copy(host = host) else it }
            prefs[TVS] = json.encodeToString(listSerializer, list)
        }
    }

    suspend fun remove(id: String) {
        dataStore.edit { prefs ->
            val list = prefs.readTvs().filterNot { it.id == id }
            prefs[TVS] = json.encodeToString(listSerializer, list)
            if (prefs[ACTIVE_TV] == id) {
                list.firstOrNull()?.let { prefs[ACTIVE_TV] = it.id } ?: prefs.remove(ACTIVE_TV)
            }
            prefs.remove(appsKey(id))
        }
    }

    val wakeUp: Flow<WakeUp> = dataStore.data.map { prefs ->
        prefs[WAKE_UP]?.let { runCatching { json.decodeFromString(WakeUp.serializer(), it) }.getOrNull() } ?: WakeUp()
    }

    suspend fun setWakeUp(wakeUp: WakeUp) {
        dataStore.edit { it[WAKE_UP] = json.encodeToString(WakeUp.serializer(), wakeUp) }
    }

    /** Apps pinned as one-tap buttons under the remote's card and as launcher shortcuts. */
    val favoriteApps: Flow<List<TvApp>> = dataStore.data.map { prefs ->
        prefs[FAVORITE_APPS]?.let { runCatching { json.decodeFromString(appListSerializer, it) }.getOrNull() }.orEmpty()
    }

    /** Pins [app], or unpins it when it is already pinned. */
    suspend fun toggleFavoriteApp(app: TvApp): List<TvApp> {
        var result = emptyList<TvApp>()
        dataStore.edit { prefs ->
            val list = prefs[FAVORITE_APPS]?.let { runCatching { json.decodeFromString(appListSerializer, it) }.getOrNull() }.orEmpty()
            result = if (list.any { it.uri == app.uri }) list.filterNot { it.uri == app.uri } else list + app
            prefs[FAVORITE_APPS] = json.encodeToString(appListSerializer, result)
        }
        return result
    }

    val scenes: Flow<List<Scene>> = dataStore.data.map { prefs ->
        prefs[SCENES]?.let { runCatching { json.decodeFromString(sceneListSerializer, it) }.getOrNull() }.orEmpty()
    }

    suspend fun saveScene(scene: Scene) {
        dataStore.edit { prefs ->
            val list = prefs[SCENES]?.let { runCatching { json.decodeFromString(sceneListSerializer, it) }.getOrNull() }.orEmpty()
            val updated = if (list.any { it.id == scene.id }) list.map { if (it.id == scene.id) scene else it } else list + scene
            prefs[SCENES] = json.encodeToString(sceneListSerializer, updated)
        }
    }

    suspend fun deleteScene(id: String) {
        dataStore.edit { prefs ->
            val list = prefs[SCENES]?.let { runCatching { json.decodeFromString(sceneListSerializer, it) }.getOrNull() }.orEmpty()
            prefs[SCENES] = json.encodeToString(sceneListSerializer, list.filterNot { it.id == id })
        }
    }

    /** The TV's app list as last fetched, shown at once while a fresh one loads (the TV takes up to 3 s). */
    suspend fun cachedApps(tvId: String): List<TvApp> =
        dataStore.data.first()[appsKey(tvId)]
            ?.let { runCatching { json.decodeFromString(appListSerializer, it) }.getOrNull() }
            .orEmpty()

    suspend fun saveApps(tvId: String, apps: List<TvApp>) {
        dataStore.edit { it[appsKey(tvId)] = json.encodeToString(appListSerializer, apps) }
    }

    suspend fun setActive(id: String) {
        dataStore.edit { it[ACTIVE_TV] = id }
    }

    suspend fun setHaptics(enabled: Boolean) {
        dataStore.edit { it[HAPTICS] = enabled }
    }

    suspend fun setTouchpad(enabled: Boolean) {
        dataStore.edit { it[TOUCHPAD] = enabled }
    }

    suspend fun setMuteOnCalls(enabled: Boolean) {
        dataStore.edit { it[MUTE_ON_CALLS] = enabled }
    }

    suspend fun setMutedForCall(muted: Boolean) {
        dataStore.edit { it[MUTED_FOR_CALL] = muted }
    }

    suspend fun setSleepAt(epochMs: Long?) {
        dataStore.edit { if (epochMs == null) it.remove(SLEEP_AT) else it[SLEEP_AT] = epochMs }
    }

    private fun Preferences.readTvs(): List<SavedTv> =
        this[TVS]?.let { runCatching { json.decodeFromString(listSerializer, it) }.getOrNull() } ?: emptyList()

    private companion object {
        val TVS = stringPreferencesKey("tvs")
        val ACTIVE_TV = stringPreferencesKey("active_tv")
        val HAPTICS = booleanPreferencesKey("haptics")
        val TOUCHPAD = booleanPreferencesKey("touchpad")
        val NOTIFICATION_REMOTE = booleanPreferencesKey("notification_remote")
        val SLEEP_AT = longPreferencesKey("sleep_at")
        val MUTE_ON_CALLS = booleanPreferencesKey("mute_on_calls")
        val MUTED_FOR_CALL = booleanPreferencesKey("muted_for_call")
        val VOLUME_RESTORE = stringPreferencesKey("volume_restore")
        val json = Json { ignoreUnknownKeys = true }
        val listSerializer = ListSerializer(SavedTv.serializer())
        val appListSerializer = ListSerializer(TvApp.serializer())
        val sceneListSerializer = ListSerializer(Scene.serializer())
        val WAKE_UP = stringPreferencesKey("wake_up")
        val SCENES = stringPreferencesKey("scenes")
        val FAVORITE_APPS = stringPreferencesKey("favorite_apps")
        fun appsKey(tvId: String) = stringPreferencesKey("apps.$tvId")
    }
}
