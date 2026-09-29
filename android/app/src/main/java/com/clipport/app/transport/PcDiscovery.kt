package com.clipport.app.transport

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.util.Log

/**
 * BLE 扫描自动发现 PC（D-02，方向修正版：手机为扫描者）。
 * 过滤厂商段（公司ID 0xFFFF）：前缀 'C','P'，已配对时连指纹前 4 字节一起过滤。
 * 命中后回调 PC 的 ip:port，由调用方回填并连接。
 */
object PcDiscovery {
    private const val COMPANY_ID = 0xFFFF
    private var scanning = false

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val bytes = result.scanRecord?.getManufacturerSpecificData(COMPANY_ID) ?: return
            if (bytes.size < 12 || bytes[0] != 'C'.code.toByte() || bytes[1] != 'P'.code.toByte()) return
            val ip = "${bytes[2].toInt() and 0xFF}.${bytes[3].toInt() and 0xFF}.${bytes[4].toInt() and 0xFF}.${bytes[5].toInt() and 0xFF}"
            val port = ((bytes[7].toInt() and 0xFF) shl 8) or (bytes[6].toInt() and 0xFF)
            stop()
            Log.i("PcDiscovery", "found PC at $ip:$port")
            onFound?.invoke(ip, port)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w("PcDiscovery", "scan failed: $errorCode")
            scanning = false
        }
    }

    @Volatile var onFound: ((String, Int) -> Unit)? = null

    /** 返回 false 表示 BLE 不可用（无适配器/权限/扫描器），调用方应走手动 IP 兜底。 */
    fun start(fpHex: String?): Boolean {
        stop()
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false
        val scanner = try { adapter.bluetoothLeScanner } catch (_: Exception) { return false } ?: return false
        val base = byteArrayOf('C'.code.toByte(), 'P'.code.toByte())
        val fp = fpHex?.chunked(2)?.map { it.toInt(16).toByte() }?.toByteArray()
        val fpPrefix = if (fp != null && fp.isNotEmpty()) fp.copyOfRange(0, minOf(4, fp.size)) else ByteArray(0)
        val data = base + fpPrefix
        val mask = byteArrayOf(0xFF.toByte(), 0xFF.toByte()) +
            ByteArray(data.size - base.size) { 0xFF.toByte() }
        val filters = listOf(
            ScanFilter.Builder()
                .setManufacturerData(COMPANY_ID, data, mask)
                .build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        return try {
            scanner.startScan(filters, settings, callback)
            scanning = true
            // 30s 扫不到自动停（省电），由 UI 层提示走手动
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ if (scanning) stop() }, 30_000)
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
