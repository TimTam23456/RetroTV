package com.retrotv.app

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Search everything at once: games on the TV and USB drives, your PC's games, and online sources you added. */
class SearchActivity : RetroActivity() {

    private lateinit var input: EditText
    private lateinit var results: LinearLayout
    private lateinit var status: TextView
    private var local: List<Game> = emptyList()
    private var online: List<Pair<Sources.Source, LibraryScan.RemoteGame>> = emptyList()
    private var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.bg)
            setPadding(dp(48), dp(26), dp(48), dp(16))
        }
        root.addView(TextView(this).apply {
            text = "Search"
            textSize = 28f
            setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
            setTextColor(Palette.text)
        })
        input = EditText(this).apply {
            hint = "Game name - e.g. mario kart, zelda, crash"
            textSize = 20f
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setTextColor(Palette.text)
            setHintTextColor(Palette.muted)
            background = Ui.panel(this@SearchActivity)
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }
        root.addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        status = TextView(this).apply {
            textSize = 14f
            setTextColor(Palette.muted)
            setPadding(0, dp(10), 0, dp(6))
        }
        root.addView(status)
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(results); isVerticalScrollBarEnabled = false },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { searchSoon() }
        })
        input.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_DONE) {
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
                results.getChildAt(1)?.requestFocus()
                true
            } else false
        }
        // Down from the box jumps into the results.
        input.setOnKeyListener { _, code, e ->
            if (code == KeyEvent.KEYCODE_DPAD_DOWN && e.action == KeyEvent.ACTION_DOWN && results.childCount > 1) { results.getChildAt(1).requestFocus(); true } else false
        }

        status.text = "Loading your games..."
        lifecycleScope.launch {
            local = withContext(Dispatchers.IO) { Library.scan(this@SearchActivity) }
            online = withContext(Dispatchers.IO) {
                Sources.load(this@SearchActivity).flatMap { src -> OnlineCache.read(this@SearchActivity, src.url)?.games.orEmpty().map { src to it } }
            }
            status.text = "Searching ${local.size} games on the TV" + (if (online.isNotEmpty()) " and ${online.size} online" else "") +
                (PcStore.get(this@SearchActivity)?.let { " and ${it.apps.size} on ${it.name}" } ?: "") + "."
            intent.getStringExtra("q")?.let { input.setText(it) }
            input.requestFocus()
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(input, 0)
        }
    }

    private fun searchSoon() {
        job?.cancel()
        job = lifecycleScope.launch { delay(180); search(input.text.toString().trim()) }
    }

    private fun search(q: String) {
        results.removeAllViews()
        if (q.isEmpty()) return
        val sysName = { g: Game -> g.system.name + " " + g.system.short }
        val mine = Search.rank(q, local, { it.title }, sysName).take(30)
        val pc = PcStore.get(this)
        val pcHits = pc?.let { p -> Search.rank(q, p.apps, { it.name }).take(10).map { p to it } }.orEmpty()
        val web = Search.rank(q, online, { it.second.title }, { (Systems.byId(it.second.systemId)?.name ?: "") }).take(30)

        if (mine.isEmpty() && pcHits.isEmpty() && web.isEmpty()) {
            results.addView(header("No games match \"$q\""))
            results.addView(Ui.listRow(this, "Tips", "Try fewer words, or part of the name. Online sources are searched from their last scan - open Browse to refresh them.", null, null))
            return
        }
        if (mine.isNotEmpty()) {
            results.addView(header("On this TV"))
            for (g in mine) results.addView(Ui.listRow(this, g.title, g.system.name, "Play") { Launch.game(this, g) })
        }
        if (pcHits.isNotEmpty()) {
            results.addView(header("On your PC"))
            for ((p, app) in pcHits) results.addView(Ui.listRow(this, app.name, "Streams from ${p.name}", "Stream") { Launch.pc(this, p, app) })
        }
        if (web.isNotEmpty()) {
            results.addView(header("Online sources"))
            for ((src, g) in web) {
                val have = Downloads.isInLibrary(this, g)
                val sys = Systems.byId(g.systemId)?.name ?: "Unknown console"
                results.addView(Ui.listRow(this, g.title, "$sys - ${src.name}", if (have) "Play" else "Download") {
                    if (Downloads.isInLibrary(this, g)) {
                        val file = Downloads.target(this, g)
                        val system = Systems.detect(file)
                        if (system != null) Launch.game(this, Game(file, system, g.title))
                    } else {
                        Downloads.start(this, g)
                        Toast.makeText(this, "Downloading ${g.title} - it'll appear in your library", Toast.LENGTH_SHORT).show()
                    }
                })
            }
        }
    }

    private fun header(text: String) = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Palette.muted)
        setPadding(dp(4), dp(14), 0, dp(4))
    }
}

/** Starting games from anywhere (home screen, search, kids mode). */
object Launch {
    fun game(ctx: Context, game: Game) {
        GameStats.markPlayed(ctx, GameStats.keyFor(game))
        Sfx.play(Sfx.S.LAUNCH)
        ctx.startActivity(Intent(ctx, GameActivity::class.java)
            .putExtra(GameActivity.EXTRA_PATH, game.file.absolutePath)
            .putExtra(GameActivity.EXTRA_SYSTEM, game.system.id)
            .putExtra(GameActivity.EXTRA_TITLE, game.title))
    }

    fun pc(ctx: Context, pc: SavedPc, app: PcApp) {
        GameStats.markPlayed(ctx, GameStats.keyFor(pc, app))
        StreamLog.markStarted(ctx, "${app.name} on ${pc.name}")
        Sfx.play(Sfx.S.LAUNCH)
        ctx.startActivity(PcLink.streamIntent(ctx, pc, app))
    }
}
