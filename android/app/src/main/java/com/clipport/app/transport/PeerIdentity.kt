package com.clipport.app.transport

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 设备身份（二期对等模式）：每台设备首次运行生成 RSA-2048 自签证书并持久化，
 * 用于服务端角色的 TLS（对端按 SHA-256(DER) 指纹固定校验——与 PC 端 CertManager 同一定义）。
 * 采用纯 Java 原生 ASN.1 DER 编码自签（S-01=B 决策，零第三方库依赖，彻底剥离 BouncyCastle）。
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
            val cert = selfSign(kp, "ClipPort")
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

    /** 纯原生极简 ASN.1 DER 自签 X.509 v3 证书 */
    private fun selfSign(kp: KeyPair, cn: String): X509Certificate {
        // 1. sha256WithRSAEncryption: 1.2.840.113549.1.1.11
        val sigAlgId = byteArrayOf(
            0x30, 0x0D,
            0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B,
            0x05, 0x00
        )

        // 2. 序列号 (8 字节随机正整数)
        val serialBytes = ByteArray(8).apply { SecureRandom().nextBytes(this) }
        serialBytes[0] = (serialBytes[0].toInt() and 0x7F).toByte() // 确保正数
        val serial = derSequence(0x02, serialBytes)

        // 3. Subject / Issuer: CN=ClipPort (UTF8String)
        val cnBytes = cn.toByteArray(Charsets.UTF_8)
        val atv = derSequence(
            0x30,
            byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x03), // id-at-commonName
            derSequence(0x0C, cnBytes)                   // UTF8String
        )
        val rdn = derSequence(0x31, atv)
        val name = derSequence(0x30, rdn)

        // 4. 有效期 (30 年)
        val sdf = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val now = System.currentTimeMillis()
        val notBefore = sdf.format(Date(now - 24 * 3600 * 1000L)).toByteArray(Charsets.US_ASCII)
        val notAfter = sdf.format(Date(now + 30L * 365 * 24 * 3600 * 1000L)).toByteArray(Charsets.US_ASCII)
        val validity = derSequence(
            0x30,
            derSequence(0x17, notBefore),
            derSequence(0x17, notAfter)
        )

        // 5. SubjectPublicKeyInfo (直接使用公钥原生标准 DER 编码)
        val spki = kp.public.encoded

        // 6. TBSCertificate
        val tbsCertificate = derSequence(
            0x30,
            serial,
            sigAlgId,
            name,
            validity,
            name,
            spki
        )

        // 7. SHA256withRSA 签名
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(kp.private)
        signer.update(tbsCertificate)
        val signatureValue = signer.sign()

        // 8. 组装完整 X.509 Certificate DER
        val certDer = derSequence(
            0x30,
            tbsCertificate,
            sigAlgId,
            derBitString(signatureValue)
        )

        val cf = CertificateFactory.getInstance("X.509")
        return cf.generateCertificate(ByteArrayInputStream(certDer)) as X509Certificate
    }

    private fun derEncodeLength(len: Int): ByteArray = when {
        len < 128 -> byteArrayOf(len.toByte())
        len <= 255 -> byteArrayOf(0x81.toByte(), len.toByte())
        len <= 65535 -> byteArrayOf(0x82.toByte(), (len shr 8).toByte(), (len and 0xFF).toByte())
        else -> byteArrayOf(0x83.toByte(), (len shr 16).toByte(), ((len shr 8) and 0xFF).toByte(), (len and 0xFF).toByte())
    }

    private fun derSequence(tag: Int, vararg elements: ByteArray): ByteArray {
        val totalLen = elements.sumOf { it.size }
        val out = ByteArrayOutputStream()
        out.write(tag)
        out.write(derEncodeLength(totalLen))
        for (el in elements) out.write(el)
        return out.toByteArray()
    }

    private fun derBitString(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x03)
        out.write(derEncodeLength(bytes.size + 1))
        out.write(0x00) // 0 个未用 bit
        out.write(bytes)
        return out.toByteArray()
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
