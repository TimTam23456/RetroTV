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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
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
    private val adapter = GameAdapter { openGame(it) }
    private var server: UploadServer? = null
    private var scanJob: Job? = null
    private var rescanPending: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        rescan()
    }

    override fun onStart() {
        super.onStart()
        rescan()
        startServer()
        updatePads()
        (getSystemService(INPUT_SERVICE) as InputManager).registerInputDeviceListener(this, null)
    }

    override fun onStop() {
        super.onStop()
        (getSystemService(INPUT_SERVICE) as InputManager).unregisterInputDeviceListener(this)
        server?.stop()
        server = null
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

        side.addView(label("IN GAME"))
        side.addView(body().apply {
            text = "Press Select + Start (View + Menu on Xbox) or the remote's Back button for the menu: save, load, reset, quit."
            setPadding(0, 0, 0, dp(20))
        })

        side.addView(Button(this).apply {
            text = "Rescan games"
            isAllCaps = false
            textSize = 16f
            setTextColor(Palette.text)
            background = focusTile()
            setPadding(dp(20), dp(12), dp(20), dp(12))
            growOnFocus()
            setOnClickListener { rescan(); Toast.makeText(context, "Scanning...", Toast.LENGTH_SHORT).show() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(side, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

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
            count.text = when (games.size) {
                0 -> "No games found"
                1 -> "1 game"
                else -> "${games.size} games"
            }
            empty.visibility = if (games.isEmpty()) View.VISIBLE else View.GONE
            if (games.isNotEmpty() && currentFocus == null) list.post { list.getChildAt(1)?.requestFocus() }
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
        startActivity(Intent(this, GameActivity::class.java)
            .putExtra(GameActivity.EXTRA_PATH, game.file.absolutePath)
            .putExtra(GameActivity.EXTRA_SYSTEM, game.system.id)
            .putExtra(GameActivity.EXTRA_TITLE, game.title))
    }

    // ---------- upload server ----------

    private fun startServer() {
        lifecycleScope.launch {
            val started = withContext(Dispatchers.IO) {
                for (port in 8080..8090) {
                    try {
                        val s = UploadServer(applicationContext, port) { name ->
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, "Received $name", Toast.LENGTH_SHORT).show()
                                rescanSoon()
                            }
                        }
                        s.start(60_000, false)
                        return@withContext s
                    } catch (_: Exception) {
                    }
                }
                null
            }
            server = started
            val ip = UploadServer.localIp()
            uploadInfo.text = when {
                started == null -> "Couldn't start the upload page."
                ip == null -> "Connect the TV to Wi-Fi to send games from your phone or PC."
                else -> "On your phone or PC (same Wi-Fi), open:\nhttp://$ip:${started.listeningPort}"
            }
        }
    }

    // ---------- controllers ----------

    private fun updatePads() {
        val pads = InputDevice.getDeviceIds().mapNotNull { InputDevice.getDevice(it) }
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

class GameAdapter(private val onClick: (Game) -> Unit) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed class Row {
        data class Header(val system: GameSystem, val count: Int) : Row()
        data class Item(val game: Game) : Row()
    }

    private var rows: List<Row> = emptyList()

    fun submit(games: List<Game>) {
        val out = mutableListOf<Row>()
        games.groupBy { it.system }.forEach { (sys, gs) ->
            out += Row.Header(sys, gs.size)
            gs.forEach { out += Row.Item(it) }
        }
        rows = out
        @Suppress("NotifyDataSetChanged")
        notifyDataSetChanged()
    }

    fun isHeader(position: Int) = rows.getOrNull(position) is Row.Header

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
                tv.text = row.system.name + (row.system.note?.let { "   ·  $it" } ?: "")
            }
            is Row.Item -> {
                val box = holder.itemView as LinearLayout
                (box.getChildAt(0) as TextView).text = row.game.title
                (box.getChildAt(1) as TextView).text = row.game.file.parentFile?.name ?: ""
                box.setOnClickListener { onClick(row.game) }
            }
        }
    }
}
