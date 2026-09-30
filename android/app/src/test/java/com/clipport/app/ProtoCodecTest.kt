package com.clipport.app

import com.clipport.app.protocol.*
import org.junit.Assert.*
import org.junit.Test

class ProtoCodecTest {

    @Test
    fun varint_RoundTrip_Success() {
        val testValues = longArrayOf(0L, 1L, 127L, 128L, 300L, 16384L, 0xFFFFFFFFL)
        for (v in testValues) {
            val list = ArrayList<Byte>()
            Proto.writeVarint(list, v)

            val reader = Proto.Reader(list.toByteArray())
            val decoded = reader.uint32()
            assertEquals("Failed for value $v", v, decoded)
        }
    }

    @Test
    fun helloMessage_RoundTrip_Success() {
        val original = Hello().apply {
            name = "Android-Device"
            deviceType = 1
            protoVer = 1
            deviceId = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        }

        val encoded = original.encode()
        val decoded = Hello.decode(encoded)

        assertEquals(original.name, decoded.name)
        assertEquals(original.deviceType, decoded.deviceType)
        assertEquals(original.protoVer, decoded.protoVer)
        assertArrayEquals(original.deviceId, decoded.deviceId)
    }

    @Test
    fun clipBroadcast_InlineWithZeroMimeCode_RoundTrip_Success() {
        val original = ClipBroadcast().apply {
            deviceId = byteArrayOf(1, 2, 3, 4)
            seq = 99L
            needChannel = false
            mimeCodes = mutableListOf(Mime.TEXT, Mime.HTML) // 包含 0 和 1
            inline = ClipInline().apply {
                text = "测试文本"
                html = "<b>测试 HTML</b>"
                imagePng = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
            }
        }

        val encoded = original.encode()
        val decoded = ClipBroadcast.decode(encoded)

        assertArrayEquals(original.deviceId, decoded.deviceId)
        assertEquals(original.seq, decoded.seq)
        assertFalse(decoded.needChannel)
        // 关键验证：Mime.TEXT (0L) 必须被正确解码保留，不能丢失！
        assertEquals(listOf(0L, 1L), decoded.mimeCodes)
        assertNotNull(decoded.inline)
        assertEquals(original.inline?.text, decoded.inline?.text)
        assertEquals(original.inline?.html, decoded.inline?.html)
        assertArrayEquals(original.inline?.imagePng, decoded.inline?.imagePng)
    }

    @Test
    fun fileShareMetaAndChunk_RoundTrip_Success() {
        val meta = FileShareMeta().apply {
            fileName = "archive.zip"
            fileSize = 104857600L // 100MB
            sha256 = ByteArray(32) { 0x5A.toByte() }
        }

        val encodedMeta = meta.encode()
        val decodedMeta = FileShareMeta.decode(encodedMeta)

        assertEquals(meta.fileName, decodedMeta.fileName)
        assertEquals(meta.fileSize, decodedMeta.fileSize)
        assertArrayEquals(meta.sha256, decodedMeta.sha256)

        val chunk = FileShareChunk().apply {
            fileName = "archive.zip"
            offset = 65536L
            isLast = true
            data = byteArrayOf(9, 8, 7, 6)
        }

        val encodedChunk = chunk.encode()
        val decodedChunk = FileShareChunk.decode(encodedChunk)

        assertEquals(chunk.fileName, decodedChunk.fileName)
        assertEquals(chunk.offset, decodedChunk.offset)
        assertTrue(decodedChunk.isLast)
        assertArrayEquals(chunk.data, decodedChunk.data)
    }
}
