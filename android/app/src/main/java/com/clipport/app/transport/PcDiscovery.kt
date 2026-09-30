package com.clipport.app.transport

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.util.Log

/**
 * BLE 扫描自动发现 PC（D-02）：手机扫描者。
 * 不用系统硬过滤——手动解析全部广播并计数，这样"扫不到"能区分三种原因：
 * ①周围无任何 BLE 广播 ②有广播但无 ClipPort 匹配(PC 端广播异常) ③扫描失败(权限/位置服务)
 */
object PcDiscovery {
    private const val COMPANY_ID = 0xFFFF
    private var scanning = false
    private var adCount = 0
    private var onTimeout: ((Int) -> Unit)? = null

    @Volatile var onFound: ((String, Int) -> Unit)? = null

    private fun parse(bytes: ByteArray): Pair<String, Int>? {
        if (bytes.size < 12 || bytes[0] != 'C'.code.toByte() || bytes[1] != 'P'.code.toByte()) return null
        val ip = "\${bytes[2].toInt() and 0xFF}.\${bytes[3].toInt() and 0xFF}.\${bytes[4].toInt() and 0xFF}.\${bytes[5].toInt() and 0xFF}"
        val port = ((bytes[7].toInt() and 0xFF) shl 8) or (bytes[6].toInt() and 0xFF)
        return ip to port
    }

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            adCount++
            val bytes = result.scanRecord?.getManufacturerSpecificData(COMPANY_ID) ?: return
            val hit = parse(bytes) ?: return
            stop()
            Log.i("PcDiscovery", "found PC at \$hit.first:\$hit.second")
            onFound?.invoke(hit.first, hit.second)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            Log.w("PcDiscovery", "scan failed: \$errorCode")
            onTimeout?.invoke(-errorCode)   // 负数表示扫描失败而非超时
        }
    }

    /** 返回 false 表示 BLE 根本不可用（无适配器/权限/扫描器）。 */
    fun start(fpHex: String?, onDone: (Int) -> Unit): Boolean {
        stop()
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false
        val scanner = try { adapter.bluetoothLeScanner } catch (_: Exception) { return false } ?: return false
        adCount = 0
        onTimeout = onDone
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        return try {
            scanner.startScan(null, settings, callback)   // 不过滤, 全收后手动匹配
            scanning = true
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (scanning) { stop(); onTimeout?.invoke(adCount) }
            }, 30_000)
            true
        } catch (e: Exception) {
            Log.w("PcDiscovery", "startScan failed", e)
            false
        }
    }

    fun stop() {
        if (!scanning) return
        scanning = false
        try {
            BluetoothAdapter.getDefaultAdapter()?.bluetoothLeScanner?.stopScan(callback)
        } catch (_: Exception) { }
    }
}
