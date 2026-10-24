package com.hermeslive

/**
 * Gate adaptatif pour Vosk : n'ouvre le flux que pendant une phrase probable.
 * Le seuil suit le bruit de fond (EMA) — efficace en environnement calme ou
 * bruyant ; une fois déclenché, toute la phrase est transmise pendant 1,5 s
 * (les syllabes faibles ne sont plus coupées en plein milieu).
 *
 * [sensitivityFactor] : multiplicateur du plancher de bruit. 3.0 = normal,
 * 4.5 = mode bruyant (moins sensible, moins de faux positifs sur le bruit
 * ambiant type eau qui bout ou route).
 */
internal class PcmGate(private val sensitivityFactor: Double = 3.0) {
    private var noiseFloor = 250.0
    private var activeUntil = 0L

    fun open(rms: Double, nowMs: Long): Boolean {
        if (activeUntil > nowMs) return true
        if (rms > maxOf(320.0, noiseFloor * sensitivityFactor)) {
            activeUntil = nowMs + 1_500
            return true
        }
        noiseFloor = 0.95 * noiseFloor + 0.05 * rms.coerceAtLeast(50.0)
        return false
    }
}
