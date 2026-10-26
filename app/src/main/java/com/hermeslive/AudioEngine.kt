package com.hermeslive

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.util.Log

/**
 * Capture micro 48 kHz mono PCM16 (VOICE_COMMUNICATION pour l'annulation d'écho),
 * décimation x2 vers 24 kHz, trames de 50 ms rendues au consommateur.
 * Lecture : AudioTrack 24 kHz en streaming pour le TTS.
 *
 * NoiseSuppressor : réduction de bruit côté Android (quand disponible sur
 * l'appareil) pour les environnements bruyants (cuisine, route). Activé
 * via [noiseSuppression].
 */
class AudioEngine(private val onFrame: (ByteArray) -> Unit) {

    /** Second consommateur optionnel des trames brutes 48 kHz (raccroché vocal). */
    @Volatile var rawSink: ((ByteArray, Int) -> Unit)? = null

    /** Active la réduction de bruit Android (NoiseSuppressor) sur le micro.
     * DÉSACTIVÉ par défaut : sur certains appareils (GrapheneOS), le
     * NoiseSuppressor avec AudioSource.MIC produit un audio illisible pour
     * le STT (transcription vide). À réactiver manuellement si besoin. */
    @Volatile var noiseSuppression: Boolean = false

    companion object {
        const val MIC_SAMPLE_RATE = 48000
        const val OUTPUT_SAMPLE_RATE = 24000
        private const val MIC_CHUNK_BYTES = 2400   // 25 ms @ 48 kHz mono 16 bits
        private const val OUT_FRAME_BYTES = 2400  // 50 ms @ 24 kHz mono 16 bits
        /** AGC : cible RMS ~0.05 côté serveur (zone où le STT transcrit).
         * Le gain fixe x4 saturait quand on parle près du téléphone (pics à
         * pleine échelle constatés en télémétrie) ; l'AGC adapte le gain à la
         * voix observée, borné [1,0 ; 6,0]. */
        private const val AGC_TARGET_RMS = 1600.0 // échelle 16 bits (~0.05 normalisé)
        private const val AGC_MIN_GAIN = 1.0f
        private const val AGC_MAX_GAIN = 6.0f
        private const val TAG = "HermesLive.Audio"
    }

    private var record: AudioRecord? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var micThread: Thread? = null
    @Volatile private var micRunning = false

    private var track: AudioTrack? = null
    private var totalWritten = 0L
    @Volatile var playbackActive = false
        private set

    @SuppressLint("MissingPermission") // permission vérifiée par l'appelant
    fun startMic() {
        if (micRunning) return
        micRunning = true
        micThread = Thread({
            val minBuf = AudioRecord.getMinBufferSize(
                MIC_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            val rec = AudioRecord(
                // Source MIC (et non VOICE_COMMUNICATION) : l'AEC/NS de
                // VOICE_COMMUNICATION écrase le signal — télémétrie : le wake
                // word (source MIC) est reconnu à distance là où l'audio de
                // session n'était pas transcrit par Voxtral.
                MediaRecorder.AudioSource.MIC,
                MIC_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, MIC_CHUNK_BYTES * 4),
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                micRunning = false
                return@Thread
            }
            record = rec
            // Réduction de bruit Android : améliore le SNR en environnement
            // bruyant (eau qui bout, route). Disponibilité non garantie sur
            // tous les appareils — on dégrade silencieusement si absent.
            if (noiseSuppression) {
                try {
                    if (NoiseSuppressor.isAvailable()) {
                        val ns = NoiseSuppressor.create(rec.audioSessionId)
                        if (ns != null) {
                            ns.enabled = true
                            noiseSuppressor = ns
                            Log.i(TAG, "NoiseSuppressor activé (session ${rec.audioSessionId})")
                        }
                    } else {
                        Log.i(TAG, "NoiseSuppressor non disponible sur cet appareil")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "NoiseSuppressor échec: ${e.message}")
                }
            }
            rec.startRecording()
            val mic = ByteArray(MIC_CHUNK_BYTES)
            val out = ByteArray(OUT_FRAME_BYTES)
            var filled = 0
            while (micRunning) {
                val n = rec.read(mic, 0, mic.size)
                if (n <= 0) continue
                rawSink?.invoke(mic, n)
                // Décimation x2 (48 kHz -> 24 kHz) : un échantillon sur deux.
                var src = 0
                while (src + 4 <= n && filled + 2 <= out.size) {
                    out[filled] = mic[src]
                    out[filled + 1] = mic[src + 1]
                    filled += 2
                    src += 4
                }
                if (filled >= out.size) {
                    onFrame(applyAgc(out.copyOf()))
                    filled = 0
                }
            }
            try {
                rec.stop()
            } catch (_: IllegalStateException) {
            }
            rec.release()
            record = null
            try {
                noiseSuppressor?.release()
            } catch (_: Exception) {
            }
            noiseSuppressor = null
        }, "hermes-mic").apply { start() }
    }

    fun stopMic() {
        micRunning = false
        micThread?.join(500)
        micThread = null
    }

    /** AGC doux : la voix observée (RMS EMA) pilote le gain, borné et lissé. */
    private var agcSpeechRms = 0.0
    private var agcGain = 2.0f

    private fun applyAgc(pcm: ByteArray): ByteArray {
        // RMS de la trame (échelle 16 bits)
        var sum = 0.0
        var i = 0
        val samples = pcm.size / 2
        while (i + 1 < pcm.size) {
            val s = ((pcm[i + 1].toInt() shl 8) or pcm[i].toInt()).toShort().toDouble()
            sum += s * s
            i += 2
        }
        val rms = if (samples == 0) 0.0 else kotlin.math.sqrt(sum / samples)
        // La EMA ne suit que les trames significatives (parole), pas le silence.
        if (rms > 400) {
            agcSpeechRms = 0.9 * agcSpeechRms + 0.1 * rms
        }
        if (agcSpeechRms > 100) {
            val wanted = (AGC_TARGET_RMS / agcSpeechRms).toFloat().coerceIn(AGC_MIN_GAIN, AGC_MAX_GAIN)
            agcGain = 0.95f * agcGain + 0.05f * wanted
        }
        val out = ByteArray(pcm.size)
        i = 0
        while (i + 1 < pcm.size) {
            val s = ((pcm[i + 1].toInt() shl 8) or pcm[i].toInt()).toShort().toInt()
            val g = (s * agcGain).toInt().coerceIn(-32768, 32767)
            out[i] = (g and 0xFF).toByte()
            out[i + 1] = ((g shr 8) and 0xFF).toByte()
            i += 2
        }
        return out
    }

    /** Démarre la lecture TTS à la fréquence demandée par le serveur. */
    @Synchronized
    fun startPlayback(sampleRate: Int) {
        if (track != null) return
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        track = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
            maxOf(minBuf, OUT_FRAME_BYTES * 8),
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
        playbackActive = true
        totalWritten = 0
        track?.play()
    }

    @Synchronized
    fun writePcm(pcm: ByteArray) {
        val written = track?.write(pcm, 0, pcm.size) ?: return
        if (written > 0) totalWritten += written
    }

    /**
     * À appeler quand le serveur a terminé d'envoyer l'audio (response.completed) :
     * attend que l'AudioTrack ait effectivement joué tout ce qui est en file,
     * puis libère le track pour rouvrir le micro (half-duplex).
     */
    fun awaitDrainThenStop() {
        if (!playbackActive) return
        Thread({
            while (true) {
                val done = synchronized(this) {
                    val t = track ?: return@synchronized false
                    (t.playbackHeadPosition * 2).toLong() >= totalWritten
                }
                if (done) break
                Thread.sleep(100)
            }
            stopPlayback()
        }, "hermes-drain").apply { start() }
    }

    /** Coupe la lecture (barge-in). */
    @Synchronized
    fun stopPlayback() {
        val t = track ?: return
        try {
            t.pause()
            t.flush()
        } catch (_: IllegalStateException) {
        }
        t.release()
        track = null
        playbackActive = false
    }

    fun stopEverything() {
        stopMic()
        stopPlayback()
    }
}
