package com.retrotv.app

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.concurrent.thread

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Sfx.init(this)
        Music.init(this)
        // While a game or a PC stream is on screen, ask the TV for its low-latency game mode (ALLM).
        // On Bravias this switches picture processing off automatically, which removes most of the lag.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, b: Bundle?) {
                val playing = a is GameActivity || a.javaClass.name == "com.limelight.Game"
                if (playing && Build.VERSION.SDK_INT >= 30) a.window.setPreferMinimalPostProcessing(true)
            }
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
        // Keep the upload page running while RetroTV is open on screen (any screen), stop it otherwise.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = UploadHub.start(this@App)
            override fun onStop(owner: LifecycleOwner) = UploadHub.stop()
        })
    }
}

/** Owns the Wi-Fi upload page so it stays up across RetroTV's screens. */
object UploadHub {
    private var server: UploadServer? = null
    private val _address = MutableStateFlow<String?>(null)
    private val _status = MutableStateFlow("Starting upload page...")

    /** e.g. "http://192.168.1.20:8080", or null when not reachable. */
    val address: StateFlow<String?> = _address
    val status: StateFlow<String> = _status

    @Volatile
    var onUploaded: ((String) -> Unit)? = null

    private var wanted = false

    @Synchronized
    fun start(ctx: Context) {
        wanted = true
        if (server != null) return
        val app = ctx.applicationContext
        thread(name = "upload-server") {
            synchronized(this) {
                if (server != null || !wanted) return@thread
                for (port in 8080..8090) {
                    try {
                        val s = UploadServer(app, port) { name -> onUploaded?.invoke(name) }
                        s.start(60_000, false)
                        server = s
                        break
                    } catch (_: Exception) {
                    }
                }
                val s = server
                val ip = UploadServer.localIp()
                _address.value = if (s != null && ip != null) "http://$ip:${s.listeningPort}" else null
                _status.value = when {
                    s == null -> "Couldn't start the upload page."
                    ip == null -> "Connect the TV to Wi-Fi to send games from your phone or PC."
                    else -> "On your phone or PC (same Wi-Fi), open:\n${_address.value}"
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        wanted = false
        server?.stop()
        server = null
        _address.value = null
    }
}
