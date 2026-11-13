package com.discover.embypatcher

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import java.io.File

object Installer {
    const val ACTION = "com.discover.embypatcher.INSTALL_RESULT"

    /** Lets the receiver show the full result: localized text for the status line, English for the log (a Toast truncates). */
    @Volatile var sink: ((status: String, log: String) -> Unit)? = null

    /** When the patcher is open it shows its own popup with the Emby icon; otherwise it falls back to a Toast. */
    @Volatile var installedListener: (() -> Unit)? = null

    fun install(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("emby.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val intent = Intent(ctx, InstallReceiver::class.java).setAction(ACTION)
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= 31) flags = flags or PendingIntent.FLAG_MUTABLE
            val pending = PendingIntent.getBroadcast(ctx, id, intent, flags)
            session.commit(pending.intentSender)
        }
        // The APK is now in the install session; the cached copy is no longer needed
        // (saves storage on TVs, where "Failed to extract native libs" usually means too little space).
        apk.delete()
    }
}

class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(it)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Installer.sink?.invoke(ctx.getString(R.string.install_success), "Installation complete: Emby was installed successfully.")
                val p = ctx.getSharedPreferences("emby", Context.MODE_PRIVATE)
                p.getString("pending_sha", null)?.let { p.edit().putString("sha", it).remove("pending_sha").remove("notified_sha").apply() }
                val popup = Installer.installedListener
                if (popup != null) popup() else Toast.makeText(ctx, ctx.getString(R.string.popup_installed_title), Toast.LENGTH_LONG).show()
            }
            PackageInstaller.STATUS_FAILURE_CONFLICT -> {
                Installer.sink?.invoke(ctx.getString(R.string.install_conflict), "Install conflict: uninstall the existing Emby app first if it was installed with a different signature. $msg")
                Toast.makeText(
                    ctx,
                    ctx.getString(R.string.install_conflict),
                    Toast.LENGTH_LONG
                ).show()
            }
            else -> {
                val full = "Installation failed ($status): $msg"
                ctx.getSharedPreferences("emby", Context.MODE_PRIVATE).edit().putString("last_error", full).apply()
                val shown = ctx.getString(R.string.install_failed, status, msg)
                Installer.sink?.invoke(shown, full)
                Toast.makeText(ctx, shown, Toast.LENGTH_LONG).show()
            }
        }
    }
}
