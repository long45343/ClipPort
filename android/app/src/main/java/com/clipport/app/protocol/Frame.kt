package com.clipport.app.protocol

/** 线格式帧（docs/03-protocol-spec.md §1），与 Windows 端 FrameCodec 逐字节对齐。 */
object FrameCodec {
    const val MAGIC: Int = 0xC1B0
    const val VERSION: Int = 1
    const val HEADER_SIZE = 12

    const val HELLO: Byte = 0x01
    const val PAIR_REQ: Byte = 0x02
    const val PAIR_OK: Byte = 0x03
    const val CLIP_BROADCAST: Byte = 0x10
    const val REQ_TEXT: Byte = 0x11
    const val RESP_TEXT: Byte = 0x12
    const val CANCEL: Byte = 0x18
    const val PING: Byte = 0x20
    const val PONG: Byte = 0x21

    const val MAX_PAYLOAD = 64 * 1024 * 1024

    fun encode(type: Byte, seq: Long, payload: ByteArray = ByteArray(0)): ByteArray {
        val buf = ByteArray(HEADER_SIZE + payload.size)
        buf[0] = (MAGIC and 0xFF).toByte()
        buf[1] = ((MAGIC shr 8) and 0xFF).toByte()
        buf[2] = VERSION.toByte()
        buf[3] = type
        putU32(buf, 4, seq)
        putU32(buf, 8, payload.size.toLong())
        payload.copyInto(buf, HEADER_SIZE)
        return buf
    }

    /** 数据不足返回 null（consumed=0）；坏帧抛异常。 */
    fun tryDecode(buf: ByteArray, offset: Int, length: Int): Triple<Byte, Long, ByteArray>? {
        if (length < HEADER_SIZE) return null
        if ((buf[offset].toInt() and 0xFF) != (MAGIC and 0xFF) || (buf[offset + 1].toInt() and 0xFF) != ((MAGIC shr 8) and 0xFF))
            throw IllegalArgumentException("bad magic")
        if ((buf[offset + 2].toInt() and 0xFF) != VERSION) throw IllegalArgumentException("bad version")
        val len = ((buf[offset + 11].toLong() and 0xFF) shl 24) or ((buf[offset + 10].toLong() and 0xFF) shl 16) or
            ((buf[offset + 9].toLong() and 0xFF) shl 8) or (buf[offset + 8].toLong() and 0xFF)
        if (len < 0 || len > MAX_PAYLOAD) throw IllegalArgumentException("bad length $len")
        if (length < HEADER_SIZE + len.toInt()) return null
        val seq = ((buf[offset + 7].toLong() and 0xFF) shl 24) or ((buf[offset + 6].toLong() and 0xFF) shl 16) or
            ((buf[offset + 5].toLong() and 0xFF) shl 8) or (buf[offset + 4].toLong() and 0xFF)
        val payload = buf.copyOfRange(offset + HEADER_SIZE, offset + HEADER_SIZE + len.toInt())
        val type = buf[offset + 3]
        return Triple(type, seq, payload)
    }

    fun putU32(buf: ByteArray, at: Int, v: Long) {
        buf[at] = (v and 0xFF).toByte()
        buf[at + 1] = ((v shr 8) and 0xFF).toByte()
        buf[at + 2] = ((v shr 16) and 0xFF).toByte()
        buf[at + 3] = ((v shr 24) and 0xFF).toByte()
    }
}

/** 最小 Protobuf 线格式编解码（varint / length-delimited）。 */
object Proto {
    fun writeVarint(o: ArrayList<Byte>, v: Long) {
        var x = v
        while (x >= 0x80) { o.add(((x and 0x7F) or 0x80).toByte()); x = x ushr 7 }
        o.add(x.toByte())
    }

    private fun tag(o: ArrayList<Byte>, field: Int, wireType: Int) = writeVarint(o, ((field shl 3) or wireType).toLong())

    fun uint(o: ArrayList<Byte>, field: Int, v: Long) { if (v != 0L) { tag(o, field, 0); writeVarint(o, v) } }
    fun bool(o: ArrayList<Byte>, field: Int, v: Boolean) { if (v) { tag(o, field, 0); writeVarint(o, 1) } }
    fun bytes(o: ArrayList<Byte>, field: Int, b: ByteArray) {
        if (b.isEmpty()) return
        tag(o, field, 2); writeVarint(o, b.size.toLong()); for (x in b) o.add(x)
    }
    fun string(o: ArrayList<Byte>, field: Int, s: String?) { if (!s.isNullOrEmpty()) bytes(o, field, s.toByteArray(Charsets.UTF_8)) }
    fun message(o: ArrayList<Byte>, field: Int, m: ByteArray) { tag(o, field, 2); writeVarint(o, m.size.toLong()); for (x in m) o.add(x) }

    class Reader(private val buf: ByteArray) {
        private var pos = 0
        val done: Boolean get() = pos >= buf.size

        fun readTag(): Pair<Int, Int>? {
            val v = readVarint() ?: return null
            return (v.toInt() shr 3) to (v.toInt() and 7)
        }

        fun readVarint(): Long? {
            var v = 0L; var shift = 0
            while (pos < buf.size) {
                val b = buf[pos++].toInt() and 0xFF
                v = v or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return v
                shift += 7
                if (shift > 63) return null
            }
            return null
        }

        fun uint32(): Long = readVarint() ?: 0
        fun bool(): Boolean = readVarint() != 0L
        fun bytes(): ByteArray {
            val n = (readVarint() ?: 0).toInt()
            if (pos + n > buf.size) throw IllegalArgumentException("overrun")
            val r = buf.copyOfRange(pos, pos + n); pos += n; return r
        }
        fun string(): String = String(bytes(), Charsets.UTF_8)
        fun skip(wireType: Int) {
            when (wireType) {
                0 -> readVarint()
                1 -> pos += 8
                2 -> pos += (readVarint() ?: 0).toInt()
                5 -> pos += 4
                else -> throw IllegalArgumentException("bad wire type $wireType")
            }
        }
    }
}

object Mime {
    const val TEXT = 0L
    const val HTML = 1L
    const val BOTH_TEXT_HTML = 2L
    const val IMAGE_PNG = 3L
    const val FILE = 4L
}

/** CLIP_BROADCAST 负载：ClipBroadcast（spec §3）。 */
class ClipBroadcast {
    var deviceId: ByteArray = ByteArray(0)
    var seq: Long = 0
    var needChannel: Boolean = false
    var mimeCodes: MutableList<Long> = ArrayList()
    var inline: ClipInline? = null

    fun encode(): ByteArray {
        val o = ArrayList<Byte>(64)
        Proto.bytes(o, 1, deviceId)
        Proto.uint(o, 2, seq)
        Proto.bool(o, 3, needChannel)
        for (m in mimeCodes) Proto.uint(o, 4, m)
        inline?.let { Proto.message(o, 5, it.encode()) }
        return o.toByteArray()
    }

    companion object {
        fun decode(p: ByteArray): ClipBroadcast {
            val r = Proto.Reader(p)
            val m = ClipBroadcast()
            while (true) {
                val (f, wt) = r.readTag() ?: break
                when (f) {
                    1 -> m.deviceId = r.bytes()
                    2 -> m.seq = r.uint32()
                    3 -> m.needChannel = r.bool()
                    4 -> m.mimeCodes.add(r.uint32())
                    5 -> m.inline = ClipInline.decode(r.bytes())
                    else -> r.skip(wt)
                }
            }
            return m
        }
    }
}

class ClipInline {
    var text: String? = null
    var html: String? = null
    var imagePng: ByteArray? = null

    fun encode(): ByteArray {
        val o = ArrayList<Byte>()
        Proto.string(o, 1, text)
        Proto.string(o, 2, html)
        imagePng?.let { Proto.bytes(o, 3, it) }
        return o.toByteArray()
    }

    companion object {
        fun decode(p: ByteArray): ClipInline {
            val r = Proto.Reader(p)
            val m = ClipInline()
            while (true) {
                val (f, wt) = r.readTag() ?: break
                when (f) {
                    1 -> m.text = r.string()
                    2 -> m.html = r.string()
                    3 -> m.imagePng = r.bytes()
                    else -> r.skip(wt)
                }
            }
            return m
        }
    }
}

/** HELLO：设备名(1) 设备类型(2: 0=pc,1=phone) 协议版本(3) 设备ID(4)。 */
class Hello {
    var name: String = ""
    var deviceType = 0
    var protoVer = 1
    var deviceId: ByteArray = ByteArray(0)

    fun encode(): ByteArray {
        val o = ArrayList<Byte>()
        Proto.string(o, 1, name)
        Proto.uint(o, 2, deviceType.toLong())
        Proto.uint(o, 3, protoVer.toLong())
        Proto.bytes(o, 4, deviceId)
        return o.toByteArray()
    }

    companion object {
        fun decode(p: ByteArray): Hello {
            val r = Proto.Reader(p)
            val m = Hello()
            while (true) {
                val (f, wt) = r.readTag() ?: break
                when (f) {
                    1 -> m.name = r.string()
                    2 -> m.deviceType = r.uint32().toInt()
                    3 -> m.protoVer = r.uint32().toInt()
                    4 -> m.deviceId = r.bytes()
                    else -> r.skip(wt)
                }
            }
            return m
        }
    }
}

object Pairing {
    fun encodePairReq(codeHash: ByteArray): ByteArray {
        val o = ArrayList<Byte>()
        Proto.bytes(o, 1, codeHash)
        return o.toByteArray()
    }

    fun decodePairReq(p: ByteArray): ByteArray {
        val r = Proto.Reader(p)
        while (true) {
            val (f, wt) = r.readTag() ?: break
            if (f == 1) return r.bytes()
            r.skip(wt)
        }
        return ByteArray(0)
    }

    fun encodePairOk(ok: Boolean, certFp: ByteArray): ByteArray {
        val o = ArrayList<Byte>()
        Proto.bool(o, 1, ok)
        Proto.bytes(o, 2, certFp)
        return o.toByteArray()
    }

    fun decodePairOk(p: ByteArray): Pair<Boolean, ByteArray> {
        var ok = false; var fp = ByteArray(0)
        val r = Proto.Reader(p)
        while (true) {
            val (f, wt) = r.readTag() ?: break
            when (f) { 1 -> ok = r.bool(); 2 -> fp = r.bytes(); else -> r.skip(wt) }
        }
        return ok to fp
    }
}

class TextRequest {
    var seq: Long = 0
    var itemId: Long = 0
    var mime: Long = 0

    fun encode(): ByteArray {
        val o = ArrayList<Byte>()
        Proto.uint(o, 1, seq); Proto.uint(o, 2, itemId); Proto.uint(o, 3, mime)
        return o.toByteArray()
    }

    companion object {
        fun decode(p: ByteArray): TextRequest {
            val r = Proto.Reader(p)
            val m = TextRequest()
            while (true) {
                val (f, wt) = r.readTag() ?: break
                when (f) { 1 -> m.seq = r.uint32(); 2 -> m.itemId = r.uint32(); 3 -> m.mime = r.uint32(); else -> r.skip(wt) }
            }
            return m
        }
    }
}

class TextResponse {
    var seq: Long = 0
    var itemId: Long = 0
    var mime: Long = 0
    var status: Long = 0 // 0=ok 1=fail
    var content: ByteArray = ByteArray(0)

    fun encode(): ByteArray {
        val o = ArrayList<Byte>()
        Proto.uint(o, 1, seq); Proto.uint(o, 2, itemId); Proto.uint(o, 3, mime); Proto.uint(o, 4, status)
        Proto.bytes(o, 5, content)
        return o.toByteArray()
    }

    companion object {
        fun decode(p: ByteArray): TextResponse {
            val r = Proto.Reader(p)
            val m = TextResponse()
            while (true) {
                val (f, wt) = r.readTag() ?: break
                when (f) {
                    1 -> m.seq = r.uint32(); 2 -> m.itemId = r.uint32(); 3 -> m.mime = r.uint32()
                    4 -> m.status = r.uint32(); 5 -> m.content = r.bytes()
                    else -> r.skip(wt)
                }
            }
            return m
        }
    }
}
