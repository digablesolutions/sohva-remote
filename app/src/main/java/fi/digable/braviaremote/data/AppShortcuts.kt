package fi.digable.braviaremote.data

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import fi.digable.braviaremote.MainActivity
import fi.digable.braviaremote.R
import fi.digable.braviaremote.protocol.TvApp

/**
 * The pinned apps as launcher shortcuts (long-press the app icon): each opens the app on the TV.
 * Android shows about four, so only the first ones are offered.
 */
object AppShortcuts {
    const val ACTION_OPEN_TV_APP = "fi.digable.sohva.OPEN_TV_APP"
    const val EXTRA_URI = "uri"
    private const val MAX = 4

    fun publish(context: Context, favorites: List<TvApp>) {
        val shortcuts = favorites.take(MAX).map { app ->
            ShortcutInfoCompat.Builder(context, "app:${app.uri}")
                .setShortLabel(app.title)
                .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_app))
                .setIntent(
                    Intent(context, MainActivity::class.java)
                        .setAction(ACTION_OPEN_TV_APP)
                        .putExtra(EXTRA_URI, app.uri),
                )
                .build()
        }
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts) }
            .onFailure { DebugLog.w(it) { "Shortcuts not published" } }
    }
}
