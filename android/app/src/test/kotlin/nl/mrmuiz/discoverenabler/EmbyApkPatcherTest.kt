package nl.mrmuiz.discoverenabler

import com.android.apksig.ApkVerifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class EmbyApkPatcherTest {
    @get:Rule val tmp = TemporaryFolder()

    private val loader = File("../../src/Enabler.Core/discover-web-loader.js").readBytes()
    private val arsc = "ARSC-MARKER-12345".toByteArray()
    private val so = "SO-MARKER-123".toByteArray()

    private fun fakeEmby(): File {
        val f = tmp.newFile("emby.apk")
        ZipOutputStream(f.outputStream()).use { z ->
            fun deflated(name: String, s: String) {
                z.putNextEntry(ZipEntry(name)); z.write(s.toByteArray()); z.closeEntry()
            }
            fun stored(name: String, d: ByteArray) {
                val e = ZipEntry(name)
                e.method = ZipEntry.STORED; e.size = d.size.toLong(); e.compressedSize = d.size.toLong()
                e.crc = CRC32().also { it.update(d) }.value
                z.putNextEntry(e); z.write(d); z.closeEntry()
            }
            z.putNextEntry(ZipEntry("AndroidManifest.xml")); z.write(binaryManifest()); z.closeEntry()
            deflated("assets/www/index.html", "<html><body>hi</body></html>")
            deflated("assets/www/native/android/apphost.js", "var features = {};\nfeatures.restrictedplugins = true;\n")
            deflated("META-INF/OLD.SF", "stale")
            stored("resources.arsc", arsc)
            stored("lib/arm64-v8a/libx.so", so)
        }
        return f
    }

    @Test
    fun patchesAndSigns() {
        val key = SigningKey.loadOrCreate(File(tmp.root, "key.p12"))
        val out = File(tmp.root, "out.apk")
        EmbyApkPatcher.patch(fakeEmby(), out, key, loader)

        ZipFile(out).use { z ->
            val html = z.getInputStream(z.getEntry("assets/www/index.html")).readBytes().decodeToString()
            assertTrue(html.contains("<script src=\"discover-web-loader.js\" defer></script>"))
            val js = z.getInputStream(z.getEntry("assets/www/native/android/apphost.js")).readBytes().decodeToString()
            assertTrue(js.contains("features.restrictedplugins = false;"))
            assertFalse(js.contains("restrictedplugins = true"))
            assertTrue(loader.contentEquals(z.getInputStream(z.getEntry("assets/www/discover-web-loader.js")).readBytes()))
            assertNull(z.getEntry("META-INF/OLD.SF"))
        }

        val verdict = ApkVerifier.Builder(out).setMinCheckedPlatformVersion(24).setMaxCheckedPlatformVersion(34).build().verify()
        assertTrue(verdict.errors.toString(), verdict.isVerified)
        assertTrue(verdict.isVerifiedUsingV2Scheme)

        val bytes = out.readBytes()
        assertEquals("arsc at ${indexOf(bytes, arsc)}", 0, indexOf(bytes, arsc) % 4)
        assertEquals("so at ${indexOf(bytes, so)}", 0, indexOf(bytes, so) % 16384)
    }

    @Test
    fun repatchKeepsKeyAndIsIdempotent() {
        val keyFile = File(tmp.root, "key.p12")
        val key1 = SigningKey.loadOrCreate(keyFile)
        val first = File(tmp.root, "a.apk")
        EmbyApkPatcher.patch(fakeEmby(), first, key1, loader)

        val key2 = SigningKey.loadOrCreate(keyFile)
        assertTrue(key1.certificate.encoded.contentEquals(key2.certificate.encoded))
        val second = File(tmp.root, "b.apk")
        EmbyApkPatcher.patch(first, second, key2, loader)

        ZipFile(second).use { z ->
            val html = z.getInputStream(z.getEntry("assets/www/index.html")).readBytes().decodeToString()
            assertEquals(1, Regex("discover-web-loader.js").findAll(html).count())
        }
        assertTrue(ApkVerifier.Builder(second).setMinCheckedPlatformVersion(24).setMaxCheckedPlatformVersion(34).build().verify().isVerified)
    }

    @Test(expected = NotEmbyApkException::class)
    fun rejectsOtherApks() {
        val f = tmp.newFile("other.apk")
        ZipOutputStream(f.outputStream()).use { it.putNextEntry(ZipEntry("x")); it.closeEntry() }
        EmbyApkPatcher.patch(f, File(tmp.root, "o.apk"), SigningKey.loadOrCreate(File(tmp.root, "k.p12")), loader)
    }

    /** Smallest valid binary AndroidManifest.xml (package + minSdkVersion) so apksig can verify the APK. */
    private fun binaryManifest(): ByteArray {
        val strings = listOf("minSdkVersion", "package", "manifest", "uses-sdk", "x.y", "http://schemas.android.com/apk/res/android", "android")
        val pool = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
        val offsets = ArrayList<Int>()
        val data = ByteBuffer.allocate(2048).order(ByteOrder.LITTLE_ENDIAN)
        for (s in strings) {
            offsets.add(data.position())
            data.putShort(s.length.toShort()); s.forEach { data.putShort(it.code.toShort()) }; data.putShort(0)
        }
        while (data.position() % 4 != 0) data.put(0)
        val dataLen = data.position()
        val poolSize = 28 + 4 * strings.size + dataLen
        pool.putShort(1); pool.putShort(28); pool.putInt(poolSize); pool.putInt(strings.size); pool.putInt(0); pool.putInt(0)
        pool.putInt(28 + 4 * strings.size); pool.putInt(0)
        offsets.forEach { pool.putInt(it) }
        pool.put(data.array(), 0, dataLen)

        val b = ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN)
        b.putShort(3); b.putShort(8); b.putInt(0)
        b.put(pool.array(), 0, poolSize)
        b.putShort(0x180); b.putShort(8); b.putInt(12); b.putInt(0x0101020c)
        fun node(type: Int, size: Int) { b.putShort(type.toShort()); b.putShort(16); b.putInt(size); b.putInt(1); b.putInt(-1) }
        node(0x100, 24); b.putInt(6); b.putInt(5)
        node(0x102, 56); b.putInt(-1); b.putInt(2); b.putShort(20); b.putShort(20); b.putShort(1); b.putShort(0); b.putShort(0); b.putShort(0)
        b.putInt(-1); b.putInt(1); b.putInt(4); b.putShort(8); b.put(0); b.put(3); b.putInt(4)
        node(0x102, 56); b.putInt(-1); b.putInt(3); b.putShort(20); b.putShort(20); b.putShort(1); b.putShort(0); b.putShort(0); b.putShort(0)
        b.putInt(5); b.putInt(0); b.putInt(-1); b.putShort(8); b.put(0); b.put(0x10); b.putInt(21)
        node(0x103, 24); b.putInt(-1); b.putInt(3)
        node(0x103, 24); b.putInt(-1); b.putInt(2)
        node(0x101, 24); b.putInt(6); b.putInt(5)
        b.putInt(4, b.position())
        return b.array().copyOf(b.position())
    }

    private fun indexOf(h: ByteArray, n: ByteArray): Int {
        outer@ for (i in 0..h.size - n.size) {
            for (j in n.indices) if (h[i + j] != n[j]) continue@outer
            return i
        }
        error("marker not found")
    }
}
