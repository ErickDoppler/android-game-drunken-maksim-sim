package com.example.drunkenmaksim

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.drunkenmaksim.game.GameEngine
import com.example.drunkenmaksim.game.SoundFx
import com.example.drunkenmaksim.ui.GameView
import com.example.drunkenmaksim.ui.SettingsView

/**
 * Portrait shell for Drunken Maksim Sim, the chat-moderator game.
 *
 * The iPixel LED panel is the game's main output; the phone shows a mirror
 * plus the BAN / MUTE buttons and moves the crosshair with the gyro (Wii-
 * controller style). Hold Volume Down 3 s for the settings page; the
 * settings and the iPixel wiring mirror the F16 HUD project.
 */
class MainActivity : ComponentActivity() {

    private companion object {
        const val GYRO_GAIN = 140f // panel px per radian of phone rotation
        const val TICK_MS = 33L
        const val TILT45 = 0.7071f // cos/sin of the 45 deg phone-only grip
        // Phone-only field is far taller than the LED panel: gain so a full
        // sweep of the field height takes a comfortable wrist tilt. Applied
        // to both axes (the field's pixels are square on screen).
        const val LOCAL_SWEEP_RAD = 0.8f
    }

    private val state = GameState()
    private val engine = GameEngine()

    private lateinit var root: FrameLayout
    private lateinit var gameView: GameView
    private lateinit var settingsView: SettingsView

    private val mainHandler = Handler(Looper.getMainLooper())
    private val soundFx by lazy { SoundFx(this) }

    private var settingsOpen = false
    private var volDownHoldFired = false
    private var panelReady = false

    // Holding Volume Down 3 seconds opens the SETTINGS page.
    private val settingsHoldRunnable = Runnable {
        volDownHoldFired = true
        openSettings()
    }

    // ------------------------------------------------------------- game loop

    private val tickRunnable = object : Runnable {
        override fun run() {
            engine.tick(TICK_MS)
            gameView.invalidate()
            mainHandler.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        buildViews()
        setContentView(root)
        enterImmersiveMode()

        restoreUiPrefs()

        engine.listener = { event ->
            when (event) {
                GameEngine.Event.BEER -> soundFx.pop()
                GameEngine.Event.PAUSE -> soundFx.ding()
                GameEngine.Event.GAME_OVER -> Unit
            }
        }

        // Restore the persisted iPixel display output.
        if (state.ipixelMode != 0) {
            mainHandler.post { applyIpixelMode(state.ipixelMode) }
        }
        // The game needs the panel: open the iPixel settings when there is
        // none — immediately if the output is off, after a reconnect grace
        // period if it was enabled before.
        mainHandler.postDelayed(
            { if (!panelReady && !settingsOpen) openSettings() },
            if (state.ipixelMode == 1) 8000L else 500L
        )
        // Debug hook for the settings page (`--es settings 1`).
        if (intent.getStringExtra("settings") == "1") {
            mainHandler.post { openSettings() }
        }
    }

    private fun buildViews() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        gameView = GameView(this, state, engine).apply {
            callbacks = gameCallbacks
        }
        settingsView = SettingsView(this, state).apply {
            visibility = View.GONE
            host = settingsHost
        }
        for (view in listOf(gameView, settingsView)) {
            root.addView(
                view,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
    }

    private fun enterImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    // ------------------------------------------------------------- lifecycle

    override fun onResume() {
        super.onResume()
        engine.paused = settingsOpen
        registerGyro()
        mainHandler.removeCallbacks(tickRunnable)
        mainHandler.post(tickRunnable)
    }

    override fun onPause() {
        engine.paused = true
        mainHandler.removeCallbacks(tickRunnable)
        val sm = getSystemService(SENSOR_SERVICE) as SensorManager
        sm.unregisterListener(gyroListener)
        lastGyroTs = 0L
        saveUiPrefs()
        super.onPause()
    }

    // ------------------------------------------------------------------ gyro

    private var lastGyroTs = 0L

    /** Field px per radian; retuned per mode (see LOCAL_SWEEP_RAD). */
    private var gyroGain = GYRO_GAIN

    private val gyroListener = object : SensorEventListener {
        override fun onSensorChanged(ev: SensorEvent) {
            if (ev.sensor.type != Sensor.TYPE_GYROSCOPE) return
            if (lastGyroTs != 0L) {
                val dt = (ev.timestamp - lastGyroTs) / 1e9f
                val gx = ev.values[0]
                val gy = ev.values[1]
                val gz = ev.values[2]
                // Turning left/right is rotation around the WORLD vertical,
                // whose device-axis mix depends on how the phone is held;
                // raising/lowering the top edge is rotation around device X
                // in either grip.
                val turn = if (engine.localMode) {
                    // Phone-only: portrait, screen facing the player, tilted
                    // back ~45 deg — world vertical splits evenly between
                    // device Y (up the screen) and Z (out of the screen).
                    (gy + gz) * TILT45
                } else {
                    // Panel mode: portrait but held horizontally, screen up,
                    // top edge toward the panel like a TV remote — world
                    // vertical is device Z.
                    gz
                }
                engine.moveCross(-turn * gyroGain * dt, -gx * gyroGain * dt)
            }
            lastGyroTs = ev.timestamp
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private fun registerGyro() {
        val sm = getSystemService(SENSOR_SERVICE) as SensorManager
        sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            sm.registerListener(gyroListener, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    // ------------------------------------------------------------ game input

    private val gameCallbacks = object : GameView.Callbacks {
        override fun onBan() {
            soundFx.glass()
            engine.ban()
        }

        override fun onMute() {
            soundFx.whip()
            engine.mute()
        }

        override fun onTapEmpty() {
            if (engine.phase == GameEngine.Phase.OVER) {
                // A panel that connected during a local game takes over on
                // the next round.
                if (engine.localMode && panelReady) {
                    engine.localMode = false
                    engine.panelW = state.ipixelW
                    engine.panelH = state.ipixelH
                    gyroGain = GYRO_GAIN
                }
                engine.restart()
            } else if (engine.phase == GameEngine.Phase.INTRO ||
                engine.phase == GameEngine.Phase.READY
            ) {
                engine.skipIntro() // straight into the action
            } else {
                engine.recenter()
            }
        }
    }

    // ------------------------------------------------------------ permissions

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    // ---------------------------------------------------- persisted settings

    private val uiPrefs by lazy { getSharedPreferences("drunkenmaksim_ui", MODE_PRIVATE) }

    private fun restoreUiPrefs() {
        state.ipixelMode = uiPrefs.getInt("ipixel_mode", 0).coerceIn(0, 1)
        state.ipixelManualW = uiPrefs.getInt("ipixel_manual_w", 0)
        state.ipixelManualH = uiPrefs.getInt("ipixel_manual_h", 0)
    }

    private fun saveUiPrefs() {
        uiPrefs.edit()
            .putInt("ipixel_mode", state.ipixelMode)
            .putInt("ipixel_manual_w", state.ipixelManualW)
            .putInt("ipixel_manual_h", state.ipixelManualH)
            .apply()
    }

    // ----------------------------------------------------------- volume keys

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (settingsOpen) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP,
                KeyEvent.KEYCODE_VOLUME_DOWN,
                KeyEvent.KEYCODE_BACK -> return true // exit handled on key up
            }
            return super.onKeyDown(keyCode, event)
        }
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.repeatCount == 0) {
                volDownHoldFired = false
                mainHandler.postDelayed(settingsHoldRunnable, 3000L)
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (settingsOpen) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP,
                KeyEvent.KEYCODE_VOLUME_DOWN,
                KeyEvent.KEYCODE_BACK -> {
                    // The release of the hold that OPENED the page must not
                    // immediately close it again.
                    if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && volDownHoldFired) {
                        volDownHoldFired = false
                    } else {
                        closeSettings()
                    }
                    return true
                }
            }
            return super.onKeyUp(keyCode, event)
        }
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            mainHandler.removeCallbacks(settingsHoldRunnable)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    // ---------------------------------------------------------- SETTINGS page

    private val settingsHost = object : SettingsView.Host {
        override fun setIpixelMode(mode: Int) = applyIpixelMode(mode)

        override fun setIpixelManualSize(w: Int, h: Int) {
            state.ipixelManualW = w
            state.ipixelManualH = h
            ipixelHub.manualW = w
            ipixelHub.manualH = h
            ipixelHub.applyManualSize()
            saveUiPrefs()
            settingsView.invalidate()
        }

        override fun panelReady(): Boolean = this@MainActivity.panelReady

        override fun playAnyways() = startLocalGame()

        override fun settingChanged() {
            saveUiPrefs()
            settingsView.invalidate()
        }

        override fun exitRequested() = closeSettings()
    }

    private fun openSettings() {
        if (settingsOpen) return
        settingsOpen = true
        engine.paused = true
        settingsView.visibility = View.VISIBLE
        settingsView.invalidate()
    }

    private fun closeSettings() {
        if (!settingsOpen) return
        settingsOpen = false
        engine.paused = false
        settingsView.visibility = View.GONE
        saveUiPrefs()
        maybeStartGame()
    }

    /** The intro starts once the panel is connected, big enough, and the
     *  settings page is out of the way. */
    private fun maybeStartGame() {
        if (panelReady && !settingsOpen && engine.phase == GameEngine.Phase.WAITING) {
            engine.localMode = false
            gyroGain = GYRO_GAIN
            engine.startIntro()
        }
    }

    /** "PLAY ANYWAYS": no panel — run the game on the phone screen only,
     *  on a virtual field sized to the playable screen area. */
    private fun startLocalGame() {
        val (w, h) = gameView.localFieldSize()
        engine.localMode = true
        engine.panelW = w
        engine.panelH = h
        gyroGain = h / LOCAL_SWEEP_RAD
        closeSettings()
        engine.startIntro()
    }

    // ---------------------------------------------------------- iPixel LED

    private val ipixelHub by lazy { com.example.drunkenmaksim.led.IPixelHub(this) }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 77 && state.ipixelMode == 1) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startIpixel()
            } else {
                state.ipixelStatus = "BLUETOOTH PERMISSION DENIED"
                settingsView.invalidate()
            }
        }
    }

    /** IPIXEL DISPLAY setting: 0 off, 1 scan + connect + run the game. */
    private fun applyIpixelMode(mode: Int) {
        state.ipixelMode = mode.coerceIn(0, 1)
        if (state.ipixelMode == 1) {
            val missing = if (Build.VERSION.SDK_INT >= 31) {
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
                ).filter { !hasPermission(it) }
            } else {
                // Android 6-11: BLE scans return nothing without location.
                listOf(Manifest.permission.ACCESS_FINE_LOCATION)
                    .filter { !hasPermission(it) }
            }
            if (missing.isNotEmpty()) {
                state.ipixelStatus = "WAITING FOR PERMISSION..."
                requestPermissions(missing.toTypedArray(), 77)
            } else {
                startIpixel()
            }
        } else {
            ipixelHub.disableWithGoodbye()
            panelReady = false
            state.ipixelW = 0
            state.ipixelH = 0
            state.ipixelStatus = ""
        }
        saveUiPrefs()
        settingsView.invalidate()
    }

    private fun startIpixel() {
        if (!ipixelHub.btEnabled()) {
            state.ipixelStatus = "REQUESTING BLUETOOTH..."
            settingsView.invalidate()
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(
                    android.content.Intent(
                        android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE
                    ), 78
                )
            } catch (_: Exception) {
                state.ipixelStatus = "TURN BLUETOOTH ON MANUALLY"
                settingsView.invalidate()
            }
            return
        }
        ipixelHub.manualW = state.ipixelManualW
        ipixelHub.manualH = state.ipixelManualH
        ipixelHub.onStateChanged = { s ->
            state.ipixelStatus = s
            settingsView.postInvalidate()
        }
        ipixelHub.onResolution = { w, h ->
            runOnUiThread {
                state.ipixelW = w
                state.ipixelH = h
                if (GameEngine.panelSizeOk(w, h)) {
                    engine.panelW = w
                    engine.panelH = h
                    panelReady = true
                    maybeStartGame()
                } else {
                    panelReady = false
                    state.ipixelStatus = "PANEL TOO SMALL - NEED 96*16 OR BIGGER"
                }
                settingsView.invalidate()
            }
        }
        // The game is the panel's only output. 250 ms is the fastest pace
        // this panel class digests cleanly (proven by the HUD's compass
        // pages) — pushing faster desyncs the panel's receive buffer and
        // garbage flashes between frames.
        ipixelHub.renderFrame = {
            val w = ipixelHub.width
            val h = ipixelHub.height
            // A local (phone-only) game has a screen-shaped field; do not
            // stream its crop to the panel — it takes over on restart.
            if (w > 0 && h > 0 && !engine.localMode) engine.renderFrame(w, h) else null
        }
        ipixelHub.frameIntervalMs = 250L
        // Field overrides for trying other panels: adb-settable via
        // shared_prefs/ipixel_lab.xml; keys that are absent keep the hub's
        // compiled defaults (the measured-best E15 configuration).
        getSharedPreferences("ipixel_lab", MODE_PRIVATE).let { lab ->
            ipixelHub.frameMode = lab.getInt("frame_mode", ipixelHub.frameMode)
            ipixelHub.writeWithResponse = lab.getBoolean("rsp", ipixelHub.writeWithResponse)
            ipixelHub.ackGating = lab.getBoolean("gate", ipixelHub.ackGating)
            ipixelHub.highPriority = lab.getBoolean("hi_prio", ipixelHub.highPriority)
            ipixelHub.chunkMax = lab.getInt("chunk", ipixelHub.chunkMax)
            ipixelHub.useDiyMode = lab.getBoolean("diy", ipixelHub.useDiyMode)
            ipixelHub.skipWipe = lab.getBoolean("skip_wipe", ipixelHub.skipWipe)
            ipixelHub.cameraFrameLen = lab.getInt("cam_len", ipixelHub.cameraFrameLen)
            ipixelHub.cameraAcks = lab.getBoolean("cam_acks", ipixelHub.cameraAcks)
            ipixelHub.skipUnchanged = lab.getBoolean("dedupe", ipixelHub.skipUnchanged)
            ipixelHub.minIntervalMs = lab.getInt("floor_ms", ipixelHub.minIntervalMs.toInt()).toLong()
            ipixelHub.phy2m = lab.getBoolean("phy2m", ipixelHub.phy2m)
            ipixelHub.autoFallback = lab.getBoolean("auto_fallback", ipixelHub.autoFallback)
        }
        state.ipixelStatus = "STARTING..."
        settingsView.invalidate()
        ipixelHub.enable()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int,
        data: android.content.Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 78 && state.ipixelMode == 1) {
            if (ipixelHub.btEnabled()) {
                startIpixel()
            } else {
                state.ipixelStatus = "BLUETOOTH STAYED OFF"
                settingsView.invalidate()
            }
        }
    }

    override fun onDestroy() {
        ipixelHub.disable()
        soundFx.release()
        super.onDestroy()
    }
}
