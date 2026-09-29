package com.clipport.app.clip

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Message

/** 剪贴板同步私有常量（与 spec 对齐）。 */
object ClipConst {
    const val SELF_LABEL = "clipportClipData"
    const val DEBOUNCE_MS = 100L
    const val ECHO_WINDOW_MS = 1500L
    const val LAZY_THRESHOLD_BYTES = 16 * 1024 // D-05
    const val REMOTE_CLEAR_MS = 120_000L       // D-15 默认 120s
}

/** 三道过滤（spec §1.2）：自标记 → 时间戳 → 回声比对。 */
object ClipFilter {
    fun isSelfLabeled(desc: ClipDescription?): Boolean =
        desc?.label?.toString() == ClipConst.SELF_LABEL

    fun isEcho(cm: ClipboardManager, lastText: String?, lastAt: Long): Boolean {
        if (System.currentTimeMillis() - lastAt > ClipConst.ECHO_WINDOW_MS) return false
        val clip = cm.primaryClip ?: return false
        val now = clip.itemCount
        val cur = if (now > 0) clip.getItemAt(0).coerceToText(null)?.toString() else null
        return cur != null && cur == lastText
    }
}

/** 防抖合并（spec §1.3，100ms，对齐小米 CLIP_CHANGE_THRESHOLD）。 */
class ClipDebouncer : Handler(HandlerThread("clipport-debounce").apply { start() }.looper) {
    companion object { const val MSG = 1 }
    init { Thread.currentThread() }
    fun submit(what: Int = MSG, delayMs: Long = ClipConst.DEBOUNCE_MS, obj: Any? = null) {
        removeMessages(what)
        val m = Message.obtain(this, { }) ; m.what = what; m.obj = obj
        sendMessageDelayed(m, delayMs)
    }
    override fun handleMessage(msg: Message) { /* 由持有方覆写回调注入 */ }
}

/** mime 判定工具。 */
object MimeUtil {
    fun isHtml(desc: ClipDescription): Boolean = desc.hasMimeType("text/html")
    fun textOf(cm: ClipboardManager): String? {
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(null)?.toString()?.ifEmpty { null }
    }
}

/** ClipData → ClipPayload 解析（首版：文本 + HTML + 图片URI 提示）。 */
data class ClipPayload(
    val text: String?,
    val html: String?,
    val imagePng: ByteArray?,
)

/** 远端内容写入器（spec §5.1）：带 SELF_LABEL 写入 + 可配置自动清除（D-15）。 */
class RemoteClipApplier(private val cm: ClipboardManager, private val clearMs: Long = ClipConst.REMOTE_CLEAR_MS) {
    private var clearRunnable: Runnable? = null

    fun apply(text: String?, html: String?, imagePng: ByteArray?) {
        val items = ArrayList<ClipData.Item>()
        val mimes = LinkedHashSet<String>()
        if (!text.isNullOrEmpty() || !html.isNullOrEmpty()) {
            mimes.add(if (!html.isNullOrEmpty()) "text/html" else "text/plain")
            items.add(ClipData.Item(text ?: "", html))
        }
        if (items.isEmpty()) return
        val desc = ClipDescription(ClipConst.SELF_LABEL, mimes.toTypedArray())
        val clip = ClipData(desc, items[0])
        for (i in 1 until items.size) clip.addItem(items[i])
        try {
            cm.setPrimaryClip(clip)
        } catch (_: Exception) { return }
        clearRunnable?.let { android.os.Handler(android.os.Looper.getMainLooper()).removeCallbacks(it) }
        if (clearMs > 0) {
            val r = Runnable { clearIfOwned() }
            clearRunnable = r
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(r, clearMs)
        }
    }

    /** 仅清除自己写入的剪贴板（绝不清用户内容）。 */
    fun clearIfOwned() {
        val clip = cm.primaryClip ?: return
        if (ClipFilter.isSelfLabeled(clip.description)) {
            try { cm.clearPrimaryClip() } catch (_: Exception) {}
        }
    }
}

/** (设备,seq) LRU 去重窗（D-07）。 */
class DedupeWindow(private val maxSize: Int = 128, private val windowMs: Long = 30_000) {
    private data class Entry(val dev: String, val seq: Long, var at: Long)
    private val list = ArrayList<Entry>(maxSize)

    @Synchronized
    fun seen(dev: String, seq: Long): Boolean {
        val now = System.currentTimeMillis()
        list.removeAll { now - it.at > windowMs }
        val hit = list.indexOfFirst { it.dev == dev && it.seq == seq }
        if (hit >= 0) { list[hit] = list[hit].copy(at = now); return true }
        list.add(Entry(dev, seq, now))
        if (list.size > maxSize) list.removeAt(0)
        return false
    }
}
