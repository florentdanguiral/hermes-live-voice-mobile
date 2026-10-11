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
    private var startedAt = 0L
    @Volatile var running = false
        private set

    fun start() {
        if (running) return
        try {
            recognizer = Recognizer(ModelStore.get(context), VOSK_SAMPLE_RATE, GRAMMAR)
            gate = PcmGate()
            startedAt = System.currentTimeMillis()
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
        // Période de grâce : la fin du « hey hermes » qui a déclenché la session
        // ne doit pas être mappée par la grammaire de raccroché.
        if (System.currentTimeMillis() - startedAt < 2_000) return
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

    /**
     * Phrase COMPLÈTE exigée : la grammaire force toute parole à mapper sur
     * l'une de ses phrases — un matching par racine (« revoir ») déclenchait
     * des faux positifs sur n'importe quelle phrase parlée.
     */
    private fun matches(text: String): Boolean {
        val n = text.lowercase().replace(Regex("[èéê]"), "e")
        return n.contains("raccroche") ||
            n.contains("raccrocher") ||
            n.contains("au revoir hermes") ||
            n.contains("merci hermes") ||
            n.contains("stop hermes")
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
