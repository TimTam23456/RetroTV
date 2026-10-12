package com.retrotv.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.hardware.input.InputManager
import android.os.Bundle
import android.view.Gravity
import android.view.InputDevice
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.ScrollView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity(), InputManager.InputDeviceListener {

    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private lateinit var uploadInfo: TextView
    private lateinit var padInfo: TextView
    private lateinit var count: TextView
    private lateinit var tvInfo: TextView
    private var tvChecked = false
    private val adapter = GameAdapter(
        onClick = { openGame(it) },
        ratingFor = { sys -> if (tvChecked) DeviceCheck.verdict(this, sys) else null },
        onPcClick = { pc, app -> openPcGame(pc, app) },
    )
    private var pcOnline: Boolean? = null
    private var pcJob: Job? = null
    private var localCount = 0
    private var scanJob: Job? = null
    private var rescanPending: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                UploadHub.status.collect { uploadInfo.text = it }
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            askStorage.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private val askStorage = registerForActivityResult(ActivityResultContracts.RequestPermission()) { rescan() }

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

    override fun onStop() {
        super.onStop()
        (getSystemService(INPUT_SERVICE) as InputManager).unregisterInputDeviceListener(this)
        UploadHub.onUploaded = null
    }

    // ---------- layout ----------

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Palette.bg)
            setPadding(dp(48), dp(32), dp(48), dp(24))
        }

        val side = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, dp(32), 0)
        }
        side.addView(TextView(this).apply {
            text = "RetroTV"
            textSize = 34f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Palette.text)
        })
        count = TextView(this).apply {
            textSize = 15f
            setTextColor(Palette.muted)
            setPadding(0, dp(2), 0, dp(20))
        }
        side.addView(count)

        side.addView(label("ADD GAMES"))
        uploadInfo = body()
        side.addView(uploadInfo)
        side.addView(body().apply {
            text = "Or plug in a USB drive, or put games in the TV's Download folder."
            setPadding(0, dp(8), 0, dp(20))
        })

        side.addView(label("CONTROLLERS"))
        padInfo = body().apply { setPadding(0, 0, 0, dp(20)) }
        side.addView(padInfo)

        side.addView(label("YOUR TV"))
        tvInfo = body().apply {
            text = "Checking..."
            setPadding(0, 0, 0, dp(10))
        }
        side.addView(tvInfo)
        side.addView(sideButton("TV check: what can it play?") {
            startActivity(Intent(this, TvCheckActivity::class.java))
        }, sideParams())
        side.addView(sideButton("Play newer consoles from PC") {
            startActivity(Intent(this, PcStreamActivity::class.java))
        }, sideParams())
        side.addView(sideButton("Rescan games") {
            rescan()
            Toast.makeText(this, "Scanning...", Toast.LENGTH_SHORT).show()
        }, sideParams())

        side.addView(body().apply {
            text = "In a game: Select + Start (View + Menu on Xbox) or Back opens the menu."
            textSize = 13f
            setTextColor(Palette.muted)
            setPadding(0, dp(14), 0, 0)
        })

        root.addView(ScrollView(this).apply { addView(side) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        val right = android.widget.FrameLayout(this)
        list = RecyclerView(this).apply {
            val glm = GridLayoutManager(context, 3)
            glm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int) = if (this@MainActivity.adapter.isHeader(position)) 3 else 1
            }
            layoutManager = glm
            adapter = this@MainActivity.adapter
            clipToPadding = false
            setPadding(dp(6), dp(6), dp(6), dp(40))
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        empty = TextView(this).apply {
            textSize = 18f
            setTextColor(Palette.muted)
            gravity = Gravity.CENTER
            text = "No games yet.\n\nSend some from your phone or PC using the address on the left."
            visibility = View.GONE
        }
        right.addView(list)
        right.addView(empty, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 2.2f))
        return root
    }

    private fun sideButton(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        textSize = 15f
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        setTextColor(Palette.text)
        background = focusTile()
        setPadding(dp(16), dp(10), dp(16), dp(10))
        growOnFocus()
        setOnClickListener { onClick() }
    }

    private fun sideParams() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { bottomMargin = dp(8) }

    private fun label(t: String) = TextView(this).apply {
        text = t
        textSize = 12f
        letterSpacing = 0.12f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Palette.accent)
        setPadding(0, 0, 0, dp(4))
    }

    private fun body() = TextView(this).apply {
        textSize = 15f
        setTextColor(Palette.text)
        setLineSpacing(0f, 1.2f)
    }

    // ---------- games ----------

    private fun rescan() {
        scanJob?.cancel()
        scanJob = lifecycleScope.launch {
            val games = withContext(Dispatchers.IO) { Library.scan(this@MainActivity) }
            adapter.submit(games)
            localCount = games.size
            updateCount()
            if (adapter.getItemCount() > 0 && currentFocus == null) list.post { list.getChildAt(1)?.requestFocus() }
        }
    }

    /** Called from the upload server thread; waits for a pause so a batch upload only rescans once. */
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
                .setMessage(verdict.reason + if (game.system.heavy) {
                    "\n\nFor smooth play, stream it from your PC instead."
                } else "")
                .setPositiveButton("Try anyway") { _, _ -> launchGame(game) }
                .setNeutralButton("Play from PC") { _, _ ->
                    startActivity(Intent(this, PcStreamActivity::class.java))
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        launchGame(game)
    }

    // ---------- PC games ----------

    private fun loadPc() {
        val pc = PcStore.get(this)
        adapter.setPc(pc, pcOnline)
        updateCount()
        if (pc == null) return
        pcJob?.cancel()
        pcJob = lifecycleScope.launch {
            try {
                val updated = withContext(Dispatchers.IO) { PcLink.refresh(this@MainActivity, pc) }
                PcStore.save(this@MainActivity, updated)
                pcOnline = true
                adapter.setPc(updated, true)
            } catch (_: Exception) {
                pcOnline = false
                adapter.setPc(pc, false)
            }
            updateCount()
        }
    }

    private fun updateCount() {
        val pcGames = PcStore.get(this)?.apps?.size ?: 0
        val local = when (localCount) {
            0 -> "No games on the TV yet"
            1 -> "1 game"
            else -> "$localCount games"
        }
        count.text = if (pcGames > 0) "$local \u00B7 $pcGames on your PC" else local
        empty.visibility = if (adapter.getItemCount() == 0) View.VISIBLE else View.GONE
    }

    private fun openPcGame(pc: SavedPc, app: PcApp) {
        if (pcOnline == false) {
            lifecycleScope.launch {
                val sent = withContext(Dispatchers.IO) {
                    try {
                        PcLink.wake(pc); true
                    } catch (_: Exception) {
                        false
                    }
                }
                Toast.makeText(this@MainActivity,
                    if (sent) "${pc.name} looks off or asleep. Sent a wake signal - try again in about 20 seconds."
                    else "${pc.name} looks off or asleep. Turn it on and make sure Sunshine is running.",
                    Toast.LENGTH_LONG).show()
                loadPc()
            }
            return
        }
        startActivity(PcLink.streamIntent(this, pc, app))
    }

    private fun launchGame(game: Game) {
        startActivity(Intent(this, GameActivity::class.java)
            .putExtra(GameActivity.EXTRA_PATH, game.file.absolutePath)
            .putExtra(GameActivity.EXTRA_SYSTEM, game.system.id)
            .putExtra(GameActivity.EXTRA_TITLE, game.title))
    }

    // ---------- TV check ----------

    private fun checkTv() {
        lifecycleScope.launch {
            val info = withContext(Dispatchers.Default) { DeviceCheck.info(applicationContext) }
            tvChecked = true
            tvInfo.text = "${info.chip} \u00B7 ${DeviceCheck.summary(this@MainActivity)}"
            adapter.refreshHeaders()
        }
    }

    // ---------- controllers ----------

    private fun updatePads() {
        val pads = InputDevice.getDeviceIds().toList().mapNotNull { InputDevice.getDevice(it) }
            .filter { d ->
                !d.isVirtual &&
                    (d.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
                        d.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK)
            }
            .map { it.name }
            .distinct()
        if (pads.isEmpty()) {
            padInfo.setTextColor(Palette.warn)
            padInfo.text = "No controller found. Pair one in TV Settings > Remotes & Accessories. " +
                "(Xbox: hold the pair button on top until the logo flashes fast.)"
        } else {
            padInfo.setTextColor(Palette.good)
            padInfo.text = pads.joinToString("\n") { "✓ $it" }
        }
    }

    override fun onInputDeviceAdded(deviceId: Int) = updatePads()
    override fun onInputDeviceRemoved(deviceId: Int) = updatePads()
    override fun onInputDeviceChanged(deviceId: Int) = updatePads()
}

// ---------- list adapter ----------

class GameAdapter(
    private val onClick: (Game) -> Unit,
    private val ratingFor: (GameSystem) -> DeviceCheck.Verdict?,
    private val onPcClick: (SavedPc, PcApp) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed class Row {
        data class Header(val system: GameSystem, val count: Int) : Row()
        data class Item(val game: Game) : Row()
        data class PcHeader(val pc: SavedPc, val online: Boolean?) : Row()
        data class PcItem(val pc: SavedPc, val app: PcApp) : Row()
    }

    private var rows: List<Row> = emptyList()
    private var games: List<Game> = emptyList()
    private var pc: SavedPc? = null
    private var pcOnline: Boolean? = null

    fun submit(games: List<Game>) {
        this.games = games
        rebuild()
    }

    fun setPc(pc: SavedPc?, online: Boolean?) {
        this.pc = pc
        this.pcOnline = online
        rebuild()
    }

    private fun rebuild() {
        val out = mutableListOf<Row>()
        pc?.takeIf { it.apps.isNotEmpty() }?.let { p ->
            out += Row.PcHeader(p, pcOnline)
            p.apps.forEach { out += Row.PcItem(p, it) }
        }
        games.groupBy { it.system }.forEach { (sys, gs) ->
            out += Row.Header(sys, gs.size)
            gs.forEach { out += Row.Item(it) }
        }
        rows = out
        @Suppress("NotifyDataSetChanged")
        notifyDataSetChanged()
    }

    fun refreshHeaders() {
        rows.forEachIndexed { i, row -> if (row is Row.Header) notifyItemChanged(i) }
    }

    fun isHeader(position: Int) = rows.getOrNull(position).let { it is Row.Header || it is Row.PcHeader }

    override fun getItemCount() = rows.size
    override fun getItemViewType(position: Int) = if (isHeader(position)) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val ctx = parent.context
        val view: View = if (viewType == 0) {
            TextView(ctx).apply {
                textSize = 20f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Palette.text)
                setPadding(ctx.dp(6), ctx.dp(22), ctx.dp(6), ctx.dp(8))
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        } else {
            LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                isFocusable = true
                isClickable = true
                background = ctx.focusTile()
                setPadding(ctx.dp(16), ctx.dp(14), ctx.dp(16), ctx.dp(14))
                minimumHeight = ctx.dp(84)
                growOnFocus()
                addView(TextView(ctx).apply {
                    textSize = 16f
                    setTextColor(Palette.text)
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                addView(TextView(ctx).apply {
                    textSize = 12f
                    setTextColor(Palette.muted)
                    setPadding(0, ctx.dp(4), 0, 0)
                    maxLines = 1
                })
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(ctx.dp(6), ctx.dp(6), ctx.dp(6), ctx.dp(6)) }
            }
        }
        return object : RecyclerView.ViewHolder(view) {}
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> {
                val tv = holder.itemView as TextView
                val v = ratingFor(row.system)
                val extra = when {
                    v != null && !v.playable -> "   \u00B7  ${v.rating.label}" + if (row.system.heavy) " - try Play from PC" else ""
                    v?.rating == DeviceCheck.Rating.OK -> "   \u00B7  Some games may slow down"
                    row.system.note != null -> "   \u00B7  ${row.system.note}"
                    else -> ""
                }
                val text = android.text.SpannableString(row.system.name + extra)
                if (extra.isNotEmpty()) {
                    val color = when {
                        v != null && !v.playable -> Palette.bad
                        v?.rating == DeviceCheck.Rating.OK -> Palette.warn
                        else -> Palette.muted
                    }
                    text.setSpan(android.text.style.ForegroundColorSpan(color), row.system.name.length, text.length, 0)
                    text.setSpan(android.text.style.RelativeSizeSpan(0.7f), row.system.name.length, text.length, 0)
                }
                tv.text = text
            }
            is Row.Item -> {
                val box = holder.itemView as LinearLayout
                (box.getChildAt(0) as TextView).text = row.game.title
                (box.getChildAt(1) as TextView).text = row.game.file.parentFile?.name ?: ""
                box.setOnClickListener { onClick(row.game) }
            }
            is Row.PcHeader -> {
                val tv = holder.itemView as TextView
                val title = "From your PC \u00B7 ${row.pc.name}"
                val (status, color) = when (row.online) {
                    true -> "   \u25CF Online" to Palette.good
                    false -> "   \u25CF Offline - select a game to wake it" to Palette.warn
                    null -> "   Checking..." to Palette.muted
                }
                val text = android.text.SpannableString(title + status)
                text.setSpan(android.text.style.ForegroundColorSpan(color), title.length, text.length, 0)
                text.setSpan(android.text.style.RelativeSizeSpan(0.7f), title.length, text.length, 0)
                tv.text = text
            }
            is Row.PcItem -> {
                val box = holder.itemView as LinearLayout
                (box.getChildAt(0) as TextView).text = row.app.name
                (box.getChildAt(1) as TextView).text = "via PC \u00B7 ${PcGames.describe(row.app.name)}"
                box.setOnClickListener { onPcClick(row.pc, row.app) }
            }
        }
    }
}
