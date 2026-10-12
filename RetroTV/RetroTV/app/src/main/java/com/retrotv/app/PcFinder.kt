package com.retrotv.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address

/** A PC on the home network that announced itself as ready for game streaming. */
data class FoundPc(val name: String, val address: String, val port: Int)

/**
 * Listens for PCs running Sunshine (they announce "_nvstream._tcp" on the network).
 * Results are delivered on the main thread.
 */
class PcFinder(context: Context, private val onFound: (FoundPc) -> Unit) {

    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var running = false

    private val discovery = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {}
        override fun onDiscoveryStopped(serviceType: String) {}
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            running = false
        }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        override fun onServiceLost(service: NsdServiceInfo) {}
        override fun onServiceFound(service: NsdServiceInfo) {
            main.post {
                queue.addLast(service)
                resolveNext()
            }
        }
    }

    fun start() {
        if (running) return
        running = true
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
        } catch (_: Exception) {
            running = false
        }
    }

    fun stop() {
        if (!running) return
        running = false
        queue.clear()
        try {
            nsd.stopServiceDiscovery(discovery)
        } catch (_: Exception) {
        }
    }

    /** Older Android versions can only resolve one service at a time, so go through them in turn. */
    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (resolving || !running) return
        val next = queue.removeFirstOrNull() ?: return
        resolving = true
        try {
            nsd.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    main.post {
                        resolving = false
                        resolveNext()
                    }
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val host = serviceInfo.host
                    main.post {
                        resolving = false
                        if (running && host is Inet4Address) {
                            onFound(FoundPc(serviceInfo.serviceName, host.hostAddress ?: "", serviceInfo.port))
                        }
                        resolveNext()
                    }
                }
            })
        } catch (_: Exception) {
            resolving = false
        }
    }

    companion object {
        private const val SERVICE_TYPE = "_nvstream._tcp"
    }
}
