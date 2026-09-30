package com.clipport.app

import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import com.clipport.app.clip.ClipConst
import com.clipport.app.clip.ClipFilter
import com.clipport.app.clip.ClipPayload
import com.clipport.app.clip.DedupeWindow
import com.clipport.app.clip.MimeUtil
import com.clipport.app.clip.RemoteClipApplier
import com.clipport.app.protocol.*
import com.clipport.app.transport.PeerBook
import com.clipport.app.transport.PeerIdentity
import com.clipport.app.transport.PeerServer
import com.clipport.app.transport.LanDiscovery
import com.clipport.app.transport.PcDiscovery
import com.clipport.app.transport.PcLink
import java.security.MessageDigest

/**
 * 同步总管（spec §2 + 二期对等模式）：多链路连接生命周期 / 发布·接收·去重 / 懒拉取 / 本端内容服务
 * / 服务端角色（每台设备皆可被连）/ 对称配对。
 * 线程模型：同步逻辑在 handler 线程串行；每条链路自带读线程。
 */
class SyncManager(
    private val context: Context,
    private val prefs: Prefs,
    private val status: (String) -> Unit,
) : PcLink.Listener {
    companion object { const val TAG = "SyncManager" }

    private val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val applier = RemoteClipApplier(cm, prefs.clearMs, context = context, onStatus = { s -> status(s) })
    private val dedupe = DedupeWindow()
    private val handlerThread = android.os.HandlerThread("clipport-sync").apply { start() }
    private val handler = android.os.Handler(handlerThread.looper)

    private val links = java.util.concurrent.CopyOnWriteArrayList<PcLink>()
    private val peerBook by lazy { PeerBook(context) }
    private var peerServer: PeerServer? = null
    @Volatile private var pairingOpen = false
    @Volatile private var pairingCodeHash: ByteArray? = null
    @Volatile private var lastEchoText: String? = null
    @Volatile private var lastEchoAt = 0L
    @Volatile private var lastLocalPayload: ClipPayload? = null
    @Volatile private var lastLocalAt = 0L
    private val seqCounter = java.util.concurrent.atomic.AtomicLong((0 until 0x3FFFFFFF).random().toLong())
    @Volatile var pairingMode = false

    @Volatile private var localPending = false

    /** 本地剪贴板变化（Service/Watcher 回调，任意线程）；100ms 防抖合并（spec §1.3）。 */
    fun onLocalClipChanged() {
        if (localPending) return
        localPending = true
        handler.postDelayed({
            localPending = false
            handleLocalClip()
        }, ClipConst.DEBOUNCE_MS)
    }

    private fun handleLocalClip() {
        try {
            if (!prefs.syncEnabled) return
            if (ClipFilter.isSelfLabeled(cm.primaryClip?.description)) return          // 防回环第1道
            val text = MimeUtil.textOf(cm)
            if (text == null) {
                // 监听回调到了但读不到数据 = 后台读取被系统限制（无 hook/无焦点）
                status("剪贴板读取受限——需启用 LSPosed 模块（系统作用域）并重启，或等待悬浮窗读取模式")
                return
            }
            if (text == lastEchoText && System.currentTimeMillis() - lastEchoAt < ClipConst.ECHO_WINDOW_MS) return // 防回声
            val htmlText = cm.primaryClip?.let { if (it.itemCount > 0) it.getItemAt(0).htmlText?.toString() else null }
            val payload = ClipPayload(text, htmlText, null)
            publish(payload)
        } catch (e: Exception) {
            Log.w(TAG, "local clip error", e)
        }
    }

    private fun publish(payload: ClipPayload) {
        val targets = links.filter { it.alive && it.paired }
        val size = (payload.text?.length ?: 0) * 2 + (payload.html?.length ?: 0) + (payload.imagePng?.size ?: 0)
        val needChannel = size > ClipConst.LAZY_THRESHOLD_BYTES
        val seq = seqCounter.incrementAndGet()
        if (targets.isEmpty()) { status("已编码 seq=$seq 但无已配对连接，广播未送达"); return }
        lastLocalPayload = payload
        lastLocalAt = System.currentTimeMillis()
        val mimes = ArrayList<Long>()
        if (payload.text != null) mimes.add(if (payload.html != null) Mime.BOTH_TEXT_HTML else Mime.TEXT)
        else if (payload.html != null) mimes.add(Mime.HTML)
        if (payload.imagePng != null) mimes.add(Mime.IMAGE_PNG)
        val bc = ClipBroadcast().apply {
            deviceId = prefs.deviceIdBytes()
            this.seq = seq
            this.needChannel = needChannel
            mimeCodes = mimes
            if (!needChannel) inline = ClipInline().apply {
                this@apply.text = payload.text
                this@apply.html = payload.html
                this@apply.imagePng = payload.imagePng
            }
        }
        var sent = 0
        for (l in targets) {
            l.send(FrameCodec.CLIP_BROADCAST, seq, bc.encode())
            sent++
        }
        status("已发布 seq=$seq → $sent 台设备${if (needChannel) " (懒)" else ""}")
    }

    // ---- PcLink.Listener ----
    override fun onBroadcast(bc: ClipBroadcast) {
        handler.post { handleRemote(bc) }
    }

    private fun handleRemote(bc: ClipBroadcast) {
        try {
            if (!prefs.syncEnabled) return
            val dev = bc.deviceId.joinToString("") { "%02x".format(it) }
            if (dedupe.seen(dev, bc.seq)) return
            var text: String? = null
            var html: String? = null
            var image: ByteArray? = null
            if (!bc.needChannel) {
                bc.inline?.let { text = it.text; html = it.html; image = it.imagePng }
            } else {
                val l = links.firstOrNull { it.alive && it.paired } ?: return
                for (mime in bc.mimeCodes.distinct()) {
                    val resp = l.requestText(TextRequest().apply { seq = bc.seq; itemId = 0; this.mime = mime }) ?: continue
                    if (resp.status != 0L) continue
                    when (mime) {
                        Mime.TEXT -> text = String(resp.content, Charsets.UTF_8)
                        Mime.HTML -> html = String(resp.content, Charsets.UTF_8)
                        Mime.BOTH_TEXT_HTML -> {
                            html = String(resp.content, Charsets.UTF_8)
                            l.requestText(TextRequest().apply { seq = bc.seq; itemId = 0; this.mime = Mime.TEXT })
                                ?.takeIf { it.status == 0L }?.let { text = String(it.content, Charsets.UTF_8) }
                        }
                        Mime.IMAGE_PNG -> image = resp.content
                    }
                }
            }
            if (text.isNullOrEmpty() && html.isNullOrEmpty() && image == null) return
            // 锁屏缓存门（spec D-16）
            val km = context.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
            if (km.isKeyguardLocked) {
                pendingRemote = Triple(text, html, image)
                registerUnlockReceiver()
                status("锁屏中，已缓存待解锁写入")
                return
            }
            writeRemote(text, html, image)
        } catch (e: Exception) {
            Log.w(TAG, "remote clip error", e)
        }
    }

    private var pendingRemote: Triple<String?, String?, ByteArray?>? = null
    private var unlockReceiver: android.content.BroadcastReceiver? = null

    private fun registerUnlockReceiver() {
        if (unlockReceiver != null) return
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: android.content.Intent?) {
                if (intent?.action != android.content.Intent.ACTION_USER_PRESENT) return
                pendingRemote?.let { writeRemote(it.first, it.second, it.third) }
                pendingRemote = null
                try { context.unregisterReceiver(this) } catch (_: Exception) {}
                unlockReceiver = null
            }
        }
        unlockReceiver = r
        context.registerReceiver(r, android.content.IntentFilter(android.content.Intent.ACTION_USER_PRESENT))
    }

    private fun writeRemote(text: String?, html: String?, image: ByteArray?) {
        applier.apply(text, html, image)
        lastEchoText = text
        lastEchoAt = System.currentTimeMillis()
        status("已写入远端剪贴板${if (prefs.clearMs > 0) "（${prefs.clearMs / 1000}s 后清除）" else ""}")
    }

    override fun onTextRequest(req: TextRequest): TextResponse {
        // 本端作为内容源：对端懒拉取最后一次复制内容（180s 持有）
        val resp = TextResponse().apply { seq = req.seq; itemId = req.itemId; mime = req.mime }
        val p = lastLocalPayload ?: return resp.apply { status = 1 }
        if (System.currentTimeMillis() - lastLocalAt > 180_000) return resp.apply { status = 1 }
        when (req.mime) {
            Mime.TEXT -> if (p.text != null) { resp.content = p.text.toByteArray(); resp.status = 0 }
            Mime.HTML -> if (p.html != null) { resp.content = p.html.toByteArray(); resp.status = 0 }
            Mime.IMAGE_PNG -> if (p.imagePng != null) { resp.content = p.imagePng; resp.status = 0 }
            else -> resp.status = 1
        }
        return resp
    }

    override fun onPeerHello(link: PcLink, hello: Hello) {
        val id = hello.deviceId.joinToString("") { "%02x".format(it) }
        if (peerBook.known(id)) link.paired = true
        peerBook.upsert(id, hello.name, null, link.remoteEndpoint)
        status("对端: ${hello.name}（${if (link.paired) "已配对" else "未配对"}）")
    }

    /** 服务端角色：收到 PAIR_REQ——配对窗口匹配或对端 TLS 指纹已登记（幂等）则放行。 */
    override fun onPairRequest(link: PcLink, hash: ByteArray, joinerFp: ByteArray?): Boolean {
        val codeOk = pairingOpen && pairingCodeHash != null && hash.contentEquals(pairingCodeHash)
        val fpHex = link.peerTlsFp?.joinToString("") { b -> "%02x".format(b) }
        val idempotent = fpHex != null && peerBook.all().any { it.certFpHex == fpHex }
        return if (codeOk || idempotent) {
            if (codeOk) { pairingOpen = false; pairingCodeHash = null }
            peerBook.upsert(
                link.peerHelloDeviceId ?: "unknown-${System.currentTimeMillis()}",
                link.remoteName,
                joinerFp?.joinToString("") { b -> "%02x".format(b) } ?: fpHex,
                link.remoteEndpoint,
                markPaired = true,
            )
            status("配对成功（本端作为服务端）")
            true
        } else {
            status("收到配对请求但配对码不匹配/窗口未开启")
            false
        }
    }

    /** 本端作为加入方：PAIR_OK 结果处理（登记对端 + 指纹固定 + 重连）。 */
    override fun onPairResult(link: PcLink, ok: Boolean, fp: ByteArray) {
        if (ok && fp.isNotEmpty()) {
            val fpHex = fp.joinToString("") { "%02x".format(it) }
            prefs.serverFpHex = fpHex
            peerBook.upsert(
                link.peerHelloDeviceId ?: "peer-${fpHex.take(12)}",
                link.remoteName.ifEmpty { "peer" },
                fpHex,
                link.remoteEndpoint,
                markPaired = true,
            )
            pairingMode = false
            status("配对成功，指纹已固定")
            link.close()
            connect(pinned = true)
        } else {
            status("配对失败：配对码不匹配")
        }
    }

    override fun onConnected(link: PcLink) {
        if (!links.contains(link)) links.add(link)
        if (link.outgoing && prefs.serverFpHex != null) link.paired = true
        status(
            if (prefs.serverFpHex != null || link.paired) "已连接到对端，等待剪切板…"
            else "已连接但未配对——请输入对端配对码后点「配对」"
        )
        probeClipboardPrivilege()
    }

    /** 连接后自检剪贴板读写特权（hook 或焦点），结果上状态栏；顺带恢复原剪贴板。 */
    private fun probeClipboardPrivilege() {
        handler.post {
            val saved = try { cm.primaryClip?.getItemAt(0)?.coerceToText(null)?.toString() } catch (_: Exception) { null }
            val probeText = "__clipport_probe_${System.currentTimeMillis() / 1000 % 100000}__"
            val writeOk = try {
                cm.setPrimaryClip(
                    android.content.ClipData(
                        android.content.ClipDescription(ClipConst.SELF_LABEL, arrayOf("text/plain")),
                        android.content.ClipData.Item(probeText)
                    )
                )
                true
            } catch (_: Exception) { false }
            val readBack = try { cm.primaryClip?.getItemAt(0)?.text?.toString() } catch (_: Exception) { null }
            val privileged = writeOk && readBack == probeText
            try {
                if (privileged) {
                    if (saved != null) applier.apply(saved, null, null) else cm.clearPrimaryClip()
                }
            } catch (_: Exception) { }
            status(
                if (privileged) "剪贴板特权正常（hook 生效）"
                else "剪贴板读写受限——请在 LSPosed 启用本模块（作用域:系统框架）并重启；或授予悬浮窗权限用降级通道"
            )
        }
    }

    override fun onDisconnected(link: PcLink) {
        links.remove(link)
        if (link.outgoing) {
            status("连接断开，3s 后重连")
            scheduleReconnect()
        } else {
            status("对端链接断开（服务端保持监听）")
        }
    }

    /** BLE 自动发现 PC（D-02）：扫描广播回填 IP 后连接；BLE 不可用返回 false 走手动兜底。 */
    fun startAutoDiscover(): Boolean {
        status("正在通过 BLE 发现 PC…")
        PcDiscovery.onFound = { ip, port ->
            handler.post {
                prefs.host = ip
                prefs.port = port
                status("发现 PC: $ip:$port，连接中…")
                connect(pinned = prefs.serverFpHex != null)
            }
        }
        val ok = PcDiscovery.start(prefs.serverFpHex) { count ->
            status(
                when {
                    count < 0 -> "扫描失败（错误码 ${-count}）——检查蓝牙/定位权限"
                    count == 0 -> "30 秒未收到任何 BLE 广播——确认两端蓝牙已开启、PC 应用正在运行"
                    else -> "扫到 $count 条广播但无 ClipPort 匹配——查看 PC 日志「BLE 广播状态」"
                }
            )
        }
        if (!ok) status("BLE 不可用（权限/硬件），请手动填写 IP")
        return ok
    }

    /** 连接对端。pinned=true 用已固定指纹；pairingMode=true 附带配对请求。 */
    fun connect(pinned: Boolean): PcLink? {
        val host = prefs.host
        if (host.isNullOrEmpty()) return null
        handler.post {
            try {
                val self = selfHello()
                val l = PcLink.client(
                    host, prefs.port,
                    pinnedFp = prefs.serverFpHex?.hexToBytes(),
                    self = self,
                    ownFp = PeerIdentity.fingerprint(context),
                    listener = this,
                    onStep = { step -> status(step) },
                )
                links.add(l)
                if (pairingMode) {
                    val codeHash = MessageDigest.getInstance("SHA-256")
                        .digest("clipport:${prefs.pairingCode}".toByteArray(Charsets.UTF_8))
                    l.send(FrameCodec.PAIR_REQ, seqCounter.incrementAndGet(),
                        Pairing.encodePairReq(codeHash, PeerIdentity.fingerprint(context)))
                }
            } catch (e: Exception) {
                Log.w(TAG, "connect failed", e)
                status("连接失败：${e.message}")
                scheduleReconnect()
            }
        }
        return null
    }

    private fun selfHello() = Hello().apply {
        name = android.os.Build.MODEL
        deviceType = 1
        protoVer = 1
        deviceId = prefs.deviceIdBytes()
    }

    private var reconnectScheduled = false
    private fun scheduleReconnect() {
        if (reconnectScheduled) return
        reconnectScheduled = true
        handler.postDelayed({ reconnectScheduled = false; connect(pinned = prefs.serverFpHex != null) }, 3000)
    }

    fun disconnect() {
        links.forEach { it.close() }
        links.clear()
        peerServer?.stop()
        LanDiscovery.stop()
        udpDiscoveryStarted = false
    }

    /** 开启配对窗口（本端作为服务端被连方）。返回 6 位码。 */
    fun openPairing(): String {
        val code = (100000..999999).random().toString()
        pairingCodeHash = MessageDigest.getInstance("SHA-256")
            .digest("clipport:$code".toByteArray(Charsets.UTF_8))
        pairingOpen = true
        return code
    }

    private var udpDiscoveryStarted = false
    private val connectAttempts = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** M8: UDP 广播发现——通告自己 + 监听对端；已配对对端出现时自动建链。 */
    private fun startUdpDiscovery() {
        if (udpDiscoveryStarted) return
        udpDiscoveryStarted = true
        val fpHex = PeerIdentity.fingerprint(context).joinToString("") { b -> "%02x".format(b) }
        val id = prefs.deviceIdBytes().joinToString("") { b -> "%02x".format(b) }
        LanDiscovery.start(
            context = context,
            id = id,
            name = android.os.Build.MODEL,
            type = 1,
            port = PeerServer.DEFAULT_PORT,
            fpHex = fpHex.take(16),
            announced = { dId, name, type, port, fp, ip ->
                handler.post { handleAnnounced(dId, name, type, port, fp, ip) }
            },
            statusCb = { s -> status(s) }
        )
    }

    private fun handleAnnounced(dId: String, name: String, type: Int, port: Int, fp: String, ip: String) {
        try {
            val peer = peerBook.upsert(dId, name, fp.take(16).ifEmpty { null }, "$ip:$port")
            if (peer.paired) {
                // 已配对：自动建链。仲裁：仅 deviceId 较小的一方连出（防双向重复建链）
                val myId = prefs.deviceIdBytes().joinToString("") { b -> "%02x".format(b) }
                if (myId > dId) return
                if (links.any { it.alive && it.peerHelloDeviceId == dId }) return
                val last = connectAttempts[dId]
                if (last != null && System.currentTimeMillis() - last < 15_000) return
                connectAttempts[dId] = System.currentTimeMillis()
                status("自动连接已配对设备 $name ($ip:$port)")
                prefs.host = ip
                prefs.port = port
                connect(pinned = true)
            } else {
                status("发现未配对设备 $name ($ip:$port)——开启对端配对窗口后输码连接")
            }
        } catch (e: Exception) {
            Log.w(TAG, "announce error", e)
        }
    }

    /** 二期: 启动对等服务端（每台设备皆可被连）。 */
    fun startServerRole() {
        if (peerServer?.running == true) return
        peerServer = PeerServer(context, selfHello(), PeerIdentity.fingerprint(context), this) { s -> status(s) }
            .also { it.start() }
        startUdpDiscovery()
    }
}

/** 应用设置（SharedPreferences）。 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("clipport", Context.MODE_PRIVATE)

    var host: String?
        get() = sp.getString("host", null)
        set(v) = sp.edit().putString("host", v).apply()
    var port: Int
        get() = sp.getInt("port", 47190)
        set(v) = sp.edit().putInt("port", v).apply()
    var serverFpHex: String?
        get() = sp.getString("server_fp", null)
        set(v) = sp.edit().putString("server_fp", v).apply()
    var pairingCode: String
        get() = sp.getString("pairing_code", "") ?: ""
        set(v) = sp.edit().putString("pairing_code", v).apply()
    var syncEnabled: Boolean
        get() = sp.getBoolean("sync_enabled", true)
        set(v) = sp.edit().putBoolean("sync_enabled", v).apply()
    var clearMs: Long
        get() = sp.getLong("clear_ms", ClipConst.REMOTE_CLEAR_MS)
        set(v) = sp.edit().putLong("clear_ms", v).apply()

    fun deviceIdBytes(): ByteArray {
        var id = sp.getString("device_id", null)
        if (id == null) {
            id = java.util.UUID.randomUUID().toString().replace("-", "")
            sp.edit().putString("device_id", id).apply()
        }
        return id.chunked(2).take(16).map { it.toInt(16).toByte() }.toByteArray()
    }
}

private fun String.hexToBytes(): ByteArray =
    chunked(2).map { it.toInt(16).toByte() }.toByteArray()
