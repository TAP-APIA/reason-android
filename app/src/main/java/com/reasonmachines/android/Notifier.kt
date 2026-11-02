package com.reasonmachines.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object Notifier {
    const val CHANNEL = "reason_updates"
    const val EXTRA_URL = "com.reasonmachines.android.URL"

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Reason", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Session updates from Reason"
                }
            )
        }
    }

    fun show(ctx: Context, title: String, body: String, tag: String, url: String) {
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return
        val intent = Intent(ctx, MainActivity::class.java).apply {
            putExtra(EXTRA_URL, url)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val id = (tag.ifEmpty { title + body }).hashCode()
        val pi = PendingIntent.getActivity(ctx, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(tag.ifEmpty { null }, id, n)
        } catch (_: SecurityException) { }
    }

    fun cancel(ctx: Context, tag: String) {
        NotificationManagerCompat.from(ctx).cancel(tag, tag.hashCode())
    }
}
