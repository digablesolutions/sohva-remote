package fi.digable.braviaremote.data

import android.os.Build
import fi.digable.braviaremote.BuildConfig

/**
 * How this phone and app appear in the TV's device lists, e.g. "XQ-DE54 (Sohva Remote)". Debug
 * builds say "Sohva Dev": the TV's Android TV Remote service replaced the release app's fast-keys
 * pairing when a debug build paired under the same name.
 */
object DeviceName {
    val forTv: String get() = "${Build.MODEL} (${if (BuildConfig.DEBUG) "Sohva Dev" else "Sohva Remote"})"
}
