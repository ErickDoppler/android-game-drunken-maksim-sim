package com.example.drunkenmaksim.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.example.drunkenmaksim.GameState
import com.example.drunkenmaksim.game.GameEngine
import kotlin.math.abs

/**
 * The phone screen (portrait). Two modes:
 *  - Panel mode: a live pixel mirror of the iPixel panel at the top,
 *    score/time header and phase messages.
 *  - Local mode ("PLAY ANYWAYS", no panel): the game field fills the whole
 *    screen except the BAN/MUTE buttons and the how-to-play line.
 * Tapping the empty area re-centers the gyro crosshair (or restarts after
 * game over).
 */
class GameView(
    context: Context,
    private val state: GameState,
    private val engine: GameEngine
) : View(context) {

    interface Callbacks {
        fun onBan()
        fun onMute()
        fun onTapEmpty()
    }

    var callbacks: Callbacks? = null

    private val dp get() = resources.displayMetrics.density
    private val grey = Color.rgb(150, 150, 150)
    private val orange = Color.rgb(255, 144, 0)
    private val red = Color.rgb(255, 45, 45)
    private val yellow = Color.rgb(255, 255, 0)
    private val greenCol = Color.rgb(0, 255, 70)

    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        color = grey
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = grey
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val mirrorPaint = Paint().apply { isFilterBitmap = false } // crisp pixels

    private val banRect = RectF()
    private val muteRect = RectF()
    private var banPressedAt = 0L
    private var mutePressedAt = 0L

    // Button strip geometry, shared by both modes and the touch handler.
    private val btnH get() = 130f * dp
    private val btnTop get() = height - btnH - 16f * dp

    /** Virtual field size for local (phone-only) play: 96 "LEDs" wide, the
     *  height picked so the field fills the playable screen area. */
    fun localFieldSize(): Pair<Int, Int> {
        if (width == 0 || height == 0) return 96 to 160
        val areaH = (btnTop - 36f * dp).coerceAtLeast(1f)
        val h = (96f * areaH / width).toInt().coerceIn(32, 240)
        return 96 to h
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        if (width == 0) return
        if (engine.localMode) drawLocal(canvas) else drawPanelMode(canvas)
        drawButtons(canvas)
    }

    // ----------------------------------------------------- local (phone) mode

    private fun drawLocal(canvas: Canvas) {
        val hintBase = btnTop - 14f * dp
        val fieldBottom = btnTop - 36f * dp
        val bmp = engine.renderFrame(engine.panelW, engine.panelH)
        canvas.drawBitmap(
            bmp, null, RectF(0f, 0f, width.toFloat(), fieldBottom), mirrorPaint
        )
        text.textAlign = Paint.Align.CENTER
        if (engine.phase == GameEngine.Phase.OVER) {
            val (label, col) = endingLabel()
            text.color = col
            text.textSize = 15f * dp
            text.isFakeBoldText = true
            canvas.drawText("$label - TAP TO RESTART", width / 2f, hintBase, text)
            text.isFakeBoldText = false
        } else {
            text.color = grey
            text.textSize = 11f * dp
            canvas.drawText(
                "TILT TO AIM - TAP SCREEN TO CENTER CROSSHAIR",
                width / 2f, hintBase, text
            )
        }
    }

    // ------------------------------------------------------------ panel mode

    private fun drawPanelMode(canvas: Canvas) {
        val w = width.toFloat()
        var y = 16f * dp

        // Header.
        text.textSize = 16f * dp
        text.isFakeBoldText = true
        if (engine.phase == GameEngine.Phase.PLAYING ||
            engine.phase == GameEngine.Phase.OVER
        ) {
            text.textAlign = Paint.Align.LEFT
            text.color = orange
            canvas.drawText("SCORE ${engine.score}", 16f * dp, y + 14f * dp, text)
            text.textAlign = Paint.Align.RIGHT
            val secs = (engine.timeLeftMs + 999L) / 1000L
            text.color = if (engine.timeLeftMs < 10_000L) red else Color.WHITE
            canvas.drawText("TIME $secs", w - 16f * dp, y + 14f * dp, text)
        } else {
            text.textAlign = Paint.Align.CENTER
            text.color = yellow
            canvas.drawText("DRUNKEN MAKSIM SIM", w / 2f, y + 14f * dp, text)
        }
        text.isFakeBoldText = false
        y += 28f * dp

        // Panel mirror.
        val bmp = engine.renderFrame(engine.panelW, engine.panelH)
        val mw = w - 24f * dp
        val mh = mw * bmp.height / bmp.width
        val dst = RectF(12f * dp, y, 12f * dp + mw, y + mh)
        canvas.drawBitmap(bmp, null, dst, mirrorPaint)
        stroke.strokeWidth = 1.5f * dp
        stroke.color = grey
        canvas.drawRect(dst, stroke)
        y = dst.bottom + 20f * dp

        // Phase messages.
        text.textAlign = Paint.Align.CENTER
        text.textSize = 13f * dp
        when (engine.phase) {
            GameEngine.Phase.WAITING -> {
                text.color = grey
                canvas.drawText("CONNECT THE IPIXEL DISPLAY", w / 2f, y, text)
                y += 20f * dp
                if (state.ipixelStatus.isNotEmpty()) {
                    canvas.drawText(state.ipixelStatus, w / 2f, y, text)
                    y += 20f * dp
                }
                canvas.drawText("HOLD VOLUME DOWN 3 SEC - SETTINGS", w / 2f, y, text)
            }
            GameEngine.Phase.PLAYING -> {
                text.color = grey
                text.textSize = 11f * dp
                canvas.drawText("TAP SCREEN - CENTER CROSSHAIR", w / 2f, y, text)
            }
            GameEngine.Phase.OVER -> {
                val (label, col) = endingLabel()
                text.color = col
                text.textSize = 26f * dp
                text.isFakeBoldText = true
                canvas.drawText(label, w / 2f, y + 10f * dp, text)
                y += 46f * dp
                canvas.drawText("${engine.score}", w / 2f, y, text)
                text.isFakeBoldText = false
                y += 30f * dp
                text.textSize = 13f * dp
                text.color = grey
                canvas.drawText("TAP TO RESTART", w / 2f, y, text)
            }
            else -> Unit // intro/ready play out on the mirror
        }
    }

    private fun endingLabel(): Pair<String, Int> = when (engine.ending) {
        GameEngine.Ending.GOOD -> "GOOD ENDING" to greenCol
        GameEngine.Ending.BAD -> "GAME OVER" to red
        GameEngine.Ending.BAD_ADMIN -> "ADMIN HIT" to red
        GameEngine.Ending.NEUTRAL -> "TIME'S UP" to orange
    }

    // ----------------------------------------------------------- buttons

    private fun drawButtons(canvas: Canvas) {
        val w = width.toFloat()
        val by = btnTop
        banRect.set(12f * dp, by, w / 2f - 6f * dp, by + btnH)
        muteRect.set(w / 2f + 6f * dp, by, w - 12f * dp, by + btnH)
        drawButton(canvas, banRect, "BAN", red, banPressedAt) { c, cx, cy, s ->
            drawHammer(c, cx, cy, s)
        }
        drawButton(canvas, muteRect, "MUTE", yellow, mutePressedAt) { c, cx, cy, s ->
            drawMuteIcon(c, cx, cy, s)
        }
    }

    private fun drawButton(
        canvas: Canvas, r: RectF, label: String, col: Int, pressedAt: Long,
        icon: (Canvas, Float, Float, Float) -> Unit
    ) {
        val pressed = SystemClock.elapsedRealtime() - pressedAt < 150L
        if (pressed) {
            fill.color = (col and 0x00FFFFFF) or 0x40000000
            canvas.drawRoundRect(r, 10f * dp, 10f * dp, fill)
        }
        stroke.strokeWidth = 2f * dp
        stroke.color = col
        canvas.drawRoundRect(r, 10f * dp, 10f * dp, stroke)
        fill.color = col
        icon(canvas, r.centerX(), r.centerY() - 12f * dp, 34f * dp)
        text.textAlign = Paint.Align.CENTER
        text.textSize = 15f * dp
        text.isFakeBoldText = true
        text.color = col
        canvas.drawText(label, r.centerX(), r.bottom - 16f * dp, text)
        text.isFakeBoldText = false
    }

    /** Banhammer: a tilted head-and-handle silhouette. */
    private fun drawHammer(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(-45f)
        // Handle.
        canvas.drawRoundRect(
            RectF(-s * 0.08f, -s * 0.1f, s * 0.08f, s * 0.55f),
            s * 0.06f, s * 0.06f, fill
        )
        // Head.
        canvas.drawRoundRect(
            RectF(-s * 0.35f, -s * 0.42f, s * 0.35f, -s * 0.1f),
            s * 0.08f, s * 0.08f, fill
        )
        canvas.restore()
    }

    /** Mute: a speaker with a slash. */
    private fun drawMuteIcon(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        val p = Path()
        p.moveTo(cx - s * 0.4f, cy - s * 0.15f)
        p.lineTo(cx - s * 0.15f, cy - s * 0.15f)
        p.lineTo(cx + s * 0.15f, cy - s * 0.4f)
        p.lineTo(cx + s * 0.15f, cy + s * 0.4f)
        p.lineTo(cx - s * 0.15f, cy + s * 0.15f)
        p.lineTo(cx - s * 0.4f, cy + s * 0.15f)
        p.close()
        canvas.drawPath(p, fill)
        stroke.strokeWidth = 4f * dp
        stroke.color = red
        canvas.drawLine(
            cx - s * 0.45f, cy + s * 0.45f, cx + s * 0.45f, cy - s * 0.45f, stroke
        )
    }

    // ----------------------------------------------------------------- touch

    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var downInButton = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downTime = event.eventTime
                downInButton = true
                when {
                    banRect.contains(event.x, event.y) -> {
                        banPressedAt = SystemClock.elapsedRealtime()
                        callbacks?.onBan()
                    }
                    muteRect.contains(event.x, event.y) -> {
                        mutePressedAt = SystemClock.elapsedRealtime()
                        callbacks?.onMute()
                    }
                    else -> downInButton = false
                }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val slop = 24f * dp
                if (!downInButton && event.eventTime - downTime < 400L &&
                    abs(event.x - downX) < slop && abs(event.y - downY) < slop
                ) {
                    callbacks?.onTapEmpty()
                }
            }
        }
        return true
    }
}
