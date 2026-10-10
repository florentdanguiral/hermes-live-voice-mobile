package com.hermeslive

import android.content.Context
import org.json.JSONObject
import org.vosk.Recognizer

/**
 * Raccroché vocal : pendant une session, les trames brutes du micro de la
 * session passent aussi ici (décimation 48 kHz -> 16 kHz). Grammaire
 * restreinte ; les phrases de fin coupent la session sans les mains.
 */
class HangupDetector(private val context: Context, private val onDetected: () -> Unit) {

    companion object {
        private const val VOSK_SAMPLE_RATE = 16000f
        private const val GRAMMAR =
            """["raccroche", "raccrocher", "au revoir hermes", "merci hermes", "stop hermes"]"""
    }

    private var recognizer: Recognizer? = null
    private var gate = PcmGate()
    private var wasSilent = false
    @Volatile var running = false
        private set

    fun start() {
        if (running) return
        try {
            recognizer = Recognizer(ModelStore.get(context), VOSK_SAMPLE_RATE, GRAMMAR)
            gate = PcmGate()
            running = true
        } catch (_: Throwable) {
            recognizer = null
            running = false
        }
    }

    fun stop() {
        running = false
        recognizer = null
    }

    /** Trame brute PCM16 @ 48 kHz du micro de la session. */
    fun feed(pcm48: ByteArray, len: Int) {
        if (!running) return
        val rec = recognizer ?: return
        val rms = rms16(pcm48, len)
        if (!gate.open(rms, System.currentTimeMillis())) {
            if (!wasSilent) {
                rec.reset()
                wasSilent = true
            }
            return
        }
        wasSilent = false
        // Décimation 48 kHz -> 16 kHz (un échantillon sur trois).
        val out = ByteArray((len / 6) * 2)
        var o = 0
        var i = 0
        while (i + 5 < len) {
            out[o] = pcm48[i]
            out[o + 1] = pcm48[i + 1]
            o += 2
            i += 6
        }
        try {
            if (rec.acceptWaveForm(out, o)) {
                if (matches(JSONObject(rec.result).optString("text", ""))) onDetected()
            } else {
                if (matches(JSONObject(rec.partialResult).optString("partial", ""))) onDetected()
            }
        } catch (_: Throwable) {
            // Reconnaissance interrompue pendant l'arrêt : on ignore.
        }
    }

    /** Racines des phrases de fin (accents français neutralisés par le lexique).
     * Validé empiriquement : « au revoir hermes » sort en partial « revoir hermes ». */
    private fun matches(text: String): Boolean {
        val normalized = text.lowercase().replace(Regex("[èéê]"), "e")
        return normalized.contains("raccroch") ||
            normalized.contains("revoir") ||
            normalized.contains("merci herm") ||
            normalized.contains("stop herm")
    }

    private fun rms16(buf: ByteArray, len: Int): Double {
        var sum = 0.0
        var i = 0
        val samples = len / 2
        while (i + 1 < len) {
            val s = ((buf[i + 1].toInt() shl 8) or buf[i].toInt()).toShort().toDouble()
            sum += s * s
            i += 2
        }
        return if (samples == 0) 0.0 else kotlin.math.sqrt(sum / samples)
    }
}
