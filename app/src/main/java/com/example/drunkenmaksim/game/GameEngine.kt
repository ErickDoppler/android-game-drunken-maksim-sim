package com.example.drunkenmaksim.game

import android.graphics.Bitmap
import android.graphics.Color
import com.example.drunkenmaksim.led.LedPages
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Chat-moderator simulator: user names float DVD-logo-style on the iPixel
 * LED panel; the player points a gyro-driven crosshair and hits them with
 * BAN or MUTE from the phone screen.
 *
 * Rules (from the game spec):
 *  - WHITE: neutral. Ban or mute = -100 (mute still mutes it to yellow; a
 *    banned white comes back as GREY with a 20% chance). At least once per
 *    40 s one starts BLINKING: mute it within 20 s for +50, or it turns
 *    red / grey 50-50. The 5th mute of the same white turns it red.
 *  - GREY: sad / newbies. Mute attempt = -50 (stays grey; the 5th mute of
 *    the same grey turns it red). Ban = 0 points. Turns back white after
 *    20 s of being grey.
 *  - RED: aggressive. Ban = +100. Unbanned for 20 s -> dark red for 5 s ->
 *    game over. Mute helps: +10, but each mute of that word shortens its
 *    mute timer by 5 s (20, 15, 10, 5, then unmutable).
 *  - DARK RED specials (oLeg & co): spawn dark red whatever else is on
 *    the panel (they ignore the four-word cap), pay +200 for the ban, and
 *    keep coming back. Leave one unbanned for 15 s and the chat cascades
 *    to red around him (once); leave him for 40 s and the run is over.
 *    They also flood the chat with red spam over the four-word cap, and
 *    those lines stay behind for the player to clean up (see SPAM): oLeg
 *    (BABKI BABKI GDE SUKA BABKI), Kozlenko (LINUX), Konrad (DOOM /
 *    PENTIUM) and LeoLeo (BLAH-BLAH) post every two seconds, neDima
 *    (SERVICE / ZAVTRA / POTOM / REMONT) every three.
 *  - YELLOW: muted; reverts to its previous state when the timer runs out.
 *  - GREEN: admins, appear for 10-15 s per ~2 min window; ban or mute =
 *    -10000 and game over. May unmute yellows or delete reds while present.
 *  - TROLL: a smile appears behind a white name; not muted within 5 s ->
 *    the chat cascades to red and an admin drops in for 5 s, leaving a
 *    red NU VAS NAHER behind when he storms off (same as an ignored
 *    special).
 *  - Time credit 60 s; hovering the beer icon = +30 s and +5% word speed;
 *    hovering the red pause icon freezes movement for 5 s.
 *
 * Endings — an empty panel (not one name left, admins included) ends the
 * session and the score decides it: positive = GOOD (win), zero or less =
 * BAD (a flat zero gets its own YOU ARE USELESS banner). BAD also comes
 * from failing to ban a red in time (dark red running out) and, as
 * BAD_ADMIN, from banning or muting a green admin. Running out of time is
 * NEUTRAL whatever the score. Each ending scrolls its own blinking banner
 * for 5 s before the score appears.
 */
class GameEngine {

    enum class Phase { WAITING, INTRO, READY, PLAYING, OVER }
    enum class Ending { GOOD, BAD, BAD_ADMIN, NEUTRAL }
    enum class Event { BEER, PAUSE, GAME_OVER }

    private enum class St { WHITE, GREY, RED, DARK_RED, YELLOW, GREEN }

    private class Word(
        val name: String,
        var st: St,
        var x: Float,
        var y: Float,
        var dirX: Float,
        var dirY: Float,
        val special: Boolean = false
    ) {
        val label = name.uppercase()
        val w = LedPages.narrowWidth(label)
        var prev = St.WHITE      // state restored when a mute expires
        var since = 0L           // when the current state was entered
        var muteMs = 20_000L     // duration of the current yellow state
        var whiteMutes = 0
        var greyMutes = 0
        var redMutes = 0
        var blinking = false
        var blinkSince = 0L
        var troll = false
        var trollSince = 0L
        var cascaded = false     // a special's 15 s cascade already fired
        var stormsOff = false    // green leaves a red parting shot behind
        var greenUntil = 0L
    }

    companion object {
        val WHITE_NAMES = listOf(
            "Alex", "Chrom", "Vlad", "Simon", "Andy", "Defoz", "Danil",
            "Storm", "Felix", "Dima", "Sasha", "SD", "Nyaksha", "Andriy",
            "Sergey", "Sel", "Weiz", "Vadym", "Yuriy", "Vasyl", "VladMaloy",
            "Sharov", "George"
        )
        val SPECIAL_NAMES = listOf("oLeg", "Kozlenko", "Konrad", "neDima", "LeoLeo")

        /** Specials that flood the chat while they sit unbanned: boss name
         *  -> (spam lines, in order and repeating, ms between them). The
         *  lines are red names over the normal word cap and they stay after
         *  the boss is banned — the moderator cleans up his own chat. */
        val SPAM: Map<String, Pair<List<String>, Long>> = mapOf(
            "oLeg" to (listOf("BABKI", "BABKI", "GDE", "SUKA", "BABKI") to 2000L),
            "Kozlenko" to (listOf("LINUX") to 2000L),
            "Konrad" to (listOf("DOOM", "PENTIUM") to 2000L),
            "LeoLeo" to (listOf("BLAH-BLAH") to 2000L),
            "neDima" to (
                listOf("SERVICE", "ZAVTRA", "POTOM", "REMONT") to 3000L
            )
        )
        val GREEN_NAMES = listOf("Kontiki", "667", "ne667", "SHARIK")

        const val MAX_WORDS = 4          // greens are allowed on top
        const val BASE_SPEED = 0.5f      // px/s: one pixel in two seconds
        const val START_TIME_MS = 60_000L
        const val INTRO_TEXT = "MAKSIM IS THE CHAT ADMIN. IT IS YOU ARE."
        const val INTRO_SPEED = 30f      // px/s rolling text

        /** What an admin leaves behind when a cascade drives him off. */
        const val ADMIN_FAREWELL = "NU VAS NAHER"

        /** One narrow-font digit: 3 px glyph plus its 1 px gap. */
        const val NARROW_DIGIT_W = 4

        /** Game-over banner: one scrolling pass, blinking, then the score. */
        const val BANNER_MS = 5_000L
        const val BANNER_BLINK_MS = 400L
        const val BANNER_DARK_MS = 100L  // dark slice of each blink cycle

        /** The game needs a 96x16-class panel or bigger. */
        fun panelSizeOk(w: Int, h: Int): Boolean = h >= 16 && w * h >= 96 * 16
    }

    /** Fired from tick()/actions on the game-loop thread. */
    @Volatile var listener: ((Event) -> Unit)? = null

    // Panel geometry; the phone mirror renders with the same size. In
    // local mode ("PLAY ANYWAYS" without a panel) this is a virtual field
    // sized to the phone screen's playable area instead.
    @Volatile var panelW = 96
    @Volatile var panelH = 16

    /** True when the game runs on the phone screen only (no LED panel). */
    @Volatile var localMode = false

    @Volatile var phase = Phase.WAITING; private set
    @Volatile var ending = Ending.NEUTRAL; private set
    @Volatile var score = 0; private set
    @Volatile var timeLeftMs = START_TIME_MS; private set

    /** Set while the settings page is open or the activity is paused. */
    @Volatile var paused = false

    private var now = 0L
    private val words = ArrayList<Word>()
    private val whitePool = ArrayList<String>()
    private val comebacks = ArrayList<Pair<String, Long>>() // name -> due time

    private var crossX = 48f
    private var crossY = 8f

    private var speedMul = 1f
    private var frozenUntil = 0L

    // Icons.
    private var beerX = 0
    private var beerY = 0
    private var beerVisibleUntil = 0L
    private var nextBeerAt = 0L
    private var pauseX = 0
    private var pauseY = 0
    private var pauseVisibleUntil = 0L
    private var nextPauseAt = 0L

    // Schedulers.
    private var nextWhiteAt = 0L
    private var nextBlinkAt = 0L
    private var nextTrollAt = 0L
    private var nextSpecialAt = 0L
    private var nextGreenAt = 0L
    private var nextGreenActionAt = 0L
    private var spamAt = 0L
    private var spamIdx = 0

    private var introX = 0f
    private var readyStart = 0L
    private var overStart = 0L

    private fun rndMs(minS: Int, maxS: Int): Long =
        Random.nextInt(minS, maxS + 1) * 1000L

    // -------------------------------------------------------------- lifecycle

    /** Starts the rolling intro; called when the panel connects (and by
     *  restart()). */
    @Synchronized fun startIntro() {
        reset()
        introX = panelW.toFloat()
        phase = Phase.INTRO
    }

    /** Tap during the rolling splash or the READY countdown: straight
     *  into the action. */
    @Synchronized fun skipIntro() {
        if (phase == Phase.INTRO || phase == Phase.READY) startPlay()
    }

    /** Tap on the game-over screen: play again. */
    @Synchronized fun restart() {
        if (phase == Phase.OVER) startIntro()
    }

    private fun reset() {
        score = 0
        timeLeftMs = START_TIME_MS
        speedMul = 1f
        frozenUntil = 0L
        beerVisibleUntil = 0L
        pauseVisibleUntil = 0L
        words.clear()
        comebacks.clear()
        whitePool.clear()
        whitePool.addAll(WHITE_NAMES.shuffled())
        spamAt = 0L
        spamIdx = 0
        crossX = panelW / 2f
        crossY = panelH / 2f
        ending = Ending.NEUTRAL
    }

    private fun startPlay() {
        phase = Phase.PLAYING
        repeat(2) { spawnFromPool() }
        nextWhiteAt = now + 15_000L
        nextBlinkAt = now + rndMs(20, 40)
        nextTrollAt = now + rndMs(50, 90)
        nextSpecialAt = now + rndMs(30, 60)
        nextGreenAt = now + rndMs(60, 120)
        nextBeerAt = now + rndMs(8, 12)
        nextPauseAt = now + rndMs(30, 40)
    }

    private fun over(e: Ending) {
        if (phase == Phase.OVER) return
        phase = Phase.OVER
        ending = e
        overStart = now
        listener?.invoke(Event.GAME_OVER)
    }

    // ------------------------------------------------------------------ tick

    @Synchronized fun tick(dtMs: Long) {
        if (paused || phase == Phase.WAITING) return
        now += dtMs
        when (phase) {
            Phase.INTRO -> {
                introX -= INTRO_SPEED * dtMs / 1000f
                if (introX < -LedPages.textWidth(INTRO_TEXT).toFloat()) {
                    phase = Phase.READY
                    readyStart = now
                }
            }
            Phase.READY -> if (now - readyStart >= 2600L) startPlay()
            Phase.PLAYING -> update(dtMs)
            else -> Unit
        }
    }

    private fun update(dtMs: Long) {
        timeLeftMs -= dtMs
        if (timeLeftMs <= 0L) {
            timeLeftMs = 0L
            over(Ending.NEUTRAL)
            return
        }

        // DVD-style movement with edge bounces (frozen by the pause icon).
        if (now >= frozenUntil) {
            val v = BASE_SPEED * speedMul * dtMs / 1000f
            for (wd in words) {
                wd.x += wd.dirX * v
                wd.y += wd.dirY * v
                val maxX = (panelW - wd.w).toFloat().coerceAtLeast(0f)
                val maxY = (panelH - 5).toFloat().coerceAtLeast(0f)
                if (wd.x < 0f) { wd.x = 0f; wd.dirX = -wd.dirX }
                if (wd.x > maxX) { wd.x = maxX; wd.dirX = -wd.dirX }
                if (wd.y < 0f) { wd.y = 0f; wd.dirY = -wd.dirY }
                if (wd.y > maxY) { wd.y = maxY; wd.dirY = -wd.dirY }
            }
        }

        // Per-word timers and escalations.
        for (wd in ArrayList(words)) {
            when (wd.st) {
                St.WHITE -> {
                    if (wd.troll && now - wd.trollSince > 5_000L) {
                        cascadeToRed()
                        break // every word just changed state
                    }
                    if (wd.blinking && now - wd.blinkSince > 20_000L) {
                        wd.blinking = false
                        setState(wd, if (Random.nextBoolean()) St.RED else St.GREY)
                    }
                }
                St.GREY -> if (now - wd.since > 20_000L) setState(wd, St.WHITE)
                St.RED -> if (now - wd.since > 20_000L) setState(wd, St.DARK_RED)
                St.DARK_RED -> {
                    if (wd.special) {
                        // A special gets 40 s before he ends the run, with a
                        // warning at 15 s: the whole chat turns on Maksim and
                        // an admin drops in to watch him swing the hammer.
                        if (now - wd.since > 40_000L) {
                            over(Ending.BAD)
                            return
                        }
                        if (!wd.cascaded && now - wd.since > 15_000L) {
                            wd.cascaded = true
                            cascadeToRed()
                            break
                        }
                    } else if (now - wd.since > 5_000L) {
                        over(Ending.BAD)
                        return
                    }
                }
                St.YELLOW -> if (now - wd.since > wd.muteMs) revert(wd)
                St.GREEN -> if (now >= wd.greenUntil) {
                    // An admin caught in a cascade does not just leave: he
                    // drops a parting shot where he stood, and THAT can be
                    // banned — an unbannable green lingering on the panel is
                    // what used to make a clean sweep impossible.
                    if (wd.stormsOff) {
                        val shot = spawn(ADMIN_FAREWELL, St.RED)
                        shot.x = wd.x.coerceAtMost(
                            (panelW - shot.w).toFloat().coerceAtLeast(0f)
                        )
                        shot.y = wd.y
                        shot.dirX = wd.dirX
                        shot.dirY = wd.dirY
                    }
                    words.remove(wd)
                }
            }
        }

        // The admin occasionally unmutes a yellow or deletes a red.
        if (words.any { it.st == St.GREEN } && now >= nextGreenActionAt) {
            nextGreenActionAt = now + 4_000L
            if (Random.nextFloat() < 0.3f) {
                if (Random.nextBoolean()) {
                    words.firstOrNull { it.st == St.YELLOW }?.let { revert(it) }
                } else {
                    words.firstOrNull {
                        it.st == St.RED || (it.st == St.DARK_RED && !it.special)
                    }?.let { words.remove(it) }
                }
            }
        }

        // Spawns.
        if (now >= nextWhiteAt) {
            nextWhiteAt = now + 15_000L
            spawnFromPool()
        }
        for (c in comebacks.filter { now >= it.second }) {
            comebacks.remove(c)
            if (regularCount() < MAX_WORDS) spawn(c.first, St.GREY)
            else comebacks.add(c.first to now + 5_000L)
        }
        if (now >= nextBlinkAt) {
            nextBlinkAt = now + rndMs(25, 40)
            words.filter { it.st == St.WHITE && !it.blinking && !it.troll }
                .randomOrNull()?.let {
                    it.blinking = true
                    it.blinkSince = now
                }
        }
        if (now >= nextTrollAt) {
            nextTrollAt = now + rndMs(50, 90)
            words.filter { it.st == St.WHITE && !it.blinking && !it.troll }
                .randomOrNull()?.let {
                    it.troll = true
                    it.trollSince = now
                }
        }
        if (now >= nextSpecialAt) {
            nextSpecialAt = now + rndMs(40, 80)
            // Specials ignore the four-word cap — a flooder barges into a
            // full chat, which is exactly when he hurts most.
            if (words.none { it.special }) {
                spawn(SPECIAL_NAMES.random(), St.DARK_RED, special = true)
            }
        }
        if (now >= nextGreenAt) {
            nextGreenAt = now + rndMs(60, 120)
            if (words.none { it.st == St.GREEN }) spawnGreen()
        }

        // Spamming specials bring their own flood: one more red line every
        // interval until banned, ignoring the normal four-word cap.
        val spam = words.firstNotNullOfOrNull { SPAM[it.name] }
        if (spam != null) {
            val (lines, everyMs) = spam
            if (spamAt == 0L) spamAt = now + everyMs
            if (now >= spamAt) {
                spawn(lines[spamIdx % lines.size], St.RED)
                spamIdx++
                spamAt = now + everyMs
            }
        } else if (spamAt != 0L) {
            // Boss banned: the flood stops, but his lines stay on the panel
            // — cleaning up after a flood is the moderator's own job.
            spamAt = 0L
            spamIdx = 0
        }

        // Beer: +30 s time and +5% word speed on hover.
        if (beerVisibleUntil <= now && now >= nextBeerAt && panelW > 8 && panelH > 8) {
            beerX = Random.nextInt(0, panelW - 6)
            beerY = Random.nextInt(0, panelH - 7 + 1)
            beerVisibleUntil = now + 5_000L
            nextBeerAt = beerVisibleUntil + rndMs(8, 12)
        }
        if (beerVisibleUntil > now &&
            crossX >= beerX - 1 && crossX <= beerX + 5 &&
            crossY >= beerY - 1 && crossY <= beerY + 7
        ) {
            beerVisibleUntil = 0L
            timeLeftMs += 30_000L
            speedMul *= 1.05f
            listener?.invoke(Event.BEER)
        }

        // Pause icon: hovering freezes all movement for 5 s.
        if (pauseVisibleUntil <= now && now >= nextPauseAt && panelW > 8 && panelH > 6) {
            pauseX = Random.nextInt(0, panelW - 5)
            pauseY = Random.nextInt(0, panelH - 5 + 1)
            pauseVisibleUntil = now + 5_000L
            nextPauseAt = pauseVisibleUntil + rndMs(30, 40)
        }
        if (pauseVisibleUntil > now &&
            crossX >= pauseX - 1 && crossX <= pauseX + 5 &&
            crossY >= pauseY - 1 && crossY <= pauseY + 5
        ) {
            pauseVisibleUntil = 0L
            frozenUntil = now + 5_000L
            listener?.invoke(Event.PAUSE)
        }

        // Not a single name left on the panel: the score decides. A cleared
        // chat with points in hand is the win; anything else means the
        // moderating itself was the problem.
        if (words.isEmpty()) {
            over(if (score > 0) Ending.GOOD else Ending.BAD)
        }
    }

    private fun setState(wd: Word, s: St) {
        wd.st = s
        wd.since = now
    }

    /** A mute expired (or the admin lifted it): back to the previous state
     *  with a fresh timer window. */
    private fun revert(wd: Word) = setState(wd, wd.prev)

    /** The chat turns hostile: every non-special word goes red with a
     *  uniform fresh window — 20 s of bright red plus 5 s of dark red —
     *  including the ones that were already red (or even dark red), and an
     *  admin watches the clean-up for 5 s before storming off and leaving
     *  a red NU VAS NAHER where he stood. Triggered by a troll left unmuted
     *  and by a special left unbanned. */
    private fun cascadeToRed() {
        for (wd in words) {
            when (wd.st) {
                St.WHITE, St.GREY, St.YELLOW, St.RED -> {
                    wd.troll = false
                    wd.blinking = false
                    setState(wd, St.RED)
                }
                St.DARK_RED -> if (!wd.special) setState(wd, St.RED)
                St.GREEN -> Unit
            }
        }
        // The admin only watches for 5 s — whether he was already in the
        // chat or turns up for the cascade — then storms off, leaving his
        // parting shot behind.
        val admin = words.firstOrNull { it.st == St.GREEN } ?: spawnGreen()
        admin.greenUntil = now + 5_000L
        admin.stormsOff = true
    }

    // ---------------------------------------------------------------- spawns

    /** Word-count cap; the admin does not occupy a chat slot. */
    private fun regularCount(): Int = words.count { it.st != St.GREEN }

    private fun spawnFromPool() {
        if (regularCount() < MAX_WORDS && whitePool.isNotEmpty()) {
            spawn(whitePool.removeAt(0), St.WHITE)
        }
    }

    private fun spawn(name: String, st: St, special: Boolean = false): Word {
        val w = LedPages.narrowWidth(name.uppercase())
        val x = Random.nextInt(0, (panelW - w).coerceAtLeast(1)).toFloat()
        val y = Random.nextInt(0, (panelH - 5).coerceAtLeast(1)).toFloat()
        val a = Math.toRadians(Random.nextInt(25, 66).toDouble())
        val wd = Word(
            name, st, x, y,
            (cos(a) * if (Random.nextBoolean()) 1 else -1).toFloat(),
            (sin(a) * if (Random.nextBoolean()) 1 else -1).toFloat(),
            special
        )
        wd.since = now
        words.add(wd)
        return wd
    }

    private fun spawnGreen(): Word {
        val wd = spawn(GREEN_NAMES.random(), St.GREEN)
        wd.greenUntil = now + rndMs(10, 15)
        nextGreenActionAt = now + 4_000L
        return wd
    }

    // ---------------------------------------------------------------- input

    /** Gyro-driven crosshair movement in panel pixels. */
    @Synchronized fun moveCross(dx: Float, dy: Float) {
        crossX = (crossX + dx).coerceIn(0f, (panelW - 1).toFloat())
        crossY = (crossY + dy).coerceIn(0f, (panelH - 1).toFloat())
    }

    /** Tap on the empty screen area: crosshair back to the panel center. */
    @Synchronized fun recenter() {
        crossX = panelW / 2f
        crossY = panelH / 2f
    }

    /** The word under the crosshair (last drawn = topmost wins). */
    private fun hitWord(): Word? = words.lastOrNull {
        crossX >= it.x - 1 && crossX <= it.x + it.w + 1 &&
            crossY >= it.y - 1 && crossY <= it.y + 6
    }

    /** BAN button. Returns true when a word was hit. */
    @Synchronized fun ban(): Boolean {
        if (phase != Phase.PLAYING) return false
        val wd = hitWord() ?: return false
        when (wd.st) {
            St.GREEN -> {
                score -= 10_000
                over(Ending.BAD_ADMIN)
            }
            St.WHITE -> {
                score -= 100
                removeBannedWhite(wd)
            }
            St.GREY -> words.remove(wd) // no points for banning newbies
            St.RED -> {
                score += 100
                words.remove(wd)
            }
            St.DARK_RED -> {
                score += if (wd.special) 200 else 100
                words.remove(wd) // specials respawn via their scheduler
            }
            St.YELLOW -> when (wd.prev) {
                St.WHITE -> {
                    score -= 100
                    removeBannedWhite(wd)
                }
                St.GREY -> words.remove(wd)
                else -> { // a muted red is still a red
                    score += 100
                    words.remove(wd)
                }
            }
        }
        return true
    }

    private fun removeBannedWhite(wd: Word) {
        words.remove(wd)
        // 20% of banned whites come back as greys a while later.
        if (Random.nextFloat() < 0.2f) {
            comebacks.add(wd.name to now + rndMs(10, 30))
        }
    }

    /** MUTE button. Returns true when a word was hit and affected. */
    @Synchronized fun mute(): Boolean {
        if (phase != Phase.PLAYING) return false
        val wd = hitWord() ?: return false
        when (wd.st) {
            St.GREEN -> {
                score -= 10_000
                over(Ending.BAD_ADMIN)
            }
            St.YELLOW, St.DARK_RED -> return false // already muted / ban-only
            St.WHITE -> {
                when {
                    wd.troll -> {
                        score += 50
                        wd.troll = false
                    }
                    wd.blinking -> {
                        score += 50
                        wd.blinking = false
                    }
                    else -> score -= 100
                }
                wd.whiteMutes++
                if (wd.whiteMutes >= 5) {
                    setState(wd, St.RED) // the 5th consecutive mute snaps
                } else {
                    wd.prev = St.WHITE
                    wd.muteMs = 20_000L
                    setState(wd, St.YELLOW)
                }
            }
            St.GREY -> {
                // Mute does not take on greys — just the penalty.
                score -= 50
                wd.greyMutes++
                if (wd.greyMutes >= 5) setState(wd, St.RED)
            }
            St.RED -> {
                val dur = 20_000L - 5_000L * wd.redMutes
                if (dur <= 0L) return false // reds become unmutable
                score += 10
                wd.redMutes++
                wd.prev = St.RED
                wd.muteMs = dur
                setState(wd, St.YELLOW)
            }
        }
        return true
    }

    // ------------------------------------------------------------- rendering

    /** Draws the current panel frame; called by the LED hub and the phone
     *  mirror. */
    @Synchronized fun renderFrame(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.BLACK)
        when (phase) {
            Phase.WAITING -> {
                val t = "MAKSIM"
                LedPages.narrow(
                    bmp, t, (w - LedPages.narrowWidth(t)) / 2,
                    ((h - 5) / 2).coerceAtLeast(0), LedPages.YELLOW
                )
            }
            Phase.INTRO -> LedPages.text(
                bmp, INTRO_TEXT, introX.roundToInt(),
                ((h - 7) / 2).coerceAtLeast(0), LedPages.GREEN
            )
            Phase.READY -> {
                val t = now - readyStart
                if (t < 1300L) {
                    val s = "READY?"
                    LedPages.text(
                        bmp, s, (w - LedPages.textWidth(s)) / 2,
                        ((h - 7) / 2).coerceAtLeast(0), LedPages.WHITE
                    )
                } else {
                    val col = if ((t / 150L) % 2L == 0L) LedPages.YELLOW else LedPages.RED
                    val s = "GO!"
                    if (h >= 14 && w >= LedPages.textWidth(s) * 2) {
                        LedPages.textScaled(
                            bmp, s, (w - LedPages.textWidth(s) * 2) / 2,
                            ((h - 14) / 2).coerceAtLeast(0), col, 2
                        )
                    } else {
                        LedPages.text(
                            bmp, s, (w - LedPages.textWidth(s)) / 2,
                            ((h - 7) / 2).coerceAtLeast(0), col
                        )
                    }
                }
            }
            Phase.PLAYING -> {
                if (beerVisibleUntil > now) drawBeer(bmp, beerX, beerY)
                if (pauseVisibleUntil > now) drawPause(bmp, pauseX, pauseY)
                for (wd in words) {
                    // Blinking whites flash on/off at 2 Hz.
                    if (wd.blinking && (now / 250L) % 2L == 0L) continue
                    val col = when (wd.st) {
                        St.WHITE -> LedPages.WHITE
                        St.GREY -> LedPages.GREY
                        St.RED -> LedPages.RED
                        St.DARK_RED -> LedPages.DARK_RED
                        St.YELLOW -> LedPages.YELLOW
                        St.GREEN -> LedPages.GREEN
                    }
                    val x = wd.x.roundToInt()
                    val y = wd.y.roundToInt()
                    LedPages.narrow(bmp, wd.label, x, y, col)
                    if (wd.troll) drawSmile(bmp, x - 6, y)
                }
                // Score top-left (orange), remaining time top-right. On the
                // phone screen the field runs edge to edge, so both counters
                // are pulled three digits inward — rounded corners and curved
                // edges swallow anything sitting in them. The LED panel has
                // square corners and no width to spare, so it keeps the ends.
                val inset = if (localMode) 3 * NARROW_DIGIT_W else 0
                LedPages.narrow(bmp, score.toString(), inset, 0, LedPages.ORANGE)
                val secs = ((timeLeftMs + 999L) / 1000L).toString()
                LedPages.narrow(
                    bmp, secs, w - inset - LedPages.narrowWidth(secs), 0,
                    if (timeLeftMs < 10_000L) LedPages.RED else LedPages.WHITE
                )
                drawCross(bmp)
            }
            Phase.OVER -> {
                val col = endingColor()
                val t = now - overStart
                if (t < BANNER_MS) {
                    // Blinking banner, scrolling one full pass in 5 seconds.
                    val msg = endingBanner()
                    val span = (w + LedPages.textWidth(msg)).toFloat()
                    val x = (w - span * t / BANNER_MS).roundToInt()
                    if (t % BANNER_BLINK_MS >= BANNER_DARK_MS) {
                        LedPages.text(
                            bmp, msg, x, ((h - 7) / 2).coerceAtLeast(0), col
                        )
                    }
                    return bmp
                }
                val s = score.toString()
                if (h >= 14 && w >= LedPages.textWidth(s) * 2) {
                    LedPages.textScaled(
                        bmp, s, (w - LedPages.textWidth(s) * 2) / 2,
                        ((h - 14) / 2).coerceAtLeast(0), col, 2
                    )
                } else {
                    LedPages.text(
                        bmp, s, (w - LedPages.textWidth(s)) / 2,
                        ((h - 7) / 2).coerceAtLeast(0), col
                    )
                }
            }
        }
        return bmp
    }

    /** Win in lime green, both bad endings red, a wash in orange. */
    private fun endingColor(): Int = when (ending) {
        Ending.GOOD -> LedPages.LIME
        Ending.BAD, Ending.BAD_ADMIN -> LedPages.RED
        Ending.NEUTRAL -> LedPages.ORANGE
    }

    private fun endingBanner(): String = when (ending) {
        Ending.GOOD -> "WINNER WINNER HYENA DINNER"
        Ending.BAD_ADMIN -> "ONE MISTAKE AND YOU ARE MISTAKEN!"
        // Losing on a flat zero earns its own verdict.
        Ending.BAD -> if (score == 0) "YOU ARE USELESS" else "GAME OVER! LOOSER!!"
        Ending.NEUTRAL -> "TIME IS UP! GAME OVER!"
    }

    /** 5x7 beer mug: white foam over an amber body with a handle. */
    private fun drawBeer(bmp: Bitmap, x: Int, y: Int) {
        for (c in 0..3) {
            LedPages.px(bmp, x + c, y, LedPages.WHITE)
            LedPages.px(bmp, x + c, y + 1, LedPages.WHITE)
            LedPages.vline(bmp, x + c, y + 2, y + 6, LedPages.AMBER)
        }
        LedPages.px(bmp, x + 4, y + 3, LedPages.AMBER)
        LedPages.px(bmp, x + 4, y + 4, LedPages.AMBER)
    }

    /** 5x5 red pause bars. */
    private fun drawPause(bmp: Bitmap, x: Int, y: Int) {
        for (c in intArrayOf(0, 1, 3, 4)) {
            LedPages.vline(bmp, x + c, y, y + 4, LedPages.RED)
        }
    }

    /** 5x5 troll smiley left of the word. */
    private fun drawSmile(bmp: Bitmap, x: Int, y: Int) {
        for (r in 0..4) {
            for (c in 0..4) {
                val corner = (r == 0 || r == 4) && (c == 0 || c == 4)
                val eye = r == 1 && (c == 1 || c == 3)
                val mouth = r == 3 && c in 1..3
                if (!corner && !eye && !mouth) {
                    LedPages.px(bmp, x + c, y + r, LedPages.YELLOW)
                }
            }
        }
    }

    /** Sky-blue crosshair, drawn on top of everything. */
    private fun drawCross(bmp: Bitmap) {
        val cx = crossX.roundToInt()
        val cy = crossY.roundToInt()
        for (d in -2..2) {
            LedPages.px(bmp, cx + d, cy, LedPages.SKY)
            LedPages.px(bmp, cx, cy + d, LedPages.SKY)
        }
    }
}
