package com.mothandgravel.sohva.data

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList

/**
 * The app's own language, apart from the phone's: a tag from [TAGS], or null for the phone's language.
 *
 * Android 13+ keeps it as the per-app language, which the system settings (Apps › Sohva Remote ›
 * Language) also set, so both stay in step. Older versions have no per-app language: the choice is
 * kept here and [wrap] applies it to the app and the activity as they start.
 */
object AppLanguage {
    /** The languages the app is translated into, as in res/values (en) and res/values-fi. */
    val TAGS = listOf("en", "fi")

    private const val PREFS = "app_language"
    private const val KEY = "tag"

    fun current(context: Context): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java).applicationLocales
                .takeUnless { it.isEmpty }?.get(0)?.language
        } else {
            prefs(context).getString(KEY, null)
        }

    /** Switches to [tag] (null: the phone's language) and redraws [activity] in it. */
    fun set(activity: Activity, tag: String?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // The system recreates the activity itself.
            activity.getSystemService(LocaleManager::class.java).applicationLocales =
                if (tag == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        } else {
            prefs(activity).edit().putString(KEY, tag).commit()
            activity.recreate()
        }
    }

    /** Before Android 13: [base] in the chosen language, for attachBaseContext. Otherwise [base] itself. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = prefs(base).getString(KEY, null) ?: return base
        val config = Configuration(base.resources.configuration).apply { setLocales(LocaleList.forLanguageTags(tag)) }
        return base.createConfigurationContext(config)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
