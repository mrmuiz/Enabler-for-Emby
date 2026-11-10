package nl.mrmuiz.discoverenabler

import com.android.apksig.ApkSigner
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class NotEmbyApkException(message: String) : Exception(message)

/**
 * Same patch as src/Enabler.Core/EmbyApkPatcher.cs:
 *  1. index.html loads discover-web-loader.js,
 *  2. apphost.js no longer reports "restrictedplugins",
 *  3. discover-web-loader.js is added,
 * then the APK is zip-aligned and re-signed with the given key.
 */
object EmbyApkPatcher {
    const val MARKER = "discover-web-loader.js"
    private const val INDEX = "assets/www/index.html"
    private const val APPHOST = "assets/www/native/android/apphost.js"
    private const val LOADER = "assets/www/discover-web-loader.js"
    private const val RESTRICTED_ON = "features.restrictedplugins = true;"
    private const val RESTRICTED_OFF = "features.restrictedplugins = false; /* Discover Enabler */"

    fun patch(input: File, output: File, key: SigningKey, loader: ByteArray, log: (String) -> Unit = {}) {
        val unsigned = File(output.path + ".unsigned")
        try {
            log("Reading ${input.name} ...")
            ZipFile(input).use { zip ->
                if (zip.getEntry(INDEX) == null || zip.getEntry(APPHOST) == null) {
                    throw NotEmbyApkException("Not the Emby for Android phone/tablet app")
                }
                writePatched(zip, unsigned, loader, log)
            }
            log("Signing ...")
            val signer = ApkSigner.SignerConfig.Builder("DISCOVER", key.privateKey, listOf(key.certificate)).build()
            ApkSigner.Builder(listOf(signer))
                .setInputApk(unsigned)
                .setOutputApk(output)
                .setMinSdkVersion(21)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .setV4SigningEnabled(false)
                .build()
                .sign()
            log("Done: ${output.name}")
        } finally {
            unsigned.delete()
        }
    }

    /** Pure text transformations, kept separate so they can be tested on their own. */
    fun patchIndex(html: String): String? {
        if (html.contains(MARKER)) return null
        val at = html.lastIndexOf("</body>", ignoreCase = true)
        if (at < 0) throw NotEmbyApkException("index.html has no </body>; unknown Emby version")
        return html.substring(0, at) + "    <script src=\"$MARKER\" defer></script>\n" + html.substring(at)
    }

    fun patchAppHost(js: String): String? =
        if (js.contains(RESTRICTED_ON)) js.replace(RESTRICTED_ON, RESTRICTED_OFF) else null

    private fun isSignatureFile(name: String): Boolean {
        if (!name.startsWith("META-INF/", ignoreCase = true)) return false
        val n = name.uppercase()
        return n == "META-INF/MANIFEST.MF" || n.endsWith(".SF") || n.endsWith(".RSA") ||
            n.endsWith(".DSA") || n.endsWith(".EC") || n.startsWith("META-INF/SIG-")
    }

    private fun writePatched(zip: ZipFile, dest: File, loader: ByteArray, log: (String) -> Unit) {
        val counter = CountingOutputStream(dest.outputStream().buffered(1 shl 16))
        ZipOutputStream(counter).use { out ->
            var loaderWritten = false
            val entries = zip.entries().toList()
            for (e in entries) {
                if (isSignatureFile(e.name) || e.name == LOADER) continue
                when (e.name) {
                    INDEX -> {
                        val html = String(zip.getInputStream(e).use(InputStream::readBytes), Charsets.UTF_8)
                        val patched = patchIndex(html)
                        log(if (patched == null) "index.html: already patched" else "index.html: Discover loader added")
                        writeBytes(out, counter, e, (patched ?: html).toByteArray(Charsets.UTF_8))
                        writeBytes(out, counter, e, loader, LOADER)
                        loaderWritten = true
                        log("discover-web-loader.js: added")
                    }
                    APPHOST -> {
                        val js = String(zip.getInputStream(e).use(InputStream::readBytes), Charsets.UTF_8)
                        val patched = patchAppHost(js)
                        log(
                            when {
                                patched != null -> "apphost.js: plugin pages allowed in the app (Discover in the menu)"
                                js.contains("features.restrictedplugins = false;") -> "apphost.js: already patched"
                                else -> "apphost.js: restriction not found (newer Emby version?) - menu entry may be missing"
                            }
                        )
                        writeBytes(out, counter, e, (patched ?: js).toByteArray(Charsets.UTF_8))
                    }
                    else -> copyEntry(zip, e, out, counter)
                }
            }
            check(loaderWritten)
        }
    }

    private fun writeBytes(out: ZipOutputStream, counter: CountingOutputStream, like: ZipEntry, data: ByteArray, name: String = like.name) {
        val compress = like.method == ZipEntry.DEFLATED
        val ze = ZipEntry(name)
        if (compress) {
            ze.method = ZipEntry.DEFLATED
        } else {
            ze.method = ZipEntry.STORED
            ze.size = data.size.toLong()
            ze.compressedSize = data.size.toLong()
            ze.crc = CRC32().also { it.update(data) }.value
            ze.extra = padding(counter.count, name, name.endsWith(".so"))
        }
        out.putNextEntry(ze)
        out.write(data)
        out.closeEntry()
    }

    private fun copyEntry(zip: ZipFile, e: ZipEntry, out: ZipOutputStream, counter: CountingOutputStream) {
        val ze = ZipEntry(e.name)
        if (e.isDirectory) {
            out.putNextEntry(ze)
            out.closeEntry()
            return
        }
        if (e.method == ZipEntry.STORED) {
            ze.method = ZipEntry.STORED
            ze.size = e.size
            ze.compressedSize = e.size
            ze.crc = e.crc
            ze.extra = padding(counter.count, e.name, e.name.endsWith(".so"))
        } else {
            ze.method = ZipEntry.DEFLATED
        }
        out.putNextEntry(ze)
        zip.getInputStream(e).use { it.copyTo(out) }
        out.closeEntry()
    }

    /** zipalign behaviour: stored data starts on a 4-byte boundary (16 KiB for native libraries). */
    private fun padding(offset: Long, name: String, pageAlign: Boolean): ByteArray {
        val align = if (pageAlign) 16384L else 4L
        val header = 30L + name.toByteArray(Charsets.UTF_8).size
        var pad = ((align - (offset + header) % align) % align).toInt()
        if (pad == 0) return ByteArray(0)
        if (pad < 4) pad += align.toInt()
        val extra = ByteArray(pad)
        extra[0] = 0x35; extra[1] = 0xD9.toByte() // zipalign's padding field id 0xD935
        extra[2] = ((pad - 4) and 0xFF).toByte(); extra[3] = ((pad - 4) shr 8).toByte()
        return extra
    }

    private class CountingOutputStream(out: OutputStream) : FilterOutputStream(out) {
        var count = 0L
            private set

        override fun write(b: Int) { out.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
    }
}
