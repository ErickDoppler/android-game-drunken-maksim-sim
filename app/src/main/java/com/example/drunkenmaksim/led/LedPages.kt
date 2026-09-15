package com.example.drunkenmaksim.led

import android.graphics.Bitmap

/**
 * Pixel-font drawing for the iPixel LED panel, ported from the F16 HUD
 * project: a 5x7 font for headline text and a proportional 3x5 narrow font
 * for the floating chat names and counters — LED matrices are far too small
 * for anti-aliased system fonts. The game renderer (GameEngine) draws with
 * these primitives.
 */
object LedPages {

    const val GREEN = 0xFF00FF46.toInt()
    const val YELLOW = 0xFFFFFF00.toInt()
    const val RED = 0xFFFF2D2D.toInt()
    const val DARK_RED = 0xFF990000.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
    const val GREY = 0xFF8C8C8C.toInt()
    const val SKY = 0xFF78D2FF.toInt()
    const val ORANGE = 0xFFFF9000.toInt()
    const val AMBER = 0xFFFFC400.toInt()
    const val LIME = 0xFF80FF00.toInt()

    fun px(bmp: Bitmap, x: Int, y: Int, color: Int) {
        if (x in 0 until bmp.width && y in 0 until bmp.height) bmp.setPixel(x, y, color)
    }

    fun vline(bmp: Bitmap, x: Int, y0: Int, y1: Int, color: Int) {
        for (y in y0..y1) px(bmp, x, y, color)
    }

    /** The sign-off notice shown when the display is switched off: orange,
     *  centred, stepped down through smaller layouts until one fits. */
    fun screenOff(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(0xFF000000.toInt())
        val full = "SCREEN OFF"
        val top = "SCREEN"
        val tail = "OFF"
        when {
            textWidth(full) <= w && h >= 7 ->
                text(bmp, full, (w - textWidth(full)) / 2, (h - 7) / 2, ORANGE)
            h >= 15 && textWidth(top) <= w -> {
                val y = (h - 15) / 2
                text(bmp, top, (w - textWidth(top)) / 2, y, ORANGE)
                text(bmp, tail, (w - textWidth(tail)) / 2, y + 8, ORANGE)
            }
            narrowWidth(full) <= w && h >= 5 ->
                narrow(bmp, full, (w - narrowWidth(full)) / 2, (h - 5) / 2, ORANGE)
            h >= 11 && narrowWidth(top) <= w -> {
                val y = (h - 11) / 2
                narrow(bmp, top, (w - narrowWidth(top)) / 2, y, ORANGE)
                narrow(bmp, tail, (w - narrowWidth(tail)) / 2, y + 6, ORANGE)
            }
            else ->
                narrow(bmp, tail, (w - narrowWidth(tail)) / 2, (h - 5) / 2, ORANGE)
        }
        return bmp
    }

    // ----------------------------------------------------- 5x7 font plumbing

    fun textWidth(s: String): Int = s.length * 6 - 1

    fun text(bmp: Bitmap, s: String, x0: Int, y0: Int, color: Int) {
        var x = x0
        for (ch in s) {
            val glyph = FONT[ch] ?: FONT['?']!!
            for (col in 0..4) {
                val bits = glyph[col]
                for (row in 0..6) {
                    if (bits and (1 shl row) != 0) px(bmp, x + col, y0 + row, color)
                }
            }
            x += 6
        }
    }

    /** 5x7 text with every font pixel drawn as a [scale] x [scale] block. */
    fun textScaled(bmp: Bitmap, s: String, x0: Int, y0: Int, color: Int, scale: Int) {
        var x = x0
        for (ch in s) {
            val glyph = FONT[ch] ?: FONT['?']!!
            for (col in 0..4) {
                val bits = glyph[col]
                for (row in 0..6) {
                    if (bits and (1 shl row) != 0) {
                        for (dx in 0 until scale) {
                            for (dy in 0 until scale) {
                                px(bmp, x + col * scale + dx, y0 + row * scale + dy, color)
                            }
                        }
                    }
                }
            }
            x += 6 * scale
        }
    }

    // ------------------------------------------------------- 3x5 narrow font
    // Proportional: ':' and '.' take a single column.

    private fun narrowCharW(ch: Char): Int = if (ch == ':' || ch == '.' || ch == '!') 1 else 3

    fun narrowWidth(s: String): Int {
        var w = 0
        for (ch in s) w += narrowCharW(ch) + 1
        return (w - 1).coerceAtLeast(0)
    }

    fun narrow(bmp: Bitmap, s: String, x0: Int, y0: Int, color: Int) {
        var x = x0
        for (ch in s) {
            val glyph = NARROW[ch] ?: NARROW['-']!!
            val cw = narrowCharW(ch)
            val startCol = if (cw == 1) 1 else 0 // 1-wide glyphs live in col 1
            for (i in 0 until cw) {
                val bits = glyph[startCol + i]
                for (row in 0..4) {
                    if (bits and (1 shl row) != 0) px(bmp, x + i, y0 + row, color)
                }
            }
            x += cw + 1
        }
    }

    /** Classic 5x7 font, column bytes, bit 0 = top row. */
    private val FONT: Map<Char, IntArray> = mapOf(
        ' ' to intArrayOf(0x00, 0x00, 0x00, 0x00, 0x00),
        '!' to intArrayOf(0x00, 0x00, 0x5F, 0x00, 0x00),
        '%' to intArrayOf(0x23, 0x13, 0x08, 0x64, 0x62),
        '+' to intArrayOf(0x08, 0x08, 0x3E, 0x08, 0x08),
        ',' to intArrayOf(0x00, 0x50, 0x30, 0x00, 0x00),
        '-' to intArrayOf(0x08, 0x08, 0x08, 0x08, 0x08),
        '.' to intArrayOf(0x00, 0x60, 0x60, 0x00, 0x00),
        '/' to intArrayOf(0x20, 0x10, 0x08, 0x04, 0x02),
        '0' to intArrayOf(0x3E, 0x51, 0x49, 0x45, 0x3E),
        '1' to intArrayOf(0x00, 0x42, 0x7F, 0x40, 0x00),
        '2' to intArrayOf(0x42, 0x61, 0x51, 0x49, 0x46),
        '3' to intArrayOf(0x21, 0x41, 0x45, 0x4B, 0x31),
        '4' to intArrayOf(0x18, 0x14, 0x12, 0x7F, 0x10),
        '5' to intArrayOf(0x27, 0x45, 0x45, 0x45, 0x39),
        '6' to intArrayOf(0x3C, 0x4A, 0x49, 0x49, 0x30),
        '7' to intArrayOf(0x01, 0x71, 0x09, 0x05, 0x03),
        '8' to intArrayOf(0x36, 0x49, 0x49, 0x49, 0x36),
        '9' to intArrayOf(0x06, 0x49, 0x49, 0x29, 0x1E),
        ':' to intArrayOf(0x00, 0x36, 0x36, 0x00, 0x00),
        '?' to intArrayOf(0x02, 0x01, 0x51, 0x09, 0x06),
        'A' to intArrayOf(0x7E, 0x11, 0x11, 0x11, 0x7E),
        'B' to intArrayOf(0x7F, 0x49, 0x49, 0x49, 0x36),
        'C' to intArrayOf(0x3E, 0x41, 0x41, 0x41, 0x22),
        'D' to intArrayOf(0x7F, 0x41, 0x41, 0x22, 0x1C),
        'E' to intArrayOf(0x7F, 0x49, 0x49, 0x49, 0x41),
        'F' to intArrayOf(0x7F, 0x09, 0x09, 0x09, 0x01),
        'G' to intArrayOf(0x3E, 0x41, 0x49, 0x49, 0x7A),
        'H' to intArrayOf(0x7F, 0x08, 0x08, 0x08, 0x7F),
        'I' to intArrayOf(0x00, 0x41, 0x7F, 0x41, 0x00),
        'J' to intArrayOf(0x20, 0x40, 0x41, 0x3F, 0x01),
        'K' to intArrayOf(0x7F, 0x08, 0x14, 0x22, 0x41),
        'L' to intArrayOf(0x7F, 0x40, 0x40, 0x40, 0x40),
        'M' to intArrayOf(0x7F, 0x02, 0x0C, 0x02, 0x7F),
        'N' to intArrayOf(0x7F, 0x04, 0x08, 0x10, 0x7F),
        'O' to intArrayOf(0x3E, 0x41, 0x41, 0x41, 0x3E),
        'P' to intArrayOf(0x7F, 0x09, 0x09, 0x09, 0x06),
        'Q' to intArrayOf(0x3E, 0x41, 0x51, 0x21, 0x5E),
        'R' to intArrayOf(0x7F, 0x09, 0x19, 0x29, 0x46),
        'S' to intArrayOf(0x46, 0x49, 0x49, 0x49, 0x31),
        'T' to intArrayOf(0x01, 0x01, 0x7F, 0x01, 0x01),
        'U' to intArrayOf(0x3F, 0x40, 0x40, 0x40, 0x3F),
        'V' to intArrayOf(0x1F, 0x20, 0x40, 0x20, 0x1F),
        'W' to intArrayOf(0x3F, 0x40, 0x38, 0x40, 0x3F),
        'X' to intArrayOf(0x63, 0x14, 0x08, 0x14, 0x63),
        'Y' to intArrayOf(0x07, 0x08, 0x70, 0x08, 0x07),
        'Z' to intArrayOf(0x61, 0x51, 0x49, 0x45, 0x43),
        '|' to intArrayOf(0x00, 0x00, 0x7F, 0x00, 0x00)
    )

    /** Narrow 3x5 font, column bytes, bit 0 = top row. Full A-Z for the
     *  floating chat names (rendered uppercase). */
    private val NARROW: Map<Char, IntArray> = mapOf(
        ' ' to intArrayOf(0x00, 0x00, 0x00),
        '0' to intArrayOf(0x1F, 0x11, 0x1F),
        '1' to intArrayOf(0x02, 0x1F, 0x00),
        '2' to intArrayOf(0x1D, 0x15, 0x17),
        '3' to intArrayOf(0x15, 0x15, 0x1F),
        '4' to intArrayOf(0x07, 0x04, 0x1F),
        '5' to intArrayOf(0x17, 0x15, 0x1D),
        '6' to intArrayOf(0x1F, 0x15, 0x1D),
        '7' to intArrayOf(0x01, 0x01, 0x1F),
        '8' to intArrayOf(0x1F, 0x15, 0x1F),
        '9' to intArrayOf(0x17, 0x15, 0x1F),
        ':' to intArrayOf(0x00, 0x0A, 0x00),
        '.' to intArrayOf(0x00, 0x10, 0x00),
        '!' to intArrayOf(0x00, 0x17, 0x00),
        '-' to intArrayOf(0x04, 0x04, 0x04),
        '/' to intArrayOf(0x18, 0x04, 0x03),
        '%' to intArrayOf(0x19, 0x04, 0x13),
        '+' to intArrayOf(0x04, 0x0E, 0x04),
        'A' to intArrayOf(0x1E, 0x05, 0x1E),
        'B' to intArrayOf(0x1F, 0x15, 0x0A),
        'C' to intArrayOf(0x1F, 0x11, 0x11),
        'D' to intArrayOf(0x1F, 0x11, 0x0E),
        'E' to intArrayOf(0x1F, 0x15, 0x11),
        'F' to intArrayOf(0x1F, 0x05, 0x01),
        'G' to intArrayOf(0x1F, 0x11, 0x1D),
        'H' to intArrayOf(0x1F, 0x04, 0x1F),
        'I' to intArrayOf(0x11, 0x1F, 0x11),
        'J' to intArrayOf(0x18, 0x10, 0x1F),
        'K' to intArrayOf(0x1F, 0x04, 0x1B),
        'L' to intArrayOf(0x1F, 0x10, 0x10),
        'M' to intArrayOf(0x1F, 0x06, 0x1F),
        'N' to intArrayOf(0x1F, 0x02, 0x1F),
        'O' to intArrayOf(0x0E, 0x11, 0x0E),
        'P' to intArrayOf(0x1F, 0x05, 0x07),
        'Q' to intArrayOf(0x0E, 0x11, 0x1E),
        'R' to intArrayOf(0x1F, 0x05, 0x1A),
        'S' to intArrayOf(0x17, 0x15, 0x1D),
        'T' to intArrayOf(0x01, 0x1F, 0x01),
        'U' to intArrayOf(0x1F, 0x10, 0x1F),
        'V' to intArrayOf(0x0F, 0x10, 0x0F),
        'W' to intArrayOf(0x1F, 0x08, 0x1F),
        'X' to intArrayOf(0x1B, 0x04, 0x1B),
        'Y' to intArrayOf(0x03, 0x1C, 0x03),
        'Z' to intArrayOf(0x19, 0x15, 0x13)
    )
}
