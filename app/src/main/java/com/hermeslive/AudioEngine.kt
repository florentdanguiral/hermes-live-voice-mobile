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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

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
        // 16 kHz : le STT Voxtral attend du 16 kHz. Envoyer directement du
        // 16 kHz élimine la conversion serveur (24k->16k) et simplifie la
        // chaîne. La lecture TTS reste en 24 kHz (sampleRate du serveur).
        const val OUTPUT_SAMPLE_RATE = 16000
        private const val MIC_CHUNK_BYTES = 2400   // 25 ms @ 48 kHz mono 16 bits
        private const val OUT_FRAME_BYTES = 1600  // 50 ms @ 16 kHz mono 16 bits
        /** AGC doux : cible RMS ~0.03, gain borné [1,0 ; 3,0]. Le gain max
         * agressif (x6) déformait l'audio (STT illisible). x3 suffit pour
         * relever une voix faible sans saturer. */
        private const val AGC_TARGET_RMS = 1000.0 // échelle 16 bits (~0.03 normalisé)
        private const val AGC_MIN_GAIN = 1.0f
        private const val AGC_MAX_GAIN = 3.0f
        /** File de lecture TTS max ~62 s à 24 kHz (3 Mo). Au-delà, on lâche les
         * plus anciennes trames : une réponse interminable ne doit ni faire
         * exploser la mémoire ni bloquer le thread WebSocket. */
        private const val MAX_QUEUED_PLAYBACK_BYTES = 3 * 1024 * 1024
        private const val TAG = "HermesLive.Audio"
    }

    private var record: AudioRecord? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var micThread: Thread? = null
    @Volatile private var micRunning = false

    private var track: AudioTrack? = null
    private var playbackThread: Thread? = null
    private var playbackWriterAlive: AtomicBoolean? = null
    private var totalWritten = 0L
    @Volatile var playbackActive = false
        private set

    // File bornée entre le thread lecteur WebSocket et le thread de lecture.
    // Le serveur envoie toute la réponse TTS en rafale ; AudioTrack.write()
    // bloque quand son buffer est plein, et l'appeler depuis le thread lecteur
    // OkHttp bloquerait le traitement des pongs — le watchdog ping (20 s)
    // tuerait alors la connexion en pleine parole ("sent ping but didn't
    // receive pong"). Ici writePcm() n'est jamais bloquant.
    private val playbackQueue = ConcurrentLinkedQueue<ByteArray>()
    private val queuedBytes = AtomicLong(0)

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
                // Décimation x3 (48 kHz -> 16 kHz) : un échantillon sur trois.
                // 16 kHz = natif STT Voxtral, pas de conversion serveur.
                var src = 0
                while (src + 6 <= n && filled + 2 <= out.size) {
                    out[filled] = mic[src]
                    out[filled + 1] = mic[src + 1]
                    filled += 2
                    src += 6
                }
                if (filled >= out.size) {
                    // AGC désactivé : même avec gain max 3.0, l'AGC déforme
                    // l'audio au point que le STT ne transcrit plus. Gain fixe
                    // 1.0 — parler un peu plus fort/près du micro compense.
                    onFrame(out.copyOf())
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
            maxOf(minBuf, sampleRate * 2), // ~1 s de tampon
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
        playbackQueue.clear()
        queuedBytes.set(0)
        playbackActive = true
        totalWritten = 0
        track?.play()
        startPlaybackWriter()
    }

    /** Thread dédié, seul écrivain de l'AudioTrack : write() bloquant OK ici. */
    private fun startPlaybackWriter() {
        val alive = AtomicBoolean(true)
        playbackWriterAlive = alive
        playbackThread = Thread({
            while (alive.get()) {
                val pcm = playbackQueue.poll()
                if (pcm == null) {
                    Thread.sleep(20)
                    continue
                }
                queuedBytes.addAndGet(-pcm.size.toLong())
                val t = track ?: continue
                try {
                    val written = t.write(pcm, 0, pcm.size)
                    if (written > 0) synchronized(this) { totalWritten += written }
                } catch (_: IllegalStateException) {
                    // Track libéré (barge-in) pendant l'écriture.
                }
            }
        }, "hermes-playback").apply { start() }
    }

    /** Enfile une trame TTS. Appelé depuis le thread lecteur WebSocket :
     * jamais bloquant — l'AudioTrack est écrit par le thread de lecture. */
    fun writePcm(pcm: ByteArray) {
        if (!playbackActive) return
        var queued = queuedBytes.addAndGet(pcm.size.toLong())
        while (queued > MAX_QUEUED_PLAYBACK_BYTES) {
            val dropped = playbackQueue.poll() ?: break
            queued = queuedBytes.addAndGet(-dropped.size.toLong())
        }
        playbackQueue.offer(pcm)
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
                val drained = synchronized(this) {
                    val t = track ?: return@synchronized true
                    playbackQueue.isEmpty() && (t.playbackHeadPosition * 2).toLong() >= totalWritten
                }
                if (drained) break
                Thread.sleep(100)
            }
            stopPlayback()
        }, "hermes-drain").apply { start() }
    }

    /** Coupe la lecture (barge-in). */
    @Synchronized
    fun stopPlayback() {
        playbackActive = false
        playbackWriterAlive?.set(false)
        playbackWriterAlive = null
        playbackQueue.clear()
        queuedBytes.set(0)
        val t = track
        track = null
        if (t != null) {
            try {
                t.pause()
                t.flush()
            } catch (_: IllegalStateException) {
            }
            t.release()
        }
        playbackThread?.join(300)
        playbackThread = null
    }

    fun stopEverything() {
        stopMic()
        stopPlayback()
    }
}
