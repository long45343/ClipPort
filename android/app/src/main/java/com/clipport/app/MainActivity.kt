package com.clipport.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.clipport.app.service.ClipPortService

/** 主界面（D-12：Compose Material 3 设置页，兼作 Xposed 配置宿主）。
 *  权限门控启动：先申请运行时权限，全部落定后才启动前台服务（connectedDevice 类型
 *  在 Android 12+ 要求已持有 BLUETOOTH_CONNECT，先斩后奏会 SecurityException 闪退）。 */
class MainActivity : ComponentActivity() {
    private val prefs by lazy { Prefs(this) }

    private var host by mutableStateOf("")
    private var port by mutableStateOf("47190")
    private var code by mutableStateOf("")
    private var clearMinutes by mutableStateOf("2")
    private var syncEnabled by mutableStateOf(true)
    private var status by mutableStateOf("初始化…")
    private var paired by mutableStateOf(false)

    /// 权限落定后要执行的续作：null=仅启动服务；"pair"=配对；"discover"=自动发现
    private var pendingAction: String? = null

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val missing = grants.filterValues { !it }.keys.toList()
        if (missing.isNotEmpty()) {
            status = "权限不全：${missing.joinToString { shortName(it) }}，部分功能受限"
            Log.w("MainActivity", "missing permissions: $missing")
        }
        when (pendingAction) {
            "pair" -> { saveAndPair(); pendingAction = null }
            "discover" -> { doAutoDiscover(); pendingAction = null }
            else -> startServiceSafely()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        host = prefs.host ?: ""
        port = prefs.port.toString()
        clearMinutes = (prefs.clearMs / 60000).toString()
        syncEnabled = prefs.syncEnabled
        paired = prefs.serverFpHex != null
        status = if (ClipPortService.running) ClipPortService.statusText else "申请权限中…"

        requestEssentialPermissions(then = null)
        setContent { MaterialTheme { Screen() } }
        // 服务状态实时回显（服务在后台持续更新 ClipPortService.statusText）
        val poll = object : Runnable {
            override fun run() {
                // 无条件镜像服务状态：服务是状态唯一事实源，任何过滤都会造成界面冻结
                if (ClipPortService.statusText.isNotBlank() && status != ClipPortService.statusText)
                    status = ClipPortService.statusText
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this, 1000)
            }
        }
        android.os.Handler(android.os.Looper.getMainLooper()).post(poll)
    }

    /** 申请关键运行时权限；全部已持有时直接执行续作。 */
    private fun requestEssentialPermissions(then: String?) {
        pendingAction = then
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }

        if (needed.isEmpty()) {
            when (pendingAction) {
                "pair" -> { saveAndPair(); pendingAction = null }
                "discover" -> { doAutoDiscover(); pendingAction = null }
                else -> startServiceSafely()
            }
        } else {
            permLauncher.launch(needed.toTypedArray())
        }
    }

    private fun startServiceSafely() {
        try {
            startForegroundService(Intent(this, ClipPortService::class.java).setAction(ClipPortService.ACTION_START))
        } catch (e: Exception) {
            Log.w("MainActivity", "start service failed", e)
            status = "服务启动失败：${e.message}"
        }
    }

    @Composable
    private fun Screen() {
        Scaffold(
            topBar = { Column(Modifier.padding(16.dp)) {
                Text("ClipPort", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "状态: $status" + if (paired) " ｜ 已配对" else " ｜ 未配对",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } },
        ) { pad ->
            Column(
                Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = host, onValueChange = { host = it },
                    label = { Text("PC 地址（自动发现时免填）") }, modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = port, onValueChange = { port = it.filter { c -> c.isDigit() } },
                        label = { Text("端口") }, modifier = Modifier.weight(1f), singleLine = true,
                    )
                    OutlinedTextField(
                        value = code, onValueChange = { code = it.filter { c -> c.isDigit() }.take(6) },
                        label = { Text("配对码") }, modifier = Modifier.weight(1f), singleLine = true,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = {
                        prefs.pairingCode = code
                        prefs.serverFpHex = null
                        paired = false
                        ClipPortService.statusText = "配对中…"
                        status = "配对中…"
                        requestEssentialPermissions(then = "pair")
                    }, enabled = code.length == 6) {
                        Text(if (paired) "重新配对" else "配对")
                    }
                    Button(onClick = {
                        prefs.host = host.trim()
                        prefs.port = port.toIntOrNull() ?: 47190
                        status = "连接中…"
                        requestEssentialPermissions(then = null)
                    }, enabled = host.isNotBlank()) {
                        Text("连接")
                    }
                    Button(onClick = {
                        prefs.host = ""
                        requestEssentialPermissions(then = "discover")
                    }) {
                        Text("自动发现")
                    }
                }
                Text(
                    "同一 WiFi 下点「自动发现」免填 IP（BLE 广播发现）；跨网段时手动填 PC 主界面显示的本机地址。配对码在 PC 端「开始配对」处生成。",
                    style = MaterialTheme.typography.bodySmall,
                )
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("剪贴板同步")
                    Switch(checked = syncEnabled, onCheckedChange = {
                        syncEnabled = it; prefs.syncEnabled = it
                    })
                }
                OutlinedTextField(
                    value = clearMinutes,
                    onValueChange = { clearMinutes = it.filter { c -> c.isDigit() }.take(3) },
                    label = { Text("远端剪贴板自动清除（分钟，0=不清除）") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                )
                Button(onClick = {
                    prefs.clearMs = (clearMinutes.toLongOrNull() ?: 2) * 60000
                    status = "已保存清除策略"
                }) { Text("保存清除策略") }
                HorizontalDivider()
                Text(
                    "后台读取（PC→手机方向收到内容需读取本机剪贴板）：本应用同时是 LSPosed 模块，" +
                        "在 LSPosed 管理器中启用并勾选「系统作用域」后重启，即可后台读剪贴板；" +
                        "未 root 设备可用 ADB/Shizuku 授予 READ_LOGS 走降级通道。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    /** 配对：权限落定 → saveAndPair 启动服务并发 PAIR_REQ。 */
    private fun saveAndPair() {
        prefs.host = host.trim()
        prefs.port = port.toIntOrNull() ?: 47190
        prefs.serverFpHex = null
        paired = false
        ClipPortService.statusText = "配对中…"
        status = "配对中…"
        startServiceSafely()
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            ClipPortService.instance?.requestPair()
        }, 500)
    }

    private fun doAutoDiscover() {
        status = "发现中…"
        startServiceSafely()
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            ClipPortService.instance?.requestDiscovery()
        }, 500)
    }

    private fun shortName(permission: String): String = when (permission) {
        Manifest.permission.BLUETOOTH_CONNECT -> "蓝牙连接"
        Manifest.permission.BLUETOOTH_SCAN -> "蓝牙扫描"
        Manifest.permission.POST_NOTIFICATIONS -> "通知"
        Manifest.permission.ACCESS_FINE_LOCATION -> "定位"
        else -> permission.substringAfterLast('.')
    }
}
