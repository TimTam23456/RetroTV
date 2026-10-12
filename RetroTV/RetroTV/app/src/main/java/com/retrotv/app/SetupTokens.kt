package com.retrotv.app

import android.content.Context
import java.security.SecureRandom

/** One-time passes baked into each downloaded copy of RetroTV PC Setup. */
object SetupTokens {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("setup_tokens", Context.MODE_PRIVATE)

    fun create(ctx: Context): String {
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val token = bytes.joinToString("") { "%02x".format(it) }
        val keep = (prefs(ctx).getString("tokens", "") ?: "").split(",").filter { it.isNotEmpty() }.takeLast(9) + token
        prefs(ctx).edit().putString("tokens", keep.joinToString(",")).apply()
        return token
    }

    fun valid(ctx: Context, token: String): Boolean =
        token.length == 32 && (prefs(ctx).getString("tokens", "") ?: "").split(",").contains(token)
}
