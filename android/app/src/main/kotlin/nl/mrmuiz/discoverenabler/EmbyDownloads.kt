package nl.mrmuiz.discoverenabler

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Mirrors src/Enabler.Core/EmbyDownloads.cs. */
class EmbyBuild(val id: String, val file: String) {
    val url get() = EmbyDownloads.RELEASE_BASE + file
    val outputName get() = "Emby-" + titleFor(id) + "-Discover.apk"

    private fun titleFor(id: String) = when (id) {
        "arm64" -> "ARM64"; "armv7" -> "ARMv7"; "firetv" -> "FireTV"; "x64" -> "x64"; else -> "x86"
    }
}

object EmbyDownloads {
    const val RELEASE_BASE = "https://github.com/MediaBrowser/Emby.Releases/raw/master/android/"

    val builds = listOf(
        EmbyBuild("arm64", "emby-android-google-arm64-v8a-release.apk"),
        EmbyBuild("armv7", "emby-android-google-armeabi-v7a-release.apk"),
        EmbyBuild("firetv", "emby-android-amazon-armeabi-v7a-release.apk"),
        EmbyBuild("x64", "emby-android-google-x86_64-release.apk"),
        EmbyBuild("x86", "emby-android-google-x86-release.apk"),
    )

    fun find(id: String) = builds.firstOrNull { it.id.equals(id, ignoreCase = true) }

    /** progress gets (bytes done, total or -1). */
    fun download(build: EmbyBuild, dest: File, progress: (Long, Long) -> Unit) {
        val conn = URL(build.url).openConnection() as HttpURLConnection
        try {
            conn.setRequestProperty("User-Agent", "DiscoverEnabler/1.0")
            conn.connectTimeout = 30_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = true
            if (conn.responseCode !in 200..299) throw java.io.IOException("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            var done = 0L
            conn.inputStream.use { src ->
                dest.outputStream().use { dst ->
                    val buf = ByteArray(81920)
                    while (true) {
                        val n = src.read(buf)
                        if (n < 0) break
                        dst.write(buf, 0, n)
                        done += n
                        progress(done, total)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }
}
