package com.clipport.app

import com.clipport.app.protocol.ClipBroadcast
import com.clipport.app.protocol.ClipInline
import com.clipport.app.protocol.Hello
import com.clipport.app.protocol.Mime
import org.junit.Assert.*
import org.junit.Test

class GoldenVectorTest {

    @Test
    fun verify_GoldenHello_CrossPlatform_Alignment() {
        val hello = Hello().apply {
            name = "Golden-Peer"
            deviceType = 1
            protoVer = 1
            deviceId = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        }

        val encoded = hello.encode()
        val decoded = Hello.decode(encoded)

        assertEquals("Golden-Peer", decoded.name)
        assertEquals(1, decoded.deviceType)
        assertEquals(1, decoded.protoVer)
        assertArrayEquals(byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()), decoded.deviceId)
    }

    @Test
    fun verify_GoldenBroadcast_CrossPlatform_Alignment() {
        val bc = ClipBroadcast().apply {
            deviceId = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte())
            seq = 8888L
            needChannel = false
            mimeCodes = mutableListOf(Mime.TEXT, Mime.HTML)
            inline = ClipInline().apply {
                text = "Golden-Vector-Text"
                html = "<span>Golden-Vector-Html</span>"
            }
        }

        val encoded = bc.encode()
        val decoded = ClipBroadcast.decode(encoded)

        assertArrayEquals(byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte()), decoded.deviceId)
        assertEquals(8888L, decoded.seq)
        assertFalse(decoded.needChannel)
        assertEquals(listOf(0L, 1L), decoded.mimeCodes)
        assertNotNull(decoded.inline)
        assertEquals("Golden-Vector-Text", decoded.inline?.text)
        assertEquals("<span>Golden-Vector-Html</span>", decoded.inline?.html)
    }
}
