package com.hermeslive

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder

/**
 * Capture micro 48 kHz mono PCM16 (VOICE_COMMUNICATION pour l'annulation d'écho),
 * décimation x2 vers 24 kHz, trames de 50 ms rendues au consommateur.
 * Lecture : AudioTrack 24 kHz en streaming pour le TTS.
 */
class AudioEngine(private val onFrame: (ByteArray) -> Unit) {

    /** Second consommateur optionnel des trames brutes 48 kHz (raccroché vocal). */
    @Volatile var rawSink: ((ByteArray, Int) -> Unit)? = null

    companion object {
        const val MIC_SAMPLE_RATE = 48000
        const val OUTPUT_SAMPLE_RATE = 24000
        private const val MIC_CHUNK_BYTES = 2400   // 25 ms @ 48 kHz mono 16 bits
        private const val OUT_FRAME_BYTES = 2400  // 50 ms @ 24 kHz mono 16 bits
        /** Gain numérique sur l'audio envoyé au serveur : la voix doit atteindre
         * RMS ~0.03+ côté serveur pour que Voxtral la transcrive — observé en
         * télémétrie : sous ~0.02, le STT ne produit rien. */
        private const val OUTPUT_GAIN = 4.0f
    }

    private var record: AudioRecord? = null
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
                    onFrame(applyGain(out.copyOf()))
                    filled = 0
                }
            }
            try {
                rec.stop()
            } catch (_: IllegalStateException) {
            }
            rec.release()
            record = null
        }, "hermes-mic").apply { start() }
    }

    fun stopMic() {
        micRunning = false
        micThread?.join(500)
        micThread = null
    }

    /** Gain x2 avec protection d'écrêtage, sur les trames PCM16 sortantes. */
    private fun applyGain(pcm: ByteArray): ByteArray {
        val out = ByteArray(pcm.size)
        var i = 0
        while (i + 1 < pcm.size) {
            val s = ((pcm[i + 1].toInt() shl 8) or pcm[i].toInt()).toShort().toInt()
            val g = (s * OUTPUT_GAIN).toInt().coerceIn(-32768, 32767)
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
