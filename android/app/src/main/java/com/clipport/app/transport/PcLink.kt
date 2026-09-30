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
 * PC 链路（TLS 客户端，D-01/D-03）。
 * 配对阶段信任任意证书；配对完成后按存储的证书 SHA-256 指纹固定校验。
 * 职责：HELLO/PAIR 握手、收发帧、心跳应答、REQ_TEXT 应答（本端内容服务）与发起（懒拉取）。
 */
class PcLink(
    private val host: String,
    private val port: Int,
    private val pinnedFp: ByteArray?,
    private val self: Hello,
    private val listener: Listener,
    private val onStep: (String) -> Unit = {},
) {
    interface Listener {
        fun onBroadcast(bc: ClipBroadcast)
        fun onTextRequest(req: TextRequest): TextResponse
        fun onPairResult(ok: Boolean, fp: ByteArray)
        fun onDisconnected()
        fun onConnected()
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
    @Volatile var serverFp: ByteArray? = null
        private set

    fun connect(trustAny: Boolean): Boolean {
        val plain = Socket()
        plain.tcpNoDelay = true
        plain.connect(InetSocketAddress(host, port), 5000)
        onStep("TCP 已连 $host:$port")
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(trustManager(trustAny)), SecureRandom())
        val s = ctx.socketFactory.createSocket(plain, host, port, true) as SSLSocket
        s.soTimeout = 10_000   // 握手期 10s 超时，杜绝"永久挂起无反应"
        onStep("TLS 握手中…")
        s.startHandshake()
        s.soTimeout = 0        // 握手完成后恢复无超时（读循环靠心跳判活）
        serverFp = MessageDigest.getInstance("SHA-256").digest(s.session.peerCertificates[0].encoded)
        onStep("TLS 完成 (${s.session.protocol})")
        socket = plain
        ssl = s
        input = s.inputStream
        output = s.outputStream
        alive = true
        send(FrameCodec.HELLO, nextSeq(), self.encode())
        listener.onConnected()
        Thread({ readLoop() }, "clipport-link-read").start()
        return true
    }

    private fun trustManager(trustAny: Boolean): X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            val fp = MessageDigest.getInstance("SHA-256").digest(chain[0].encoded)
            if (trustAny) return
            val pin = pinnedFp ?: throw CertificateException("not paired")
            if (!fp.contentEquals(pin)) throw CertificateException("fingerprint mismatch")
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
        listener.onDisconnected()
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
            FrameCodec.PAIR_OK -> {
                val (ok, fp) = Pairing.decodePairOk(payload)
                listener.onPairResult(ok, fp)
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
