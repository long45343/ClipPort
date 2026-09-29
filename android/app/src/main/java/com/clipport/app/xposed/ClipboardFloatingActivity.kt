package com.clipport.app.xposed

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.WindowManager

/**
 * 透明悬浮获焦 Activity（spec §6.2 通道2）：
 * logcat 检测到剪贴板拒绝日志后拉起本 Activity，获焦窗口内读取剪贴板，读完即 finish。
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
            val clip = cm.primaryClip
            val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(this)?.toString() else null
            Log.i("ClipPortFallback", "floating read ok len=${text?.length ?: 0}")
            text?.let { BackgroundClipboardCache.offer(it) }
        } catch (e: Exception) {
            Log.w("ClipPortFallback", "floating read failed", e)
        } finally {
            finish()
        }
    }

    companion object {
        fun intent(context: Context): Intent =
            Intent(context, ClipboardFloatingActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }
}

/** 降级通道读取到的内容缓存（由 SyncManager 消费）。 */
object BackgroundClipboardCache {
    @Volatile var lastText: String? = null
    @Volatile var lastAtMs: Long = 0

    fun offer(text: String) {
        lastText = text
        lastAtMs = System.currentTimeMillis()
    }
}
