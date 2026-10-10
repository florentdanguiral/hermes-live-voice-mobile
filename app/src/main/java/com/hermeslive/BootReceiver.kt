package com.hermeslive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Après un reboot, Android interdit le démarrage d'un service micro depuis
 * le background (FGS type microphone). On poste une notification : un tap
 * ouvre l'app, qui relance l'écoute depuis le premier plan (éligible).
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
