package com.mothandgravel.sohva.shortcuts

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import com.mothandgravel.sohva.MainActivity
import com.mothandgravel.sohva.R
import com.mothandgravel.sohva.data.TvStore
import com.mothandgravel.sohva.protocol.RemoteKey
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A quiet, ongoing notification with the keys people reach for from the sofa: power, volume, mute,
 * pause and play, also on the lock screen. Its buttons go to [RemoteWidget]'s receiver, so they work
 * with the app closed, exactly like the widget's.
 */
@Singleton
class NotificationRemote @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: TvStore,
) {
    /** Shows or removes the notification to match the setting; at app start, boot and on change. */
    suspend fun sync() = if (store.notificationRemote.first()) show() else hide()

    fun hide() = NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)

    private fun show() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            // Default importance keeps it with the other notifications (low folds it into the silent
            // row); no sound or vibration, so it never makes a noise.
            NotificationChannel(CHANNEL_ID, context.getString(R.string.notif_remote_channel), NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            },
        )
        val keys = RemoteViews(context.packageName, R.layout.notification_remote).apply {
            KEYS.forEachIndexed { i, (id, action) -> setOnClickPendingIntent(id, press(REQUEST_BASE + i, action)) }
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_remote)
            .setContentTitle(context.getString(R.string.app_name))
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(keys)
            .setCustomBigContentView(keys)
            .setContentIntent(
                PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE),
            )
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
    }

    private fun press(requestCode: Int, action: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        requestCode,
        Intent(context, RemoteWidget::class.java).setAction(RemoteWidget.ACTION_PRESS).putExtra(RemoteWidget.EXTRA_ACTION, action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private companion object {
        const val CHANNEL_ID = "remote_keys"
        const val NOTIFICATION_ID = 3
        /** Clear of the widget's request codes, which start at 0. */
        const val REQUEST_BASE = 100
        val KEYS = listOf(
            R.id.notif_power to RemoteWidget.POWER,
            R.id.notif_volume_down to RemoteKey.VOLUME_DOWN.name,
            R.id.notif_mute to RemoteWidget.MUTE,
            R.id.notif_volume_up to RemoteKey.VOLUME_UP.name,
            R.id.notif_pause to RemoteKey.PAUSE.name,
            R.id.notif_play to RemoteKey.PLAY.name,
        )
    }
}
