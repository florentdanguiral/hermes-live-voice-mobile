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
 * Wake word « hey hermes » via Vosk (modèle small-fr 16 kHz embarqué, grammaire
 * restreinte). Validé empiriquement : le modèle exige du 16 kHz (le micro
 * capture en 48 kHz, on décime par 3) et le match apparaît dans les résultats
 * PARTIELS (« hey » -> « hermes »), rarement en final.
 *
 * Gate RMS : en silence, Vosk ne reçoit rien (batterie) et l'état partiel est
 * purgé pour éviter les faux matchs résiduels.
 */
class WakeWordDetector(private val context: Context, private val onDetected: () -> Unit) {

    companion object {
        private const val MIC_SAMPLE_RATE = 48000
        private const val VOSK_SAMPLE_RATE = 16000f
        private const val GRAMMAR = """["hey hermes", "hermes", "[unk]"]"""
        private const val CHUNK = 2048 // octets PCM16 @ 48 kHz
        private const val SPEECH_RMS_THRESHOLD = 600.0 // seuil d'entrée Vosk
    }

    private var model: Model? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    fun start() {
        // Un seul thread d'écoute à la fois : si l'arrêt précédent n'a pas eu le
        // temps de finir, on n'ouvre pas un second AudioRecord.
        if (running || thread?.isAlive == true) return
        running = true
        thread = Thread({
            try {
                val m = model ?: loadModel(context).also { model = it }
                val recognizer = Recognizer(m, VOSK_SAMPLE_RATE, GRAMMAR)
                val minBuf = AudioRecord.getMinBufferSize(
                    MIC_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                )
                val rec = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    MIC_SAMPLE_RATE,
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
                var wasSilent = false
                while (running) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    if (rms16(buf, n) < SPEECH_RMS_THRESHOLD) {
                        if (!wasSilent) {
                            recognizer.reset()
                            wasSilent = true
                        }
                        continue
                    }
                    wasSilent = false
                    // Décimation 48 kHz -> 16 kHz (un échantillon sur trois).
                    val samples = n / 2
                    val out = ByteArray((samples / 3) * 2)
                    var o = 0
                    var i = 0
                    while (i + 5 < n) {
                        out[o] = buf[i]
                        out[o + 1] = buf[i + 1]
                        o += 2
                        i += 6
                    }
                    if (recognizer.acceptWaveForm(out, o)) {
                        val final = JSONObject(recognizer.result).optString("text", "")
                        if (matches(final)) {
                            rec.stop()
                            rec.release()
                            onDetected()
                            return@Thread
                        }
                    } else {
                        // Les phrases courtes ne produisent pas de final fiable :
                        // le match vit dans les résultats partiels.
                        val partial = JSONObject(recognizer.partialResult).optString("partial", "")
                        if (matches(partial)) {
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

    /** RMS d'un buffer PCM16 little-endian. */
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

    fun stop() {
        running = false
        // Le thread sort de sa boucle au retour du read (~20 ms) ; on lui laisse
        // le temps de fermer et relâcher l'AudioRecord (sinon le micro reste vert).
        thread?.join(2000)
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
