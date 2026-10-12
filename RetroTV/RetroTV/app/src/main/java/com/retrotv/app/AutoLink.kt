package com.retrotv.app

import android.content.Context
import android.util.Base64
import com.limelight.nvstream.http.PairingManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

/** The Sunshine web login for a PC, so the TV can approve its own linking code. */
data class SunshineLogin(val address: String, val user: String, val pass: String)

object SunshineLogins {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("sunshine", Context.MODE_PRIVATE)

    fun get(ctx: Context, address: String): SunshineLogin? {
        val raw = prefs(ctx).getString(address, null) ?: return null
        return try {
            val o = JSONObject(raw)
            SunshineLogin(address, o.getString("user"), o.getString("pass"))
        } catch (_: Exception) {
            null
        }
    }

    fun save(ctx: Context, login: SunshineLogin) {
        prefs(ctx).edit()
            .putString(login.address, JSONObject().put("user", login.user).put("pass", login.pass).toString())
            .apply()
    }
}

/**
 * Talks to Sunshine's web API on the PC (port 47990) to approve a linking code automatically.
 * Sunshine uses a self-signed certificate, so it is trusted for this one connection only.
 */
object SunshineApi {
    private const val PORT = 47990

    private val trustAll: SSLContext by lazy {
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }), SecureRandom())
        }
    }

    private class Reply(val code: Int, val body: String)

    private fun call(login: SunshineLogin, method: String, path: String, json: JSONObject? = null, timeoutMs: Int = 8000): Reply {
        val conn = URL("https://${login.address}:$PORT$path").openConnection() as HttpsURLConnection
        conn.sslSocketFactory = trustAll.socketFactory
        conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
        conn.requestMethod = method
        conn.connectTimeout = 5000
        conn.readTimeout = timeoutMs
        val auth = Base64.encodeToString("${login.user}:${login.pass}".toByteArray(), Base64.NO_WRAP)
        conn.setRequestProperty("Authorization", "Basic $auth")
        if (json != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(json.toString().toByteArray()) }
        }
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            Reply(code, stream?.bufferedReader()?.readText().orEmpty())
        } finally {
            conn.disconnect()
        }
    }

    /** True if the login works. */
    fun check(login: SunshineLogin): Boolean = try {
        call(login, "GET", "/api/apps").code == HttpURLConnection.HTTP_OK
    } catch (_: Exception) {
        false
    }

    /**
     * Enters [pin] on the PC for the TV's waiting link request. Works with both newer Sunshine
     * (which lists waiting requests) and older versions. Keeps trying for [seconds].
     */
    fun approve(login: SunshineLogin, pin: String, seconds: Int = 25): Boolean {
        val deadline = System.currentTimeMillis() + seconds * 1000L
        while (System.currentTimeMillis() < deadline) {
            try {
                val pending = call(login, "GET", "/api/pin")
                val body = when {
                    pending.code == HttpURLConnection.HTTP_OK && pending.body.contains("\"pairings\"") -> {
                        val list = JSONObject(pending.body).optJSONArray("pairings") ?: JSONArray()
                        if (list.length() == 0) null
                        else JSONObject().put("pairing_id", list.getJSONObject(list.length() - 1).getString("id"))
                            .put("pin", pin).put("name", "RetroTV")
                    }
                    pending.code == 401 -> return false // wrong login
                    else -> JSONObject().put("pin", pin).put("name", "RetroTV") // older Sunshine
                }
                if (body != null) {
                    val r = call(login, "POST", "/api/pin", body, timeoutMs = 30000)
                    if (r.code == 200 && r.body.replace(" ", "").contains("\"status\":true")) return true
                }
            } catch (_: Exception) {
            }
            Thread.sleep(1500)
        }
        return false
    }
}

/**
 * Links the TV to a PC with no code to type: the TV makes up the code, starts linking,
 * and approves the code itself through Sunshine's web API.
 */
object AutoLink {
    data class State(val working: Boolean, val linked: Boolean, val message: String)

    private val _state = MutableStateFlow(State(false, false, ""))
    val state: StateFlow<State> = _state

    /** Runs in the background. [onDone] gets the linked PC, or null with the reason in [state]. */
    fun start(ctx: Context, address: String, port: Int = PcLink.DEFAULT_PORT, onDone: ((SavedPc?) -> Unit)? = null) {
        val app = ctx.applicationContext
        val login = SunshineLogins.get(app, address)
        if (login == null) {
            _state.value = State(false, false, "No Sunshine login saved for $address.")
            onDone?.invoke(null)
            return
        }
        if (_state.value.working) return
        _state.value = State(true, false, "Linking to your PC...")
        thread(name = "auto-link") {
            val pc = try {
                link(app, address, port, login)
            } catch (e: Exception) {
                _state.value = State(false, false, e.message ?: "Linking failed.")
                null
            }
            if (pc != null) _state.value = State(false, true, "Linked to ${pc.name}!")
            onDone?.invoke(pc)
        }
    }

    /** Blocking version: pair while approving the code on the PC at the same time. */
    fun link(ctx: Context, address: String, port: Int, login: SunshineLogin): SavedPc {
        val pin = PairingManager.generatePinString()
        val result = java.util.concurrent.atomic.AtomicReference<Any?>(null)
        // Linking waits for the code to be approved on the PC, so it runs on its own thread.
        val pairer = thread(name = "auto-pair") {
            result.set(try {
                PcLink.pair(ctx, address, port, pin)
            } catch (e: Exception) {
                e
            })
        }
        val approved = SunshineApi.approve(login, pin)
        pairer.join(if (approved) 30_000 else 3_000)
        return when (val r = result.get()) {
            is SavedPc -> r
            is Exception -> throw r
            else -> throw PairException(
                if (approved) "The PC accepted the code but didn't finish linking. Try again."
                else "Couldn't approve the link on the PC automatically. Check the PC is on and Sunshine is running.")
        }
    }

    fun reset() {
        if (!_state.value.working) _state.value = State(false, false, "")
    }
}
