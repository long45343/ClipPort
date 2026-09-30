package com.clipport.app.transport

import android.content.Context
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date

/**
 * 设备身份（二期对等模式）：每台设备首次运行生成 RSA-2048 自签证书并持久化，
 * 用于服务端角色的 TLS（对端按 SHA-256(DER) 指纹固定校验——与 PC 端 CertManager 同一定义）。
 */
object PeerIdentity {
    private const val ALIAS = "clipport"
    private const val STORE_FILE = "clipport.p12"
    private const val STORE_TYPE = "PKCS12"

    data class Identity(val privateKey: java.security.PrivateKey, val cert: X509Certificate, val chain: Array<X509Certificate>)

    @Volatile private var cached: Identity? = null

    fun get(context: Context): Identity {
        cached?.let { return it }
        val pass = storePass(context)
        val file = File(context.filesDir, STORE_FILE)
        val ks = KeyStore.getInstance(STORE_TYPE)
        val identity: Identity = if (file.exists()) {
            file.inputStream().use { ks.load(it, pass) }
            val key = ks.getKey(ALIAS, pass) as java.security.PrivateKey
            val cert = ks.getCertificate(ALIAS) as X509Certificate
            Identity(key, cert, arrayOf(cert))
        } else {
            val kp = generate()
            val cert = selfSign(kp, "CN=ClipPort")
            ks.load(null, null)
            ks.setKeyEntry(ALIAS, kp.private, pass, arrayOf(cert))
            file.outputStream().use { ks.store(it, pass) }
            Identity(kp.private, cert, arrayOf(cert))
        }
        cached = identity
        return identity
    }

    /** 证书指纹 = SHA-256(DER)，与 PC 端 CertManager.Fingerprint 同一定义。 */
    fun fingerprint(context: Context): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(get(context).cert.encoded)

    private fun generate(): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }.generateKeyPair()

    private fun selfSign(kp: KeyPair, cn: String): X509Certificate {
        val now = System.currentTimeMillis()
        val name = X500Name(cn)
        val builder = JcaX509v3CertificateBuilder(
            name, BigInteger.valueOf(now),
            Date(now - 86_400_000L), Date(now + 3650L * 86_400_000),
            name, kp.public,
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(kp.private)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }

    private fun storePass(context: Context): CharArray {
        val sp = context.getSharedPreferences("clipport", Context.MODE_PRIVATE)
        var pass = sp.getString("p12pass", null)
        if (pass == null) {
            pass = java.util.UUID.randomUUID().toString().replace("-", "").take(24)
            sp.edit().putString("p12pass", pass).apply()
        }
        return pass.toCharArray()
    }
}
