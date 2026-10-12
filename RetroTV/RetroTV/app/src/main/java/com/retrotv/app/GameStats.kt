package com.retrotv.app

import android.content.Context
import android.text.format.DateUtils

/** Remembers what you played recently and what you've starred, for the home screen rows. */
object GameStats {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("stats", Context.MODE_PRIVATE)

    fun keyFor(game: Game) = "g:" + game.file.absolutePath
    fun keyFor(pc: SavedPc, app: PcApp) = "pc:" + pc.uuid + ":" + app.id

    fun markPlayed(ctx: Context, key: String) {
        prefs(ctx).edit().putLong("played:$key", System.currentTimeMillis()).apply()
    }

    fun lastPlayed(ctx: Context, key: String): Long = prefs(ctx).getLong("played:$key", 0)

    fun isFavourite(ctx: Context, key: String) = prefs(ctx).getBoolean("fav:$key", false)

    fun setFavourite(ctx: Context, key: String, fav: Boolean) {
        prefs(ctx).edit().apply { if (fav) putBoolean("fav:$key", true) else remove("fav:$key") }.apply()
    }

    fun forget(ctx: Context, key: String) {
        prefs(ctx).edit().remove("played:$key").remove("fav:$key").apply()
    }

    /** "Played 2 hours ago", or null if never. */
    fun playedText(ctx: Context, key: String): String? {
        val t = lastPlayed(ctx, key)
        if (t == 0L) return null
        return "Played " + DateUtils.getRelativeTimeSpanString(t, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            .toString().replaceFirstChar { it.lowercase() }
    }
}
