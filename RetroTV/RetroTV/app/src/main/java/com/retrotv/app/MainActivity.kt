package com.retrotv.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.input.InputManager
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The home screen: a console-style game library.
 *
 *  [ backdrop: blurred art of the selected game ]
 *  RetroTV   Games  PC  Settings              (+) (gauge)  pad  12:30
 *  SNES · Played yesterday
 *  Super Mario World                              <- hero (selected item)
 *  (A) Play   (Y) Options
 *  Continue playing  [][][][]
 *  From your PC      [][][]
 *  SNES              [][][][][]
 *  ...
 */
class MainActivity : RetroActivity(), InputManager.InputDeviceListener {

    override val tabIndex = 0

    // ---- views
    private lateinit var backdrop: ImageView
    private lateinit var heroChip: TextView
    private lateinit var heroMeta: TextView
    private lateinit var heroTitle: TextView
    private lateinit var heroNote: TextView
    private lateinit var heroHints: LinearLayout
    private lateinit var rowsScroll: ScrollView
    private lateinit var rowsBox: LinearLayout
    private lateinit var padIcon: ImageView
    private lateinit var padText: TextView

    // ---- data
    private var games: List<Game> = emptyList()
    private var scanned = false
    private var tvChecked = false
    private var pcOnline: Boolean? = null
    private var scanJob: Job? = null
    private var rescanPending: Job? = null
    private var pcJob: Job? = null
    private var backdropJob: Job? = null

    /** Which card had focus (row id + item key), so it can be restored after the rows are rebuilt. */
    private var focusRow: String? = null
    private var focusKey: String? = null

    private val askStorage = registerForActivityResult(ActivityResultContracts.RequestPermission()) { rescan() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            askStorage.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AutoLink.state.collect { st ->
                    if (st.linked) {
                        loadPc()
                        Toast.makeText(this@MainActivity, st.message, Toast.LENGTH_LONG).show()
                        AutoLink.reset()
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        rescan()
        updatePads()
        checkTv()
        loadPc()
        UploadHub.onUploaded = { name ->
            runOnUiThread {
                Toast.makeText(this, "Received $name", Toast.LENGTH_SHORT).show()
                rescanSoon()
            }
        }
        (getSystemService(INPUT_SERVICE) as InputManager).registerInputDeviceListener(this, null)
    }

    private var resumedBefore = false

    override fun onResume() {
        super.onResume()
        showStreamProblemIfAny(this)
        // Back from a game, Settings or the PC screen: refresh "Continue playing", favourites, etc.
        if (resumedBefore && scanned) render()
        resumedBefore = true
    }

    override fun onStop() {
        super.onStop()
        (getSystemService(INPUT_SERVICE) as InputManager).unregisterInputDeviceListener(this)
        UploadHub.onUploaded = null
    }

    // ===================================================================== layout

    private fun buildLayout(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Palette.bg) }

        backdrop = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0f
        }
        root.addView(backdrop, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(View(this).apply { background = Scrims.home() },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(buildTopBar())
        content.addView(buildHero())

        rowsBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(220)) // room so the last row can scroll up to the top
            clipChildren = false
            clipToPadding = false
        }
        rowsScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = true
            clipChildren = false
            addView(rowsBox)
        }
        content.addView(rowsScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(content)
        return root
    }

    private fun buildTopBar(): View {
        // Controller status (not focusable)
        padIcon = ImageView(this).apply { setImageDrawable(IconDrawable(Icon.GAMEPAD, Palette.muted)) }
        padText = TextView(this).apply {
            textSize = 13f
            setTextColor(Palette.muted)
            setPadding(dp(5), 0, 0, 0)
        }
        val pad = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, 0, 0)
            addView(padIcon, LinearLayout.LayoutParams(dp(24), dp(24)))
            addView(padText)
        }
        return TabBar.build(this, 0, listOf(
            Ui.iconButton(this, Icon.PLUS, "Add games") { AddGames.show(this) },
            Ui.iconButton(this, Icon.GAUGE, "TV check") { startActivity(Intent(this, TvCheckActivity::class.java)) },
            pad,
        ))
    }

    private fun buildHero(): View {
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(14), dp(48), dp(10))
        }
        val metaRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        heroChip = TextView(this).apply {
            textSize = 11f
            letterSpacing = 0.12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(dp(9), dp(3), dp(9), dp(3))
        }
        metaRow.addView(heroChip)
        heroMeta = TextView(this).apply {
            textSize = 13f
            setTextColor(Palette.muted)
            setPadding(dp(10), 0, 0, 0)
        }
        metaRow.addView(heroMeta)
        hero.addView(metaRow)
        heroTitle = TextView(this).apply {
            textSize = 36f
            setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
            setTextColor(Palette.text)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(6), 0, 0)
            setShadowLayer(12f, 0f, 2f, 0x99000000.toInt())
        }
        hero.addView(heroTitle)
        heroNote = TextView(this).apply {
            textSize = 14f
            setTextColor(Palette.muted)
            maxLines = 2
            setPadding(0, dp(2), 0, 0)
        }
        hero.addView(heroNote)
        heroHints = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        hero.addView(heroHints)
        hero.minimumHeight = dp(150)
        return hero
    }

    // ===================================================================== rows

    /** One horizontal row of cards. */
    private data class RowData(val id: String, val title: String, val subtitle: String?, val items: List<Card>)

    sealed class Card {
        abstract val key: String
        data class GameCard(val game: Game) : Card() { override val key get() = GameStats.keyFor(game) }
        data class PcCard(val pc: SavedPc, val app: PcApp) : Card() { override val key get() = GameStats.keyFor(pc, app) }
        data class ActionCard(val id: String, val title: String, val text: String, val icon: Icon, val color: Int) : Card() {
            override val key get() = "action:$id"
        }
    }

    private fun buildRows(): List<RowData> {
        val pc = PcStore.get(this)
        val pcCards = pc?.apps?.map { Card.PcCard(pc, it) }.orEmpty()
        val gameCards = games.map { Card.GameCard(it) }
        val everything = gameCards + pcCards
        val rows = mutableListOf<RowData>()

        val recent = everything.filter { GameStats.lastPlayed(this, it.key) > 0 }
            .sortedByDescending { GameStats.lastPlayed(this, it.key) }.take(12)
        if (recent.isNotEmpty()) rows += RowData("recent", "Continue playing", null, recent)

        val favs = everything.filter { GameStats.isFavourite(this, it.key) }
        if (favs.isNotEmpty()) rows += RowData("favs", "Favourites", null, favs)

        if (pc != null && pcCards.isNotEmpty()) {
            val status = when (pcOnline) {
                true -> "● Online"
                false -> "● Offline - select a game to wake it"
                null -> "Checking..."
            }
            rows += RowData("pc", "From your PC · ${pc.name}", status, pcCards)
        }

        games.groupBy { it.system }.toSortedMap(compareBy { Systems.all.indexOf(it) }).forEach { (sys, list) ->
            val verdict = if (tvChecked) DeviceCheck.verdict(this, sys) else null
            val sub = when {
                verdict != null && !verdict.playable -> if (sys.heavy) "Too slow on this TV - stream it from your PC" else "May be too slow on this TV"
                verdict?.rating == DeviceCheck.Rating.OK -> "Some games may slow down"
                else -> "${list.size} game" + if (list.size == 1) "" else "s"
            }
            rows += RowData("sys:${sys.id}", sys.name, sub, list.map { Card.GameCard(it) })
        }

        val tools = listOf(
            Card.ActionCard("add", "Add games", "Scan the code with your phone to send games to the TV, or plug in a USB drive.", Icon.QR, 0xFF7C5CFF.toInt()),
            Card.ActionCard("pc", if (pc == null) "Play from your PC" else "PC settings",
                if (pc == null) "Stream PS2, PS3, GameCube, Switch and more from your Windows PC." else "Stream quality, wake your PC, or link a different one.",
                Icon.PC, 0xFF1E3A8A.toInt()),
            Card.ActionCard("browse", "Browse online", "Find and download games from web pages you add, like your own NAS or home server.", Icon.LINK, 0xFF0B7FAB.toInt()),
            Card.ActionCard("tvcheck", "TV check", "See which consoles this TV can run smoothly.", Icon.GAUGE, 0xFF0E7C86.toInt()),
            Card.ActionCard("settings", "Settings", "Controllers, cover art, PC streaming and more.", Icon.SETTINGS, 0xFF374151.toInt()),
        )
        if (games.isEmpty() && pcCards.isEmpty()) {
            rows.add(0, RowData("tools", "Get started", "Add your first games", tools))
        } else {
            rows += RowData("tools", "More", null, tools)
        }
        return rows
    }

    private fun render() {
        val rows = buildRows()
        rowsBox.removeAllViews()
        var restoreTarget: Pair<RecyclerView, Int>? = null
        for (row in rows) {
            val (view, rv) = buildRow(row)
            rowsBox.addView(view)
            if (restoreTarget == null && row.id == focusRow) {
                val idx = row.items.indexOfFirst { it.key == focusKey }
                if (idx >= 0) restoreTarget = rv to idx
            }
        }
        if (restoreTarget == null && focusKey != null) {
            // The card moved to another row (e.g. into "Continue playing"); find it anywhere.
            for (i in 0 until rowsBox.childCount) {
                val rv = (rowsBox.getChildAt(i) as ViewGroup).getChildAt(1) as RecyclerView
                val idx = (rv.adapter as CardAdapter).items.indexOfFirst { it.key == focusKey }
                if (idx >= 0) { restoreTarget = rv to idx; break }
            }
        }
        val target = restoreTarget
        if (target != null) {
            target.first.scrollToPosition(target.second)
            target.first.post { target.first.findViewHolderForAdapterPosition(target.second)?.itemView?.requestFocus() }
        } else {
            focusFirstCard()
        }
    }

    private fun focusFirstCard() {
        rowsBox.post {
            val first = (rowsBox.getChildAt(0) as? ViewGroup)?.getChildAt(1) as? RecyclerView ?: return@post
            first.scrollToPosition(0)
            first.post { first.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
        }
    }

    private fun buildRow(row: RowData): Pair<View, RecyclerView> {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            tag = row.id
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(dp(50), dp(14), dp(48), dp(2))
        }
        header.addView(TextView(this).apply {
            text = row.title
            textSize = 17f
            setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
            setTextColor(Palette.text)
        })
        if (row.subtitle != null) {
            header.addView(TextView(this).apply {
                text = row.subtitle
                textSize = 13f
                setTextColor(when {
                    row.subtitle.startsWith("● Online") -> Palette.good
                    row.subtitle.startsWith("● Offline") || row.subtitle.contains("slow") -> Palette.warn
                    else -> Palette.muted
                })
                setPadding(dp(12), 0, 0, dp(2))
            })
        }
        box.addView(header)

        val rv = RecyclerView(this).apply {
            layoutManager = RowLayoutManager(this@MainActivity)
            adapter = CardAdapter(row.id, row.items)
            clipToPadding = false
            clipChildren = false
            setPadding(dp(44), dp(12), dp(44), dp(14))
            itemAnimator = null
            overScrollMode = View.OVER_SCROLL_NEVER
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        box.addView(rv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(Ui.CARD + 26)))
        return box to rv
    }

    /** Keeps the remote from jumping off the end of a row. */
    private class RowLayoutManager(ctx: android.content.Context) : LinearLayoutManager(ctx, HORIZONTAL, false) {
        override fun onInterceptFocusSearch(focused: View, direction: Int): View? {
            val pos = try { getPosition(focused) } catch (_: Exception) { return null }
            if (direction == View.FOCUS_RIGHT && pos == itemCount - 1) return focused
            if (direction == View.FOCUS_LEFT && pos == 0) return focused
            return null
        }
    }

    private inner class CardAdapter(val rowId: String, val items: List<Card>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            object : RecyclerView.ViewHolder(CardView(this@MainActivity)) {}

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val card = items[position]
            val v = holder.itemView as CardView
            v.bind(card, GameStats.isFavourite(this@MainActivity, card.key))
            v.setOnClickListener { open(card) }
            v.setOnLongClickListener { showOptions(card); true }
            v.onFocus = { has ->
                if (has) {
                    focusRow = rowId
                    focusKey = card.key
                    showHero(card)
                    scrollRowIntoPlace(v)
                }
            }
        }
    }

    /** Keeps the focused row near the top, under the hero, like a console home screen. */
    private fun scrollRowIntoPlace(card: View) {
        var row: View = card
        while (row.parent != null && row.parent !== rowsBox) row = row.parent as View
        if (row.parent !== rowsBox) return
        rowsScroll.smoothScrollTo(0, maxOf(0, row.top - dp(4)))
    }

    // ===================================================================== hero + backdrop

    private fun showHero(card: Card) {
        heroHints.removeAllViews()
        when (card) {
            is Card.GameCard -> {
                val g = card.game
                setChip(g.system.short, g.system.color)
                heroMeta.text = GameStats.playedText(this, card.key) ?: "Not played yet"
                heroTitle.text = g.title
                val verdict = if (tvChecked) DeviceCheck.verdict(this, g.system) else null
                heroNote.setTextColor(if (verdict != null && !verdict.playable) Palette.warn else Palette.muted)
                heroNote.text = when {
                    verdict != null && !verdict.playable -> "${verdict.rating.label}. ${verdict.reason}"
                    verdict?.rating == DeviceCheck.Rating.OK -> "Playable on this TV - demanding games may slow down."
                    else -> g.system.name + if (Library.isDeletable(this, g)) " \u00B7 on this TV" else " \u00B7 on USB / storage"
                }
                hint("A", "Play"); hint("Y", "Options")
                loadBackdrop(g)
            }
            is Card.PcCard -> {
                setChip("VIA PC", PcArt.colorFor(card.app.name))
                heroMeta.text = (GameStats.playedText(this, card.key) ?: "Streams from ${card.pc.name}") +
                    when (pcOnline) { false -> "  ·  PC offline"; else -> "" }
                heroTitle.text = card.app.name
                heroNote.setTextColor(Palette.muted)
                heroNote.text = "${PcGames.describe(card.app.name)} on ${card.pc.name}. Hold View + Menu + LB + RB to stop streaming."
                hint("A", "Stream"); hint("Y", "Options")
                clearBackdrop()
            }
            is Card.ActionCard -> {
                setChip("RETROTV", Palette.accent)
                heroMeta.text = ""
                heroTitle.text = card.title
                heroNote.setTextColor(Palette.muted)
                heroNote.text = card.text
                hint("A", "Open")
                clearBackdrop()
            }
        }
    }

    private fun setChip(text: String, color: Int) {
        heroChip.text = text
        heroChip.background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(color) }
    }

    private fun hint(button: String, label: String) {
        heroHints.addView(TextView(this).apply {
            text = button
            textSize = 11f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Palette.bg)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Palette.text) }
        }, LinearLayout.LayoutParams(dp(20), dp(20)))
        heroHints.addView(TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(Palette.text)
            setPadding(dp(7), 0, dp(20), 0)
        })
    }

    private fun loadBackdrop(game: Game) {
        backdropJob?.cancel()
        backdropJob = lifecycleScope.launch {
            delay(180) // don't load art for every card the remote flies past
            val bmp = Covers.backdrop(applicationContext, game)
            if (bmp == null) { clearBackdrop(); return@launch }
            backdrop.animate().cancel()
            backdrop.setImageBitmap(bmp)
            backdrop.animate().alpha(0.55f).setDuration(350).start()
        }
    }

    private fun clearBackdrop() {
        backdropJob?.cancel()
        backdrop.animate().cancel()
        backdrop.animate().alpha(0f).setDuration(250).start()
    }

    // ===================================================================== actions

    private fun open(card: Card) {
        Sfx.play(if (card is Card.ActionCard) Sfx.S.SELECT else Sfx.S.LAUNCH)
        when (card) {
            is Card.GameCard -> openGame(card.game)
            is Card.PcCard -> openPcGame(card.pc, card.app)
            is Card.ActionCard -> when (card.id) {
                "add" -> AddGames.show(this)
                "pc" -> startActivity(Intent(this, PcStreamActivity::class.java))
                "tvcheck" -> startActivity(Intent(this, TvCheckActivity::class.java))
                "settings" -> startActivity(Intent(this, SettingsActivity::class.java))
                "browse" -> goToTab(1)
            }
        }
    }

    private fun showOptions(card: Card) {
        if (card is Card.ActionCard) return
        val fav = GameStats.isFavourite(this, card.key)
        val options = mutableListOf<Pair<String, () -> Unit>>()
        options += (if (card is Card.PcCard) "Stream" else "Play") to { open(card) }
        options += (if (fav) "Remove from Favourites" else "Add to Favourites") to {
            GameStats.setFavourite(this, card.key, !fav)
            render()
        }
        if (GameStats.lastPlayed(this, card.key) > 0) {
            options += "Remove from Continue playing" to {
                GameStats.forget(this, card.key)
                if (fav) GameStats.setFavourite(this, card.key, true)
                render()
            }
        }
        if (card is Card.GameCard && Library.isDeletable(this, card.game)) {
            options += "Delete game from TV" to { confirmDelete(card.game) }
        }
        val title = when (card) {
            is Card.GameCard -> card.game.title
            is Card.PcCard -> card.app.name
            else -> ""
        }
        Ui.actionSheet(this, title, options)
    }

    private fun confirmDelete(game: Game) {
        AlertDialog.Builder(this)
            .setTitle("Delete ${game.title}?")
            .setMessage("This removes the game file from the TV. Save files are kept.")
            .setPositiveButton("Delete") { _, _ ->
                Library.delete(this, game)
                GameStats.forget(this, GameStats.keyFor(game))
                rescan()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_Y || keyCode == KeyEvent.KEYCODE_MENU) {
            val v = currentFocus
            if (v is CardView) {
                v.card?.let { showOptions(it) }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ===================================================================== games

    private fun rescan() {
        scanJob?.cancel()
        scanJob = lifecycleScope.launch {
            games = withContext(Dispatchers.IO) { Library.scan(this@MainActivity) }
            scanned = true
            render()
        }
    }

    private fun rescanSoon() {
        lifecycleScope.launch {
            rescanPending?.cancel()
            rescanPending = launch {
                delay(1500)
                rescan()
            }
        }
    }

    private fun openGame(game: Game) {
        val verdict = if (tvChecked) DeviceCheck.verdict(this, game.system) else null
        if (verdict != null && !verdict.playable) {
            AlertDialog.Builder(this)
                .setTitle("${game.system.name} may not run on this TV")
                .setMessage(verdict.reason + if (game.system.heavy) "\n\nFor smooth play, stream it from your PC instead." else "")
                .setPositiveButton("Try anyway") { _, _ -> launchGame(game) }
                .setNeutralButton("Play from PC") { _, _ -> startActivity(Intent(this, PcStreamActivity::class.java)) }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        launchGame(game)
    }

    private fun launchGame(game: Game) {
        GameStats.markPlayed(this, GameStats.keyFor(game))
        startActivity(Intent(this, GameActivity::class.java)
            .putExtra(GameActivity.EXTRA_PATH, game.file.absolutePath)
            .putExtra(GameActivity.EXTRA_SYSTEM, game.system.id)
            .putExtra(GameActivity.EXTRA_TITLE, game.title))
    }

    // ===================================================================== PC

    private fun loadPc() {
        val pc = PcStore.get(this) ?: run { if (scanned) render(); return }
        if (scanned) render()
        pcJob?.cancel()
        pcJob = lifecycleScope.launch {
            try {
                val updated = withContext(Dispatchers.IO) { PcLink.refresh(this@MainActivity, pc) }
                PcStore.save(this@MainActivity, updated)
                pcOnline = true
            } catch (_: Exception) {
                pcOnline = false
            }
            if (scanned) render()
        }
    }

    private fun openPcGame(pc: SavedPc, app: PcApp) {
        if (pcOnline == false) {
            lifecycleScope.launch {
                val sent = withContext(Dispatchers.IO) {
                    try { PcLink.wake(pc); true } catch (_: Exception) { false }
                }
                Toast.makeText(this@MainActivity,
                    if (sent) "${pc.name} looks off or asleep. Sent a wake signal - try again in about 20 seconds."
                    else "${pc.name} looks off or asleep. Turn it on and make sure Sunshine is running.",
                    Toast.LENGTH_LONG).show()
                loadPc()
            }
            return
        }
        GameStats.markPlayed(this, GameStats.keyFor(pc, app))
        StreamLog.markStarted(this, "${app.name} on ${pc.name}")
        startActivity(PcLink.streamIntent(this, pc, app))
    }

    // ===================================================================== TV + controllers

    private fun checkTv() {
        lifecycleScope.launch {
            withContext(Dispatchers.Default) { DeviceCheck.info(applicationContext) }
            tvChecked = true
            if (scanned) render()
        }
    }

    private fun updatePads() {
        val pads = Controllers.connected()
        if (pads.isEmpty()) {
            padIcon.setImageDrawable(IconDrawable(Icon.GAMEPAD, Palette.warn))
            padText.text = "No controller"
            padText.setTextColor(Palette.warn)
        } else {
            padIcon.setImageDrawable(IconDrawable(Icon.GAMEPAD, Palette.good))
            padText.text = pads.size.toString()
            padText.setTextColor(Palette.text)
        }
    }

    override fun onInputDeviceAdded(deviceId: Int) = updatePads()
    override fun onInputDeviceRemoved(deviceId: Int) = updatePads()
    override fun onInputDeviceChanged(deviceId: Int) = updatePads()
}

/** A game card: square cover with rounded corners that grows and gets a white rim when selected. */
class CardView(ctx: android.content.Context) : FrameLayout(ctx) {
    private val image = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val rim = View(ctx)
    private val badge = TextView(ctx)
    private val star = ImageView(ctx)
    var card: MainActivity.Card? = null
        private set
    var onFocus: ((Boolean) -> Unit)? = null

    init {
        val size = ctx.dp(Ui.CARD)
        layoutParams = RecyclerView.LayoutParams(size, size).apply { setMargins(ctx.dp(8), 0, ctx.dp(8), 0) }
        isFocusable = true
        isClickable = true
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, ctx.dp(12).toFloat())
            }
        }
        setBackgroundColor(Palette.panel)
        addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        badge.apply {
            textSize = 10f
            letterSpacing = 0.1f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(ctx.dp(6), ctx.dp(2), ctx.dp(6), ctx.dp(2))
            background = GradientDrawable().apply { cornerRadius = ctx.dp(5).toFloat(); setColor(0xCC000000.toInt()) }
            visibility = View.GONE
        }
        addView(badge, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START)
            .apply { setMargins(ctx.dp(7), ctx.dp(7), 0, 0) })
        star.apply {
            setImageDrawable(IconDrawable(Icon.STAR_FILLED, 0xFFFFD45C.toInt()))
            visibility = View.GONE
        }
        addView(star, LayoutParams(ctx.dp(18), ctx.dp(18), Gravity.TOP or Gravity.END).apply { setMargins(0, ctx.dp(7), ctx.dp(7), 0) })
        rim.background = GradientDrawable().apply {
            cornerRadius = ctx.dp(12).toFloat()
            setStroke(ctx.dp(3), Color.WHITE)
        }
        rim.visibility = View.INVISIBLE
        addView(rim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        setOnFocusChangeListener { _, has ->
            rim.visibility = if (has) View.VISIBLE else View.INVISIBLE
            animate().scaleX(if (has) 1.12f else 1f).scaleY(if (has) 1.12f else 1f)
                .translationZ(if (has) ctx.dp(12).toFloat() else 0f).setDuration(140).start()
            onFocus?.invoke(has)
        }
    }

    fun bind(card: MainActivity.Card, favourite: Boolean) {
        this.card = card
        star.visibility = if (favourite) View.VISIBLE else View.GONE
        badge.visibility = View.GONE
        when (card) {
            is MainActivity.Card.GameCard -> Covers.bindGame(image, card.game)
            is MainActivity.Card.PcCard -> {
                image.tag = null
                image.setImageDrawable(PcArt.drawable(card.app))
            }
            is MainActivity.Card.ActionCard -> {
                image.tag = null
                image.setImageDrawable(ActionArt(card.title, card.icon, card.color))
            }
        }
    }
}
