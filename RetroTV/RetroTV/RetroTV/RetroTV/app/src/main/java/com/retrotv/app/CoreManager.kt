package com.retrotv.app

import android.content.Context
import android.os.Build
import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Emulator cores are downloaded from the official libretro build server the first
 * time each console is used, so the APK stays small.
 */
object CoreManager {

    private const val BUILDBOT = "https://buildbot.libretro.com/nightly/android/latest"
    private const val ASSETS = "https://buildbot.libretro.com/assets/system"

    /** Folder each core expects its extra files in, inside the system folder. */
    private val assetFolders = mapOf("PPSSPP" to "PPSSPP/", "Dolphin" to "dolphin-emu/")

    /** Must match the app process (many TVs run 32-bit apps even on 64-bit chips). */
    fun abi(): String {
        val abis = if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS
        return abis.firstOrNull() ?: Build.SUPPORTED_ABIS.first()
    }

    fun systemDir(ctx: Context): File = File(ctx.filesDir, "system").apply { mkdirs() }
    fun savesDir(ctx: Context, sys: GameSystem): File = File(ctx.filesDir, "saves/${sys.id}").apply { mkdirs() }
    fun statesDir(ctx: Context, sys: GameSystem): File = File(ctx.filesDir, "states/${sys.id}").apply { mkdirs() }

    private fun coreFile(ctx: Context, core: String): File =
        File(ctx.filesDir, "cores/${abi()}/${core}_libretro_android.so")

    fun isInstalled(ctx: Context, core: String): Boolean = coreFile(ctx, core).let { it.exists() && it.length() > 0 }

    suspend fun ensureCore(ctx: Context, sys: GameSystem, progress: (String) -> Unit): File =
        withContext(Dispatchers.IO) {
            val target = coreFile(ctx, sys.core)
            if (!(target.exists() && target.length() > 0)) {
                target.parentFile?.mkdirs()
                val zip = File(ctx.cacheDir, "${sys.core}.zip")
                download("$BUILDBOT/${abi()}/${sys.core}_libretro_android.so.zip", zip) { pct ->
                    progress("Downloading ${sys.name} emulator... $pct%")
                }
                progress("Installing ${sys.name} emulator...")
                val tmp = File(target.path + ".part")
                var ok = false
                ZipInputStream(zip.inputStream().buffered()).use { zis ->
                    var e = zis.nextEntry
                    while (e != null) {
                        if (!e.isDirectory && e.name.endsWith(".so")) {
                            FileOutputStream(tmp).use { zis.copyTo(it) }
                            ok = true
                            break
                        }
                        e = zis.nextEntry
                    }
                }
                zip.delete()
                if (!ok) throw IOException("Core download was empty")
                if (!tmp.renameTo(target)) throw IOException("Couldn't install core")
                target.setReadOnly()
            }
            sys.systemAssets?.let { ensureAssets(ctx, it, sys.name, progress) }
            target
        }

    private fun ensureAssets(ctx: Context, pack: String, label: String, progress: (String) -> Unit) {
        val sysDir = systemDir(ctx)
        val prefix = assetFolders[pack] ?: ""
        val marker = File(sysDir, ".retrotv-$pack-ok")
        if (marker.exists()) return
        val zip = File(ctx.cacheDir, "$pack-assets.zip")
        download("$ASSETS/$pack.zip", zip) { pct -> progress("Downloading $label files... $pct%") }
        progress("Installing $label files...")
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                var name = e.name.replace('\\', '/')
                if (prefix.isNotEmpty() && !name.startsWith(prefix, ignoreCase = true)) name = prefix + name
                val out = File(sysDir, name)
                // Never let a zip entry escape the system folder.
                if (out.canonicalPath.startsWith(sysDir.canonicalPath)) {
                    if (e.isDirectory) out.mkdirs() else {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { zis.copyTo(it) }
                    }
                }
                e = zis.nextEntry
            }
        }
        zip.delete()
        marker.writeText("ok")
    }

    private fun open(link: String): HttpURLConnection =
        (URL(link).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            instanceFollowRedirects = false
        }

    private fun download(url: String, dest: File, onPct: (Int) -> Unit) {
        var link = url
        var redirects = 0
        var conn = open(link)
        while (conn.responseCode in 300..399 && redirects < 5) {
            val next = conn.getHeaderField("Location") ?: break
            conn.disconnect()
            link = URL(URL(link), next).toString()
            conn = open(link)
            redirects++
        }
        val code = conn.responseCode
        if (code != 200) throw IOException("Download failed (HTTP $code). Is the TV online?")
        val total = conn.contentLength.toLong()
        conn.inputStream.use { input ->
            FileOutputStream(dest).use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                var lastPct = -1
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0) {
                        val pct = (done * 100 / total).toInt()
                        if (pct != lastPct) {
                            lastPct = pct
                            onPct(pct)
                        }
                    }
                }
            }
        }
        conn.disconnect()
    }
}
