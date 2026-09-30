package com.clipport.app.transport

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * UDP 广播发现（M8，与 PC 端 UdpDiscovery 对齐）：
 * 每 10s 向 47192/udp 广播 {id,name,type,port,fp}；监听对端通告。
 * 已配对对端出现时由 SyncManager 自动建链；未配对仅登记供配对。
 */
object LanDiscovery {
    const val UDP_PORT = 47192

    @Volatile private var running = false
    private var socket: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var thread: Thread? = null
    private var selfId = ""
    private var selfName = ""
    private var selfType = 1
    private var selfPort = 0
    private var selfFp = ""
    @Volatile var onAnnounced: ((id: String, name: String, type: Int, port: Int, fp: String, ip: String) -> Unit)? = null
    @Volatile var onStatus: ((String) -> Unit)? = null

    fun start(context: Context, id: String, name: String, type: Int, port: Int, fpHex: String,
              announced: (id: String, name: String, type: Int, port: Int, fp: String, ip: String) -> Unit,
              statusCb: (String) -> Unit) {
        if (running) return
        selfId = id; selfName = name; selfType = type; selfPort = port; selfFp = fpHex
        onAnnounced = announced
        onStatus = statusCb
        // 广播接收在部分 WiFi 驱动上需要组播锁
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wm.createMulticastLock("clipport-udp").apply { setReferenceCounted(false); acquire() }
        } catch (_: Exception) { }
        try {
            socket = DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(UDP_PORT))
                broadcast = true
            }
        } catch (e: Exception) {
            Log.w("UdpDiscovery", "bind failed", e)
            onStatus?.invoke("UDP 发现监听失败: ${e.message}")
            return
        }
        running = true
        thread = Thread({
            onStatus?.invoke("UDP 发现已启动 (47192/udp, 10s 周期)")
            val buf = ByteArray(2048)
            while (running) {
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    socket?.receive(pkt)
                    val json = String(pkt.data, 0, pkt.length, Charsets.UTF_8)
                    val o = JSONObject(json)
                    val id = o.optString("id")
                    if (id.isEmpty() || id == selfId) continue
                    val port = o.optInt("port")
                    if (port <= 0) continue
                    onAnnounced?.invoke(id, o.optString("name"), o.optInt("type", 1), port,
                        o.optString("fp"), pkt.address.hostAddress ?: continue)
                } catch (e: Exception) {
                    if (running) Log.w("UdpDiscovery", "recv", e)
                }
            }
        }, "clipport-udp-rx").apply { isDaemon = true; start() }
        sendLoop()
    }

    private fun announcePacket(): ByteArray {
        val o = JSONObject()
            .put("id", selfId)
            .put("name", selfName)
            .put("type", selfType)
            .put("port", selfPort)
            .put("fp", selfFp)
            .put("v", 1)
        return o.toString().toByteArray(Charsets.UTF_8)
    }

    private fun sendLoop() {
        Thread({
            while (running) {
                try {
                    val data = announcePacket()
                    val any = InetAddress.getByName("255.255.255.255")
                    socket?.send(DatagramPacket(data, data.size, any, UDP_PORT))
                    // 子网定向广播（每接口）
                    java.net.NetworkInterface.getNetworkInterfaces().asSequence()
                        .filter { it.isUp && !it.isLoopback }
                        .forEach { nif ->
                            nif.interfaceAddresses.forEach { ia ->
                                val bc = ia.broadcast ?: return@forEach
                                try { socket?.send(DatagramPacket(data, data.size, bc, UDP_PORT)) } catch (_: Exception) {}
                            }
                        }
                } catch (e: Exception) {
                    Log.w("UdpDiscovery", "send", e)
                }
                try { Thread.sleep(10_000) } catch (_: InterruptedException) { return@Thread }
            }
        }, "clipport-udp-tx").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        try { multicastLock?.release() } catch (_: Exception) {}
        multicastLock = null
    }
}
