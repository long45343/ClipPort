package com.clipport.app.transport

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log

/**
 * Android 网卡与网络状态感知器（D-31=A）：
 * 监听 WiFi / 移动热点连接、断开与能力变化，提供即刻感知能力。
 */
class NetworkWatcher(
    private val context: Context,
    private val onNetworkAvailable: (isWifi: Boolean) -> Unit,
    private val onNetworkLost: () -> Unit,
) {
    companion object {
        private const val TAG = "NetworkWatcher"
    }

    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private var callback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var running = false

    fun start() {
        if (running || cm == null) return
        running = true
        try {
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val caps = cm.getNetworkCapabilities(network)
                    val isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                    Log.d(TAG, "Network available, isWifi=$isWifi")
                    onNetworkAvailable(isWifi)
                }

                override fun onLost(network: Network) {
                    Log.d(TAG, "Network lost")
                    onNetworkLost()
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities
                ) {
                    val isWifi = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    Log.d(TAG, "Network capabilities changed, isWifi=$isWifi")
                    onNetworkAvailable(isWifi)
                }
            }
            callback = cb
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                cm.registerDefaultNetworkCallback(cb)
            } else {
                val req = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                cm.registerNetworkCallback(req, cb)
            }
        } catch (e: Exception) {
            Log.w(TAG, "register network callback failed", e)
        }
    }

    fun stop() {
        if (!running) return
        running = false
        callback?.let {
            try {
                cm?.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                Log.w(TAG, "unregister network callback failed", e)
            }
        }
        callback = null
    }
}
