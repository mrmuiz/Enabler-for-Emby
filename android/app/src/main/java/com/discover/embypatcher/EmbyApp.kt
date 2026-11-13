package com.discover.embypatcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import java.io.File

/** Which Emby app is on this device. */
object EmbyApp {
    const val PACKAGE = "com.mb.android"

    enum class Kind {
        /** No Emby app on this device. */
        NOT_INSTALLED,
        /** Emby is installed, but with a different signature (official app or a patch with another key). */
        FOREIGN,
        /** Emby was built by this patcher (same key), so updates install over it. */
        PATCHED
    }

    data class State(val kind: Kind, val versionName: String?)

    @Suppress("DEPRECATION")
    fun state(ctx: Context): State {
        val pm = ctx.packageManager
        val info = try {
            if (Build.VERSION.SDK_INT >= 28) pm.getPackageInfo(PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES)
            else pm.getPackageInfo(PACKAGE, PackageManager.GET_SIGNATURES)
        } catch (_: PackageManager.NameNotFoundException) {
            return State(Kind.NOT_INSTALLED, null)
        }
        val signers: List<ByteArray> = try {
            if (Build.VERSION.SDK_INT >= 28) {
                val si = info.signingInfo
                val sigs: Array<android.content.pm.Signature>? = if (si == null) null
                    else if (si.hasMultipleSigners()) si.apkContentsSigners
                    else si.signingCertificateHistory
                sigs.orEmpty().map { it.toByteArray() }
            } else info.signatures.orEmpty().map { it.toByteArray() }
        } catch (_: Exception) { emptyList() }
        val mine = File(ctx.filesDir, "sign.crt").takeIf { it.isFile }?.readBytes()
        val patched = mine != null && signers.any { it.contentEquals(mine) }
        return State(if (patched) Kind.PATCHED else Kind.FOREIGN, info.versionName)
    }

    /** The real Emby icon from the installed app (we do not ship an Emby logo ourselves). */
    fun icon(ctx: Context): Drawable? = try { ctx.packageManager.getApplicationIcon(PACKAGE) } catch (_: Exception) { null }

    fun iconBitmap(ctx: Context, sizePx: Int = 128): Bitmap? {
        val d = icon(ctx) ?: return null
        if (d is BitmapDrawable && d.bitmap != null) return d.bitmap
        return try {
            Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).also { bmp ->
                val c = Canvas(bmp); d.setBounds(0, 0, sizePx, sizePx); d.draw(c)
            }
        } catch (_: Exception) { null }
    }

    fun launchIntent(ctx: Context): Intent? =
        ctx.packageManager.getLeanbackLaunchIntentForPackage(PACKAGE)
            ?: ctx.packageManager.getLaunchIntentForPackage(PACKAGE)

    /** Opens the Android "Uninstall app" dialog for Emby (the user confirms). */
    @Suppress("DEPRECATION")
    fun uninstallIntent(): Intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:$PACKAGE"))
        .putExtra(Intent.EXTRA_RETURN_RESULT, false)

    fun appDetailsIntent(): Intent =
        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$PACKAGE"))
}
