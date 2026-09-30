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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.clipport.app.service.ClipPortService
import com.clipport.app.transport.PeerBook
import com.clipport.app.transport.PeerEntry
import com.clipport.app.ui.ScanPairActivity
import com.clipport.app.util.AppDetailsUtil
import com.clipport.app.util.BatteryOptimizationHelper

data class PairTarget(
    val host: String,
    val port: String,
    val code: String,
    val fp: String,
    val name: String
)

/** 主界面（D-12：Compose Material 3 设置页，兼作 Xposed 配置宿主）。
 *  权限门控启动：先申请运行时权限，全部落定后才启动前台服务（connectedDevice 类型
 *  在 Android 12+ 要求已持有 BLUETOOTH_CONNECT，先斩后奏会 SecurityException 闪退）。 */
class MainActivity : ComponentActivity() {
    private val prefs by lazy { Prefs(this) }
    private val peerBook by lazy { PeerBook(this) }

    private var host by mutableStateOf("")
    private var port by mutableStateOf("47190")
    private var code by mutableStateOf("")
    private var clearMinutes by mutableStateOf("2")
    private var syncEnabled by mutableStateOf(true)
    private var status by mutableStateOf("初始化…")
    private var paired by mutableStateOf(false)
    private var peers by mutableStateOf(listOf<PeerEntry>())
    private var batteryOptimized by mutableStateOf(true)
    private var showPairConfirmDialog by mutableStateOf(false)
    private var pendingPairTarget by mutableStateOf<PairTarget?>(null)

    private val scanLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val h = result.data?.getStringExtra(ScanPairActivity.EXTRA_HOST) ?: ""
            val p = result.data?.getStringExtra(ScanPairActivity.EXTRA_PORT) ?: "47190"
            val c = result.data?.getStringExtra(ScanPairActivity.EXTRA_CODE) ?: ""
            val f = result.data?.getStringExtra(ScanPairActivity.EXTRA_FP) ?: ""
            val n = result.data?.getStringExtra(ScanPairActivity.EXTRA_NAME) ?: ""
            if (h.isNotBlank() && c.length == 6) {
                pendingPairTarget = PairTarget(h, p, c, f, n)
                showPairConfirmDialog = true
            }
        }
    }

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (!uris.isNullOrEmpty()) {
            startServiceSafely()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                for (u in uris) ClipPortService.instance?.sendFile(u)
            }, 500)
        }
    }

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
        batteryOptimized = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(this)
        handleSendIntent(intent)
        setContent { MaterialTheme { Screen() } }
        // 服务状态实时回显（服务在后台持续更新 ClipPortService.statusText）
        val poll = object : Runnable {
            override fun run() {
                // 无条件镜像服务状态：服务是状态唯一事实源，任何过滤都会造成界面冻结
                if (ClipPortService.statusText.isNotBlank() && status != ClipPortService.statusText)
                    status = ClipPortService.statusText
                peers = peerBook.all()
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this, 1000)
            }
        }
        android.os.Handler(android.os.Looper.getMainLooper()).post(poll)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSendIntent(intent)
    }

    private fun handleSendIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.action == Intent.ACTION_SEND) {
            val uri = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            if (uri != null) {
                startServiceSafely()
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    ClipPortService.instance?.sendFile(uri)
                }, 1000)
            }
        } else if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            val uris = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
            }
            if (!uris.isNullOrEmpty()) {
                startServiceSafely()
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    for (u in uris) ClipPortService.instance?.sendFile(u)
                }, 1000)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        batteryOptimized = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(this)
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
                if (!batteryOptimized) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.85f),
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        ),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(
                                "⚠️ 灭屏常驻保活受限",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "请将 ClipPort 加入「忽略电池优化」白名单并允许后台自启动，否则手机锁屏灭屏后网络连接将被系统冻结而导致断连。",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    BatteryOptimizationHelper.requestIgnoreBatteryOptimizations(this@MainActivity)
                                }) {
                                    Text("忽略电池优化")
                                }
                                OutlinedButton(onClick = {
                                    AppDetailsUtil.openAppDetails(this@MainActivity)
                                }) {
                                    Text("应用自启管理")
                                }
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = host, onValueChange = { host = it },
                    label = { Text("PC 地址（自动发现时免填）") }, modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = port, onValueChange = { port = normalizeDigits(it).take(5) },
                        label = { Text("端口") }, modifier = Modifier.weight(1f), singleLine = true,
                    )
                    OutlinedTextField(
                        value = code, onValueChange = { code = normalizeDigits(it).take(6) },
                        label = { Text("配对码") }, modifier = Modifier.weight(1f), singleLine = true,
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(onClick = {
                        scanLauncher.launch(Intent(this@MainActivity, ScanPairActivity::class.java))
                    }) {
                        Text("📷 扫码")
                    }
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
                        val c = ClipPortService.instance?.openPairing()
                        status = if (c != null) "本机配对码: $c（请在其他设备填入并连接本机）" else "服务未运行"
                    }) {
                        Text("开启配对")
                    }
                }
                Text(
                    "UDP 广播每 10 秒自动发现同一局域网设备；也可手动填地址。如需让其他设备连入本机，请点击「开启配对」。",
                    style = MaterialTheme.typography.bodySmall,
                )
                HorizontalDivider()
                Text("发现的设备与对端（UDP 自动发现）", style = MaterialTheme.typography.titleMedium)
                if (peers.isEmpty()) {
                    Text(
                        "暂未发现设备。请确保所有设备连接在同一 WiFi/局域网内，UDP 广播每 10 秒自动通告。",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    for (p in peers) {
                        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(p.name.ifEmpty { "未知设备" }, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        "${p.endpoint ?: "无端点"} ｜ ${if (p.paired) "已配对" else "未配对"}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                if (p.endpoint != null) {
                                    Button(onClick = {
                                        val ep = p.endpoint!!.split(':')
                                        host = ep[0]
                                        if (ep.size > 1) port = ep[1]
                                    }) {
                                        Text("填入")
                                    }
                                }
                            }
                        }
                    }
                }
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("剪贴板同步")
                    Switch(checked = syncEnabled, onCheckedChange = {
                        syncEnabled = it; prefs.syncEnabled = it
                    })
                }
                OutlinedTextField(
                    value = clearMinutes,
                    onValueChange = { clearMinutes = normalizeDigits(it).take(3) },
                    label = { Text("远端剪贴板自动清除（分钟，0=不清除）") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                )
                Button(onClick = {
                    prefs.clearMs = (clearMinutes.toLongOrNull() ?: 2) * 60000
                    status = "已保存清除策略"
                }) { Text("保存清除策略") }
                HorizontalDivider()
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("独立文件共享", style = MaterialTheme.typography.titleSmall)
                            Text("选文件推发给已配对设备，接收自动存入 Download/ClipPort", style = MaterialTheme.typography.bodySmall)
                        }
                        Button(onClick = {
                            filePickerLauncher.launch("*/*")
                        }) {
                            Text("发送文件")
                        }
                    }
                }
                HorizontalDivider()
                Button(onClick = {
                    if (android.provider.Settings.canDrawOverlays(this@MainActivity)) {
                        status = "悬浮窗权限已授予"
                    } else {
                        startActivity(
                            android.content.Intent(
                                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                android.net.Uri.parse("package:" + packageName)
                            )
                        )
                    }
                }) {
                    val overlayGranted = android.provider.Settings.canDrawOverlays(this@MainActivity)
                    Text(if (overlayGranted) "悬浮窗权限已授予" else "授予悬浮窗权限（无 root 备用通道）")
                }
                HorizontalDivider()
                Text(
                    "后台读取（PC→手机方向收到内容需读取本机剪贴板）：本应用同时是 LSPosed 模块，" +
                        "在 LSPosed 管理器中启用并勾选「系统作用域」后重启，即可后台读剪贴板；" +
                        "未 root 设备可用 ADB/Shizuku 授予 READ_LOGS 走降级通道。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (showPairConfirmDialog && pendingPairTarget != null) {
                val target = pendingPairTarget!!
                AlertDialog(
                    onDismissRequest = { showPairConfirmDialog = false },
                    title = { Text("确认信任并配对设备？") },
                    text = {
                        Column {
                            Text("设备名称: ${target.name.ifEmpty { "局域网 PC" }}", fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(4.dp))
                            Text("连接端点: ${target.host}:${target.port}")
                            Spacer(Modifier.height(4.dp))
                            Text("临时配对码: ${target.code}")
                            if (target.fp.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text("证书指纹: ${target.fp.take(16)}…", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    },
                    confirmButton = {
                        Button(onClick = {
                            showPairConfirmDialog = false
                            host = target.host
                            port = target.port
                            code = target.code
                            prefs.host = target.host
                            prefs.port = target.port.toIntOrNull() ?: 47190
                            prefs.pairingCode = target.code
                            prefs.serverFpHex = null
                            paired = false
                            ClipPortService.statusText = "配对中…"
                            status = "配对中…"
                            startServiceSafely()
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                ClipPortService.instance?.requestPair()
                            }, 500)
                        }) {
                            Text("确认配对")
                        }
                    },
                    dismissButton = {
                        OutlinedButton(onClick = { showPairConfirmDialog = false }) {
                            Text("取消")
                        }
                    }
                )
            }
        }
    }

    /** 配对：权限落定 → saveAndPair 启动服务并发 PAIR_REQ。 */
    private fun saveAndPair() {
        // 自动发现已写入 host 而输入框为空时，不得覆盖（否则连接因无地址而静默失效）
        if (host.isNotBlank()) prefs.host = host.trim()
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

    /** 数字输入规范化：中文输入法的全角数字(１２３)显示与半角无法分辨但字节不同，
     *  直接进入哈希会导致配对码"看着对却哈希不匹配"。统一转为 ASCII 半角。 */
    private fun normalizeDigits(s: String): String =
        s.filter { it.isDigit() }
            .map { Character.getNumericValue(it).toString() }
            .joinToString("")

    private fun shortName(permission: String): String = when (permission) {
        Manifest.permission.BLUETOOTH_CONNECT -> "蓝牙连接"
        Manifest.permission.BLUETOOTH_SCAN -> "蓝牙扫描"
        Manifest.permission.POST_NOTIFICATIONS -> "通知"
        Manifest.permission.ACCESS_FINE_LOCATION -> "定位"
        else -> permission.substringAfterLast('.')
    }
}
