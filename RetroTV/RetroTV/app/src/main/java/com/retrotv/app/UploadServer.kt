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
            session.method == Method.GET && session.uri == "/pc" ->
                newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", pcPage())
            session.method == Method.GET && session.uri == "/pc/$SETUP_FILE" -> setupFile(session)
            session.method == Method.POST && session.uri == "/pc/link" -> linkFromSetup(session)
            session.method == Method.GET && session.uri == "/pc/link-status" -> json(linkStatus())
            session.method == Method.POST && session.uri == "/pc/login" -> loginFromPage(session)
            session.method == Method.GET && session.uri == "/library" ->
                newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", libraryPage(null))
            session.method == Method.GET && session.uri == "/library/$SERVER_FILE" -> gameServerFile(session)
            session.method == Method.POST && session.uri == "/library/add" -> libraryAdd(session)
            session.method == Method.POST && session.uri == "/library/remove" -> libraryRemove(session)
            session.method == Method.GET && session.uri == "/log" ->
                newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", StreamLog.forWeb(ctx))
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

    private fun json(o: org.json.JSONObject) =
        newFixedLengthResponse(Response.Status.OK, "application/json", o.toString())

    /** The PC's address as the TV sees it. */
    private fun remoteIp(session: IHTTPSession) = session.remoteIpAddress.removePrefix("::ffff:")

    /** Called by RetroTV PC Setup when it finishes: saves the Sunshine login and links in the background. */
    private fun linkFromSetup(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val body = org.json.JSONObject(files["postData"] ?: "{}")
        if (!SetupTokens.valid(ctx, body.optString("token"))) return bad("This setup file is out of date. Download it again from the TV.")
        val login = SunshineLogin(remoteIp(session), body.optString("user"), body.optString("pass"))
        if (login.user.isEmpty() || login.pass.isEmpty()) return bad("Missing login")
        SunshineLogins.save(ctx, login)
        AutoLink.start(ctx, login.address)
        return json(org.json.JSONObject().put("ok", true))
    }

    private fun linkStatus(): org.json.JSONObject {
        val st = AutoLink.state.value
        val pc = PcStore.get(ctx)
        return org.json.JSONObject()
            .put("state", when {
                st.working -> "working"
                st.linked -> "linked"
                st.message.isNotEmpty() -> "failed"
                pc != null -> "linked"
                else -> "idle"
            })
            .put("message", st.message)
            .put("pc", pc?.name ?: "")
    }

    // ---------------------------------------------------------------- online library sources

    private fun libraryAdd(session: IHTTPSession): Response {
        session.parseBody(HashMap())
        val url = session.parameters["url"]?.firstOrNull().orEmpty().trim()
        val name = session.parameters["name"]?.firstOrNull().orEmpty().trim()
        val msg = if (url.isEmpty()) "Enter an address." else {
            val s = Sources.add(ctx, url, name)
            "Added ${s.name}. It's on the TV under Browse now."
        }
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", libraryPage(msg))
    }

    /** RetroTV Game Server for the PC, personalised with this TV's address so it adds itself under Browse. */
    private fun gameServerFile(session: IHTTPSession): Response {
        val host = session.headers["host"] ?: ""
        val text = ctx.assets.open(SERVER_FILE).use { it.readBytes() }.toString(Charsets.ISO_8859_1)
            .replace("__RETROTV_TV__", if (host.isNotEmpty()) "http://$host" else "")
        val bytes = text.toByteArray(Charsets.ISO_8859_1)
        return newFixedLengthResponse(Response.Status.OK, "application/octet-stream",
            java.io.ByteArrayInputStream(bytes), bytes.size.toLong()).apply {
            addHeader("Content-Disposition", "attachment; filename=\"$SERVER_FILE\"")
        }
    }

    private fun libraryRemove(session: IHTTPSession): Response {
        session.parseBody(HashMap())
        val url = session.parameters["url"]?.firstOrNull().orEmpty()
        if (url.isNotEmpty()) Sources.remove(ctx, url)
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", libraryPage("Removed."))
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun libraryPage(msg: String?): String {
        val rows = Sources.load(ctx).joinToString("") { s ->
            "<li><div><b>${esc(s.name)}</b><span>${esc(s.url)}</span></div>" +
                "<form method=\"post\" action=\"/library/remove\"><input type=\"hidden\" name=\"url\" value=\"${esc(s.url)}\">" +
                "<button class=\"rm\">Remove</button></form></li>"
        }.ifEmpty { "<li class=\"none\">No sources yet.</li>" }
        return """
<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>RetroTV - Online library</title>
<style>
 :root{color-scheme:dark}
 body{margin:0;font-family:system-ui,sans-serif;background:#0e0f13;color:#eceaf4}
 main{max-width:600px;margin:0 auto;padding:28px 16px 48px}
 h1{margin:0 0 6px;font-size:28px} p{color:#a9a6b8;line-height:1.5} a{color:#b9a8ff}
 input{display:block;width:100%;box-sizing:border-box;margin:8px 0;padding:12px;border-radius:10px;border:1px solid #2c2b38;background:#181822;color:inherit;font-size:16px}
 button{padding:12px 16px;border:0;border-radius:10px;background:#7c5cff;color:#fff;font-size:16px;font-weight:700}
 .add{width:100%;margin-top:4px}
 ul{list-style:none;padding:0} li{display:flex;align-items:center;gap:12px;background:#181822;border-radius:12px;padding:12px 14px;margin:8px 0}
 li div{flex:1;min-width:0} li span{display:block;color:#a9a6b8;font-size:13px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
 .rm{background:#2c2646;font-size:14px;padding:8px 12px} .none{color:#a9a6b8} .msg{color:#6ee7a8}
 .pc{background:#181822;border-radius:14px;padding:16px;margin:18px 0}
 .dl{display:block;text-align:center;padding:14px;border-radius:10px;background:#7c5cff;color:#fff;text-decoration:none;font-weight:700}
</style></head><body><main>
<h1>Online library</h1>
<p>Add a web page or folder that links to game files - your own NAS or home server, a folder of your backups, or a homebrew site. The TV finds every game on it (including sub-folders) under <b>Browse</b>.</p>
${if (msg != null) "<p class=\"msg\">${esc(msg)}</p>" else ""}
<div class="pc">
<h2 style="font-size:18px;margin:0 0 4px">Share a games folder from this PC</h2>
<p style="margin:0 0 12px">One file turns a folder on your Windows PC into a library for the TV, and adds it here by itself. Drop games into its console folders and they show up under Browse.</p>
<a class="dl" href="/library/$SERVER_FILE" download>Download RetroTV Game Server</a>
<p style="font-size:13px;margin:10px 0 0">Double-click it (if Windows warns: <i>More info</i> &rarr; <i>Run anyway</i>). Keep its window open while you play, or let it start with Windows.</p>
</div>
<h2 style="font-size:18px;margin-top:28px">Or add any address</h2>
<form method="post" action="/library/add">
<input name="url" placeholder="Address, e.g. 192.168.1.50:8000/games" required autocapitalize="off" autocorrect="off" inputmode="url">
<input name="name" placeholder="Name (optional), e.g. My NAS">
<button class="add">Add to TV</button>
</form>
<h2 style="font-size:18px;margin-top:28px">Your sources</h2>
<ul>$rows</ul>
<p style="font-size:13px">Only add places you're allowed to download from, and use games you own. Pages that build their file list with JavaScript can't be read; plain folder listings work best.</p>
<p><a href="/">&larr; Back to sending games</a></p>
</main></body></html>
""".trimIndent()
    }

    /** "Installed Sunshine yourself?" form on the /pc page. */
    private fun loginFromPage(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val user = session.parameters["user"]?.firstOrNull().orEmpty().trim()
        val pass = session.parameters["pass"]?.firstOrNull().orEmpty()
        val login = SunshineLogin(remoteIp(session), user, pass)
        val msg = when {
            user.isEmpty() || pass.isEmpty() -> "Enter both the username and password."
            !SunshineApi.check(login) -> "That login didn't work, or Sunshine isn't reachable on this PC (${login.address})."
            else -> {
                SunshineLogins.save(ctx, login)
                AutoLink.start(ctx, login.address)
                null
            }
        }
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", pcPage(msg))
    }

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
<p style="margin-top:28px">Have games on a home server or NAS? <a href="/library" style="color:#b9a8ff">Add it to the online library</a>.</p>
<p>Want PS2, GameCube, PS3 or Switch on the TV? <a href="/pc" style="color:#b9a8ff">Get RetroTV PC Setup</a>.</p>
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

    /**
     * Serves RetroTV PC Setup straight from the TV. The copy is personalised with this TV's address
     * and a one-time pass, so when setup finishes it can hand the TV the Sunshine login and the
     * TV links itself - no code to type.
     */
    private fun setupFile(session: IHTTPSession): Response {
        val host = session.headers["host"] ?: ""
        val text = ctx.assets.open(SETUP_FILE).use { it.readBytes() }.toString(Charsets.ISO_8859_1)
            .replace("__RETROTV_TV__", if (host.isNotEmpty()) "http://$host" else "")
            .replace("__RETROTV_TOKEN__", SetupTokens.create(ctx))
        val bytes = text.toByteArray(Charsets.ISO_8859_1)
        return newFixedLengthResponse(Response.Status.OK, "application/octet-stream",
            java.io.ByteArrayInputStream(bytes), bytes.size.toLong()).apply {
            addHeader("Content-Disposition", "attachment; filename=\"$SETUP_FILE\"")
        }
    }

    private fun pcPage(error: String? = null): String = """
<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>RetroTV - Play from your PC</title>
<style>
 :root{color-scheme:dark}
 body{margin:0;font-family:system-ui,sans-serif;background:#0e0f13;color:#eceaf4}
 main{max-width:640px;margin:0 auto;padding:28px 16px 48px}
 h1{margin:0 0 6px;font-size:28px} p,li{color:#a9a6b8;line-height:1.55}
 a{color:#b9a8ff}
 .dl{display:block;text-align:center;margin:22px 0 8px;padding:16px;border-radius:12px;background:#7c5cff;color:#fff;text-decoration:none;font-size:18px;font-weight:700}
 .small{font-size:13px;color:#8d8a9c}
 ol{padding:0;list-style:none;counter-reset:s}
 li{counter-increment:s;background:#181822;border-radius:12px;padding:14px 16px 14px 58px;margin:10px 0;position:relative}
 li::before{content:counter(s);position:absolute;left:16px;top:14px;width:28px;height:28px;border-radius:50%;background:#7c5cff;color:#fff;font-weight:700;display:flex;align-items:center;justify-content:center}
 li b{color:#eceaf4;display:block;margin-bottom:2px}
 h2{font-size:18px;margin:32px 0 4px}
 .status{display:none;margin:16px 0;padding:12px 14px;border-radius:10px;background:#181822}
 .status.ok{display:block;border:1px solid #6ee7a8;color:#6ee7a8}
 .status.work{display:block;border:1px solid #7c5cff;color:#cfc6ff}
 .status.bad{display:block;border:1px solid #ff7a8a;color:#ff7a8a}
 .err{color:#ff7a8a}
 .login input{display:block;width:100%;box-sizing:border-box;margin:8px 0;padding:12px;border-radius:10px;border:1px solid #2c2b38;background:#181822;color:inherit;font-size:16px}
 .login button{width:100%;padding:12px;border:0;border-radius:10px;background:#2c2646;color:#fff;font-size:16px;font-weight:600}
</style></head><body><main>
<h1>Play from your PC</h1>
<p>Your PC runs the console and streams it to RetroTV. This one file sets everything up on Windows 10 or 11.</p>
<div id="status" class="status"></div>
<a class="dl" href="/pc/$SETUP_FILE" download>Download RetroTV PC Setup</a>
<p class="small">If your browser warns about the file, choose <i>Keep</i>.</p>
<ol>
<li><b>Run it</b>Double-click <code>$SETUP_FILE</code> in your Downloads. If Windows says "Windows protected your PC", click <i>More info</i> &rarr; <i>Run anyway</i>, then <i>Yes</i> to allow the install.</li>
<li><b>That's it</b>It installs Sunshine and the controller driver, adds a tile for each emulator it finds, and links your TV by itself - no code to type.</li>
</ol>
<p>Your PC games then appear in the RetroTV library. Run the setup again whenever you install another emulator.</p>
<h2>Installed Sunshine yourself?</h2>
<p class="small">Enter Sunshine's web login (the one you use at https://localhost:47990) and the TV links to this PC automatically.</p>
${if (error != null) "<p class=\"err\">${error.replace("<", "&lt;")}</p>" else ""}
<form method="post" action="/pc/login" class="login">
<input name="user" placeholder="Sunshine username" autocomplete="username">
<input name="pass" type="password" placeholder="Sunshine password" autocomplete="current-password">
<button type="submit">Link this PC</button>
</form>
<p class="small">Missing an emulator? <a href="https://pcsx2.net/downloads" target="_blank" rel="noopener">PCSX2</a> (PS2) &middot;
<a href="https://dolphin-emu.org/download/" target="_blank" rel="noopener">Dolphin</a> (GameCube / Wii) &middot;
<a href="https://rpcs3.net/download" target="_blank" rel="noopener">RPCS3</a> (PS3)</p>
<p><a href="/">&larr; Back to sending games</a></p>
</main>
<script>
const box=document.getElementById('status');
async function poll(){
 try{
  const s=await (await fetch('/pc/link-status',{cache:'no-store'})).json();
  if(s.state==='linked'){box.className='status ok';box.textContent='\u2713 Linked'+(s.pc?' to '+s.pc:'')+'. Your PC games are in the RetroTV library.';}
  else if(s.state==='working'){box.className='status work';box.textContent='Linking the TV to this PC...';}
  else if(s.state==='failed'){box.className='status bad';box.textContent='Linking didn\'t work: '+s.message;}
  else box.className='status';
 }catch(e){}
 setTimeout(poll,2000);
}
poll();
</script>
</body></html>
""".trimIndent()

    companion object {
        const val SETUP_FILE = "RetroTV-PC-Setup.bat"
        const val SERVER_FILE = "RetroTV-Game-Server.bat"

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
