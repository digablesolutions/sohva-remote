package fi.digable.braviaremote.data

import android.os.Build
import fi.digable.braviaremote.BuildConfig

/**
 * How this phone and app appear in the TV's device lists, e.g. "XQ-DE54 (Sohva Remote)". Debug
 * builds say "Sohva Dev", so the two are told apart in those lists.
 */
object DeviceName {
    val forTv: String get() = "${Build.MODEL} (${if (BuildConfig.DEBUG) "Sohva Dev" else "Sohva Remote"})"
}
