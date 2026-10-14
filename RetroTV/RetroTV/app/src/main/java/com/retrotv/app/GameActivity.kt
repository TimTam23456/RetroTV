package com.retrotv.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroViewData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile
import kotlin.math.abs

class GameActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PATH = "path"
        const val EXTRA_SYSTEM = "system"
        const val EXTRA_TITLE = "title"
    }

    private lateinit var root: FrameLayout
    private lateinit var status: TextView
    private lateinit var loading: android.widget.LinearLayout
    private lateinit var system: GameSystem
    private lateinit var gameFile: File
    private lateinit var gameTitle: String

    private var retroView: GLRetroView? = null
    private var menuOpen = false
    private var pendingAction: String? = null

    // Live speed check: frames drawn per second vs the screen's refresh rate.
    @Volatile private var frames = 0
    private val speedSamples = ArrayList<Double>()
    private var slowSeconds = 0
    private var warnedSlow = false
    private lateinit var banner: TextView

    /** Controller device id -> player number (0 = player 1). */
    private val ports = LinkedHashMap<Int, Int>()
    private val held = HashSet<Int>()
    private val triggers = HashMap<Int, BooleanArray>() // port -> [L2 down, R2 down]
    private val stickDpad = HashMap<Int, IntArray>()     // port -> [x, y] last sent from stick

    private val sramFile get() = File(CoreManager.savesDir(this, system), gameFile.nameWithoutExtension + ".srm")
    private val stateFile get() = File(CoreManager.statesDir(this, system), gameFile.nameWithoutExtension + ".state")

    private val menu = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        menuOpen = false
        pendingAction = if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringExtra(MenuActivity.EXTRA_ACTION)
        } else {
            null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUi()

        val sys = Systems.byId(intent.getStringExtra(EXTRA_SYSTEM))
        val path = intent.getStringExtra(EXTRA_PATH)
        if (sys == null || path == null) {
            finish()
            return
        }
        system = sys
        gameFile = File(path)
        gameTitle = intent.getStringExtra(EXTRA_TITLE) ?: gameFile.nameWithoutExtension

        root = FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.BLACK) }
        // Loading screen: the game's cover and title while the emulator starts.
        loading = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = Scrims.screen()
        }
        val cover = android.widget.ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(16).toFloat())
                }
            }
            elevation = dp(16).toFloat()
        }
        Covers.bindGame(cover, Game(gameFile, system, gameTitle))
        loading.addView(cover, android.widget.LinearLayout.LayoutParams(dp(200), dp(200)))
        loading.addView(TextView(this).apply {
            text = gameTitle
            textSize = 26f
            setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD))
            setTextColor(Palette.text)
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(22), dp(24), 0)
        })
        status = TextView(this).apply {
            textSize = 16f
            setTextColor(Palette.muted)
            gravity = Gravity.CENTER
            text = "Starting ${system.name}..."
            setPadding(dp(48), dp(8), dp(48), 0)
        }
        loading.addView(status)
        root.addView(loading, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        banner = TextView(this).apply {
            textSize = 15f
            setTextColor(Palette.text)
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(android.graphics.Color.parseColor("#E6181822"))
                setStroke(dp(2), Palette.warn)
            }
            visibility = View.GONE
        }
        root.addView(banner, FrameLayout.LayoutParams(dp(560), ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(24) })
        setContentView(root)

        lifecycleScope.launch {
            try {
                val core = CoreManager.ensureCore(this@GameActivity, system) { msg ->
                    runOnUiThread { status.text = msg }
                }
                status.text = "Loading..."
                val playable = withContext(Dispatchers.IO) { prepareGameFile() }
                startEmulator(core, playable)
            } catch (e: Exception) {
                fail(e.message ?: "Something went wrong")
            }
        }
    }

    /** Zipped cartridge games are unpacked to a temp folder; everything else is used in place. */
    private fun prepareGameFile(): File {
        if (gameFile.extension.lowercase() != "zip") return gameFile
        val entryName = Systems.zipEntry(gameFile) ?: throw IllegalStateException("No game found inside the zip")
        val dir = File(cacheDir, "launch").apply { deleteRecursively(); mkdirs() }
        val out = File(dir, gameFile.nameWithoutExtension + "." + entryName.substringAfterLast('.'))
        ZipFile(gameFile).use { zip ->
            val entry = zip.getEntry(entryName)
            zip.getInputStream(entry).use { input -> FileOutputStream(out).use { input.copyTo(it) } }
        }
        return out
    }

    private fun startEmulator(core: File, game: File) {
        val data = GLRetroViewData(this).apply {
            coreFilePath = core.absolutePath
            gameFilePath = game.absolutePath
            systemDirectory = CoreManager.systemDir(this@GameActivity).absolutePath
            savesDirectory = CoreManager.savesDir(this@GameActivity, system).absolutePath
            saveRAMState = sramFile.takeIf { it.exists() && it.length() > 0 }?.readBytes()
            preferLowLatencyAudio = true
            rumbleEventsEnabled = false
        }
        val view = GLRetroView(this, data)
        view.isFocusable = false
        view.isFocusableInTouchMode = false
        lifecycle.addObserver(view)
        root.addView(view, 0, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        retroView = view

        lifecycleScope.launch {
            view.getGLRetroEvents().collect { event ->
                if (event is GLRetroView.GLRetroEvents.FrameRendered) {
                    frames++
                    if (loading.visibility == View.VISIBLE) {
                        loading.visibility = View.GONE
                        showHint()
                        applyCheats()
                    }
                }
            }
        }
        lifecycleScope.launch {
            view.getGLRetroErrors().collect { code ->
                fail(when (code) {
                    GLRetroView.ERROR_LOAD_LIBRARY -> "Couldn't start the ${system.name} emulator on this TV."
                    GLRetroView.ERROR_LOAD_GAME -> "This game file didn't load. It may be damaged, or need a BIOS file."
                    GLRetroView.ERROR_GL_NOT_COMPATIBLE -> "This TV's graphics chip can't run ${system.name}."
                    else -> "The emulator hit an error ($code)."
                })
            }
        }
        lifecycleScope.launch { watchSpeed() }
        // Back up in-game saves every 20 seconds.
        lifecycleScope.launch {
            while (isActive) {
                delay(20_000)
                if (!menuOpen && retroView != null) saveSram()
            }
        }
    }

    private suspend fun watchSpeed() {
        @Suppress("DEPRECATION")
        val target = windowManager.defaultDisplay.refreshRate.toDouble().coerceIn(30.0, 60.0)
        delay(5000) // skip the loading stutter
        frames = 0
        var seconds = 0
        while (true) {
            delay(1000)
            val counted = frames
            frames = 0
            if (menuOpen || !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) || retroView == null) {
                continue
            }
            seconds++
            val speed = (counted / target).coerceAtMost(1.0)
            speedSamples += speed
            slowSeconds = if (speed < 0.85) slowSeconds + 1 else 0
            if (slowSeconds >= 10 && !warnedSlow) {
                warnedSlow = true
                banner.text = "Running at ${(speed * 100).toInt()}% speed - your TV is struggling with this game. " +
                    "Newer consoles play smoother streamed from your PC (TV check on the home screen)."
                banner.visibility = View.VISIBLE
                banner.bringToFront()
                delay(9000)
                banner.visibility = View.GONE
            }
            if (seconds % 30 == 0) recordSpeed()
        }
    }

    /** Stores the typical speed so the TV check can learn from real play. */
    private fun recordSpeed() {
        if (speedSamples.size < 20) return
        val sorted = speedSamples.sorted()
        SpeedLog.put(this, system, sorted[sorted.size / 2])
    }

    override fun onPause() {
        super.onPause()
        if (::system.isInitialized) recordSpeed()
    }

    private fun showHint() {
        Toast.makeText(this, "Select + Start for the menu", Toast.LENGTH_SHORT).show()
    }

    private fun fail(msg: String) {
        if (isFinishing) return
        retroView = null // Back now exits instead of opening the menu
        loading.bringToFront()
        loading.visibility = View.VISIBLE
        status.setTextColor(Palette.warn)
        status.text = "$msg\n\nPress Back to return."
    }

    // ---------- saves ----------

    private suspend fun saveSram() {
        val view = retroView ?: return
        try {
            val bytes = withContext(Dispatchers.IO) { view.serializeSRAM() }
            if (bytes.isNotEmpty()) withContext(Dispatchers.IO) { sramFile.writeBytes(bytes) }
        } catch (_: Exception) {
        }
    }

    private suspend fun runAction(action: String) {
        val view = retroView ?: return
        when (action) {
            MenuActivity.SAVE -> {
                val bytes = withContext(Dispatchers.IO) { view.serializeState() }
                if (bytes.isNotEmpty()) {
                    withContext(Dispatchers.IO) { stateFile.writeBytes(bytes) }
                    toast("State saved")
                } else toast("This console doesn't support save states")
            }
            MenuActivity.LOAD -> {
                if (!stateFile.exists()) return toast("No saved state yet")
                val ok = withContext(Dispatchers.IO) { view.unserializeState(stateFile.readBytes()) }
                toast(if (ok) "State loaded" else "Couldn't load state")
            }
            MenuActivity.RESET -> view.reset()
            MenuActivity.FAST -> {
                view.frameSpeed = if (view.frameSpeed > 1) 1 else 3
                toast(if (view.frameSpeed > 1) "Fast forward on (3x) - pause menu to turn it off" else "Normal speed")
            }
            MenuActivity.QUIT -> {
                saveSram()
                finish()
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private var cheatsApplied = ""

    /** Sends the switched-on cheats to the emulator (after clearing the old ones). */
    private fun applyCheats() {
        val view = retroView ?: return
        lifecycleScope.launch {
            val game = Game(gameFile, system, gameTitle)
            val codes = withContext(Dispatchers.IO) {
                val st = Cheats.state(this@GameActivity, game)
                if (st.enabled.isEmpty()) emptyList() else Cheats.activeCodes(this@GameActivity, game, try { Cheats.findInDb(this@GameActivity, game) } catch (_: Exception) { emptyList() })
            }
            val key = codes.joinToString("|")
            if (key == cheatsApplied) return@launch
            cheatsApplied = key
            try {
                view.queueEvent { try { com.swordfish.libretrodroid.LibretroDroid.resetCheat() } catch (_: Throwable) { } }
                codes.forEachIndexed { i, code -> view.setCheat(i, true, code) }
                if (codes.isNotEmpty()) toast("${codes.size} cheat" + (if (codes.size == 1) "" else "s") + " on")
            } catch (_: Throwable) {
                toast("This console's emulator doesn't accept those cheats")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        if (retroView != null && loading.visibility != View.VISIBLE) applyCheats()
        val action = pendingAction ?: return
        pendingAction = null
        lifecycleScope.launch {
            delay(300) // let the emulator wake up first
            runAction(action)
        }
    }

    private fun openMenu() {
        if (menuOpen || retroView == null) return
        menuOpen = true
        // Release anything held so no button gets stuck down.
        (ports.values + 0).toSet().forEach { port ->
            held.forEach { retroView?.sendKeyEvent(KeyEvent.ACTION_UP, it, port) }
        }
        held.clear()
        lifecycleScope.launch {
            saveSram()
            menu.launch(Intent(this@GameActivity, MenuActivity::class.java)
                .putExtra(MenuActivity.EXTRA_TITLE, gameTitle)
                .putExtra(MenuActivity.EXTRA_HAS_STATE, stateFile.exists())
                .putExtra(MenuActivity.EXTRA_PLAYERS, ports.size)
                .putExtra(MenuActivity.EXTRA_PATH, gameFile.absolutePath)
                .putExtra(MenuActivity.EXTRA_SYSTEM, system.id)
                .putExtra(MenuActivity.EXTRA_SPEED, retroView?.frameSpeed ?: 1))
        }
    }

    // ---------- input ----------

    private val gamepadKeys = setOf(
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
        KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2,
        KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR,
        KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT,
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
    )

    /** Each controller gets the next free player slot; the TV remote always drives player 1. */
    private fun portFor(deviceId: Int): Int {
        val dev = InputDevice.getDevice(deviceId) ?: return 0
        val isPad = dev.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            dev.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
        if (!isPad) return 0
        return ports.getOrPut(deviceId) { ports.size.coerceAtMost(3) }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val view = retroView
        val code = event.keyCode

        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_BUTTON_MODE || code == KeyEvent.KEYCODE_MENU) {
            if (event.action == KeyEvent.ACTION_UP) {
                if (view == null) finish() else openMenu()
            }
            return true
        }
        if (view == null || menuOpen) return super.dispatchKeyEvent(event)

        // TV remote OK button acts as A, so you can test without a controller.
        val key = if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
            KeyEvent.KEYCODE_BUTTON_A
        } else code
        if (key !in gamepadKeys) return super.dispatchKeyEvent(event)
        if (event.repeatCount > 0) return true

        val port = portFor(event.deviceId)
        when (event.action) {
            KeyEvent.ACTION_DOWN -> held.add(key)
            KeyEvent.ACTION_UP -> held.remove(key)
        }
        if (KeyEvent.KEYCODE_BUTTON_SELECT in held && KeyEvent.KEYCODE_BUTTON_START in held) {
            openMenu()
            return true
        }
        view.sendKeyEvent(event.action, key, port)
        return true
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val view = retroView
        if (view == null || menuOpen ||
            event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK ||
            event.action != MotionEvent.ACTION_MOVE
        ) return super.dispatchGenericMotionEvent(event)

        val port = portFor(event.deviceId)
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        val lx = event.getAxisValue(MotionEvent.AXIS_X)
        val ly = event.getAxisValue(MotionEvent.AXIS_Y)

        if (system.hasAnalog) {
            view.sendMotionEvent(GLRetroView.MOTION_SOURCE_DPAD, hatX, hatY, port)
            view.sendMotionEvent(GLRetroView.MOTION_SOURCE_ANALOG_LEFT, lx, ly, port)
            view.sendMotionEvent(GLRetroView.MOTION_SOURCE_ANALOG_RIGHT,
                event.getAxisValue(MotionEvent.AXIS_Z), event.getAxisValue(MotionEvent.AXIS_RZ), port)
        } else {
            // Retro consoles: the left stick works as a d-pad too.
            val sx = if (abs(lx) > 0.5f) Math.signum(lx) else 0f
            val sy = if (abs(ly) > 0.5f) Math.signum(ly) else 0f
            val x = if (hatX != 0f) hatX else sx
            val y = if (hatY != 0f) hatY else sy
            val last = stickDpad.getOrPut(port) { intArrayOf(0, 0) }
            if (last[0] != x.toInt() || last[1] != y.toInt()) {
                last[0] = x.toInt(); last[1] = y.toInt()
                view.sendMotionEvent(GLRetroView.MOTION_SOURCE_DPAD, x, y, port)
            }
        }

        // Xbox triggers are analog axes; turn them into L2/R2 button presses.
        val lt = maxOf(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE)) > 0.5f
        val rt = maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS)) > 0.5f
        val state = triggers.getOrPut(port) { booleanArrayOf(false, false) }
        if (lt != state[0]) {
            state[0] = lt
            view.sendKeyEvent(if (lt) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_L2, port)
        }
        if (rt != state[1]) {
            state[1] = rt
            view.sendKeyEvent(if (rt) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_R2, port)
        }
        return true
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
    }
}
