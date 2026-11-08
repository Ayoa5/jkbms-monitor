package com.jkbms.monitor

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.jkbms.monitor.ble.BleManager
import com.jkbms.monitor.model.BmsData

class BmsViewModel(app: Application) : AndroidViewModel(app) {

    private val ble = BleManager(app)

    var statusText by mutableStateOf("未连接")
        private set
    var data by mutableStateOf<BmsData?>(null)
        private set
    var devices by mutableStateOf<List<BleManager.DeviceItem>>(emptyList())
        private set
    private val prefs = app.getSharedPreferences("jkbms", android.content.Context.MODE_PRIVATE)
    var fullRangeKm by mutableStateOf(prefs.getFloat("fullRangeKm", 100f))
    var logs by mutableStateOf<List<String>>(emptyList())
        private set

    init {
        ble.onLog = { msg -> logs = (logs + msg).takeLast(120) }
        ble.onStatus = { statusText = it }
        ble.onDeviceList = { devices = it }
        ble.onData = { data = it }
    }

    fun startScan() = ble.startScan()
    fun connect(address: String) {
        prefs.edit().putString("lastAddress", address).apply()
        ble.connect(address)
    }

    fun autoConnect() {
        prefs.getString("lastAddress", null)?.let { ble.connect(it) }
    }
    fun disconnect() = ble.disconnect()

    fun setFullRange(km: Float) {
        val v = km.coerceIn(1f, 2000f)
        fullRangeKm = v
        prefs.edit().putFloat("fullRangeKm", v).apply()
    }

    override fun onCleared() {
        super.onCleared()
        ble.disconnect()
    }
}
