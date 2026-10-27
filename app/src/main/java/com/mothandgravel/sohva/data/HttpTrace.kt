package com.mothandgravel.sohva.data

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Logs one line per TV call: method, host, path, status and round-trip time. Headers and bodies
 * are never logged, because they carry the auth cookie and the PIN.
 */
class HttpTrace : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val what = "${request.method} ${request.url.host}${request.url.encodedPath}"
        val start = System.nanoTime()
        val response = try {
            chain.proceed(request)
        } catch (e: IOException) {
            DebugLog.w(e) { "$what failed after ${elapsedMs(start)} ms" }
            throw e
        }
        DebugLog.d { "$what -> ${response.code} (${elapsedMs(start)} ms)" }
        return response
    }

    private fun elapsedMs(start: Long) = (System.nanoTime() - start) / 1_000_000
}
