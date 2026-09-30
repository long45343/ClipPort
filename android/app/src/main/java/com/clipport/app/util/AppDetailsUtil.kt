package com.clipport.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log

object AppDetailsUtil {
    private const val TAG = "AppDetailsUtil"

    /**
     * 直接跳转系统标准应用详情页（对齐 D-18=C 决策：各大品牌手机的自启动、后台运行、神隐模式与省电策略均可在该页找到）
     */
    fun openAppDetails(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "failed to open application details settings", e)
        }
    }
}
