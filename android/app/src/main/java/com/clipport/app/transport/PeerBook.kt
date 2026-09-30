package com.clipport.app.transport

import android.content.Context

/** 对端设备信息（二期对等模式）。 */
data class PeerEntry(
    val deviceId: String,
    var name: String = "",
    var certFpHex: String? = null,
    var endpoint: String? = null,
    var lastSeen: Long = 0,
)

/** 对端库（SharedPreferences JSON 持久化）。 */
class PeerBook(context: Context) {
    private val sp = context.getSharedPreferences("clipport_peers", Context.MODE_PRIVATE)
    private val items = LinkedHashMap<String, PeerEntry>()

    init {
        runCatching {
            val arr = org.json.JSONArray(sp.getString("peers", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                items[o.getString("deviceId")] = PeerEntry(
                    o.getString("deviceId"),
                    o.optString("name"),
                    o.optString("certFpHex").ifEmpty { null },
                    o.optString("endpoint").ifEmpty { null },
                    o.optLong("lastSeen"),
                )
            }
        }
    }

    @Synchronized
    fun upsert(deviceId: String, name: String?, certFpHex: String?, endpoint: String?): PeerEntry {
        val e = items.getOrPut(deviceId) { PeerEntry(deviceId) }
        if (!name.isNullOrEmpty()) e.name = name
        if (!certFpHex.isNullOrEmpty()) e.certFpHex = certFpHex
        if (!endpoint.isNullOrEmpty()) e.endpoint = endpoint
        e.lastSeen = System.currentTimeMillis()
        persist()
        return e
    }

    @Synchronized
    fun known(deviceId: String): Boolean = items.containsKey(deviceId)

    @Synchronized
    fun byEndpoint(endpoint: String): PeerEntry? = items.values.firstOrNull { it.endpoint == endpoint }

    @Synchronized
    fun fingerprintOf(deviceId: String): ByteArray? =
        items[deviceId]?.certFpHex?.chunked(2)?.map { it.toInt(16).toByte() }?.toByteArray()

    @Synchronized
    fun all(): List<PeerEntry> = items.values.toList()

    @Synchronized
    private fun persist() {
        val arr = org.json.JSONArray()
        for (e in items.values) {
            arr.put(
                org.json.JSONObject()
                    .put("deviceId", e.deviceId)
                    .put("name", e.name)
                    .put("certFpHex", e.certFpHex ?: "")
                    .put("endpoint", e.endpoint ?: "")
                    .put("lastSeen", e.lastSeen)
            )
        }
        sp.edit().putString("peers", arr.toString()).apply()
    }
}
