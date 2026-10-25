package fi.digable.braviaremote.data

import android.util.Log
import fi.digable.braviaremote.BuildConfig

/**
 * Debug-build-only logging under the `BraviaRemote` tag; release builds log nothing.
 * Filter with `adb logcat -s BraviaRemote`. Never pass cookies, PSKs or PINs in a message.
 */
object DebugLog {
    private const val TAG = "BraviaRemote"

    fun d(message: () -> String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message())
    }

    fun w(error: Throwable? = null, message: () -> String) {
        if (BuildConfig.DEBUG) Log.w(TAG, message(), error)
    }
}
