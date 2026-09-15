package com.example.drunkenmaksim

/**
 * Connection/settings state shared between MainActivity, the settings page
 * and the game view (the Telemetry pattern from the F16 HUD project). The
 * gameplay itself lives in [com.example.drunkenmaksim.game.GameEngine].
 */
class GameState {

    // IPIXEL DISPLAY: BLE LED matrix output. Mode and the manual size
    // fallback are persisted.
    @Volatile var ipixelMode = 0        // 0 off, 1 enabled
    @Volatile var ipixelW = 0           // detected resolution (0 = unknown)
    @Volatile var ipixelH = 0
    @Volatile var ipixelManualW = 0     // manual fallback size (0 = auto)
    @Volatile var ipixelManualH = 0
    @Volatile var ipixelStatus = ""     // connection state for the settings
}
