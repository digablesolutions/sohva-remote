package fi.digable.braviaremote.data

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import fi.digable.braviaremote.R
import fi.digable.braviaremote.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Switches the TV on at a set time on set days, also with the app closed: like the sleep timer,
 * an exact alarm starts a short foreground service ([WakeUpService]), which turns the TV on and to
 * the chosen channel and volume, then sets the alarm for the next day.
 */
@Singleton
class WakeUpAlarm @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: TvStore,
) {
    val settings: Flow<WakeUp> = store.wakeUp

    suspend fun save(wakeUp: WakeUp) {
        store.setWakeUp(wakeUp)
        schedule(wakeUp)
    }

    /** At app start and after each run: sets the alarm for the next time, or clears it. */
    suspend fun reschedule() = schedule(store.wakeUp.first())

    /**
     * Debug builds: runs the wake-up once, a minute from now, without changing the settings. Its own
     * alarm, so the app starting meanwhile (which calls [reschedule]) does not replace it.
     */
    fun tryInOneMinute() = setAlarm(System.currentTimeMillis() + 60_000L, alarmIntent(TRIAL_REQUEST))

    private fun schedule(wakeUp: WakeUp) {
        val next = wakeUp.nextAfter(ZonedDateTime.now())
        if (next == null) {
            alarms().cancel(alarmIntent(SCHEDULE_REQUEST))
            DebugLog.d { "Wake-up off" }
        } else {
            setAlarm(next.toInstant().toEpochMilli(), alarmIntent(SCHEDULE_REQUEST))
            DebugLog.d { "Wake-up at $next" }
        }
    }

    private fun setAlarm(at: Long, intent: PendingIntent) = alarms().setWhileIdle(at, intent)

    private fun alarms() = context.getSystemService(AlarmManager::class.java)

    private fun alarmIntent(request: Int): PendingIntent = PendingIntent.getBroadcast(
        context, request, Intent(context, WakeUpReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private companion object {
        const val SCHEDULE_REQUEST = 0
        const val TRIAL_REQUEST = 1
    }
}

/** Exact when allowed (an exact alarm may start a foreground service), otherwise as close as Android lets it. */
internal fun AlarmManager.setWhileIdle(at: Long, intent: PendingIntent) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || canScheduleExactAlarms()) {
        setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
    } else {
        setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
    }
}

/**
 * Scheduled scenes: one alarm, for the soonest run of any scene. When it fires, [WakeUpService]
 * runs that scene (only if the TV is on) and calls [reschedule] for the next one.
 */
@Singleton
class SceneSchedule @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: TvStore,
) {
    /** At app start, after a scene is saved or deleted, and after each run. */
    suspend fun reschedule() {
        val now = ZonedDateTime.now()
        val next = store.scenes.first()
            .mapNotNull { scene -> scene.nextRunAfter(now)?.let { it to scene } }
            .minByOrNull { it.first }
        val alarms = context.getSystemService(AlarmManager::class.java)
        if (next == null) {
            alarms.cancel(alarmIntent(null))
            DebugLog.d { "No scheduled scene" }
        } else {
            alarms.setWhileIdle(next.first.toInstant().toEpochMilli(), alarmIntent(next.second.id))
            DebugLog.d { "Scene ${next.second.name} at ${next.first}" }
        }
    }

    private fun alarmIntent(sceneId: String?): PendingIntent = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, SceneAlarmReceiver::class.java).apply { sceneId?.let { putExtra(WakeUpService.EXTRA_SCENE, it) } },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

/** A scheduled scene's time: runs it in [WakeUpService], which leaves a TV that is off alone. */
@AndroidEntryPoint
class SceneAlarmReceiver : InjectedReceiver() {
    @Inject lateinit var controller: TvController
    @Inject lateinit var store: TvStore
    @Inject lateinit var sceneSchedule: SceneSchedule
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val sceneId = intent.getStringExtra(WakeUpService.EXTRA_SCENE) ?: return
        val started = runCatching {
            context.startForegroundService(
                Intent(context, WakeUpService::class.java)
                    .putExtra(WakeUpService.EXTRA_SCENE, sceneId)
                    .putExtra(WakeUpService.EXTRA_SCHEDULED, true),
            )
        }.onFailure { DebugLog.w(it) { "Scheduled scene: service not started" } }.isSuccess
        if (started) return
        // Without exact alarms Android may refuse the service. A scheduled scene only acts on a TV
        // that is already on, which fits in a receiver's few seconds; and the next alarm must be set
        // either way, or the schedule would stop here.
        val pending = goAsync()
        scope.launch {
            try {
                withTimeoutOrNull(RECEIVER_BUDGET_MS) {
                    store.scenes.first().firstOrNull { it.id == sceneId }
                        ?.let { controller.apply(it.target, it.volume, onlyIfOn = true) }
                }
            } finally {
                sceneSchedule.reschedule()
                pending.finish()
            }
        }
    }
}

/** The wake-up alarm: an exact alarm may start a foreground service, which has time for a booting TV. */
@AndroidEntryPoint
class WakeUpReceiver : InjectedReceiver() {
    @Inject lateinit var alarm: WakeUpAlarm
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val started = runCatching { context.startForegroundService(Intent(context, WakeUpService::class.java)) }
            .onFailure { DebugLog.w(it) { "Wake-up: service not started" } }.isSuccess
        if (started) return
        // Switching a TV on takes too long for a receiver; at least keep tomorrow's alarm.
        val pending = goAsync()
        scope.launch {
            try {
                alarm.reschedule()
            } finally {
                pending.finish()
            }
        }
    }
}

/** What a receiver may spend on the TV after goAsync(): Android allows about ten seconds. */
private const val RECEIVER_BUDGET_MS = 8_000L

/**
 * Sets the wake-up and sleep timer alarms again after the phone restarts (a restart drops all
 * alarms). Android sends this only after the first unlock, so a wake-up cannot fire on a phone that
 * is still locked after a restart.
 */
@AndroidEntryPoint
class BootReceiver : InjectedReceiver() {
    @Inject lateinit var wakeUp: WakeUpAlarm
    @Inject lateinit var sleepTimer: SleepTimer
    @Inject lateinit var sceneSchedule: SceneSchedule
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        // After a restart, or once the user allows exact alarms: set everything again, now exact.
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
        ) return
        val pending = goAsync()
        scope.launch {
            try {
                wakeUp.reschedule()
                sceneSchedule.reschedule()
                sleepTimer.restore()
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * Turns the TV on to the wake-up's channel and volume and sets the next alarm; or, with
 * [EXTRA_SCENE], runs that scene ([EXTRA_SCHEDULED]: only if the TV is on, then the next alarm). A foreground service, because a TV that has to come on first
 * takes up to a minute, and a cached app would lose the network halfway.
 */
@AndroidEntryPoint
class WakeUpService : Service() {
    @Inject lateinit var controller: TvController
    @Inject lateinit var alarm: WakeUpAlarm
    @Inject lateinit var store: TvStore
    @Inject lateinit var sceneSchedule: SceneSchedule
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification())
        }
        if (job?.isActive == true) return START_NOT_STICKY
        job = scope.launch {
            val wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BraviaRemote:wakeUp")
            wakeLock.acquire(WAKE_LOCK_MS)
            val sceneId = intent?.getStringExtra(EXTRA_SCENE)
            val scheduled = intent?.getBooleanExtra(EXTRA_SCHEDULED, false) == true
            try {
                if (sceneId != null) {
                    val scene = store.scenes.first().firstOrNull { it.id == sceneId }
                    DebugLog.d { "Scene ${scene?.name}${if (scheduled) " (scheduled)" else ""}" }
                    if (scene != null) controller.apply(scene.target, scene.volume, onlyIfOn = scheduled)
                } else {
                    val wakeUp = alarm.settings.first()
                    DebugLog.d { "Wake-up: switching the TV on" }
                    controller.apply(wakeUp.target, wakeUp.volume)
                }
            } finally {
                if (sceneId == null) alarm.reschedule()
                if (scheduled) sceneSchedule.reschedule()
                if (wakeLock.isHeld) wakeLock.release()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int) {
        job?.cancel()
        stopSelf()
    }

    override fun onDestroy() {
        job?.cancel()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.wake_title), NotificationManager.IMPORTANCE_LOW),
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_power)
            .setContentTitle(getString(R.string.wake_running))
            .build()
    }

    companion object {
        const val EXTRA_SCENE = "scene"
        const val EXTRA_SCHEDULED = "scheduled"
        private const val CHANNEL_ID = "wake_up"
        private const val NOTIFICATION_ID = 2
        private const val WAKE_LOCK_MS = 3 * 60_000L

        /** Runs [scene] in the service; false when Android does not allow starting it now. */
        fun runScene(context: Context, scene: Scene): Boolean = runCatching {
            context.startForegroundService(Intent(context, WakeUpService::class.java).putExtra(EXTRA_SCENE, scene.id))
        }.onFailure { DebugLog.w(it) { "Scene service not started" } }.isSuccess
    }
}
