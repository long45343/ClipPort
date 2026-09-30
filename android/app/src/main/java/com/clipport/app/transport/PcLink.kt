package com.clipport.app.transport

import android.util.Log
import com.clipport.app.protocol.*
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * 对端链路（二期对等模式）：双模式 TLS 链路。
 * - client：主动连出（PcLink.client），按 pinnedFp 校验对端证书，trustAny 用于配对
 * - accepted：服务端 accept() 接入（PcLink.accepted），设备身份靠 HELLO/PAIR 应用层判定
 * 两侧握手完成后互发 HELLO（对称识别），收发帧/心跳/懒取完全对称。
 */
class PcLink private constructor(
    private val self: Hello,
    private val listener: Listener,
    private val ownFp: ByteArray?,
    private val onStep: (String) -> Unit,
    val outgoing: Boolean,
) {
    interface Listener {
        fun onBroadcast(bc: ClipBroadcast)
        fun onTextRequest(req: TextRequest): TextResponse
        fun onPairResult(link: PcLink, ok: Boolean, fp: ByteArray)
        fun onDisconnected(link: PcLink)
        fun onConnected(link: PcLink)
        /** 对端 HELLO 就绪（设备ID可判定身份/恢复 paired 态）。 */
        fun onPeerHello(link: PcLink, hello: Hello)
        /** 服务端角色：收到 PAIR_REQ（返回是否接受配对）。 */
        fun onPairRequest(link: PcLink, hash: ByteArray, joinerFp: ByteArray?): Boolean
    }

    companion object {
        /** 主动连出（client 模式）。 */
        fun client(
            host: String,
            port: Int,
            pinnedFp: ByteArray?,
            self: Hello,
            ownFp: ByteArray?,
            listener: Listener,
            onStep: (String) -> Unit = {},
        ): PcLink {
            val link = PcLink(self, listener, ownFp, onStep, outgoing = true)
            link.connectClient(host, port, pinnedFp)
            return link
        }

        /** 服务端 accept() 接入（accepted 模式）。 */
        fun accepted(ssl: SSLSocket, self: Hello, ownFp: ByteArray?, listener: Listener): PcLink {
            val link = PcLink(self, listener, ownFp, {}, outgoing = false)
            link.attachServerSide(ssl)
            return link
        }
    }

    private var socket: Socket? = null
    private var ssl: SSLSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private val sendGate = Object()
    private val pending = ConcurrentHashMap<Long, kotlinx.coroutines.CompletableDeferred<TextResponse>>()
    private val reqSeq = AtomicLong(0x40000000L)
    @Volatile var alive = false
        private set
    @Volatile var paired = false
    @Volatile var serverFp: ByteArray? = null
        private set
    var remoteName: String = ""
        private set
    /** 对端 TLS 证书指纹（SHA-256 of DER）——服务端侧身份判定用。 */
    val peerTlsFp: ByteArray? get() = serverFp
    var peerHelloDeviceId: String? = null
        private set
    val remoteEndpoint: String
        get() = socket?.let { "${it.inetAddress?.hostAddress}:${it.port}" } ?: ""

    /** client 模式：连接+TLS 握手（握手期 10s 超时防永久挂起）。 */
    private fun connectClient(host: String, port: Int, pinnedFp: ByteArray?) {
        val plain = Socket()
        plain.tcpNoDelay = true
        plain.connect(InetSocketAddress(host, port), 5000)
        onStep("TCP 已连 $host:$port")
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(trustManager(pinnedFp)), SecureRandom())
        val s = ctx.socketFactory.createSocket(plain, host, port, true) as SSLSocket
        s.soTimeout = 10_000
        onStep("TLS 握手中…")
        s.startHandshake()
        s.soTimeout = 0
        serverFp = MessageDigest.getInstance("SHA-256").digest(s.session.peerCertificates[0].encoded)
        onStep("TLS 完成 (${s.session.protocol})")
        attach(s)
    }

    /** accepted 模式：包一层已握手的服务端 socket。 */
    private fun attachServerSide(s: SSLSocket) {
        s.soTimeout = 0
        onStep("对端接入 ${s.inetAddress?.hostAddress}")
        attach(s)
    }

    private fun attach(s: SSLSocket) {
        socket = s
        ssl = s
        input = s.inputStream
        output = s.outputStream
        alive = true
        send(FrameCodec.HELLO, nextSeq(), self.encode())   // 对称识别：两侧都发 HELLO
        listener.onConnected(this)
        Thread({ readLoop() }, "clipport-link-read").start()
    }

    private fun trustManager(pinnedFp: ByteArray?): X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            val fp = MessageDigest.getInstance("SHA-256").digest(chain[0].encoded)
            if (pinnedFp == null) throw CertificateException("not paired")
            if (!fp.contentEquals(pinnedFp)) throw CertificateException("fingerprint mismatch")
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    private fun readLoop() {
        val buf = ByteArray(256 * 1024)
        val acc = ArrayList<Byte>(64 * 1024)
        val ins = input ?: return
        try {
            while (alive) {
                val n = ins.read(buf)
                if (n <= 0) break
                for (i in 0 until n) acc.add(buf[i])
                parseFrames(acc)
            }
        } catch (_: Exception) { }
        alive = false
        listener.onDisconnected(this)
    }

    private fun parseFrames(acc: ArrayList<Byte>) {
        val arr = acc.toByteArray()
        var consumed = 0
        while (true) {
            val frame = try { FrameCodec.tryDecode(arr, consumed, arr.size - consumed) } catch (e: Exception) { alive = false; return } ?: break
            val (type, seq, payload) = frame
            consumed += FrameCodec.HEADER_SIZE + payload.size
            dispatch(type, seq, payload)
        }
        if (consumed > 0) {
            acc.clear()
            for (i in consumed until arr.size) acc.add(arr[i])
        }
    }

    private fun dispatch(type: Byte, seq: Long, payload: ByteArray) {
        when (type) {
            FrameCodec.PING -> send(FrameCodec.PONG, seq, payload)
            FrameCodec.HELLO -> {
                val h = Hello.decode(payload)
                remoteName = h.name
                peerHelloDeviceId = h.deviceId.joinToString("") { "%02x".format(it) }
                listener.onPeerHello(this, h)   // 设备ID就绪 → 身份判定/登记
            }
            FrameCodec.PAIR_OK -> {
                val (ok, fp, _) = Pairing.decodePairOk(payload)
                listener.onPairResult(this, ok, fp)
            }
            FrameCodec.PAIR_REQ -> {
                val (hash, joinerFp) = Pairing.decodePairReq(payload)
                val ok = listener.onPairRequest(this, hash, joinerFp)
                if (ok) paired = true
                send(FrameCodec.PAIR_OK, seq, Pairing.encodePairOk(ok, ownFp ?: ByteArray(0), self.name))
            }
            FrameCodec.CLIP_BROADCAST -> listener.onBroadcast(ClipBroadcast.decode(payload))
            FrameCodec.RESP_TEXT -> {
                val resp = TextResponse.decode(payload)
                pending.remove(resp.seq)?.complete(resp)
            }
            FrameCodec.REQ_TEXT -> {
                val resp = listener.onTextRequest(TextRequest.decode(payload))
                send(FrameCodec.RESP_TEXT, seq, resp.encode())
            }
        }
    }

    private fun nextSeq() = reqSeq.incrementAndGet()

    fun send(type: Byte, seq: Long, payload: ByteArray) {
        val out = output ?: return
        synchronized(sendGate) {
            try {
                out.write(FrameCodec.encode(type, seq, payload))
                out.flush()
            } catch (e: Exception) {
                Log.w("PcLink", "send failed", e)
                alive = false
            }
        }
    }

    /** 发起 REQ_TEXT 并等待响应（懒拉取大内容）。 */
    fun requestText(req: TextRequest, timeoutMs: Long = 15_000): TextResponse? {
        val rid = nextSeq()
        val deferred = kotlinx.coroutines.CompletableDeferred<TextResponse>()
        pending[rid] = deferred
        send(FrameCodec.REQ_TEXT, rid, req.encode())
        val result = kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { deferred.await() }
        }
        pending.remove(rid)
        return result
    }

    fun close() {
        alive = false
        try { ssl?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
    }
}
