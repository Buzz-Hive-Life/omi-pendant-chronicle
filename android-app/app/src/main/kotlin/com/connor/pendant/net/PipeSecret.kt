package com.connor.pendant.net

import android.content.Context
import com.connor.pendant.BuildConfig

/**
 * The pipe's shared secret, held on the DEVICE and never in the build.
 *
 * ⚠️ WHY NOT A BUILD CONFIG: the upstream design embeds the secret in the APK, which means a shared APK
 * carries a credential and rotating it needs a rebuild. Storing it here means the artifact is inert.
 *
 * The build-time value is still honoured as a FALLBACK, so a local build with `local.properties` keeps
 * working exactly as upstream intended.
 */
object PipeSecret {
    private const val PREFS = "hive_pipe"
    private const val KEY = "pipe_secret"
    private const val TAG = "PipeSecret"

    fun get(context: Context): String {
        val stored = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null)
        return stored?.takeIf { it.isNotBlank() }
            ?: try { BuildConfig.PENDANT_SECRET } catch (t: Throwable) { "" }
    }

    fun set(context: Context, value: String) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, value.trim()).apply()
    }

    fun isSet(context: Context): Boolean = get(context).isNotBlank()

    /** Whether the value came from the device rather than the build — shown in Settings. */
    fun source(context: Context): String {
        val stored = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null)
        return if (!stored.isNullOrBlank()) "typed on this device" else "build default"
    }
}
