package com.discover.embypatcher

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class EmbyUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            val prefs = applicationContext.getSharedPreferences("emby", Context.MODE_PRIVATE)
            val installedMarker = prefs.getString("sha", null) ?: return Result.success()
            val latest = Updater.latestSha(Updater.apkName()) ?: return Result.retry()
            val installedSha = installedMarker.substringBefore("#")
            if (latest != installedSha && prefs.getString("notified_sha", null) != latest) {
                val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (Build.VERSION.SDK_INT >= 26) {
                    manager.createNotificationChannel(NotificationChannel(CHANNEL, applicationContext.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_DEFAULT))
                }
                val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
                    .setSmallIcon(R.drawable.discover_notification)
                    .setContentTitle(applicationContext.getString(R.string.notif_update_title))
                    .setContentText(applicationContext.getString(R.string.notif_update_text))
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .apply { EmbyApp.iconBitmap(applicationContext)?.let { setLargeIcon(it) } }
                    .setAutoCancel(true)
                    .setContentIntent(android.app.PendingIntent.getActivity(
                        applicationContext, 20,
                        android.content.Intent(applicationContext, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP),
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) android.app.PendingIntent.FLAG_IMMUTABLE else 0
                    )).build()
                if (Build.VERSION.SDK_INT < 33 || applicationContext.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    NotificationManagerCompat.from(applicationContext).notify(UPDATE_NOTIFICATION, notification)
                    prefs.edit().putString("notified_sha", latest).apply()
                }
            }
            Result.success()
        } catch (_: Exception) { Result.retry() }
    }

    companion object { const val CHANNEL = "emby_updates"; const val UPDATE_NOTIFICATION = 2401 }
}
