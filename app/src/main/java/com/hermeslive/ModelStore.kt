package com.hermeslive

import android.content.Context
import org.vosk.Model
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/** Modèle Vosk small-fr partagé (wake word + raccroché vocal), extrait une seule fois. */
object ModelStore {

    @Volatile private var model: Model? = null

    @Synchronized
    fun get(context: Context): Model {
        model?.let { return it }
        val dir = File(context.filesDir, "vosk-model")
        val marker = File(dir, "README")
        if (!marker.exists()) {
            dir.deleteRecursively()
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
                    val extracted = File(tmp, "vosk-model-small-fr-0.22")
                    if (extracted.exists()) extracted.renameTo(dir) else tmp.renameTo(dir)
                    tmp.deleteRecursively()
                }
            }
        }
        val loaded = Model(dir.absolutePath)
        model = loaded
        return loaded
    }
}
