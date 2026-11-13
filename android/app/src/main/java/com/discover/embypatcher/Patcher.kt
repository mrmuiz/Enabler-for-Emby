package com.discover.embypatcher

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Pure JVM logic (no Android dependencies), so it can be tested outside the app. */
object Patcher {

    /** The only file that is not replaced 1:1 but edited automatically. */
    const val INDEX = "assets/www/index.html"

    /**
     * Writes a new APK:
     *  - everything from the original APK, except old signatures and files present in [files];
     *  - [INDEX] from the original APK with [snippet] inserted before </body>;
     *  - then all [files] (replaced or added).
     * Stored (uncompressed) entries are aligned (4 bytes, .so at 4096).
     */
    fun patch(
        src: File,
        dst: File,
        files: Map<String, () -> InputStream>,
        snippet: String,
        log: (String) -> Unit
    ) {
        ZipFile(src).use { zf ->
            val indexEntry = zf.getEntry(INDEX) ?: error("$INDEX not found in the Emby APK")
            val originalNames = zf.entries().asSequence().map { it.name }.toHashSet()

            // Build index.html first: if this fails we stop before anything is written.
            val html = zf.getInputStream(indexEntry).use { String(it.readBytes(), Charsets.UTF_8) }
            val newHtml = inject(html, snippet)
            log(if (newHtml == html) "index.html: loader line already present" else "index.html: loader line added")

            val counting = CountingOut(BufferedOutputStream(FileOutputStream(dst), 1 shl 16))
            ZipOutputStream(counting).use { zos ->
                for (e in zf.entries().asSequence()) {
                    val name = e.name
                    if (e.isDirectory || isOldSignature(name)) continue
                    if (name == INDEX) continue
                    if (files.containsKey(name)) continue
                    copyEntry(zf, e, zos, counting)
                }
                zos.putNextEntry(ZipEntry(INDEX).apply { method = ZipEntry.DEFLATED })
                zos.write(newHtml.toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                for ((name, open) in files) {
                    if (name == INDEX) { log("skipped (edited automatically): $name"); continue }
                    zos.putNextEntry(ZipEntry(name).apply { method = ZipEntry.DEFLATED })
                    open().use { it.copyTo(zos) }
                    zos.closeEntry()
                    log((if (name in originalNames) "replaced: " else "added: ") + name)
                }
            }
        }
    }

    /** Inserts [snippet] before the last </body>. Does nothing if the snippet is already there. */
    fun inject(html: String, snippet: String): String {
        val sn = snippet.replace("\r\n", "\n").trim()
        if (sn.isEmpty()) return html
        if (html.replace("\r\n", "\n").contains(sn)) return html

        val idx = html.lastIndexOf("</body>", ignoreCase = true)
        if (idx < 0) error("index.html has no </body>: Emby changed its structure, the patcher needs an update")

        val nl = if (html.contains("\r\n")) "\r\n" else "\n"
        val block = sn.lines().joinToString(nl) { "    $it" }
        val lineStart = html.lastIndexOf('\n', idx - 1) + 1
        return if (html.substring(lineStart, idx).isBlank())
            html.substring(0, lineStart) + block + nl + html.substring(lineStart)
        else
            html.substring(0, idx) + nl + block + nl + html.substring(idx)
    }

    /** Checks that all stored entries in a (signed) APK are aligned. Empty = OK. */
    fun misaligned(apk: File): List<String> {
        val bad = ArrayList<String>()
        java.io.RandomAccessFile(apk, "r").use { raf ->
            val len = raf.length()
            val tailSize = minOf(len, 65557L).toInt()
            val tail = ByteArray(tailSize)
            raf.seek(len - tailSize)
            raf.readFully(tail)
            var p = tailSize - 22
            while (p >= 0 && !(tail[p] == 0x50.toByte() && tail[p + 1] == 0x4b.toByte() &&
                        tail[p + 2] == 5.toByte() && tail[p + 3] == 6.toByte())) p--
            if (p < 0) { bad.add("no end of zip found"); return bad }
            val e = java.nio.ByteBuffer.wrap(tail).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val count = e.getShort(p + 10).toInt() and 0xFFFF
            val cdSize = e.getInt(p + 12)
            val cdOff = e.getInt(p + 16).toLong() and 0xFFFFFFFFL
            val cd = ByteArray(cdSize)
            raf.seek(cdOff)
            raf.readFully(cd)
            val b = java.nio.ByteBuffer.wrap(cd).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val lfh = ByteArray(30)
            val l = java.nio.ByteBuffer.wrap(lfh).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            var o = 0
            repeat(count) {
                val method = b.getShort(o + 10).toInt() and 0xFFFF
                val nl = b.getShort(o + 28).toInt() and 0xFFFF
                val el = b.getShort(o + 30).toInt() and 0xFFFF
                val cl = b.getShort(o + 32).toInt() and 0xFFFF
                val off = b.getInt(o + 42).toLong() and 0xFFFFFFFFL
                val name = String(cd, o + 46, nl, Charsets.UTF_8)
                if (method == 0 && !name.endsWith("/")) {
                    raf.seek(off)
                    raf.readFully(lfh)
                    val dataStart = off + 30 + (l.getShort(26).toInt() and 0xFFFF) + (l.getShort(28).toInt() and 0xFFFF)
                    val align = if (name.endsWith(".so")) 4096L else 4L
                    if (dataStart % align != 0L) bad.add("$name: offset ${dataStart % align} (must be 0, mod $align)")
                }
                o += 46 + nl + el + cl
            }
        }
        return bad
    }

    private fun isOldSignature(n: String) =
        n.startsWith("META-INF/") &&
            (n == "META-INF/MANIFEST.MF" || n.endsWith(".SF") || n.endsWith(".RSA") ||
                n.endsWith(".DSA") || n.endsWith(".EC"))

    private fun copyEntry(zf: ZipFile, e: ZipEntry, zos: ZipOutputStream, co: CountingOut) {
        val ne = ZipEntry(e.name)
        if (e.method == ZipEntry.STORED) {
            ne.method = ZipEntry.STORED
            ne.size = e.size
            ne.compressedSize = e.size
            ne.crc = e.crc
            val align = if (e.name.endsWith(".so")) 4096L else 4L
            val dataStart = co.count + 30 + e.name.toByteArray(Charsets.UTF_8).size
            var pad = ((align - dataStart % align) % align).toInt()
            // zipalign format: 4-byte header + 2-byte alignment value = at least 6 bytes.
            while (pad in 1..5) pad += align.toInt()
            if (pad > 0) {
                val extra = ByteArray(pad)
                extra[0] = 0x35; extra[1] = 0xD9.toByte() // Android alignment-extra (0xD935)
                extra[2] = ((pad - 4) and 0xFF).toByte()
                extra[3] = (((pad - 4) shr 8) and 0xFF).toByte()
                extra[4] = (align and 0xFF).toByte()          // alignment value, as zipalign writes it
                extra[5] = ((align shr 8) and 0xFF).toByte()
                ne.extra = extra
            }
        } else {
            ne.method = ZipEntry.DEFLATED
        }
        zos.putNextEntry(ne)
        zf.getInputStream(e).use { it.copyTo(zos) }
        zos.closeEntry()
    }

    private class CountingOut(o: OutputStream) : FilterOutputStream(o) {
        var count = 0L
        override fun write(b: Int) { out.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
    }
}
