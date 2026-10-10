package com.hermeslive

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * Wake word « hey hermes » via Vosk (modèle small-fr embarqué, grammaire restreinte).
 * Écoute le micro à 48 kHz tant que le détecteur est actif ; déclenche le callback
 * au premier match, puis se met en veille jusqu'à stop()/start().
 */
class WakeWordDetector(private val context: Context, private val onDetected: () -> Unit) {

    companion object {
        private const val SAMPLE_RATE = 48000
        private const val GRAMMAR = """["hey hermes", "hermes", "[unk]"]"""
        private const val CHUNK = 2048 // octets, 16 bits mono
    }

    private var model: Model? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        thread = Thread({
            try {
                val m = model ?: loadModel(context).also { model = it }
                val recognizer = Recognizer(m, SAMPLE_RATE.toFloat(), GRAMMAR)
                val minBuf = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                )
                val rec = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuf, CHUNK * 4),
                )
                if (rec.state != AudioRecord.STATE_INITIALIZED) {
                    running = false
                    return@Thread
                }
                rec.startRecording()
                val buf = ByteArray(CHUNK)
                while (running) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    if (recognizer.acceptWaveForm(buf, n)) {
                        val text = JSONObject(recognizer.result).optString("text", "")
                        if (matches(text)) {
                            rec.stop()
                            rec.release()
                            onDetected()
                            return@Thread
                        }
                    }
                }
                try {
                    rec.stop()
                } catch (_: IllegalStateException) {
                }
                rec.release()
            } catch (_: Throwable) {
                // Modèle absent ou micro indisponible : le wake word se désactive silencieusement.
            } finally {
                running = false
            }
        }, "hermes-wake").apply { start() }
    }

    /** Le lexique FR peut écrire « hermès », « hermés »… on matche la racine. */
    private fun matches(text: String): Boolean {
        val normalized = text.lowercase().replace(Regex("[èéê]"), "e")
        return normalized.contains("herm")
    }

    fun stop() {
        running = false
        thread?.join(1000)
        thread = null
    }

    /** Extrait le modèle zippé des assets vers filesDir, une seule fois. */
    private fun loadModel(context: Context): Model {
        val dir = File(context.filesDir, "vosk-model")
        val marker = File(dir, "README")
        if (!marker.exists()) {
            dir.deleteRecursively()
            dir.parentFile?.mkdirs()
            context.assets.open("vosk-model-small-fr-0.22.zip").use { input ->
                ZipInputStream(input).use { zip ->
                    var entry = zip.nextEntry
                    val tmp = File(context.filesDir, "vosk-extract")
                    tmp.deleteRecursively()
                    tmp.mkdirs()
                    while (entry != null) {
                        val out = File(tmp, entry.name)
                        if (entry.isDirectory) {
                            out.mkdirs()
                        } else {
                            out.parentFile?.mkdirs()
                            FileOutputStream(out).use { zip.copyTo(it) }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                    // L'archive contient un dossier racine vosk-model-small-fr-0.22
                    val extracted = File(tmp, "vosk-model-small-fr-0.22")
                    if (extracted.exists()) extracted.renameTo(dir) else tmp.renameTo(dir)
                    tmp.deleteRecursively()
                }
            }
        }
        return Model(dir.absolutePath)
    }
}
