package com.clipport.app

import com.clipport.app.protocol.FrameCodec
import org.junit.Assert.*
import org.junit.Test

class FrameCodecTest {

    @Test
    fun encodeAndDecode_RoundTrip_Success() {
        val type = FrameCodec.CLIP_BROADCAST
        val seq = 2048L
        val payload = "Hello Android ClipPort".toByteArray(Charsets.UTF_8)

        val frame = FrameCodec.encode(type, seq, payload)
        assertEquals(FrameCodec.HEADER_SIZE + payload.size, frame.size)

        val decoded = FrameCodec.tryDecode(frame, 0, frame.size)
        assertNotNull(decoded)
        assertEquals(type, decoded!!.first)
        assertEquals(seq, decoded.second)
        assertArrayEquals(payload, decoded.third)
    }

    @Test
    fun decode_InsufficientHeader_ReturnsNull() {
        val buffer = ByteArray(FrameCodec.HEADER_SIZE - 1)
        val decoded = FrameCodec.tryDecode(buffer, 0, buffer.size)
        assertNull(decoded)
    }

    @Test
    fun decode_InsufficientPayload_ReturnsNull() {
        val frame = FrameCodec.encode(FrameCodec.PING, 1L, ByteArray(20))
        // 截取掉后 5 字节
        val decoded = FrameCodec.tryDecode(frame, 0, frame.size - 5)
        assertNull(decoded)
    }

    @Test(expected = IllegalArgumentException::class)
    fun decode_BadMagic_ThrowsException() {
        val frame = FrameCodec.encode(FrameCodec.PING, 1L)
        frame[0] = 0x00 // 篡改 magic
        FrameCodec.tryDecode(frame, 0, frame.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun decode_BadVersion_ThrowsException() {
        val frame = FrameCodec.encode(FrameCodec.PING, 1L)
        frame[2] = 99 // 篡改 version
        FrameCodec.tryDecode(frame, 0, frame.size)
    }

    @Test
    fun decode_ConsecutiveFrames_SuccessiveConsumed() {
        val frame1 = FrameCodec.encode(FrameCodec.PING, 10L, "First".toByteArray())
        val frame2 = FrameCodec.encode(FrameCodec.PONG, 20L, "Second".toByteArray())

        val combined = ByteArray(frame1.size + frame2.size)
        frame1.copyInto(combined, 0)
        frame2.copyInto(combined, frame1.size)

        // 解析第一帧
        val dec1 = FrameCodec.tryDecode(combined, 0, combined.size)
        assertNotNull(dec1)
        assertEquals(FrameCodec.PING, dec1!!.first)
        assertEquals(10L, dec1.second)
        assertArrayEquals("First".toByteArray(), dec1.third)

        // 解析第二帧
        val dec2 = FrameCodec.tryDecode(combined, frame1.size, frame2.size)
        assertNotNull(dec2)
        assertEquals(FrameCodec.PONG, dec2!!.first)
        assertEquals(20L, dec2.second)
        assertArrayEquals("Second".toByteArray(), dec2.third)
    }
}
