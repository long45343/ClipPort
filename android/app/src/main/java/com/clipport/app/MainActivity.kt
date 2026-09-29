package com.clipport.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
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

/** 主界面（D-12：Compose Material 3 设置页，兼作 Xposed 配置宿主）。 */
class MainActivity : ComponentActivity() {
    private val prefs by lazy { Prefs(this) }

    private var host by mutableStateOf("")
    private var port by mutableStateOf("47190")
    private var code by mutableStateOf("")
    private var clearMinutes by mutableStateOf("2")
    private var syncEnabled by mutableStateOf(true)
    private var status by mutableStateOf("未连接")
    private var paired by mutableStateOf(false)

    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        host = prefs.host ?: ""
        // 首次进入自动尝试发现（已配对过的设备免一切输入）
        port = prefs.port.toString()
        clearMinutes = (prefs.clearMs / 60000).toString()
        syncEnabled = prefs.syncEnabled
        paired = prefs.serverFpHex != null
        status = if (ClipPortService.running) ClipPortService.statusText else "服务未运行"

        if (Build.VERSION.SDK_INT >= 33) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // 前台服务启动（Android 12+ 需前台可启动——本 Activity 就是前台）
        startForegroundService(Intent(this, ClipPortService::class.java).setAction(ClipPortService.ACTION_START))

        setContent { MaterialTheme { Screen() } }
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
                    label = { Text("PC 地址 (IP)") }, modifier = Modifier.fillMaxWidth(),
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
                    Button(onClick = { saveAndPair() }, enabled = code.length == 6) {
                        Text(if (paired) "重新配对" else "配对")
                    }
                    Button(onClick = { saveAndConnect() }, enabled = host.isNotBlank()) {
                        Text("连接")
                    }
                    Button(onClick = { autoDiscover() }) {
                        Text("自动发现")
                    }
                }
                Text(
                    "同一 WiFi 下可点「自动发现」免填 IP（BLE 广播发现）；跨网段时手动填 PC 界面显示的地址。",
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
                    label = { Text("远端剪贴板自动清除（分钟，0=不清除，D-15）") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                )
                Button(onClick = {
                    prefs.clearMs = (clearMinutes.toLongOrNull() ?: 2) * 60000
                    status = "已保存清除策略"
                }) { Text("保存清除策略") }
                HorizontalDivider()
                Text(
                    "后台读取: 安装为 LSPosed 模块并在系统框架作用域启用后，重启即可后台读剪贴板（spec D-08）。\n" +
                        "未 root 设备可授予 READ_LOGS（Shizuku/ADB）作为降级通道。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    private fun autoDiscover() {
        // Android 12+ 需要 BLUETOOTH_SCAN；29~30 需要定位权限
        if (Build.VERSION.SDK_INT >= 31) {
            scanPerm.launch(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            scanPerm.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        status = "发现中…"
        ClipPortService.instance?.requestDiscovery()
    }

    private val scanPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) status = "未授予扫描权限，请手动填写 IP"
    }

    private fun saveAndPair() {
        prefs.host = host.trim()
        prefs.port = port.toIntOrNull() ?: 47190
        prefs.pairingCode = code
        prefs.serverFpHex = null
        paired = false
        ClipPortService.statusText = "配对中…"
        status = "配对中…"
        // 重启服务入口（前台 Activity 允许启动 FGS），随后触发配对
        startForegroundService(Intent(this, ClipPortService::class.java).setAction(ClipPortService.ACTION_START))
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            ClipPortService.instance?.requestPair()
        }, 500)
    }

    private fun saveAndConnect() {
        prefs.host = host.trim()
        prefs.port = port.toIntOrNull() ?: 47190
        prefs.clearMs = (clearMinutes.toLongOrNull() ?: 2) * 60000
        status = "连接中…"
        ClipPortService.statusText = "连接中…"
        startForegroundService(Intent(this, ClipPortService::class.java).setAction(ClipPortService.ACTION_START))
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            ClipPortService.instance?.requestConnect()
        }, 500)
    }
}
