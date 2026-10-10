package com.hermeslive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/** Redémarre l'écoute « Hey Hermes » après un reboot si elle était activée. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = context.getSharedPreferences(WakeWordService.PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("wake_enabled", false)) return
        if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        try {
            WakeWordService.start(context)
        } catch (_: Exception) {
            // Android peut restreindre le démarrage background d'un service micro :
            // l'écoute reprendra à la prochaine ouverture de l'app.
        }
    }
}
