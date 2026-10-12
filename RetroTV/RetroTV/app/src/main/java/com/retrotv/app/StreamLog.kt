package com.retrotv.app

import android.content.Context
import java.io.File

/**
 * Records what happened during a PC stream, so failures can be explained instead of guessed.
 *
 * Before a stream starts we set a flag. When RetroTV is back on screen, we save the app's recent
 * system log (Android lets an app read its own log lines) and look for a crash or connection error.
 * The saved log is also served at http://<tv>:8080/log so it can be copied from a PC.
 */
object StreamLog {
    private const val PREFS = "streamlog"

    fun logFile(ctx: Context) = File(ctx.filesDir, "stream-log.txt")

    fun markStarted(ctx: Context, what: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("active", true)
            .putString("what", what)
            .putLong("at", System.currentTimeMillis())
            .apply()
    }

    data class Problem(val title: String, val detail: String)

    /** Call when RetroTV comes back on screen. Returns a problem if the last stream crashed or failed. */
    fun checkAfterStream(ctx: Context): Problem? {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("active", false)) return null
        val what = prefs.getString("what", "the stream") ?: "the stream"
        val startedAt = prefs.getLong("at", 0)
        prefs.edit().putBoolean("active", false).apply()

        val lines = readOwnLog()
        try {
            logFile(ctx).writeText(buildString {
                appendLine("RetroTV stream log")
                appendLine("Game: $what")
                appendLine("Started: ${java.util.Date(startedAt)}")
                appendLine("TV: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}")
                appendLine("----")
                lines.forEach { appendLine(it) }
            })
        } catch (_: Exception) {
        }

        // A Java crash ("FATAL EXCEPTION") or a native crash ("Fatal signal") in the streaming engine.
        val crashAt = lines.indexOfLast { "FATAL EXCEPTION" in it || "Fatal signal" in it }
        if (crashAt >= 0) {
            val detail = lines.drop(crashAt).take(6)
                .joinToString("\n") { it.substringAfter("): ").trim() }
                .take(600)
            return Problem("Streaming crashed", detail)
        }
        // Came straight back without a crash: look for the streaming engine's own error.
        // (Only when it was quick - after a normal play session, ending the stream is expected.)
        if (System.currentTimeMillis() - startedAt > 25_000) return null
        val failAt = lines.indexOfLast {
            "Connection terminated" in it || "Starting connection failed" in it ||
                "stageFailed" in it || "Failed to start" in it || "No H264 decoder" in it
        }
        if (failAt >= 0) {
            return Problem("The stream couldn't start", lines[failAt].substringAfter("): ").trim().take(400))
        }
        return null
    }

    private fun readOwnLog(): List<String> = try {
        val p = ProcessBuilder("logcat", "-d", "-v", "time", "-t", "2500").redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readLines()
        p.waitFor()
        out
    } catch (_: Exception) {
        emptyList()
    }

    /** What /log on the web page shows: the saved stream log, or the live log if there isn't one. */
    fun forWeb(ctx: Context): String {
        val f = logFile(ctx)
        return if (f.exists()) f.readText() else "No stream log yet.\n\n" + readOwnLog().joinToString("\n")
    }
}
