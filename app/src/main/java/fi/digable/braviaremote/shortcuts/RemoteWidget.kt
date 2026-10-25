package fi.digable.braviaremote.shortcuts

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import android.view.View
import android.widget.RemoteViews
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import fi.digable.braviaremote.MainActivity
import fi.digable.braviaremote.R
import fi.digable.braviaremote.data.ConnectionState
import fi.digable.braviaremote.data.DebugLog
import fi.digable.braviaremote.data.SleepTimer
import fi.digable.braviaremote.data.TvController
import fi.digable.braviaremote.di.ApplicationScope
import fi.digable.braviaremote.protocol.RemoteKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The home-screen widget: what the TV shows and its volume, power, volume, mute and channel keys.
 * A tap may start the app's process from nothing, so it waits for the TV session first, and holds
 * the broadcast open ([goAsync]) until the key has gone out.
 */
@AndroidEntryPoint
class RemoteWidget : AppWidgetProvider() {
    @Inject lateinit var controller: TvController
    @Inject lateinit var updater: WidgetUpdater
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        updater.renderNow()
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_PRESS) return
        val action = intent.getStringExtra(EXTRA_ACTION) ?: return
        val pending = goAsync()
        scope.launch {
            try {
                withTimeoutOrNull(RECEIVER_BUDGET_MS) { perform(action) }
                    ?: DebugLog.w { "Widget $action: TV did not answer in time" }
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun perform(action: String) {
        if (action == POWER) {
            controller.togglePowerNow()
            return
        }
        // Keys to a TV in standby would go nowhere.
        if (controller.awaitSession() !is ConnectionState.Connected) return
        when (action) {
            MUTE -> controller.toggleMute()?.await()
            else -> controller.press(RemoteKey.valueOf(action)).await()
        }
    }

    companion object {
        const val ACTION_PRESS = "fi.digable.braviaremote.widget.PRESS"
        const val EXTRA_ACTION = "action"
        const val POWER = "POWER"
        const val MUTE = "MUTE"

        /** A receiver is killed after about 10 s. */
        private const val RECEIVER_BUDGET_MS = 8_500L
    }
}

/** Keeps the widgets current while the app's process runs; the TV session's state drives them. */
@Singleton
class WidgetUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    private val controller: TvController,
    private val sleepTimer: SleepTimer,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private data class Model(
        val tvName: String,
        val headline: String,
        val detail: String?,
        val on: Boolean,
        val muted: Boolean,
    )

    @Volatile private var last: Model? = null

    fun start() {
        val appTitle = combine(controller.currentApp, controller.apps) { pkg, apps ->
            pkg?.let { name -> apps.firstOrNull { it.isPackage(name) }?.title }
        }
        scope.launch {
            combine(controller.state, controller.playing, controller.inputs, appTitle, sleepTimer.endsAt) { state, playing, inputs, app, sleepAt ->
                model(state, playing?.uri, playing?.title, inputs.firstOrNull { it.uri == playing?.uri }?.displayName, app, sleepAt)
            }.distinctUntilChanged().collect {
                last = it
                render(it)
            }
        }
    }

    /** For the system's update request: draws the last known state (or "connecting" before the first). */
    fun renderNow() = render(last ?: model(controller.state.value, null, null, null, null, null))

    private fun model(
        state: ConnectionState,
        playingUri: String?,
        playingTitle: String?,
        inputName: String?,
        appTitle: String?,
        sleepAt: Long?,
    ): Model {
        val name = state.tvOrNull?.name.orEmpty()
        val sleep = sleepAt?.takeIf { it > System.currentTimeMillis() }?.let {
            context.getString(R.string.sleep_off_at, DateFormat.getTimeFormat(context).format(Date(it)))
        }
        return when (state) {
            is ConnectionState.Connected -> {
                val volume = state.volume?.let {
                    if (it.mute) context.getString(R.string.tile_muted) else context.getString(R.string.tile_volume, it.volume)
                }
                Model(
                    tvName = name,
                    headline = inputName
                        ?: playingTitle?.ifBlank { null }
                        ?: appTitle
                        ?: context.getString(if (playingUri == null) R.string.widget_tv_on else R.string.remote_source_unknown),
                    detail = listOfNotNull(volume, sleep).joinToString(" · ").ifEmpty { null },
                    on = true,
                    muted = state.volume?.mute == true,
                )
            }
            is ConnectionState.NoTv -> Model("", context.getString(R.string.widget_no_tv), null, on = false, muted = false)
            is ConnectionState.Connecting -> Model(name, context.getString(R.string.tile_connecting), sleep, on = false, muted = false)
            is ConnectionState.Standby -> Model(name, context.getString(R.string.tile_standby), null, on = false, muted = false)
            is ConnectionState.Unreachable -> Model(name, context.getString(R.string.tile_unreachable), null, on = false, muted = false)
            is ConnectionState.Unauthorized -> Model(name, context.getString(R.string.tile_unauthorized), null, on = false, muted = false)
        }
    }

    private fun render(model: Model) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, RemoteWidget::class.java))
        if (ids.isEmpty()) return
        val views = RemoteViews(context.packageName, R.layout.widget_remote).apply {
            setTextViewText(R.id.widget_tv_name, model.tvName)
            setTextViewText(R.id.widget_headline, model.headline)
            setTextViewText(R.id.widget_detail, model.detail.orEmpty())
            setViewVisibility(R.id.widget_detail, if (model.detail == null) View.GONE else View.VISIBLE)
            setInt(R.id.widget_power, "setColorFilter", context.getColor(if (model.on) R.color.widget_power_on else R.color.widget_accent))
            setImageViewResource(R.id.widget_mute, if (model.muted) R.drawable.ic_volume_up else R.drawable.ic_volume_off)
            setContentDescription(R.id.widget_mute, context.getString(if (model.muted) R.string.remote_unmute else R.string.remote_mute))
            setOnClickPendingIntent(R.id.widget_info, openApp())
            KEYS.forEachIndexed { i, (id, action) -> setOnClickPendingIntent(id, press(i, action)) }
        }
        manager.updateAppWidget(ids, views)
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
    )

    /** One broadcast per key; the request code keeps them apart. */
    private fun press(requestCode: Int, action: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        requestCode,
        Intent(context, RemoteWidget::class.java).setAction(RemoteWidget.ACTION_PRESS).putExtra(RemoteWidget.EXTRA_ACTION, action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private companion object {
        val KEYS = listOf(
            R.id.widget_power to RemoteWidget.POWER,
            R.id.widget_mute to RemoteWidget.MUTE,
            R.id.widget_volume_down to RemoteKey.VOLUME_DOWN.name,
            R.id.widget_volume_up to RemoteKey.VOLUME_UP.name,
            R.id.widget_channel_down to RemoteKey.CHANNEL_DOWN.name,
            R.id.widget_channel_up to RemoteKey.CHANNEL_UP.name,
        )
    }
}
