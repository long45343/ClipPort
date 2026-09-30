package com.clipport.app.transport

import android.content.Context
import android.util.Log
import com.clipport.app.protocol.Hello
import java.security.cert.X509Certificate
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * 对等服务端（二期对等模式）：本设备同时是可被连接的节点。
 * TLS 服务端出本设备证书（对端按指纹校验）；客户端证书不做 TLS 层校验，
 * 设备身份在应用层由 HELLO/PAIR 判定（对端库 + 配对码）。
 */
class PeerServer(
    private val context: Context,
    private val self: Hello,
    private val ownFp: ByteArray,
    private val listener: PcLink.Listener,
    private val onStatus: (String) -> Unit,
) {
    companion object {
        const val DEFAULT_PORT = 47191
    }

    private var serverSocket: SSLServerSocket? = null
    private var thread: Thread? = null
    @Volatile var running = false
        private set

    fun start(port: Int = DEFAULT_PORT) {
        if (running) return
        val identity = PeerIdentity.get(context)
        val pass = CharArray(0)
        val ks = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("clipport", identity.privateKey, pass, identity.chain)
        }
        val kmf = KeyManagerFactory.getInstance("X509").apply { init(ks, pass) }
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, arrayOf<TrustManager>(trustAll), null) }
        val thread = Thread({
            running = true
            try {
                val server = ctx.serverSocketFactory.createServerSocket(port) as SSLServerSocket
                serverSocket = server
                onStatus("对等服务监听 :$port")
                while (running) {
                    val ssl = try { server.accept() as SSLSocket } catch (e: Exception) {
                        if (running) Log.w("PeerServer", "accept failed", e)
                        continue
                    }
                    try {
                        PcLink.accepted(ssl, self, ownFp, listener)
                    } catch (e: Exception) {
                        Log.w("PeerServer", "link setup failed", e)
                        try { ssl.close() } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                if (running) { Log.w("PeerServer", "server error", e); onStatus("对等服务异常: ${e.message}") }
                running = false
            }
        }, "clipport-peer-server")
        this.thread = thread
        thread.start()
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
    }
}
