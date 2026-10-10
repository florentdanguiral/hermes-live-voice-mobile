package com.hermeslive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Après un reboot : si Hermes Live détient le rôle assistant par défaut
 * (ROLE_ASSISTANT), Android autorise le démarrage background du service
 * micro — l'écoute reprend toute seule. Sinon, notification « touche ici »
 * (le démarrage FGS micro depuis le boot reste interdit aux autres apps).
 */
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
        val roleManager = context.getSystemService(RoleManager::class.java)
        val isAssistant = roleManager != null &&
            roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT) &&
            roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)
        if (isAssistant) {
            try {
                WakeWordService.start(context)
                return
            } catch (_: Exception) {
                // Chute sur la notification ci-dessous.
            }
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                WakeWordService.CHANNEL_ID,
                context.getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        val openApp = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        manager.notify(
            2,
            Notification.Builder(context, WakeWordService.CHANNEL_ID)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(context.getString(R.string.notification_tap_to_listen))
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentIntent(openApp)
                .setAutoCancel(true)
                .build(),
        )
    }
}
