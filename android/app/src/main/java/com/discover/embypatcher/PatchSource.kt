package com.discover.embypatcher

import android.content.Context
import java.io.File
import java.io.InputStream

/**
 * Where the files to patch come from. Works everywhere (phone and TV), without a file picker:
 *  1. Built into the patcher: app/src/main/assets/patchfiles/  (always included)
 *  2. Optional extras/overrides on the device: <external app folder>/patchfiles/ (e.g. via adb push)
 * The path inside those folders is the path in the APK, e.g. assets/www/modules/discover-web-loader.js.
 */
object PatchSource {

    private val ignored = setOf(".DS_Store", "Thumbs.db", "desktop.ini")

    fun externalRoot(ctx: Context): File = ctx.getExternalFilesDir(null) ?: ctx.filesDir

    fun externalPatchDir(ctx: Context) = File(externalRoot(ctx), "patchfiles")

    fun collect(ctx: Context): Map<String, () -> InputStream> {
        val out = LinkedHashMap<String, () -> InputStream>()

        fun walkAssets(dir: String, prefix: String) {
            val names = ctx.assets.list(dir) ?: return
            for (n in names) {
                if (n in ignored) continue
                val path = "$dir/$n"
                val sub = ctx.assets.list(path)
                if (sub != null && sub.isNotEmpty()) walkAssets(path, "$prefix$n/")
                else out["$prefix$n"] = { ctx.assets.open(path) }
            }
        }
        walkAssets("patchfiles", "")

        val ext = externalPatchDir(ctx)
        if (ext.isDirectory) {
            ext.walkTopDown().filter { it.isFile && it.name !in ignored }.forEach { f ->
                out[f.relativeTo(ext).path.replace(File.separatorChar, '/')] = { f.inputStream() }
            }
        }
        return out
    }

    /** The line(s) inserted into index.html. A file on the device takes precedence over the built-in one. */
    fun snippet(ctx: Context): String {
        val ext = File(externalRoot(ctx), "index-snippet.html")
        if (ext.isFile) return ext.readText()
        return ctx.assets.open("inject/index-snippet.html").use { String(it.readBytes(), Charsets.UTF_8) }
    }
}
