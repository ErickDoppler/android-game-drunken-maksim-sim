package com.example.drunkenmaksim.game

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Game sound effects, synthesized at first run (no audio assets needed):
 * breaking glass for the banhammer, a whip crack for mute, a bottle-cap pop
 * for the beer pickup and a ding for the pause pickup. Rendered as small
 * WAV files in the cache dir and played through a SoundPool.
 */
class SoundFx(private val context: Context) {

    private companion object {
        const val RATE = 22050
    }

    private val pool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val glassId = load("fx_glass", synthGlass())
    private val whipId = load("fx_whip", synthWhip())
    private val popId = load("fx_pop", synthPop())
    private val dingId = load("fx_ding", synthDing())

    fun glass() = play(glassId)
    fun whip() = play(whipId)
    fun pop() = play(popId)
    fun ding() = play(dingId)

    fun release() = pool.release()

    private fun play(id: Int) {
        pool.play(id, 1f, 1f, 1, 0, 1f)
    }

    // -------------------------------------------------------------- synthesis

    /** Breaking glass: a noise burst plus jittery high partials. */
    private fun synthGlass(): ShortArray {
        val partials = FloatArray(6) { Random.nextInt(2400, 7800).toFloat() }
        val decays = FloatArray(6) { 8f + Random.nextFloat() * 6f }
        return synth(0.4f) { t ->
            var v = (Random.nextFloat() * 2f - 1f) * 0.8f * exp(-t * 18f)
            for (i in partials.indices) {
                v += 0.25f * sin(2f * PI.toFloat() * partials[i] * t) * exp(-t * decays[i])
            }
            v
        }
    }

    /** Whip crack: a fast noise snap with a falling sweep. */
    private fun synthWhip(): ShortArray = synth(0.2f) { t ->
        val attack = (t / 0.02f).coerceAtMost(1f)
        val noise = (Random.nextFloat() * 2f - 1f) * attack * exp(-t * 30f)
        val sweep = sin(2f * PI.toFloat() * (1400f - 5500f * t) * t) * exp(-t * 18f) * 0.5f
        noise + sweep
    }

    /** Bottle-cap pop: a short low thump with a click on top. */
    private fun synthPop(): ShortArray = synth(0.09f) { t ->
        val thump = sin(2f * PI.toFloat() * 220f * t) * exp(-t * 50f)
        val click = if (t < 0.006f) (Random.nextFloat() * 2f - 1f) * 0.6f else 0f
        thump + click
    }

    /** Ding: a decaying bell tone with one harmonic. */
    private fun synthDing(): ShortArray = synth(0.6f) { t ->
        sin(2f * PI.toFloat() * 1568f * t) * exp(-t * 6f) +
            0.4f * sin(2f * PI.toFloat() * 3136f * t) * exp(-t * 9f)
    }

    /** Renders [f] over [durS] seconds, normalized to 90% full scale. */
    private fun synth(durS: Float, f: (Float) -> Float): ShortArray {
        val n = (durS * RATE).toInt()
        val raw = FloatArray(n) { f(it.toFloat() / RATE) }
        var peak = 1e-6f
        for (v in raw) if (abs2(v) > peak) peak = abs2(v)
        return ShortArray(n) { (raw[it] / peak * 0.9f * Short.MAX_VALUE).toInt().toShort() }
    }

    private fun abs2(v: Float) = if (v < 0f) -v else v

    private fun load(name: String, samples: ShortArray): Int {
        val file = File(context.cacheDir, "$name.wav")
        file.writeBytes(wav(samples))
        return pool.load(file.absolutePath, 1)
    }

    /** 16-bit mono PCM WAV bytes. */
    private fun wav(samples: ShortArray): ByteArray {
        val dataLen = samples.size * 2
        val buf = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray())
        buf.putInt(36 + dataLen)
        buf.put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray())
        buf.putInt(16)
        buf.putShort(1)              // PCM
        buf.putShort(1)              // mono
        buf.putInt(RATE)
        buf.putInt(RATE * 2)         // byte rate
        buf.putShort(2)              // block align
        buf.putShort(16)             // bits per sample
        buf.put("data".toByteArray())
        buf.putInt(dataLen)
        for (s in samples) buf.putShort(s)
        return buf.array()
    }
}
