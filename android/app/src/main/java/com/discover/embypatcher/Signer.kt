package com.discover.embypatcher

import android.content.Context
import com.android.apksig.ApkSigner
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

object Signer {

    fun sign(ctx: Context, input: File, output: File, minSdk: Int) {
        val (key, cert) = loadOrCreateKey(ctx)
        val cfg = ApkSigner.SignerConfig.Builder("emby", key, listOf(cert)).build()
        ApkSigner.Builder(listOf(cfg))
            .setInputApk(input)
            .setOutputApk(output)
            .setMinSdkVersion(minSdk)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .build()
            .sign()
    }

    /** One fixed key, so every new version installs over the previous one. */
    private fun loadOrCreateKey(ctx: Context): Pair<PrivateKey, X509Certificate> {
        val keyFile = File(ctx.filesDir, "sign.key")
        val certFile = File(ctx.filesDir, "sign.crt")
        if (!keyFile.exists() || !certFile.exists()) {
            val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val name = X500Name("CN=Emby Patched")
            val now = Date()
            val end = Date(now.time + 10000L * 24 * 3600 * 1000)
            val builder = JcaX509v3CertificateBuilder(
                name, BigInteger.valueOf(System.currentTimeMillis()), now, end, name, kp.public
            )
            val signer = JcaContentSignerBuilder("SHA256withRSA").build(kp.private)
            val cert = JcaX509CertificateConverter().getCertificate(builder.build(signer))
            keyFile.writeBytes(kp.private.encoded)
            certFile.writeBytes(cert.encoded)
        }
        val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(certFile.readBytes())) as X509Certificate
        return key to cert
    }
}
