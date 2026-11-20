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

    // Discover Patcher itself. "latest/download" always points at the newest release, so this needs no
    // GitHub API call and no rate limit. The file is attached to every release by the build workflow.
    const val PATCHER_RELEASES = "https://github.com/mrmuiz/Enabler-for-Emby/releases/latest"
    private const val PATCHER_VERSION_FILE = "$PATCHER_RELEASES/download/patcher-version.json"

    /** A release of Discover Patcher itself. */
    class PatcherRelease(val versionCode: Long, val versionName: String)

    @Volatile private var patcherCached: Pair<PatcherRelease?, Long>? = null

    /**
     * The newest Discover Patcher on GitHub, or null when it cannot be read (no connection, or a release
     * from before this file existed). Cached for an hour.
     */
    fun latestPatcher(): PatcherRelease? {
        patcherCached?.let { (r, at) -> if (System.currentTimeMillis() - at < 60 * 60 * 1000L) return r }
        var result: PatcherRelease? = null
        try {
            val c = URL(PATCHER_VERSION_FILE).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 15000
                c.readTimeout = 20000
                c.instanceFollowRedirects = true
                c.setRequestProperty("User-Agent", "DiscoverPatcher")
                if (c.responseCode == 200) {
                    val o = org.json.JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                    val code = o.optLong("versionCode", 0L)
                    if (code > 0L) result = PatcherRelease(code, o.optString("versionName", ""))
                }
            } finally { c.disconnect() }
        } catch (_: Exception) { }
        patcherCached = result to System.currentTimeMillis()
        return result
    }

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

    @Volatile private var cached: Pair<String, Long>? = null
    private const val CACHE_MS = 10 * 60 * 1000L

    /** Why the last check failed (English, for the activity log); null when it worked. */
    @Volatile var lastError: String? = null
        private set

    /** Marker prefix of an id that is the ETag of the APK file instead of a commit sha. */
    const val ETAG = "etag:"

    /**
     * Id of the current Emby APK online. First choice: the ETag of the APK file itself, asked with a HEAD
     * request on the same address the download uses (no GitHub API, so no rate limit of 60 calls per hour
     * and no slow history lookup). Fallback: the last commit of the file from the GitHub API.
     * Cached for 10 minutes.
     */
    fun latestId(name: String): String? {
        cached?.let { (id, at) -> if (System.currentTimeMillis() - at < CACHE_MS) return id }
        val errors = ArrayList<String>()
        val id = etag(name, errors) ?: commitSha(name, errors)
        lastError = if (id == null) errors.joinToString("; ") else null
        if (id != null) cached = id to System.currentTimeMillis()
        return id
    }

    /** The commit sha only (to compare with a build that was installed by an older patcher version). */
    private fun latestSha(name: String, errors: MutableList<String>): String? = commitSha(name, errors)

    /**
     * Result of comparing the installed build with the APK online.
     * current: true = up to date, false = newer APK online, null = could not be compared.
     * latest: id of the APK online (null when the check itself failed).
     * newMarker: the installed marker rewritten with the new kind of id (store it; the next check then needs no GitHub API).
     */
    class Check(val current: Boolean?, val latest: String?, val newMarker: String?)

    /** marker = what was stored after the last install: id#patcherVersion#beta#menu=1 */
    fun check(name: String, marker: String): Check {
        val installed = marker.substringBefore("#")
        val latest = latestId(name) ?: return Check(null, null, null)
        if (latest.startsWith(ETAG) == installed.startsWith(ETAG)) return Check(latest == installed, latest, null)
        if (installed.startsWith(ETAG)) return Check(null, latest, null)
        // Installed by an older patcher version (commit sha): compare once by commit, then switch to the new id.
        val errors = ArrayList<String>()
        val sha = latestSha(name, errors)
        if (sha == null) { lastError = errors.joinToString("; "); return Check(null, latest, null) }
        val same = sha == installed
        return Check(same, latest, if (same) latest + marker.substring(installed.length) else null)
    }

    private fun etag(name: String, errors: MutableList<String>): String? = try {
        val c = URL(BASE + name).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "HEAD"
            c.connectTimeout = 15000
            c.readTimeout = 20000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "DiscoverPatcher")
            // Always the same representation, so the ETag does not depend on compression.
            c.setRequestProperty("Accept-Encoding", "identity")
            val code = c.responseCode
            val tag = c.getHeaderField("ETag")?.removePrefix("W/")?.trim('"', ' ')
            when {
                code != 200 -> { errors.add("file check: HTTP $code"); null }
                tag.isNullOrEmpty() -> { errors.add("file check: no ETag"); null }
                else -> ETAG + tag
            }
        } finally { c.disconnect() }
    } catch (e: Exception) { errors.add("file check: ${e.javaClass.simpleName} ${e.message ?: ""}".trim()); null }

    private fun commitSha(name: String, errors: MutableList<String>): String? = try {
        val c = github(API_BASE + name + "&per_page=1")
        try {
            val code = c.responseCode
            if (code != 200) {
                val limited = code == 403 && c.getHeaderField("X-RateLimit-Remaining") == "0"
                errors.add("GitHub API: HTTP $code" + if (limited) " (rate limit reached)" else "")
                null
            } else {
                val commits = JSONArray(c.inputStream.bufferedReader().use { it.readText() })
                // "sha" is a plain string in the GitHub commits API.
                val sha = if (commits.length() == 0) null else commits.getJSONObject(0).optString("sha").ifEmpty { null }
                if (sha == null) errors.add("GitHub API: no commit found")
                sha
            }
        } finally { c.disconnect() }
    } catch (e: Exception) { errors.add("GitHub API: ${e.javaClass.simpleName} ${e.message ?: ""}".trim()); null }

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
