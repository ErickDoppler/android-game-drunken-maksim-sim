package com.example.drunkenmaksim.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import com.example.drunkenmaksim.GameState
import kotlin.math.abs
import kotlin.math.max

/**
 * SETTINGS page: a vertical "stripe" of settings, opened by holding Volume
 * Down for 3 seconds (or a long screen press). Scroll by dragging; tap a
 * button to toggle a setting (applied and persisted immediately by the
 * host). Back, any volume button, or a long display press exits back to
 * the game. Mirrored from the F16 HUD project's settings page.
 *
 * Style: active option = grey button with black label; inactive option =
 * grey outline with grey label on black.
 */
class SettingsView(context: Context, private val state: GameState) : View(context) {

    interface Host {
        fun setIpixelMode(mode: Int)
        fun setIpixelManualSize(w: Int, h: Int)
        fun panelReady(): Boolean
        fun playAnyways()
        fun settingChanged()
        fun exitRequested()
    }

    var host: Host? = null

    private val dp get() = resources.displayMetrics.density
    private val grey = Color.rgb(150, 150, 150)

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = grey
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        color = grey
    }

    private var scrollY = 0f
    private var contentH = 0f

    // Tap targets recorded during the last draw (screen coords, scroll baked in).
    private val hits = ArrayList<Pair<RectF, () -> Unit>>()

    // --------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        hits.clear()
        text.color = grey
        stroke.color = grey
        val w = width.toFloat()
        if (w == 0f) return
        var y = 20f * dp - scrollY

        // Header.
        text.textAlign = Paint.Align.CENTER
        text.textSize = 18f * dp
        text.isFakeBoldText = true
        canvas.drawText("SETTINGS", w / 2f, y + 14f * dp, text)
        text.isFakeBoldText = false
        y += 26f * dp
        text.textSize = 10f * dp
        canvas.drawText("VOLUME / BACK / LONG PRESS - EXIT", w / 2f, y + 10f * dp, text)
        y += 26f * dp

        // --------------------------------------------------- IPIXEL DISPLAY
        y = drawSetting(
            canvas, y, "IPIXEL DISPLAY",
            listOf("OFF", "ENABLE"),
            state.ipixelMode.coerceIn(0, 1),
            if (state.ipixelMode == 1 && state.ipixelStatus.isNotEmpty()) {
                listOf(state.ipixelStatus)
            } else {
                emptyList()
            }
        ) { i -> host?.setIpixelMode(i) }
        if (state.ipixelMode == 1) {
            if (state.ipixelW == 0) {
                // Manual fallback for displays that do not report a size.
                // Only game-capable panels (96x16 class or bigger).
                val sizes = listOf(
                    "AUTO", "96*16", "96*32", "128*16", "128*24", "128*32"
                )
                val curSize = "${state.ipixelManualW}*${state.ipixelManualH}"
                val sel = sizes.indexOf(curSize).let { if (it < 0) 0 else it }
                y = drawSetting(canvas, y, "SIZE", sizes, sel, emptyList()) { i ->
                    if (i == 0) {
                        host?.setIpixelManualSize(0, 0)
                    } else {
                        val p = sizes[i].split("*")
                        host?.setIpixelManualSize(p[0].toInt(), p[1].toInt())
                    }
                }
            }
        }

        // ------------------------------------------------------ PLAY ANYWAYS
        // No panel connected / detected / output off: offer running the game
        // entirely on the phone screen.
        if (host?.panelReady() != true) {
            y = drawSetting(
                canvas, y, "NO DISPLAY?",
                listOf("PLAY ANYWAYS"), -1,
                listOf("RUN THE GAME ON THE PHONE SCREEN ONLY")
            ) { host?.playAnyways() }
        }

        contentH = y + scrollY + 24f * dp
    }

    /** Tappable value box (URL / key entry) with a status marker right of it. */
    private fun drawTapField(
        canvas: Canvas, startY: Float, label: String, marker: String,
        onTap: () -> Unit
    ): Float {
        text.textAlign = Paint.Align.LEFT
        text.textSize = 12f * dp
        val left = 18f * dp
        val boxW = max(text.measureText(label) + 24f * dp, 240f * dp)
        val box = RectF(left, startY, left + boxW, startY + 30f * dp)
        stroke.strokeWidth = 1.5f * dp
        canvas.drawRoundRect(box, 6f * dp, 6f * dp, stroke)
        canvas.drawText(label, left + 12f * dp, box.centerY() + 4f * dp, text)
        hits.add(RectF(box).apply { inset(-5f * dp, -10f * dp) } to onTap)
        if (marker.isNotEmpty()) {
            text.isFakeBoldText = true
            canvas.drawText(marker, box.right + 12f * dp, box.centerY() + 4f * dp, text)
            text.isFakeBoldText = false
        }
        return box.bottom + 14f * dp
    }

    /** Plain ON/OFF setting; [notesOn] shown only while it is ON. */
    private fun drawOnOff(
        canvas: Canvas, y: Float, title: String, on: Boolean,
        notesOn: List<String>, set: (Boolean) -> Unit
    ): Float = drawSetting(
        canvas, y, title, listOf("ON", "OFF"), if (on) 0 else 1,
        if (on) notesOn else emptyList()
    ) { i ->
        set(i == 0)
        host?.settingChanged()
    }

    /**
     * One setting block: name, option buttons, description lines under them.
     * Returns the y below the block.
     */
    private fun drawSetting(
        canvas: Canvas, startY: Float, title: String, options: List<String>,
        selected: Int, notes: List<String>, onSelect: (Int) -> Unit
    ): Float {
        var y = startY
        val left = 18f * dp

        text.textAlign = Paint.Align.LEFT
        text.textSize = 14f * dp
        text.isFakeBoldText = true
        text.color = grey
        canvas.drawText(title, left, y + 12f * dp, text)
        text.isFakeBoldText = false
        y += 22f * dp

        // Buttons row(s): wraps when a button would leave the screen.
        text.textSize = 12f * dp
        val btnH = 30f * dp
        var x = left
        for ((i, label) in options.withIndex()) {
            val btnW = text.measureText(label) + 24f * dp
            if (x > left && x + btnW > width - 18f * dp) {
                x = left
                y += btnH + 8f * dp
            }
            val box = RectF(x, y, x + btnW, y + btnH)
            if (i == selected) {
                fill.color = grey
                canvas.drawRoundRect(box, 6f * dp, 6f * dp, fill)
                text.color = Color.BLACK
            } else {
                stroke.strokeWidth = 1.5f * dp
                canvas.drawRoundRect(box, 6f * dp, 6f * dp, stroke)
                text.color = grey
            }
            text.textAlign = Paint.Align.CENTER
            canvas.drawText(label, box.centerX(), box.centerY() + 4f * dp, text)
            val idx = i
            // Generous hit target: half the button gap sideways, more above
            // and below (nothing else is stacked that close vertically).
            hits.add(RectF(box).apply { inset(-5f * dp, -10f * dp) } to {
                if (idx != selected) onSelect(idx)
            })
            x += btnW + 10f * dp
        }
        text.color = grey
        y += btnH + 8f * dp

        // Description lines.
        text.textAlign = Paint.Align.LEFT
        text.textSize = 11f * dp
        for (line in notes) {
            canvas.drawText(line, left, y + 9f * dp, text)
            y += 15f * dp
        }
        return y + 14f * dp
    }

    // ----------------------------------------------------------------- touch

    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var downTime = 0L
    private var dragged = false
    private val longExit = Runnable { host?.exitRequested() }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val slop = 20f * dp
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastY = event.y
                downTime = event.eventTime
                dragged = false
                handler?.postDelayed(longExit, 700L)
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(event.y - downY) > slop || abs(event.x - downX) > slop) {
                    dragged = true
                    handler?.removeCallbacks(longExit)
                }
                if (dragged) {
                    scrollY = (scrollY - (event.y - lastY))
                        .coerceIn(0f, max(0f, contentH - height))
                    invalidate()
                }
                lastY = event.y
            }
            MotionEvent.ACTION_UP -> {
                handler?.removeCallbacks(longExit)
                if (!dragged && event.eventTime - downTime < 400L) {
                    for ((box, action) in hits) {
                        if (box.contains(event.x, event.y)) {
                            action()
                            invalidate()
                            break
                        }
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> handler?.removeCallbacks(longExit)
        }
        return true
    }
}
