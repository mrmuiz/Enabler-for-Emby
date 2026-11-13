package com.discover.embypatcher

import android.os.Build
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object Updater {
    // The official Android folder holds the current Emby build (including the beta).
    const val BASE = "https://github.com/MediaBrowser/Emby.Releases/raw/master/android/"
    private const val API_BASE = "https://api.github.com/repos/MediaBrowser/Emby.Releases/commits?path=android/"

    /** Picks the right APK variant for this device. */
    fun apkName(): String {
        val known = setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        val abi = Build.SUPPORTED_ABIS.firstOrNull { it in known } ?: "armeabi-v7a"
        return "emby-android-google-$abi-release.apk"
    }

    private fun github(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15000
        readTimeout = 30000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "DiscoverPatcher")
        setRequestProperty("Accept", "application/vnd.github+json")
    }

    /** Uses the last commit of the APK file as the version check for the beta. */
    fun latestSha(name: String): String? = try {
        val c = github(API_BASE + name + "&per_page=1")
        try {
            if (c.responseCode != 200) null else {
                val commits = JSONArray(c.inputStream.bufferedReader().use { it.readText() })
                if (commits.length() == 0) null else commits.getJSONObject(0).getJSONObject("sha").let { it.toString().trim('"') }
            }
        } finally { c.disconnect() }
    } catch (_: Exception) { null }

    /** Always downloads the APK straight from the official Emby Android folder. */
    fun downloadUrl(name: String): String = BASE + name

    fun download(url: String, dest: File, onProgress: (Int) -> Unit = {}) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 60000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "DiscoverPatcher")
        try {
            if (c.responseCode != 200) error("Download failed: HTTP ${c.responseCode}")
            val total = c.contentLengthLong
            var copied = 0L
            c.inputStream.use { input -> dest.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    copied += count
                    if (total > 0) onProgress(((copied * 100) / total).toInt().coerceIn(0, 100))
                }
            } }
            onProgress(100)
        } finally { c.disconnect() }
    }
}
