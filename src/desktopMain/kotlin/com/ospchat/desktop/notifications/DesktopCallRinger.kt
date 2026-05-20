package com.ospchat.desktop.notifications

import com.ospchat.shared.data.calls.Call
import com.ospchat.shared.notifications.CallNotifier
import com.ospchat.shared.util.Log
import java.io.ByteArrayInputStream
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import kotlin.math.PI
import kotlin.math.sin

/**
 * Desktop [CallNotifier] that loops a synthesized ringtone via
 * `javax.sound.sampled.Clip`. The ringtone is generated programmatically at
 * first use so we don't need a bundled WAV resource (and don't have to deal
 * with Compose Desktop's classloader-related resource quirks documented in
 * `EmojiFont.kt`).
 *
 * The tone is a 2-second pattern (440 Hz beep — beep — silence) looped
 * indefinitely until [cancel] is called. One ringtone instance / call at
 * a time; we cancel any prior clip before starting a new one.
 */
class DesktopCallRinger : CallNotifier {
    // (activeClip, activeCallId) is mutated as a pair; the @Synchronized on
    // every public method (and the private helper) prevents the torn-state
    // window where one field is updated before the other.
    private var activeClip: Clip? = null
    private var activeCallId: String? = null

    @Synchronized
    override fun notifyIncomingCall(call: Call) {
        cancelActiveClipLocked()
        runCatching {
            val clip = AudioSystem.getClip()
            clip.open(buildRingtoneStream())
            clip.loop(Clip.LOOP_CONTINUOUSLY)
            activeClip = clip
            activeCallId = call.id
        }.onFailure { Log.w(TAG, "ringtone start failed", it) }
    }

    @Synchronized
    override fun cancel(callId: String) {
        // Only cancel if the ringing clip matches; a stale cancel for a prior
        // call shouldn't silence a fresher one.
        if (activeCallId != callId) return
        cancelActiveClipLocked()
        activeCallId = null
    }

    private fun cancelActiveClipLocked() {
        val clip = activeClip ?: return
        runCatching {
            clip.stop()
            clip.close()
        }
        activeClip = null
    }

    /**
     * Synthesizes the ringtone PCM bytes and wraps them in an
     * [AudioInputStream]. PCM 16-bit mono at 22050 Hz — the smallest viable
     * format that doesn't sound terrible. The pattern repeats every 2 s:
     * 400 ms tone, 200 ms silence, 400 ms tone, 1000 ms silence.
     */
    private fun buildRingtoneStream(): AudioInputStream {
        val sampleRate = 22_050
        val format = AudioFormat(sampleRate.toFloat(), 16, 1, true, false)
        val pcm = ByteArray(sampleRate * 2 * 2) // 2 seconds of 16-bit mono samples

        fun writeSample(
            i: Int,
            sample: Short,
        ) {
            val byteIndex = i * 2
            pcm[byteIndex] = (sample.toInt() and 0xff).toByte()
            pcm[byteIndex + 1] = ((sample.toInt() shr 8) and 0xff).toByte()
        }

        val toneStart1 = 0
        val toneEnd1 = (sampleRate * 0.4).toInt()
        val toneStart2 = (sampleRate * 0.6).toInt()
        val toneEnd2 = (sampleRate * 1.0).toInt()
        val totalSamples = sampleRate * 2
        for (i in 0 until totalSamples) {
            val inTone1 = i in toneStart1 until toneEnd1
            val inTone2 = i in toneStart2 until toneEnd2
            if (inTone1 || inTone2) {
                val amplitude = (Short.MAX_VALUE * 0.4).toInt()
                val sample = (amplitude * sin(2.0 * PI * 440.0 * i / sampleRate)).toInt().toShort()
                writeSample(i, sample)
            } else {
                writeSample(i, 0)
            }
        }
        return AudioInputStream(ByteArrayInputStream(pcm), format, totalSamples.toLong())
    }

    private companion object {
        const val TAG = "DesktopCallRinger"
    }
}
