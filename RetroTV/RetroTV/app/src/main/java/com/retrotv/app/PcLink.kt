package com.retrotv.app

import android.content.Context
import android.content.Intent
import android.util.Base64
import com.limelight.Game
import com.limelight.binding.PlatformBinding
import com.limelight.computers.IdentityManager
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.NvHTTP
import com.limelight.nvstream.http.PairingManager
import com.limelight.nvstream.wol.WakeOnLanSender
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/** A game or program on the PC that can be streamed (one Sunshine "app"). */
data class PcApp(val id: Int, val name: String, val hdr: Boolean)

/** The PC RetroTV is linked to. */
data class SavedPc(
    val uuid: String,
    val name: String,
    val address: String,
    val httpPort: Int,
    val httpsPort: Int,
    val mac: String?,
    val certB64: String,
    val apps: List<PcApp>,
) {
    val cert: X509Certificate
        get() = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(Base64.decode(certB64, Base64.NO_WRAP))) as X509Certificate

    fun toJson(): JSONObject = JSONObject()
        .put("uuid", uuid).put("name", name).put("address", address)
        .put("httpPort", httpPort).put("httpsPort", httpsPort).put("mac", mac ?: "")
        .put("cert", certB64)
        .put("apps", JSONArray().apply {
            apps.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("hdr", it.hdr)) }
        })

    companion object {
        fun fromJson(o: JSONObject): SavedPc {
            val arr = o.optJSONArray("apps") ?: JSONArray()
            return SavedPc(
                uuid = o.getString("uuid"),
                name = o.getString("name"),
                address = o.getString("address"),
                httpPort = o.optInt("httpPort", NvHTTP.DEFAULT_HTTP_PORT),
                httpsPort = o.optInt("httpsPort", 0),
                mac = o.optString("mac").ifEmpty { null },
                certB64 = o.getString("cert"),
                apps = (0 until arr.length()).map {
                    val a = arr.getJSONObject(it)
                    PcApp(a.getInt("id"), a.getString("name"), a.optBoolean("hdr"))
                },
            )
        }
    }
}

object PcStore {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("pc", Context.MODE_PRIVATE)

    fun get(ctx: Context): SavedPc? = try {
        prefs(ctx).getString("pc", null)?.let { SavedPc.fromJson(JSONObject(it)) }
    } catch (_: Exception) {
        null
    }

    fun save(ctx: Context, pc: SavedPc) {
        prefs(ctx).edit().putString("pc", pc.toJson().toString()).apply()
    }

    fun forget(ctx: Context) {
        prefs(ctx).edit().remove("pc").apply()
    }
}

class PairException(message: String) : IOException(message)

/**
 * Talks to the PC (Sunshine) using Moonlight's streaming engine.
 * Every function here does network work: call them off the main thread.
 */
object PcLink {

    const val DEFAULT_PORT = NvHTTP.DEFAULT_HTTP_PORT

    data class Probe(val name: String, val uuid: String, val paired: Boolean)

    private fun uniqueId(ctx: Context) = IdentityManager(ctx.applicationContext).uniqueId
    private fun crypto(ctx: Context) = PlatformBinding.getCryptoProvider(ctx.applicationContext)

    private fun http(ctx: Context, address: String, port: Int, httpsPort: Int = 0, cert: X509Certificate? = null) =
        NvHTTP(ComputerDetails.AddressTuple(address, port), httpsPort, uniqueId(ctx), cert, crypto(ctx))

    /** Checks there's a streaming-ready PC at this address. */
    fun probe(ctx: Context, address: String, port: Int = DEFAULT_PORT): Probe {
        val http = http(ctx, address, port)
        val info = http.getServerInfo(true)
        val d = http.getComputerDetails(info)
        return Probe(d.name, d.uuid, d.pairState == PairingManager.PairState.PAIRED)
    }

    /**
     * Links the TV to the PC. Blocks until the code is typed on the PC (or it gives up),
     * then saves the PC along with its game list.
     */
    fun pair(ctx: Context, address: String, port: Int, pin: String): SavedPc {
        val http = http(ctx, address, port)
        val info = http.getServerInfo(true)
        val d = http.getComputerDetails(info)
        val pm = http.pairingManager
        when (pm.pair(info, pin)) {
            PairingManager.PairState.PAIRED -> Unit
            PairingManager.PairState.PIN_WRONG -> throw PairException("That code didn't match.")
            PairingManager.PairState.ALREADY_IN_PROGRESS -> throw PairException("The PC is already linking with another device.")
            else -> throw PairException("The PC didn't accept the link. Make sure the code was typed on the PC.")
        }
        val cert = pm.pairedCert ?: throw PairException("The PC didn't send its security certificate.")
        val base = SavedPc(
            uuid = d.uuid,
            name = d.name,
            address = address,
            httpPort = port,
            httpsPort = d.httpsPort,
            mac = d.macAddress?.takeIf { it != "00:00:00:00:00:00" },
            certB64 = Base64.encodeToString(cert.encoded, Base64.NO_WRAP),
            apps = emptyList(),
        )
        val withApps = try {
            refresh(ctx, base)
        } catch (_: Exception) {
            base
        }
        PcStore.save(ctx, withApps)
        return withApps
    }

    /** Re-reads the PC's game list. Throws if the PC is off or no longer linked. */
    fun refresh(ctx: Context, pc: SavedPc): SavedPc {
        val http = http(ctx, pc.address, pc.httpPort, pc.httpsPort, pc.cert)
        val info = http.getServerInfo(true)
        val d = http.getComputerDetails(info)
        if (d.uuid != pc.uuid) throw IOException("A different PC is using this address now.")
        if (d.pairState != PairingManager.PairState.PAIRED) throw PairException("This TV isn't linked to the PC any more.")
        val apps = http.appList.map { PcApp(it.appId, it.appName, it.isHdrSupported) }
        return pc.copy(name = d.name, httpsPort = d.httpsPort, apps = apps,
            mac = d.macAddress?.takeIf { it != "00:00:00:00:00:00" } ?: pc.mac)
    }

    /** Sends a Wake-on-LAN packet (works if the PC has Wake-on-LAN turned on). */
    fun wake(pc: SavedPc) {
        val mac = pc.mac ?: throw IOException("RetroTV doesn't know this PC's network address for waking it.")
        WakeOnLanSender.sendWolPacket(ComputerDetails().apply {
            macAddress = mac
            localAddress = ComputerDetails.AddressTuple(pc.address, pc.httpPort)
        })
    }

    /** Opens the streaming screen for one PC game. */
    fun streamIntent(ctx: Context, pc: SavedPc, app: PcApp): Intent {
        applyStreamDefaults(ctx)
        return Intent(ctx, Game::class.java)
            .putExtra(Game.EXTRA_HOST, pc.address)
            .putExtra(Game.EXTRA_PORT, pc.httpPort)
            .putExtra(Game.EXTRA_HTTPS_PORT, pc.httpsPort)
            .putExtra(Game.EXTRA_APP_NAME, app.name)
            .putExtra(Game.EXTRA_APP_ID, app.id)
            .putExtra(Game.EXTRA_APP_HDR, app.hdr)
            .putExtra(Game.EXTRA_UNIQUEID, uniqueId(ctx))
            .putExtra(Game.EXTRA_PC_UUID, pc.uuid)
            .putExtra(Game.EXTRA_PC_NAME, pc.name)
            .putExtra(Game.EXTRA_SERVER_CERT, Base64.decode(pc.certB64, Base64.NO_WRAP))
    }

    /** TV-friendly first-time stream settings: 1080p at 60 fps. Changeable under "Stream quality". */
    private fun applyStreamDefaults(ctx: Context) {
        val prefs = ctx.getSharedPreferences(ctx.packageName + "_preferences", Context.MODE_PRIVATE)
        if (!prefs.contains("list_resolution")) {
            prefs.edit()
                .putString("list_resolution", "1920x1080")
                .putString("list_fps", "60")
                .apply()
        }
    }
}
