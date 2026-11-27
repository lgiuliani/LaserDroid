package com.laserdroid.service

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Short synthesized notification sounds (no audio files).
 * Played by the service: audible with the screen off.
 */
class SoundPlayer {

    enum class Sound {
        SUCCESS,
        WARNING,
        ERROR,
        CONNECT,
        DISCONNECT,

        /** Program received from LaserGRBL. */
        RECEIVED
    }

    /** Frequency (Hz, 0 = silence) and duration (ms). */
    private class Note(val frequency: Double, val millis: Int)

    companion object {
        private const val SAMPLE_RATE = 22_050
        private const val FADE_MILLIS = 6
        private const val AMPLITUDE = 0.6
    }

    @Volatile
    var enabled: Boolean = true

    private val executor = Executors.newSingleThreadExecutor()
    private val cache = HashMap<Sound, ShortArray>()

    private fun melody(sound: Sound): List<Note> = when (sound) {
        // Ascending major arpeggio.
        Sound.SUCCESS -> listOf(Note(523.3, 120), Note(659.3, 120), Note(784.0, 120), Note(1046.5, 260))
        // Two identical medium beeps.
        Sound.WARNING -> listOf(Note(880.0, 160), Note(0.0, 90), Note(880.0, 160))
        // Descending low tones.
        Sound.ERROR -> listOf(
            Note(466.2, 220), Note(0.0, 60), Note(349.2, 220), Note(0.0, 60), Note(233.1, 420)
        )
        // Short rising pair.
        Sound.CONNECT -> listOf(Note(659.3, 90), Note(987.8, 160))
        // Short falling pair.
        Sound.DISCONNECT -> listOf(Note(987.8, 90), Note(659.3, 200))
        // Three quick high notes.
        Sound.RECEIVED -> listOf(
            Note(1318.5, 80), Note(0.0, 40), Note(1568.0, 80), Note(0.0, 40), Note(2093.0, 160)
        )
    }

    fun play(sound: Sound) {
        if (!enabled) return
        executor.execute { playNow(sound) }
    }

    private fun playNow(sound: Sound) {
        val samples = synchronized(cache) {
            cache.getOrPut(sound) { synthesize(melody(sound)) }
        }

        var track: AudioTrack? = null
        try {
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size * 2)
                .build()

            track.write(samples, 0, samples.size)
            track.play()
            Thread.sleep(samples.size * 1_000L / SAMPLE_RATE + 60L)
        } catch (exception: Exception) {
            // Sound is a convenience: never fail because of it.
        } finally {
            runCatching { track?.stop() }
            runCatching { track?.release() }
        }
    }

    private fun synthesize(notes: List<Note>): ShortArray {
        val total = notes.sumOf { it.millis } * SAMPLE_RATE / 1_000
        val output = ShortArray(total)
        var offset = 0
        for (note in notes) {
            val count = note.millis * SAMPLE_RATE / 1_000
            val fade = FADE_MILLIS * SAMPLE_RATE / 1_000
            if (note.frequency > 0.0) {
                for (index in 0 until count) {
                    val envelope = min(1.0, min(index, count - 1 - index).toDouble() / fade)
                    val value = sin(2.0 * PI * note.frequency * index / SAMPLE_RATE) *
                            AMPLITUDE * envelope
                    output[offset + index] = (value * Short.MAX_VALUE).toInt().toShort()
                }
            }
            offset += count
        }
        return output
    }

    fun close() {
        executor.shutdown()
    }
}
