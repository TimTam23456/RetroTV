package com.retrotv.app

import android.content.Intent
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Browse: game files found on web pages you add (your own NAS / home server, homebrew sites...).
 * Pick a game to download it straight into the library.
 */
class LibraryActivity : RetroActivity() {

    override val tabIndex = 1

    private lateinit var sourcesBox: LinearLayout
    private lateinit var heading: TextView
    private lateinit var status: TextView
    private lateinit var chips: LinearLayout
    private lateinit var grid: RecyclerView
    private lateinit var emptyBox: LinearLayout
    private val adapter = GameGrid()

    private var current: Sources.Source? = null
    private var result: LibraryScan.Result? = null
    private var filter: String? = null // system id, "other", or null for all
    private var scanJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Scrims.screen()
        }
        page.addView(TabBar.build(this, 1, listOf(
            Ui.iconButton(this, Icon.PLUS, "Add a source") { showAddSource() },
            Ui.iconButton(this, Icon.REFRESH, "Rescan") { current?.let { scan(it, force = true) } },
        )))
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(48), dp(14), dp(40), 0)
        }
        page.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // ---- left: sources
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(label("SOURCES"))
        sourcesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(sourcesBox) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        body.addView(left, LinearLayout.LayoutParams(dp(250), ViewGroup.LayoutParams.MATCH_PARENT))

        // ---- right: games
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), 0, 0, 0)
        }
        heading = TextView(this).apply {
            textSize = 22f
            setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
            setTextColor(Palette.text)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        right.addView(heading)
        status = TextView(this).apply {
            textSize = 13f
            setTextColor(Palette.muted)
            setPadding(0, dp(2), 0, dp(8))
        }
        right.addView(status)
        chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        right.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips)
        })
        val stack = FrameLayout(this)
        grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@LibraryActivity, 5)
            adapter = this@LibraryActivity.adapter
            clipToPadding = false
            clipChildren = false
            setPadding(0, dp(10), 0, dp(40))
            itemAnimator = null
        }
        stack.addView(grid)
        emptyBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(10), dp(20), 0)
        }
        stack.addView(emptyBox)
        right.addView(stack, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        body.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        setContentView(page)

        // Live updates: sources added from a phone/PC, and download progress.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Sources.list.collect { renderSources() }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Downloads.state.collect { adapter.progressChanged(it) }
            }
        }
        Sources.load(this)
        renderSources()
    }

    override fun onStart() {
        super.onStart()
        Downloads.onFinished = { title -> Toast.makeText(this, "$title is in your library", Toast.LENGTH_SHORT).show() }
    }

    override fun onStop() {
        super.onStop()
        Downloads.onFinished = null
    }

    // ------------------------------------------------------------------ sources

    private fun renderSources() {
        val list = Sources.list.value
        sourcesBox.removeAllViews()
        for (s in list) {
            val cached = OnlineCache.read(this, s.url)
            val row = Ui.listRow(this, s.name, cached?.let { "${it.games.size} games" } ?: "Not scanned yet") {
                selectSource(s)
                grid.post { grid.getChildAt(0)?.requestFocus() }
            }
            row.setOnLongClickListener { confirmRemove(s); true }
            row.onFocusChangeListener.let { prev ->
                row.setOnFocusChangeListener { v, has ->
                    prev?.onFocusChange(v, has)
                    if (has && current?.url != s.url) selectSource(s)
                }
            }
            sourcesBox.addView(row)
        }
        sourcesBox.addView(Ui.listRow(this, "+ Add a source", "A web page or folder with game files") { showAddSource() })
        if (list.isEmpty()) {
            current = null
            showEmpty()
            sourcesBox.getChildAt(0)?.requestFocus()
        } else if (current == null || list.none { it.url == current?.url }) {
            selectSource(list.first())
            sourcesBox.getChildAt(0)?.requestFocus()
        }
    }

    private fun selectSource(s: Sources.Source) {
        current = s
        filter = null
        heading.text = s.name
        val cached = OnlineCache.read(this, s.url)
        if (cached == null) { scan(s, force = false); return }
        show(cached)
        // Show the last scan straight away, then quietly check the source for new games
        // (once per visit to Browse), so games you've added appear without pressing Rescan.
        if (refreshed.add(s.url)) refreshQuietly(s)
    }

    private val refreshed = HashSet<String>()

    private fun refreshQuietly(s: Sources.Source) {
        scanJob?.cancel()
        scanJob = lifecycleScope.launch {
            try {
                val r = withContext(Dispatchers.IO) { OnlineCache.scan(this@LibraryActivity, s.url) }
                if (current?.url == s.url && r.games != result?.games) show(r)
                renderSourcesKeepFocus()
            } catch (_: Exception) {
                // Source not reachable right now (PC off?) - keep showing the last scan.
            }
        }
    }

    private fun scan(s: Sources.Source, force: Boolean) {
        if (!force) OnlineCache.read(this, s.url)?.let { show(it); return }
        scanJob?.cancel()
        status.setTextColor(Palette.accent)
        status.text = "Looking for games on ${s.url} ..."
        adapter.submit(emptyList())
        emptyBox.visibility = View.GONE
        scanJob = lifecycleScope.launch {
            try {
                val r = withContext(Dispatchers.IO) {
                    OnlineCache.scan(this@LibraryActivity, s.url) { pages, files ->
                        runOnUiThread {
                            if (current?.url == s.url) status.text =
                                "Looking for games on ${s.url} ...  $pages folder" + (if (pages == 1) "" else "s") +
                                " checked, $files file" + (if (files == 1) "" else "s") + " found"
                        }
                    }
                }
                if (current?.url == s.url) show(r)
                renderSourcesKeepFocus()
            } catch (e: Exception) {
                if (current?.url != s.url) return@launch
                status.setTextColor(Palette.warn)
                status.text = e.message ?: "Couldn't scan that page."
            }
        }
    }

    private fun renderSourcesKeepFocus() {
        val focusedIndex = (0 until sourcesBox.childCount).indexOfFirst { sourcesBox.getChildAt(it).hasFocus() }
        renderSources()
        if (focusedIndex >= 0) sourcesBox.getChildAt(focusedIndex)?.requestFocus()
    }

    private fun show(r: LibraryScan.Result) {
        result = r
        emptyBox.visibility = View.GONE
        val skipped = if (r.skippedArchives > 0) "  ·  ${r.skippedArchives} .7z/.rar files skipped (not supported)" else ""
        status.setTextColor(Palette.muted)
        status.text = if (r.games.isEmpty()) {
            "No game files found on this page.$skipped Pages that load their lists with JavaScript can't be read."
        } else {
            "${r.games.size} game" + (if (r.games.size == 1) "" else "s") + " · press A to download$skipped"
        }
        renderChips(r)
        applyFilter()
    }

    private fun renderChips(r: LibraryScan.Result) {
        chips.removeAllViews()
        if (r.games.isEmpty()) return
        val present = r.games.map { it.systemId ?: "other" }.distinct()
        fun chip(id: String?, text: String) {
            val selected = filter == id
            chips.addView(Ui.tab(this, text, selected) {
                filter = id
                renderChips(r)
                applyFilter()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { rightMargin = dp(4) }
            })
        }
        chip(null, "All")
        Systems.all.filter { it.id in present }.forEach { chip(it.id, it.short) }
        if ("other" in present) chip("other", "Other")
    }

    private fun applyFilter() {
        val games = result?.games.orEmpty().filter {
            when (filter) {
                null -> true
                "other" -> it.systemId == null
                else -> it.systemId == filter
            }
        }
        adapter.submit(games)
        grid.scrollToPosition(0)
    }

    private fun showEmpty() {
        heading.text = "Online library"
        status.text = ""
        chips.removeAllViews()
        adapter.submit(emptyList())
        emptyBox.removeAllViews()
        emptyBox.visibility = View.VISIBLE
        emptyBox.addView(text("Add a web address and RetroTV finds every game file on it - including in its sub-folders - so you can download them straight to the TV.", 16f, Palette.text))
        emptyBox.addView(text("Good for: your own NAS or home server, a folder of your backups, homebrew sites. " +
            "Only add places you're allowed to download from, and use games you own.", 14f, Palette.muted).apply { setPadding(0, dp(10), 0, 0) })
        val addr = UploadHub.address.value
        if (addr != null) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(20), 0, 0)
            }
            row.addView(ImageView(this).apply {
                setImageBitmap(Qr.bitmap("$addr/library", dp(140)))
                background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Color.WHITE) }
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }, LinearLayout.LayoutParams(dp(140), dp(140)))
            row.addView(text("Easiest: scan this with your phone (or open $addr/library on a PC) and paste the address there.", 14f, Palette.muted)
                .apply { setPadding(dp(20), 0, 0, 0) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            emptyBox.addView(row)
        }
    }

    private fun confirmRemove(s: Sources.Source) {
        AlertDialog.Builder(this)
            .setTitle("Remove ${s.name}?")
            .setMessage("Games you already downloaded stay in your library.")
            .setPositiveButton("Remove") { _, _ -> Sources.remove(this, s.url) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAddSource() {
        val input = EditText(this).apply {
            hint = "e.g. 192.168.1.50:8000/games"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(6), dp(22), 0)
            addView(input)
            UploadHub.address.value?.let { addr ->
                addView(text("Typing with the remote is slow - you can also add it from your phone or PC at $addr/library", 13f, 0xFF666666.toInt())
                    .apply { setPadding(0, dp(8), 0, 0) })
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Add a source")
            .setMessage("A web page or folder that links to game files.")
            .setView(box)
            .setPositiveButton("Add") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotEmpty()) {
                    val s = Sources.add(this, url)
                    selectSource(s)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------------ games

    private fun onGameClicked(g: LibraryScan.RemoteGame) {
        val p = Downloads.state.value[g.url]
        when {
            p?.status == Downloads.Status.QUEUED || p?.status == Downloads.Status.DOWNLOADING ->
                Toast.makeText(this, "Downloading ${g.title}... ${p.percent}%", Toast.LENGTH_SHORT).show()
            Downloads.isInLibrary(this, g) -> playDownloaded(g)
            else -> {
                Downloads.start(this, g)
                Toast.makeText(this, "Downloading ${g.title}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun playDownloaded(g: LibraryScan.RemoteGame) {
        val file: File = Downloads.target(this, g)
        val sys = Systems.detect(file) ?: Systems.byId(g.systemId)
        if (sys == null) {
            Toast.makeText(this, "${g.title} is in your library, but RetroTV couldn't tell which console it's for.", Toast.LENGTH_LONG).show()
            return
        }
        Sfx.play(Sfx.S.LAUNCH)
        GameStats.markPlayed(this, "g:" + file.absolutePath)
        startActivity(Intent(this, GameActivity::class.java)
            .putExtra(GameActivity.EXTRA_PATH, file.absolutePath)
            .putExtra(GameActivity.EXTRA_SYSTEM, sys.id)
            .putExtra(GameActivity.EXTRA_TITLE, g.title))
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Y / menu on a source: remove it.
        if ((keyCode == KeyEvent.KEYCODE_BUTTON_Y || keyCode == KeyEvent.KEYCODE_MENU)) {
            val i = (0 until sourcesBox.childCount).indexOfFirst { sourcesBox.getChildAt(it).hasFocus() }
            val list = Sources.list.value
            if (i in list.indices) { confirmRemove(list[i]); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    private inner class GameGrid : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var items: List<LibraryScan.RemoteGame> = emptyList()
        private var positions: Map<String, Int> = emptyMap()
        private var last: Map<String, Downloads.Progress> = emptyMap()

        fun submit(list: List<LibraryScan.RemoteGame>) {
            items = list
            positions = list.withIndex().associate { it.value.url to it.index }
            @Suppress("NotifyDataSetChanged")
            notifyDataSetChanged()
        }

        /** Only redraw the cards whose download state changed (progress updates come often). */
        fun progressChanged(state: Map<String, Downloads.Progress>) {
            for ((url, p) in state) {
                if (last[url] != p) positions[url]?.let { notifyItemChanged(it, PROGRESS) }
            }
            last = state
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            object : RecyclerView.ViewHolder(BrowseCard(this@LibraryActivity)) {}

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val g = items[position]
            val card = holder.itemView as BrowseCard
            card.bind(g)
            card.setStatus(g, Downloads.state.value[g.url])
            card.setOnClickListener { onGameClicked(g) }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
            if (payloads.contains(PROGRESS)) {
                val g = items[position]
                (holder.itemView as BrowseCard).setStatus(g, Downloads.state.value[g.url])
            } else {
                onBindViewHolder(holder, position)
            }
        }
    }

    private fun label(t: String) = TextView(this).apply {
        text = t
        textSize = 11f
        letterSpacing = 0.14f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Palette.accent)
        setPadding(dp(4), 0, 0, dp(4))
    }

    private fun text(t: String, size: Float, color: Int) = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(color)
        setLineSpacing(0f, 1.2f)
    }

    companion object {
        private const val PROGRESS = "progress"
    }
}

/** A cover with the title and download status underneath (nothing covers the art). */
class BrowseCard(ctx: android.content.Context) : LinearLayout(ctx) {
    private val cover = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val frame = FrameLayout(ctx)
    private val rim = View(ctx)
    private val bar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal)
    private val title = TextView(ctx)
    private val state = TextView(ctx)

    init {
        orientation = VERTICAL
        isFocusable = true
        isClickable = true
        layoutParams = RecyclerView.LayoutParams(ctx.dp(124), ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(ctx.dp(6), ctx.dp(6), ctx.dp(6), ctx.dp(10)) }
        frame.clipToOutline = true
        frame.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, ctx.dp(10).toFloat())
            }
        }
        frame.setBackgroundColor(Palette.panel)
        frame.addView(cover, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        bar.max = 100
        bar.visibility = View.GONE
        frame.addView(bar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, ctx.dp(6), Gravity.BOTTOM))
        rim.background = GradientDrawable().apply { cornerRadius = ctx.dp(10).toFloat(); setStroke(ctx.dp(3), Color.WHITE) }
        rim.visibility = View.INVISIBLE
        frame.addView(rim, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        addView(frame, LayoutParams(ctx.dp(124), ctx.dp(124)))
        title.apply {
            textSize = 12f
            setTextColor(Palette.muted)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(ctx.dp(2), ctx.dp(6), ctx.dp(2), 0)
        }
        addView(title)
        state.apply {
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(ctx.dp(2), ctx.dp(1), ctx.dp(2), 0)
        }
        addView(state)
        setOnFocusChangeListener { _, has ->
            rim.visibility = if (has) View.VISIBLE else View.INVISIBLE
            title.setTextColor(if (has) Palette.text else Palette.muted)
            frame.animate().scaleX(if (has) 1.08f else 1f).scaleY(if (has) 1.08f else 1f).setDuration(120).start()
        }
    }

    fun bind(g: LibraryScan.RemoteGame) {
        Covers.bindRemote(cover, g)
        title.text = g.title
    }

    fun setStatus(g: LibraryScan.RemoteGame, p: Downloads.Progress?) {
        bar.visibility = View.GONE
        when {
            p?.status == Downloads.Status.DOWNLOADING -> {
                bar.visibility = View.VISIBLE
                bar.progress = p.percent
                state.text = "Downloading ${p.percent}%"
                state.setTextColor(Palette.accent)
            }
            p?.status == Downloads.Status.QUEUED -> {
                state.text = "Waiting..."
                state.setTextColor(Palette.muted)
            }
            p?.status == Downloads.Status.FAILED -> {
                state.text = "Failed - press A to retry"
                state.setTextColor(Palette.bad)
            }
            p?.status == Downloads.Status.DONE || Downloads.isInLibrary(context, g) -> {
                state.text = "\u2713 In library - A to play"
                state.setTextColor(Palette.good)
            }
            else -> {
                state.text = "\u2193 Download"
                state.setTextColor(Palette.muted)
            }
        }
    }
}
