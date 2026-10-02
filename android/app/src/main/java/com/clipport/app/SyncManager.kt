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
import com.clipport.app.transport.PcLink
import com.clipport.app.transport.ReconnectStateMachine
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
    @Volatile private var lastClipTimestamp = 0L
    @Volatile private var lastLocalPayload: ClipPayload? = null
    @Volatile private var lastLocalAt = 0L
    private val seqCounter = java.util.concurrent.atomic.AtomicLong((0 until 0x3FFFFFFF).random().toLong())
    @Volatile var pairingMode = false

    @Volatile private var localPending = false

    private var pendingReconnectRunnable: Runnable? = null
    /** 自动建链目标（UDP 通告/网络事件携带），与手动目标（manualHost/manualPort）解耦（B-6/U-4）。 */
    private data class AutoTarget(val deviceId: String, val host: String, val port: Int)
    @Volatile private var autoConnectTarget: AutoTarget? = null
    /** 最近一次 connectTo 的目标（配对成功后 pinned 重连用）。 */
    @Volatile private var lastTarget: AutoTarget? = null

    private val reconnectMachine = ReconnectStateMachine(
        maxAttempts = 5,
        backoffDelaysMs = longArrayOf(3000L, 6000L, 12000L, 60000L),
        scheduler = { delay, action ->
            val r = Runnable { action() }
            pendingReconnectRunnable = r
            handler.postDelayed(r, delay)
        },
        cancelScheduled = {
            pendingReconnectRunnable?.let { handler.removeCallbacks(it) }
            pendingReconnectRunnable = null
        },
        onExecuteConnect = {
            val t = autoConnectTarget
            if (t != null) {
                val fp = peerBook.fingerprintOf(t.deviceId)?.joinToString("") { b -> "%02x".format(b) }
                    ?.takeIf { it.length == 64 }
                connectTo(t.host, t.port, pinnedFpHex = fp ?: pinnedFpHexFor(t.host, t.port))
            } else {
                connectManual()
            }
        },
        onStateChanged = { state, delay, reason ->
            when (state) {
                ReconnectStateMachine.State.BACKOFF -> status("连接断开，${delay / 1000}s 后重试 ($reason)")
                ReconnectStateMachine.State.FROZEN -> status("连续 5 次重连失败，进入休眠省电（等待对端上线或网络变动唤醒）")
                ReconnectStateMachine.State.CONNECTING -> status("正在发起重连…")
                ReconnectStateMachine.State.IDLE -> {}
            }
        }
    )

    init {
        com.clipport.app.xposed.FloatingClipboardBridge.register { text, html ->
            onFloatingClipRead(text, html)
        }
    }

    private fun onFloatingClipRead(text: String?, html: String?) {
        handler.post {
            try {
                if (!prefs.syncEnabled) return@post
                if (text.isNullOrEmpty() && html.isNullOrEmpty()) return@post
                if (text == lastEchoText && System.currentTimeMillis() - lastEchoAt < ClipConst.ECHO_WINDOW_MS) return@post
                val last = lastLocalPayload
                if (last != null && System.currentTimeMillis() - lastLocalAt < ClipConst.LOCAL_REWRITE_GUARD_MS &&
                    ClipFilter.sameContent(text, html, null, last.text, last.html, last.imagePng)) return@post
                val payload = ClipPayload(text, html, null)
                publish(payload)
                status("已通过悬浮降级通道读取并同步剪贴板")
            } catch (e: Exception) {
                Log.w(TAG, "floating clip handling error", e)
            }
        }
    }

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
            val primary = cm.primaryClip ?: return
            val desc = primary.description
            if (ClipFilter.isSelfLabeled(desc)) return // 防回环第1道：私有标签
            if (ClipFilter.isSameTimestamp(desc, lastClipTimestamp)) return // 防重复第2道：系统时间戳未变阻断（对齐小米 UniversalClipDataPublisher）

            // D-25=A: 过滤常规纯文件。若仅含非图片 URI，严格静默忽略
            if (primary.itemCount > 0 && primary.getItemAt(0).uri != null) {
                val uri = primary.getItemAt(0).uri
                val mime = context.contentResolver.getType(uri)
                    ?: (if (desc.mimeTypeCount > 0) desc.getMimeType(0) else "")
                if (!mime.startsWith("image/")) {
                    return // 纯文件，静默忽略
                }
            }

            val text = MimeUtil.textOf(cm)
            val htmlText = if (primary.itemCount > 0) primary.getItemAt(0).htmlText?.toString() else null
            val imagePng = MimeUtil.imagePngOf(context, cm)

            if (text == null && htmlText == null && imagePng == null) {
                // 监听回调到了但读不到数据 = 后台读取被系统限制（无 hook/无焦点）
                status("剪贴板读取受限——需启用 LSPosed 模块（系统作用域）并重启，或等待悬浮窗读取模式")
                return
            }
            if (text != null && text == lastEchoText && System.currentTimeMillis() - lastEchoAt < ClipConst.ECHO_WINDOW_MS) return // 防回声第3道

            // 防改写第4道（D-07-B"内容没变不重发"）：窗口期内与刚发布内容完全一致 = 系统剪贴板管理器
            // 剥私有标签、改时间戳后的回写（MIUI/HyperOS 剪贴板历史），跳过并吸收新时间戳基线
            lastLocalPayload?.let { last ->
                if (System.currentTimeMillis() - lastLocalAt < ClipConst.LOCAL_REWRITE_GUARD_MS &&
                    ClipFilter.sameContent(text, htmlText, imagePng, last.text, last.html, last.imagePng)) {
                    if (desc != null && desc.timestamp > 0) lastClipTimestamp = desc.timestamp
                    Log.d(TAG, "skip publish: identical to last local within ${ClipConst.LOCAL_REWRITE_GUARD_MS}ms (system rewrite)")
                    return
                }
            }

            if (desc != null && desc.timestamp > 0) {
                lastClipTimestamp = desc.timestamp
            }

            val payload = ClipPayload(text, htmlText, imagePng)
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
        // 登记自身 (deviceId, seq)：多链路场景下 PC 中继回弹自己的广播时直接过滤，防止误当远端内容应用
        dedupe.seen(prefs.deviceIdBytes().joinToString("") { b -> "%02x".format(b) }, seq)
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
        // B-10：对端证书完整指纹（64 hex）落库（accepted 链路同样补算，见 PcLink.attachServerSide）
        val certFpHex = link.serverFp?.joinToString("") { b -> "%02x".format(b) }
        peerBook.upsert(id, hello.name, certFpHex, link.remoteEndpoint)
        status("对端: ${hello.name}（${if (link.paired) "已配对" else "未配对"}）")
    }

    /** 服务端角色：收到 PAIR_REQ——配对窗口匹配或对端 TLS 指纹已登记（幂等）则放行。 */
    override fun onPairRequest(link: PcLink, hash: ByteArray, joinerFp: ByteArray?): Boolean {
        val codeOk = pairingOpen && pairingCodeHash != null && hash.contentEquals(pairingCodeHash)
        val fpHex = link.peerTlsFp?.joinToString("") { b -> "%02x".format(b) }
        val idempotent = fpHex != null && peerBook.all().any { it.certFpHex == fpHex }
        return if (codeOk || idempotent) {
            // 无论码匹配还是幂等放行，都立即关闭配对窗口（终态必退出，参考 KDE Connect PairingHandler）
            pairingOpen = false
            pairingCodeHash = null
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

    /** 本端作为加入方：PAIR_OK 结果处理（登记对端 + 标记当前链路 Paired，对齐 KDE Connect）。 */
    override fun onPairResult(link: PcLink, ok: Boolean, fp: ByteArray) {
        // 对齐 KDE Connect PairingHandler：任何终态（成功/失败）都必须退出 Requested 态，绝不粘滞
        handler.removeCallbacks(pairingTimeoutRunnable)
        pairingMode = false
        if (ok && fp.isNotEmpty()) {
            val fpHex = fp.joinToString("") { "%02x".format(it) }
            val id = link.peerHelloDeviceId ?: "peer-${fpHex.take(12)}"
            link.paired = true // ★ 对齐 KDE Connect：配对成功直接复用当前加密链路，严禁断链重连！
            peerBook.upsert(
                id,
                link.remoteName.ifEmpty { "peer" },
                fpHex,
                link.remoteEndpoint,
                markPaired = true,
            )
            reconnectMachine.onConnected()
            status("配对成功，已建立信任通道")
        } else {
            status("配对失败：配对码不匹配或窗口未开")
        }
    }

    override fun onConnected(link: PcLink) {
        if (!links.contains(link)) links.add(link)
        if (link.outgoing) {
            // B-7 快路径：目标端点命中已配对条目即标记 paired（onPeerHello 会按 deviceId 毫秒级兜底）
            val t = lastTarget
            if (t != null && peerBook.all().any { it.paired && it.endpoint == "${t.host}:${t.port}" }) link.paired = true
            reconnectMachine.onConnected()
        }
        status(
            if (link.paired || peerBook.all().any { it.paired }) "已连接到对端，等待剪切板…"
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
            reconnectMachine.onDisconnected()
        } else {
            status("对端链接断开（服务端保持监听）")
        }
    }

    /** 解析对目标端点的 TLS Pin 指纹（仅取 64 hex 完整值）。无则 trustAny，由 HELLO/PAIR 应用层判定身份。 */
    private fun pinnedFpHexFor(host: String, port: Int): String? =
        peerBook.all().firstOrNull {
            it.endpoint == "$host:$port" && it.certFpHex?.length == 64
        }?.certFpHex

    /**
     * 建连统一入口（B-6，纯对等模式）。
     * @param pinnedFpHex 对端固定指纹；null = 未知对端（TLS trustAny，身份由 HELLO/PAIR 应用层判定）
     * @param code        非空表示发起配对挑战（PAIR_REQ）
     */
    fun connectTo(host: String, port: Int, pinnedFpHex: String? = null, code: String? = null): PcLink? {
        handler.post {
            // 防平行链路：同主机已有存活链接时不再重复建连（配对模式除外），并视作重连目标已达成
            if (code == null && links.any { it.alive }) {
                Log.d(TAG, "connectTo skipped: alive link already exists to $host:$port")
                reconnectMachine.onConnected()
                return@post
            }
            try {
                // 配对模式：清理已有未配对的死链接/闲置链接，避免平行链路导致状态混乱与二次建连被屏蔽
                if (code != null) {
                    val stale = links.filter { it.alive && !it.paired }
                    stale.forEach { it.close() }
                    links.removeAll(stale)
                }
                lastTarget = AutoTarget("", host, port)
                val self = selfHello()
                val l = PcLink.client(
                    host, port,
                    pinnedFp = pinnedFpHex?.hexToBytes(),
                    trustAny = pinnedFpHex == null,
                    self = self,
                    ownFp = PeerIdentity.fingerprint(context),
                    listener = this,
                    onStep = { step -> status(step) },
                )
                links.add(l)
                if (code != null) {
                    val codeHash = MessageDigest.getInstance("SHA-256")
                        .digest("clipport:$code".toByteArray(Charsets.UTF_8))
                    l.send(FrameCodec.PAIR_REQ, seqCounter.incrementAndGet(),
                        Pairing.encodePairReq(codeHash, PeerIdentity.fingerprint(context)))
                    status("已发送配对请求至 $host:$port，等待确认…")
                }
            } catch (e: Exception) {
                Log.w(TAG, "connect failed", e)
                status("连接失败：${e.message}")
                reconnectMachine.onConnectFailed(e.message)
            }
        }
        return null
    }

    /** 对指定对端发起配对挑战（若已有存活链路直接复用发送 PAIR_REQ，避免新建平行链路）。 */
    fun requestPair(host: String, port: Int, code: String) {
        handler.post {
            startPairingMode()
            val existing = links.firstOrNull { it.alive && (it.remoteEndpoint == "$host:$port" || it.remoteEndpoint.startsWith("$host:")) }
            if (existing != null) {
                // 已有链路：直接复用此链路发送 PAIR_REQ，零延迟、无平行链路！
                val codeHash = MessageDigest.getInstance("SHA-256")
                    .digest("clipport:$code".toByteArray(Charsets.UTF_8))
                existing.send(FrameCodec.PAIR_REQ, seqCounter.incrementAndGet(),
                    Pairing.encodePairReq(codeHash, PeerIdentity.fingerprint(context)))
                status("正在通过当前链路向 $host:$port 发起配对…")
            } else {
                connectTo(host, port, pinnedFpHex = null, code = code)
            }
        }
    }

    /** 手动/启动路径：连接手动目标（UI 维护的 manualHost/manualPort），按端点回查指纹。 */
    fun connectManual(): PcLink? {
        val host = prefs.manualHost ?: return null
        return connectTo(host, prefs.manualPort, pinnedFpHex = pinnedFpHexFor(host, prefs.manualPort))
    }

    /** 启动时对对端库中所有已配对端点发起重连（仲裁：仅本端 deviceId 较小时连出，与 PC 端对称）。 */
    fun reconnectKnownPeers() {
        val myId = prefs.deviceIdBytes().joinToString("") { b -> "%02x".format(b) }
        for (p in peerBook.all()) {
            if (!p.paired) continue
            if (myId <= p.deviceId) continue
            val ep = p.endpoint?.split(":") ?: continue
            if (ep.size != 2) continue
            val port = ep[1].toIntOrNull() ?: continue
            connectTo(ep[0], port, pinnedFpHex = p.certFpHex?.takeIf { it.length == 64 })
        }
    }

    private fun selfHello() = Hello().apply {
        name = android.os.Build.MODEL
        deviceType = 1
        protoVer = 1
        deviceId = prefs.deviceIdBytes()
    }

    fun disconnect() {
        com.clipport.app.xposed.FloatingClipboardBridge.unregister()
        reconnectMachine.reset()
        links.forEach { it.close() }
        links.clear()
        incomingFileStreams.values.forEach { runCatching { it.close() } }
        incomingFileStreams.clear()
        peerServer?.stop()
        peerServer = null
        LanDiscovery.stop()
        udpDiscoveryStarted = false
    }

    @Volatile private var lastAnnounceAt = 0L

    /** 网络可用/切换（D-31=A）：WiFi 或热点接入时即刻宣告并唤醒重连（广播 2s 节流，capabilities 变化会频繁回调） */
    fun onNetworkAvailable(isWifi: Boolean) {
        handler.post {
            status(if (isWifi) "WiFi 已连接，刷新在线宣告并唤醒重连…" else "网络已切换，刷新在线宣告…")
            val now = System.currentTimeMillis()
            if (now - lastAnnounceAt > 2_000) {
                lastAnnounceAt = now
                LanDiscovery.broadcastNow()
            }
            if (links.none { it.alive }) {
                reconnectMachine.wakeUp("network_available")
            }
        }
    }

    /** 网络断开：仅当设备彻底离线时才清理连接与未完成流（D-33=A）。
     *  registerDefaultNetworkCallback 跟踪的是"默认网络"——手机连上 WiFi 后系统切换默认网络
     *  （蜂窝→WiFi）会补发 onLost(蜂窝)，此时 TCP 链路所在的 WiFi 仍健康，绝不能误杀（参考 KDE Connect：
     *  链路生命周期由 TCP 自身状态管理，单网卡丢失不等于断链）。 */
    fun onNetworkLost() {
        handler.post {
            val cmSys = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val stillOnline = cmSys.allNetworks.any { n ->
                val caps = cmSys.getNetworkCapabilities(n)
                caps != null && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
            if (stillOnline) {
                Log.d(TAG, "network lost but device still online, keep links")
                return@post
            }
            status("网络已离线，关闭全部连接")
            links.forEach { it.close() }
            links.clear()
            incomingFileStreams.values.forEach { runCatching { it.close() } }
            incomingFileStreams.clear()
        }
    }

    private val pairingTimeoutRunnable = Runnable {
        if (pairingMode) {
            pairingMode = false
            status("配对模式已超时退出")
        }
    }

    /** 进入配对模式（对齐 KDE Connect PairingHandler：30s 超时自动回到 NotPaired 终态，杜绝粘滞 Requested 态）。 */
    fun startPairingMode() {
        pairingMode = true
        handler.removeCallbacks(pairingTimeoutRunnable)
        handler.postDelayed(pairingTimeoutRunnable, 30_000)
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
            fpHex = fpHex,   // B-10：广播完整 64 位指纹（对端库可 pinned 校验，UDP 包仍 <300B）
            announced = { dId, name, type, port, fp, ip ->
                handler.post { handleAnnounced(dId, name, type, port, fp, ip) }
            },
            statusCb = { s -> status(s) }
        )
    }

    private fun handleAnnounced(dId: String, name: String, type: Int, port: Int, fp: String, ip: String) {
        try {
            val peer = peerBook.upsert(dId, name, fp.ifEmpty { null }, "$ip:$port")   // B-10：完整指纹入库
            if (peer.paired) {
                // 已配对：自动建链。仲裁：仅 deviceId 较小的一方连出（防双向重复建链）
                val myId = prefs.deviceIdBytes().joinToString("") { b -> "%02x".format(b) }
                if (myId > dId) return
                if (links.any { it.alive && it.peerHelloDeviceId == dId }) return
                val last = connectAttempts[dId]
                if (last != null && System.currentTimeMillis() - last < 15_000) return
                connectAttempts[dId] = System.currentTimeMillis()
                status("发现已配对设备 $name ($ip:$port)，唤醒连接…")
                // B-6：自动目标与手动目标解耦，不再覆盖 prefs.manualHost/manualPort
                autoConnectTarget = AutoTarget(dId, ip, port)
                reconnectMachine.wakeUp("udp_announced")
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

    // ---- 独立文件共享通道（D-26=A，与剪贴板解耦）----
    private val incomingFileStreams = java.util.concurrent.ConcurrentHashMap<String, java.io.FileOutputStream>()

    override fun onFileShareChunk(chunk: FileShareChunk) {
        handler.post {
            try {
                val dir = java.io.File(
                    android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                    "ClipPort"
                ).apply { mkdirs() }
                val targetFile = java.io.File(dir, chunk.fileName)
                var fos = incomingFileStreams[chunk.fileName]
                if (fos == null) {
                    fos = java.io.FileOutputStream(targetFile)
                    incomingFileStreams[chunk.fileName] = fos
                    status("正在接收共享文件：${chunk.fileName}…")
                }
                fos.write(chunk.data)
                if (chunk.isLast) {
                    fos.flush()
                    fos.close()
                    incomingFileStreams.remove(chunk.fileName)
                    status("文件接收完毕！已保存至 Download/ClipPort/${chunk.fileName}")
                    android.media.MediaScannerConnection.scanFile(context, arrayOf(targetFile.absolutePath), null, null)
                }
            } catch (e: Exception) {
                Log.w(TAG, "write file chunk error", e)
                incomingFileStreams.remove(chunk.fileName)?.runCatching { close() }
            }
        }
    }

    override fun onCancel(link: PcLink, seq: Long) {
        Log.i(TAG, "received cancel frame from ${link.remoteName} for seq $seq")
    }

    /** 发送本地文件至在线设备 */
    fun sendFile(uri: android.net.Uri) {
        val targets = links.filter { it.alive && it.paired }
        if (targets.isEmpty()) {
            status("无可用的已配对连接，无法发送文件")
            return
        }
        kotlin.concurrent.thread(name = "clipport-file-send") {
            try {
                var fileName = "shared_file"
                var fileSize = 0L
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (cursor.moveToFirst()) {
                        if (nameIdx >= 0) fileName = cursor.getString(nameIdx) ?: fileName
                        if (sizeIdx >= 0) fileSize = cursor.getLong(sizeIdx)
                    }
                }
                status("开始发送文件：$fileName…")
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val buffer = ByteArray(64 * 1024)
                    var offset = 0L
                    var read: Int
                    while (stream.read(buffer).also { read = it } > 0) {
                        offset += read
                        val isLast = if (fileSize > 0) offset >= fileSize else stream.available() == 0
                        val data = if (read == buffer.size) buffer else buffer.copyOf(read)
                        val chunk = FileShareChunk().apply {
                            this.fileName = fileName
                            this.offset = offset
                            this.isLast = isLast
                            this.data = data
                        }
                        for (l in targets) {
                            l.send(FrameCodec.FILE_SHARE_CHUNK, 0, chunk.encode())
                        }
                        if (isLast) break
                    }
                }
                status("文件已发送完毕：$fileName")
            } catch (e: Exception) {
                Log.w(TAG, "send file failed", e)
                status("发送文件失败: ${e.message}")
            }
        }
    }
}

/** 应用设置（SharedPreferences）。 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("clipport", Context.MODE_PRIVATE)

    /// B-6/U-4：仅作为"手动连接目标"由 UI 维护；自动发现目标走 autoConnectTarget，不写这里。
    var manualHost: String?
        get() = sp.getString("manual_host", null)
        set(v) = sp.edit().putString("manual_host", v).apply()
    var manualPort: Int
        get() = sp.getInt("manual_port", 47190)
        set(v) = sp.edit().putInt("manual_port", v).apply()

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
