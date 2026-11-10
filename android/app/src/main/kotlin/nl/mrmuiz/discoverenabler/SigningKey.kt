package nl.mrmuiz.discoverenabler

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date

/**
 * The persistent key every patched APK is signed with. It lives in app-private storage, so a later
 * Emby update patched on the same device is signed identically and installs over the previous one.
 */
class SigningKey(val privateKey: PrivateKey, val certificate: X509Certificate) {
    companion object {
        private const val ALIAS = "discover-enabler"
        // Protects nothing beyond file format needs: the file is in the app's private directory.
        private val PASSWORD = "discover-enabler".toCharArray()

        fun loadOrCreate(file: File): SigningKey {
            if (file.exists()) {
                try {
                    return load(file)
                } catch (e: Exception) {
                    // unreadable key file: fall through and create a new one
                }
            }
            val key = create()
            val ks = KeyStore.getInstance("PKCS12")
            ks.load(null, null)
            ks.setKeyEntry(ALIAS, key.privateKey, PASSWORD, arrayOf(key.certificate))
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.outputStream().use { ks.store(it, PASSWORD) }
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
            return key
        }

        private fun load(file: File): SigningKey {
            val ks = KeyStore.getInstance("PKCS12")
            file.inputStream().use { ks.load(it, PASSWORD) }
            return SigningKey(
                ks.getKey(ALIAS, PASSWORD) as PrivateKey,
                ks.getCertificate(ALIAS) as X509Certificate
            )
        }

        private fun create(): SigningKey {
            val kpg = KeyPairGenerator.getInstance("RSA")
            kpg.initialize(2048, SecureRandom())
            val pair = kpg.generateKeyPair()
            val name = X500Name("CN=Discover Enabler")
            val from = Date(System.currentTimeMillis() - 24L * 3600 * 1000)
            val to = Date(from.time + 36500L * 24 * 3600 * 1000)
            val signer = JcaContentSignerBuilder("SHA256withRSA").build(pair.private)
            val holder = JcaX509v3CertificateBuilder(name, BigInteger(64, SecureRandom()).abs().add(BigInteger.ONE), from, to, name, pair.public).build(signer)
            val cert = JcaX509CertificateConverter().getCertificate(holder)
            return SigningKey(pair.private, cert)
        }
    }
}
