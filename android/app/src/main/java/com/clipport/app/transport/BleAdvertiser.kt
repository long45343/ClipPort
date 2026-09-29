package com.clipport.app.transport

import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface

/** BLE 广播发现（D-02，手机固定为广播者；载荷与 Windows 端 BleWatcher 对齐）。 */
object BleAdvertiser {
    private const val COMPANY_ID = 0xFFFF
    private var advertiser: android.bluetooth.le.BluetoothLeAdvertiser? = null
    private val cb = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) { Log.i("BleAdv", "advertise start") }
        override fun onStartFailure(errorCode: Int) { Log.w("BleAdv", "advertise fail: $errorCode") }
    }

    /** [0..1]='C','P' | [2..5]=IPv4 | [6..7]=port(LE) | [8..11]=证书指纹前4字节 */
    fun buildPayload(ipV4: ByteArray, port: Int, fp: ByteArray): ByteArray {
        val b = mutableListOf<Byte>('C'.code.toByte(), 'P'.code.toByte())
        b.addAll(ipV4.toList())
        b.add((port and 0xFF).toByte()); b.add(((port shr 8) and 0xFF).toByte())
        b.addAll(fp.take(4))
        return b.toByteArray()
    }

    fun localIpV4(): ByteArray? {
        for (nif in NetworkInterface.getNetworkInterfaces()) {
            for (addr in nif.inetAddresses) {
                if (addr is Inet4Address && !addr.isLoopbackAddress) return addr.address
            }
        }
        return null
    }

    fun start(port: Int, certFp: ByteArray) {
        stop()
        val ip = localIpV4() ?: return
        val bt = android.bluetooth.BluetoothAdapter.getDefaultAdapter() ?: return
        val adv = bt.bluetoothLeAdvertiser ?: return
        val payload = buildPayload(ip, port, certFp)
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addManufacturerData(COMPANY_ID, payload)
            .build()
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(false)
            .build()
        advertiser = adv
        try { adv.startAdvertising(settings, data, cb) } catch (e: Exception) { Log.w("BleAdv", "start failed", e) }
    }

    fun stop() {
        try { advertiser?.stopAdvertising(cb) } catch (_: Exception) {}
        advertiser = null
    }
}
