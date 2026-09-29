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
import com.clipport.app.transport.PcLink
import java.security.MessageDigest

/**
 * 同步总管（spec §2）：连接生命周期 / 发布·接收·去重 / 懒拉取 / 本端内容服务。
 * 线程模型：主链路跑在独立线程；剪贴板读取在 Service 注入的回调线程。
 */
class SyncManager(
    private val context: Context,
    private val prefs: Prefs,
    private val status: (String) -> Unit,
) : PcLink.Listener {
    companion object { const val TAG = "SyncManager" }

    private val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val applier = RemoteClipApplier(cm, prefs.clearMs)
    private val dedupe = DedupeWindow()
    private val handlerThread = android.os.HandlerThread("clipport-sync").apply { start() }
    private val handler = android.os.Handler(handlerThread.looper)

    @Volatile private var link: PcLink? = null
    @Volatile private var lastEchoText: String? = null
    @Volatile private var lastEchoAt = 0L
    @Volatile private var lastLocalPayload: ClipPayload? = null
    @Volatile private var lastLocalAt = 0L
    private var lastLocalSeq = 0L
    private val seqCounter = java.util.concurrent.atomic.AtomicLong((0 until 0x3FFFFFFF).random().toLong())
    @Volatile var pairingMode = false

    /** 本地剪贴板变化（Service/Watcher 回调，任意线程）。 */
    fun onLocalClipChanged() {
        handler.post { handleLocalClip() }
    }

    private fun handleLocalClip() {
        try {
            if (!prefs.syncEnabled) return
            if (ClipFilter.isSelfLabeled(cm.primaryClip?.description)) return          // 防回环第1道
            val text = MimeUtil.textOf(cm) ?: return
            if (text == lastEchoText && System.currentTimeMillis() - lastEchoAt < ClipConst.ECHO_WINDOW_MS) return // 防回声
            val htmlText = cm.primaryClip?.let { if (it.itemCount > 0) it.getItemAt(0).htmlText?.toString() else null }
            val payload = ClipPayload(text, htmlText, null)
            publish(payload)
        } catch (e: Exception) {
            Log.w(TAG, "local clip error", e)
        }
    }

    private fun publish(payload: ClipPayload) {
        val link = this.link?.takeIf { it.alive } ?: return
        val size = (payload.text?.length ?: 0) * 2 + (payload.html?.length ?: 0) + (payload.imagePng?.size ?: 0)
        val needChannel = size > ClipConst.LAZY_THRESHOLD_BYTES
        val seq = seqCounter.incrementAndGet()
        lastLocalPayload = payload
        lastLocalAt = System.currentTimeMillis()
        lastLocalSeq = seq
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
        link.send(FrameCodec.CLIP_BROADCAST, seq, bc.encode())
        status("已发布 seq=$seq${if (needChannel) " (懒)" else ""}")
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
                val l = link?.takeIf { it.alive } ?: return
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
        // 手机作为内容源：PC 懒拉取本端最后一次复制内容（180s 持有）
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

    override fun onPairResult(ok: Boolean, fp: ByteArray) {
        if (ok && fp.isNotEmpty()) {
            prefs.serverFpHex = fp.joinToString("") { "%02x".format(it) }
            pairingMode = false
            status("配对成功，指纹已固定")
            // 断开信任任意证书的连接，用固定指纹重连
            link?.close()
            connect(pinned = true)
        } else {
            status("配对失败：配对码不匹配")
        }
    }

    override fun onConnected() { status("已连接到 PC") }; // pairing 发生在 connect() 内部

    override fun onDisconnected() { status("连接断开，3s 后重连"); scheduleReconnect() }

    private var reconnectScheduled = false
    private fun scheduleReconnect() {
        if (reconnectScheduled) return
        reconnectScheduled = true
        handler.postDelayed({ reconnectScheduled = false; connect(pinned = prefs.serverFpHex != null) }, 3000)
    }

    /** 连接 PC。pairing=true 信任任意证书并发送配对请求。 */
    fun connect(pinned: Boolean): PcLink? {
        val host = prefs.host
        if (host.isNullOrEmpty()) return null
        handler.post {
            try {
                link?.close()
                val self = Hello().apply {
                    name = android.os.Build.MODEL
                    deviceType = 1
                    protoVer = 1
                    deviceId = prefs.deviceIdBytes()
                }
                val l = PcLink(
                    host, prefs.port,
                    pinnedFp = prefs.serverFpHex?.hexToBytes(),
                    self = self, listener = this,
                )
                link = l
                l.connect(trustAny = !pinned)
                if (pairingMode) {
                    val codeHash = MessageDigest.getInstance("SHA-256")
                        .digest("clipport:${prefs.pairingCode}".toByteArray(Charsets.UTF_8))
                    l.send(FrameCodec.PAIR_REQ, seqCounter.incrementAndGet(), Pairing.encodePairReq(codeHash))
                }
            } catch (e: Exception) {
                Log.w(TAG, "connect failed", e)
                status("连接失败：${e.message}")
                scheduleReconnect()
            }
        }
        return link
    }

    fun disconnect() {
        link?.close()
        link = null
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
