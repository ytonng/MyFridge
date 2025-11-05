package com.example.myfridge.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.myfridge.MainActivity
import com.example.myfridge.R
import com.example.myfridge.FridgeItemDisplay

object ExpiryNotifier {
    private const val CHANNEL_ID = "expiry_alerts"
    private const val CHANNEL_NAME = "Expiry Alerts"
    private const val CHANNEL_DESC = "Notifications for items nearing expiry"
    private const val NOTIFICATION_ID = 1001

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val existing = manager.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_DEFAULT
                )
                channel.description = CHANNEL_DESC
                manager.createNotificationChannel(channel)
            }
        }
    }

    fun notifyExpiringSoon(context: Context, soonCount: Int, preview: List<FridgeItemDisplay>) {
        try {
            ensureChannel(context)

            val managerCompat = NotificationManagerCompat.from(context)
            if (!managerCompat.areNotificationsEnabled()) {
                Log.w("ExpiryNotifier", "Notifications disabled; skipping expiring soon alert")
                return
            }

            val intent = Intent(context, MainActivity::class.java)
            val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
            val pendingIntent = PendingIntent.getActivity(context, 0, intent, pendingFlags)

            val title = "Expiring soon"
            val content = if (soonCount <= 0) {
                "No items expiring within 7 days"
            } else {
                "${soonCount} item${if (soonCount == 1) "" else "s"} expiring within 7 days"
            }

            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_food)
                .setContentTitle(title)
                .setContentText(content)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)

            if (preview.isNotEmpty()) {
                val lines = preview.take(3).map { item ->
                    val date = item.expiredDate ?: ""
                    "${item.name} ${if (date.isNotBlank()) "- $date" else ""}"
                }
                val style = NotificationCompat.InboxStyle()
                lines.forEach { style.addLine(it) }
                builder.setStyle(style)
            }

            if (androidx.core.app.ActivityCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                managerCompat.notify(NOTIFICATION_ID, builder.build())
                Log.d("ExpiryNotifier", "Posted expiring soon notification: soonCount=$soonCount preview=${preview.size}")
            } else {
                Log.w("ExpiryNotifier", "Notification permission not granted")
            }
        } catch (e: Exception) {
            Log.w("ExpiryNotifier", "Failed to post notification: ${e.message}")
        }
    }
}