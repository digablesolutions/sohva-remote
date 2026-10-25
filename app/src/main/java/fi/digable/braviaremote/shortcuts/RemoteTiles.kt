package fi.digable.braviaremote.shortcuts

import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dagger.hilt.android.AndroidEntryPoint
import fi.digable.braviaremote.R
import fi.digable.braviaremote.data.ConnectionState
import fi.digable.braviaremote.data.TvController
import fi.digable.braviaremote.data.TvStore
import fi.digable.braviaremote.data.WakeUpService
import fi.digable.braviaremote.di.ApplicationScope
import fi.digable.braviaremote.protocol.RemoteKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Quick Settings tiles for the TV, usable without opening the app. A tap may start the app's
 * process from nothing, so each action first waits for the TV session ([TvController.awaitSession]).
 * The work runs on the app scope: the system unbinds a tile soon after the tap.
 */
abstract class RemoteTile : TileService() {
    @Inject lateinit var controller: TvController
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    private var listening: Job? = null

    protected abstract val iconRes: Int
    protected abstract val labelRes: Int

    /** Replaces the label from [labelRes], e.g. with the name of the scene a tile runs. */
    protected var labelOverride: String? = null

    /** Runs the tap's action; the session is already settled. */
    protected abstract suspend fun act(session: ConnectionState)

    /** Tile on (highlighted) or off, and the line under the label. */
    protected abstract fun describe(state: ConnectionState): Pair<Boolean, String?>

    override fun onStartListening() {
        listening?.cancel()
        listening = scope.launch { controller.state.collect { render(it) } }
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
    }

    override fun onClick() {
        scope.launch {
            val session = controller.awaitSession() ?: return@launch
            act(session)
        }
    }

    /** Draws the tile again, e.g. after its label changed. */
    protected fun refresh() = render(controller.state.value)

    private fun render(state: ConnectionState) {
        val tile = qsTile ?: return
        val (active, subtitle) = when (state) {
            is ConnectionState.NoTv -> false to null
            is ConnectionState.Connecting -> false to getString(R.string.tile_connecting)
            is ConnectionState.Unreachable -> false to getString(R.string.tile_unreachable)
            is ConnectionState.Unauthorized -> false to getString(R.string.tile_unauthorized)
            else -> describe(state)
        }
        tile.icon = Icon.createWithResource(this, iconRes)
        tile.label = labelOverride ?: getString(labelRes)
        tile.state = when {
            state is ConnectionState.NoTv -> Tile.STATE_UNAVAILABLE
            active -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = subtitle
        tile.updateTile()
    }
}

@AndroidEntryPoint
class PowerTile : RemoteTile() {
    override val iconRes = R.drawable.ic_power
    override val labelRes = R.string.tile_power
    override suspend fun act(session: ConnectionState) = controller.togglePowerNow()
    override fun describe(state: ConnectionState) = when (state) {
        is ConnectionState.Connected -> true to getString(R.string.tile_on)
        else -> false to getString(R.string.tile_standby)
    }
}

@AndroidEntryPoint
class MuteTile : RemoteTile() {
    override val iconRes = R.drawable.ic_volume_off
    override val labelRes = R.string.tile_mute
    override suspend fun act(session: ConnectionState) {
        if (session is ConnectionState.Connected) controller.toggleMute()?.await()
    }
    override fun describe(state: ConnectionState): Pair<Boolean, String?> {
        val volume = (state as? ConnectionState.Connected)?.volume
            ?: return false to (if (state is ConnectionState.Connected) null else getString(R.string.tile_standby))
        return volume.mute to if (volume.mute) getString(R.string.tile_muted) else getString(R.string.tile_volume, volume.volume)
    }
}

@AndroidEntryPoint
class GuideTile : RemoteTile() {
    override val iconRes = R.drawable.ic_guide
    override val labelRes = R.string.tile_guide
    override suspend fun act(session: ConnectionState) {
        if (session is ConnectionState.Connected) controller.press(RemoteKey.GUIDE).await()
    }
    override fun describe(state: ConnectionState) = false to
        (if (state is ConnectionState.Connected) null else getString(R.string.tile_standby))
}

/** Runs the first scene from the app's Scenes tab, named after it. */
@AndroidEntryPoint
class SceneTile : RemoteTile() {
    @Inject lateinit var store: TvStore

    override val iconRes = R.drawable.ic_scene
    override val labelRes = R.string.tile_scene

    override fun onClick() {
        scope.launch {
            val scene = store.scenes.first().firstOrNull() ?: return@launch
            // A service keeps the scene going while the TV comes on; if Android refuses, run it here.
            if (!WakeUpService.runScene(this@SceneTile, scene)) controller.apply(scene.target, scene.volume)
        }
    }

    override suspend fun act(session: ConnectionState) = Unit

    override fun onStartListening() {
        labelOverride = cachedName
        super.onStartListening()
        scope.launch {
            val name = store.scenes.first().firstOrNull()?.name
            if (name != cachedName) {
                cachedName = name
                labelOverride = name
                refresh()
            }
        }
    }

    override fun describe(state: ConnectionState): Pair<Boolean, String?> =
        false to if (labelOverride == null) getString(R.string.tile_scene_none) else null

    private companion object {
        /** The first scene's name as last read, so the label is right before the store answers. */
        @Volatile var cachedName: String? = null
    }
}
