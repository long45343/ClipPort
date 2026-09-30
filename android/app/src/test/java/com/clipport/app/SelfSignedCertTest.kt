package com.clipport.app

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class SelfSignedCertTest {

    private fun derEncodeLength(len: Int): ByteArray {
        return if (len < 128) {
            byteArrayOf(len.toByte())
        } else if (len <= 255) {
            byteArrayOf(0x81.toByte(), len.toByte())
        } else if (len <= 65535) {
            byteArrayOf(0x82.toByte(), (len shr 8).toByte(), (len and 0xFF).toByte())
        } else {
            byteArrayOf(0x83.toByte(), (len shr 16).toByte(), ((len shr 8) and 0xFF).toByte(), (len and 0xFF).toByte())
        }
    }

    private fun derSequence(vararg elements: ByteArray): ByteArray {
        val totalLen = elements.sumOf { it.size }
        val out = ByteArrayOutputStream()
        out.write(0x30)
        out.write(derEncodeLength(totalLen))
        for (el in elements) out.write(el)
        return out.toByteArray()
    }

    private fun derBitString(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x03)
        out.write(derEncodeLength(bytes.size + 1))
        out.write(0x00) // unused bits
        out.write(bytes)
        return out.toByteArray()
    }

    private fun createSelfSignedCert(): X509Certificate {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val kp = kpg.generateKeyPair()

        // 1. sha256WithRSAEncryption: 1.2.840.113549.1.1.11
        val sigAlgId = byteArrayOf(
            0x30, 0x0D,
            0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B,
            0x05, 0x00
        )

        // 2. Serial Number: INTEGER
        val serial = byteArrayOf(0x02, 0x08, 0x01, 0x23, 0x45, 0x67, 0x89.toByte(), 0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte())

        // 3. Subject / Issuer: CN=ClipPort
        // 0x55, 0x04, 0x03 = id-at-commonName
        val cnValue = "ClipPort".toByteArray(Charsets.UTF_8)
        val atv = derSequence(
            byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x03),
            byteArrayOf(0x0C, cnValue.size.toByte()) + cnValue
        )
        val rdn = byteArrayOf(0x31, (atv.size).toByte()) + atv
        val name = derSequence(rdn)

        // 4. Validity: UTCTime YYMMDDHHMMSSZ
        val sdf = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val now = Date()
        val notBeforeStr = sdf.format(Date(now.time - 24 * 3600 * 1000L)).toByteArray(Charsets.US_ASCII)
        val notAfterStr = sdf.format(Date(now.time + 30L * 365 * 24 * 3600 * 1000L)).toByteArray(Charsets.US_ASCII)
        val validity = derSequence(
            byteArrayOf(0x17, notBeforeStr.size.toByte()) + notBeforeStr,
            byteArrayOf(0x17, notAfterStr.size.toByte()) + notAfterStr
        )

        // 5. SubjectPublicKeyInfo: 直接使用公钥的原生 encoded
        val spki = kp.public.encoded

        // 6. TBSCertificate
        val tbsCertificate = derSequence(
            serial,
            sigAlgId,
            name,      // issuer
            validity,
            name,      // subject
            spki
        )

        // 7. Signature with SHA256withRSA
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(kp.private)
        signer.update(tbsCertificate)
        val signatureValue = signer.sign()

        // 8. 完整 Certificate SEQUENCE
        val certDer = derSequence(
            tbsCertificate,
            sigAlgId,
            derBitString(signatureValue)
        )

        val cf = CertificateFactory.getInstance("X.509")
        return cf.generateCertificate(ByteArrayInputStream(certDer)) as X509Certificate
    }

    @Test
    fun testGenerateAndVerifySelfSignedCertificate() {
        val cert = createSelfSignedCert()
        assertNotNull(cert)
        assertEquals("CN=ClipPort", cert.subjectX500Principal.name)
        assertEquals("CN=ClipPort", cert.issuerX500Principal.name)
        assertEquals("SHA256withRSA", cert.sigAlgName)

        // 验证自签名公钥验签自身
        cert.verify(cert.publicKey)
        println("自签名证书生成并自验通过！指纹长度: ${cert.encoded.size}")

        // 验证能否装入标准 PKCS12 KeyStore
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        val dummyKpg = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val dummyKp = dummyKpg.generateKeyPair()
        ks.setKeyEntry("clipport", dummyKp.private, CharArray(0), arrayOf(cert))
        assertNotNull(ks.getCertificate("clipport"))
    }
}
