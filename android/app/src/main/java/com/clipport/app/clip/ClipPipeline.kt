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

    /** 第 4 道过滤（本地改写免疫）：发布后该窗口内内容完全一致的再次回调视为系统剪贴板管理器改写 */
    const val LOCAL_REWRITE_GUARD_MS = 3000L
}

/** 三道过滤（spec §1.2）：自标记 → 时间戳 → 回声比对。 */
object ClipFilter {
    fun isSelfLabeled(desc: ClipDescription?): Boolean =
        desc?.label?.toString() == ClipConst.SELF_LABEL

    /** 对齐小米 UniversalClipDataPublisher：比对系统时间戳，阻断相同时间戳的重复空回调 */
    fun isSameTimestamp(desc: ClipDescription?, lastTimestamp: Long): Boolean {
        if (desc == null || lastTimestamp <= 0L) return false
        val ts = desc.timestamp
        return ts > 0L && ts == lastTimestamp
    }

    fun isEcho(cm: ClipboardManager, lastText: String?, lastAt: Long): Boolean {
        if (System.currentTimeMillis() - lastAt > ClipConst.ECHO_WINDOW_MS) return false
        val clip = cm.primaryClip ?: return false
        val now = clip.itemCount
        val cur = if (now > 0) clip.getItemAt(0).coerceToText(null)?.toString() else null
        return cur != null && cur == lastText
    }

    /** 第 4 道过滤核心（D-07-B"内容没变不重发"思路）：文本/HTML/图片三元组完全一致视为同一内容。 */
    fun sameContent(
        text: String?, html: String?, image: ByteArray?,
        lastText: String?, lastHtml: String?, lastImage: ByteArray?,
    ): Boolean =
        text == lastText && html == lastHtml &&
            ((image == null && lastImage == null) ||
             (image != null && lastImage != null && image.contentEquals(lastImage)))
}

/** mime 判定工具。 */
object MimeUtil {
    fun isHtml(desc: ClipDescription): Boolean = desc.hasMimeType("text/html")
    fun isImage(desc: ClipDescription): Boolean =
        desc.hasMimeType("image/png") || desc.hasMimeType("image/jpeg") || desc.hasMimeType("image/*")

    fun textOf(cm: ClipboardManager): String? {
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(null)?.toString()?.ifEmpty { null }
    }

    /** 从剪贴板提取图片为 PNG 字节流（支持直接 URI 或 Intent 附件） */
    fun imagePngOf(ctx: Context, cm: ClipboardManager): ByteArray? {
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        val uri = clip.getItemAt(0).uri ?: return null
        val mime = ctx.contentResolver.getType(uri) ?: (if (clip.description.mimeTypeCount > 0) clip.description.getMimeType(0) else "")
        if (!mime.startsWith("image/")) return null
        return try {
            ctx.contentResolver.openInputStream(uri)?.use { stream ->
                val bmp = android.graphics.BitmapFactory.decodeStream(stream) ?: return null
                val out = java.io.ByteArrayOutputStream()
                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                out.toByteArray()
            }
        } catch (_: Exception) { null }
    }
}

/** ClipData → ClipPayload 解析（首版：文本 + HTML + 图片URI 提示）。 */
data class ClipPayload(
    val text: String?,
    val html: String?,
    val imagePng: ByteArray?,
)

/** 远端内容写入器（spec §5.1）：带 SELF_LABEL 写入 + 可配置自动清除（D-15）。
 *  直接写入被系统拒绝（后台无焦点/非 IME）时，拉起悬浮获焦 Activity 写入兜底。
 *  D-28=A: 图片通过私有 FileProvider 以 content:// 暴露，不污染相册。 */
class RemoteClipApplier(
    private val cm: ClipboardManager,
    private val clearMs: Long = ClipConst.REMOTE_CLEAR_MS,
    private val context: Context? = null,
    private val onStatus: (String) -> Unit = {},
) {
    private var clearRunnable: Runnable? = null

    fun apply(text: String?, html: String?, imagePng: ByteArray?) {
        val items = ArrayList<ClipData.Item>()
        val mimes = LinkedHashSet<String>()
        if (!text.isNullOrEmpty() || !html.isNullOrEmpty()) {
            mimes.add(if (!html.isNullOrEmpty()) "text/html" else "text/plain")
            items.add(ClipData.Item(text ?: "", html))
        }

        // 写入远端截图（D-28=A）
        if (imagePng != null && imagePng.isNotEmpty()) {
            val ctx = context
            if (ctx != null) {
                try {
                    val dir = java.io.File(ctx.cacheDir, "clip_images").apply { mkdirs() }
                    val file = java.io.File(dir, "remote_clip.png")
                    file.writeBytes(imagePng)
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        ctx, "com.clipport.app.fileprovider", file
                    )
                    mimes.add("image/png")
                    items.add(ClipData.Item(uri))
                } catch (e: Exception) {
                    android.util.Log.w("RemoteClipApplier", "save remote image failed", e)
                }
            }
        }

        if (items.isEmpty()) return
        val desc = ClipDescription(ClipConst.SELF_LABEL, mimes.toTypedArray())
        val clip = ClipData(desc, items[0])
        for (i in 1 until items.size) clip.addItem(items[i])
        try {
            cm.setPrimaryClip(clip)
        } catch (e: Exception) {
            floatingWriteFallback(text, html)
            return
        }
        clearRunnable?.let { android.os.Handler(android.os.Looper.getMainLooper()).removeCallbacks(it) }
        if (clearMs > 0) {
            val r = Runnable { clearIfOwned() }
            clearRunnable = r
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(r, clearMs)
        }
    }

    /** 直接写入失败时：有悬浮窗权限则拉起透明 Activity 获焦写入（Android 12+ 后台写被拒的兜底）。 */
    private fun floatingWriteFallback(text: String?, html: String?) {
        val ctx = context ?: return
        if (android.provider.Settings.canDrawOverlays(ctx)) {
            try {
                ctx.startActivity(
                    com.clipport.app.xposed.ClipboardFloatingActivity.writeIntent(ctx, text, html)
                )
                onStatus("已通过悬浮窗写入剪贴板")
            } catch (e: Exception) {
                onStatus("悬浮窗写入失败: ${e.message}")
            }
        } else {
            onStatus("写入剪贴板被系统拒绝——请启用 LSPosed 模块或在设置中授予悬浮窗权限")
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
