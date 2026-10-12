package com.retrotv.app

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Tiny web server so you can send games to the TV from a phone or PC browser
 * on the same Wi-Fi. Files are streamed straight to disk, so big PSP/PS1 games are fine.
 */
class UploadServer(
    private val ctx: Context,
    port: Int,
    private val onUploaded: (String) -> Unit,
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response = try {
        when {
            session.method == Method.GET && session.uri == "/" ->
                newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", page())
            session.method == Method.POST && session.uri == "/upload" -> upload(session)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
    } catch (e: Exception) {
        newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}")
    }

    private fun upload(session: IHTTPSession): Response {
        val params = session.parameters
        val rawName = params["name"]?.firstOrNull().orEmpty()
        val name = rawName.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9 ._()\\[\\]&+,'!-]"), "_")
            .trim().trimStart('.')
        if (name.isEmpty()) return bad("Missing file name")
        val length = session.headers["content-length"]?.toLongOrNull() ?: return bad("Missing length")
        val target = params["target"]?.firstOrNull().orEmpty()

        val dir = when {
            target == "bios" && name.lowercase().startsWith("dc_") ->
                File(CoreManager.systemDir(ctx), "dc")
            target == "bios" -> CoreManager.systemDir(ctx)
            Systems.byId(target) != null -> File(Library.gamesDir(ctx), Systems.byId(target)!!.id)
            else -> Library.gamesDir(ctx)
        }
        dir.mkdirs()
        val dest = File(dir, name)
        val part = File(dir, "$name.part")

        val input = session.inputStream
        FileOutputStream(part).use { out ->
            val buf = ByteArray(256 * 1024)
            var left = length
            while (left > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) break
                out.write(buf, 0, n)
                left -= n
            }
            if (left > 0) {
                part.delete()
                return bad("Upload was cut off")
            }
        }
        dest.delete()
        if (!part.renameTo(dest)) return bad("Couldn't save file")
        onUploaded(name)
        return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "ok")
    }

    private fun bad(msg: String) = newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, msg)

    private fun page(): String {
        val options = buildString {
            append("<option value=\"auto\">Detect automatically</option>")
            Systems.all.forEach { append("<option value=\"${it.id}\">${it.name}</option>") }
            append("<option value=\"bios\">BIOS / system file</option>")
        }
        return """
<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>RetroTV - Add games</title>
<style>
 :root{color-scheme:dark}
 body{margin:0;font-family:system-ui,sans-serif;background:#0e0f13;color:#eceaf4}
 main{max-width:560px;margin:0 auto;padding:28px 16px}
 h1{margin:0 0 4px;font-size:28px} p{color:#a9a6b8;line-height:1.5}
 label{display:block;margin:20px 0 6px;font-weight:600}
 select,input{width:100%;box-sizing:border-box;padding:12px;border-radius:10px;border:1px solid #2c2b38;background:#181822;color:inherit;font-size:16px}
 button{margin-top:22px;width:100%;padding:14px;border:0;border-radius:10px;background:#7c5cff;color:#fff;font-size:17px;font-weight:700}
 button:disabled{opacity:.5}
 .row{margin-top:14px;padding:12px;border-radius:10px;background:#181822}
 .bar{height:6px;border-radius:3px;background:#2c2b38;margin-top:8px;overflow:hidden}
 .bar>i{display:block;height:100%;width:0;background:#7c5cff}
 .ok{color:#6ee7a8}.err{color:#ff7a8a}
</style></head><body><main>
<h1>RetroTV</h1>
<p>Send game files to your TV. Use games you own. Zipped cartridge games are fine; unzip disc games (PS1, PSP, Dreamcast) first. Multi-file discs (.cue + .bin) should go up together.</p>
<label for="sys">Console</label>
<select id="sys">$options</select>
<label for="f">Files</label>
<input id="f" type="file" multiple>
<button id="go">Send to TV</button>
<div id="list"></div>
</main><script>
const go=document.getElementById('go'),list=document.getElementById('list');
go.onclick=async()=>{
 const files=[...document.getElementById('f').files];
 if(!files.length)return;
 go.disabled=true;
 const target=document.getElementById('sys').value;
 for(const file of files){
  const row=document.createElement('div');row.className='row';
  row.innerHTML='<div></div><div class="bar"><i></i></div>';
  row.firstChild.textContent=file.name;list.prepend(row);
  const bar=row.querySelector('i');
  await new Promise(res=>{
   const x=new XMLHttpRequest();
   x.open('POST','/upload?target='+encodeURIComponent(target)+'&name='+encodeURIComponent(file.name));
   x.upload.onprogress=e=>{if(e.lengthComputable)bar.style.width=(e.loaded/e.total*100)+'%'};
   x.onload=()=>{const ok=x.status==200;row.firstChild.className=ok?'ok':'err';
     row.firstChild.textContent=(ok?'Sent: ':'Failed: ')+file.name+(ok?'':' ('+x.responseText+')');res()};
   x.onerror=()=>{row.firstChild.className='err';row.firstChild.textContent='Failed: '+file.name+' (connection lost)';res()};
   x.send(file);
  });
 }
 go.disabled=false;
};
</script></body></html>
""".trimIndent()
    }

    companion object {
        fun localIp(): String? = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("eth")) 0 else 1 }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                ?.hostAddress
        } catch (_: Exception) {
            null
        }
    }
}
