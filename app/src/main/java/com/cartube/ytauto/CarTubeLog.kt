package com.cartube.ytauto

import android.util.Log
import java.util.ArrayDeque

/** Ring buffer of recent CarTube logs for on-device copy/share. */
object CarTubeLog {

    const val TAG = "CarTube"
    private const val CAPACITY = 300
    private val lock = Any()
    private val ring = ArrayDeque<String>(CAPACITY + 1)

    fun playerTag(): String = PlaybackEngine.playerHashLabel()

    fun i(message: String, tr: Throwable? = null) = write('I', message, tr)

    fun w(message: String, tr: Throwable? = null) = write('W', message, tr)

    fun e(message: String, tr: Throwable? = null) = write('E', message, tr)

    fun d(message: String) = write('D', message, null)

    fun surfaceIdentity(surface: Any?): String =
        if (surface == null) "null" else Integer.toHexString(System.identityHashCode(surface))

    fun dump(): String = synchronized(lock) { ring.joinToString("\n") }

    private fun write(level: Char, message: String, tr: Throwable?) {
        val line = "${System.currentTimeMillis()} $level ${playerTag()} $message"
        synchronized(lock) {
            if (ring.size >= CAPACITY) ring.removeFirst()
            ring.addLast(line)
        }
        when (level) {
            'E' -> if (tr != null) Log.e(TAG, message, tr) else Log.e(TAG, message)
            'W' -> if (tr != null) Log.w(TAG, message, tr) else Log.w(TAG, message)
            'D' -> Log.d(TAG, message)
            else -> Log.i(TAG, message)
        }
    }
}