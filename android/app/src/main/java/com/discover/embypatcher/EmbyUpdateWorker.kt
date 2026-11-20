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
        checkPatcher()
        return try {
            val prefs = applicationContext.getSharedPreferences("emby", Context.MODE_PRIVATE)
            val installedMarker = prefs.getString("sha", null) ?: return Result.success()
            val check = Updater.check(Updater.apkName(), installedMarker)
            val latest = check.latest ?: return Result.retry()
            check.newMarker?.let { prefs.edit().putString("sha", it).apply() }
            if (check.current == false && prefs.getString("notified_sha", null) != latest) {
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

    /** A new version of Discover Patcher itself; reported once per version. */
    private fun checkPatcher() {
        try {
            val prefs = applicationContext.getSharedPreferences("emby", Context.MODE_PRIVATE)
            val latest = Updater.latestPatcher() ?: return
            val mine = applicationContext.packageManager.getPackageInfo(applicationContext.packageName, 0).longVersionCode
            if (latest.versionCode <= mine) return
            if (prefs.getLong("notified_patcher", 0L) == latest.versionCode) return
            val name = latest.versionName.ifEmpty { latest.versionCode.toString() }
            notify(
                PATCHER_CHANNEL,
                applicationContext.getString(R.string.notif_patcher_channel),
                applicationContext.getString(R.string.notif_patcher_title),
                applicationContext.getString(R.string.notif_patcher_text, name),
                PATCHER_NOTIFICATION
            )
            prefs.edit().putLong("notified_patcher", latest.versionCode).apply()
        } catch (_: Exception) { }
    }

    private fun notify(channel: String, channelName: String, title: String, text: String, id: Int) {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(channel, channelName, NotificationManager.IMPORTANCE_DEFAULT))
        }
        val notification = NotificationCompat.Builder(applicationContext, channel)
            .setSmallIcon(R.drawable.discover_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(android.app.PendingIntent.getActivity(
                applicationContext, id,
                android.content.Intent(applicationContext, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) android.app.PendingIntent.FLAG_IMMUTABLE else 0
            )).build()
        if (Build.VERSION.SDK_INT < 33 || applicationContext.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            NotificationManagerCompat.from(applicationContext).notify(id, notification)
        }
    }

    companion object {
        const val CHANNEL = "emby_updates"; const val UPDATE_NOTIFICATION = 2401
        const val PATCHER_CHANNEL = "patcher_updates"; const val PATCHER_NOTIFICATION = 2403
    }
}
