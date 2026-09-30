package com.clipport.app.xposed

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import com.clipport.app.clip.ClipConst

/**
 * 透明悬浮获焦 Activity（spec §6.2 通道2）：
 * - 读模式：logcat/监听触发后拉起，获焦窗口内读取剪贴板（后台读降级）
 * - 写模式：远端内容写入被系统拒绝时拉起，获焦窗口内写入（后台写降级）
 * （复用 TextCascade ClipboardSources/ClipboardFloatingActivity 的降级方案）
 */
class ClipboardFloatingActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        setFinishOnTouchOutside(true)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (intent.hasExtra(EXTRA_WRITE_TEXT) || intent.hasExtra(EXTRA_WRITE_HTML)) {
                // ── 写模式：获焦状态下写入远端内容 ──
                val text = intent.getStringExtra(EXTRA_WRITE_TEXT)
                val html = intent.getStringExtra(EXTRA_WRITE_HTML)
                val mimes = if (!html.isNullOrEmpty()) arrayOf("text/html") else arrayOf("text/plain")
                val clip = ClipData(ClipDescription(ClipConst.SELF_LABEL, mimes), ClipData.Item(text ?: "", html))
                cm.setPrimaryClip(clip)
                Log.i("ClipPortFallback", "floating write ok len=${text?.length ?: 0}")
            } else {
                // ── 读模式：获焦状态下读取 ──
                val clip = cm.primaryClip
                val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(this)?.toString() else null
                Log.i("ClipPortFallback", "floating read ok len=${text?.length ?: 0}")
                text?.let { BackgroundClipboardCache.offer(it) }
            }
        } catch (e: Exception) {
            Log.w("ClipPortFallback", "floating op failed", e)
        } finally {
            finish()
        }
    }

    companion object {
        const val EXTRA_WRITE_TEXT = "clipport_write_text"
        const val EXTRA_WRITE_HTML = "clipport_write_html"

        fun intent(context: Context): Intent =
            Intent(context, ClipboardFloatingActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)

        fun writeIntent(context: Context, text: String?, html: String?): Intent =
            intent(context).putExtra(EXTRA_WRITE_TEXT, text ?: "").putExtra(EXTRA_WRITE_HTML, html ?: "")
    }
}

/** 降级通道数据交换（读模式结果缓存）。 */
object BackgroundClipboardCache {
    @Volatile var lastText: String? = null
    @Volatile var lastAtMs: Long = 0

    fun offer(text: String) {
        lastText = text
        lastAtMs = System.currentTimeMillis()
    }
}
