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
    var fullRangeKm by mutableStateOf(100f)

    init {
        ble.onStatus = { statusText = it }
        ble.onDeviceList = { devices = it }
        ble.onData = { data = it }
    }

    fun startScan() = ble.startScan()
    fun connect(address: String) = ble.connect(address)
    fun disconnect() = ble.disconnect()

    fun setFullRange(km: Float) { fullRangeKm = km.coerceIn(1f, 2000f) }

    override fun onCleared() {
        super.onCleared()
        ble.disconnect()
    }
}
