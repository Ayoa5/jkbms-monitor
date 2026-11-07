package com.jkbms.monitor.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.jkbms.monitor.model.BmsData
import com.jkbms.monitor.protocol.JkProtocol
import java.util.UUID

/**
 * JK-BMS BLE 连接器：
 *   Service 0xFFE0 / Characteristic 0xFFE1 (notify)
 *   连接后每 1.5s 写一次 cell-info 请求帧，收到 55 AA EB 90 开头的数据帧即解析。
 */
@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
        val CHAR_UUID: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    data class DeviceItem(val name: String, val address: String, val rssi: Int)

    var onDeviceList: ((List<DeviceItem>) -> Unit)? = null
    var onStatus: ((String) -> Unit)? = null
    var onData: ((BmsData) -> Unit)? = null
    var is32S: Boolean = true

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val handler = Handler(Looper.getMainLooper())
    private val found = LinkedHashMap<String, DeviceItem>()
    private val rx = ArrayList<Byte>()
    private var gatt: BluetoothGatt? = null
    private var notifyChar: BluetoothGattCharacteristic? = null
    private var scanning = false

    val isBluetoothReady: Boolean get() = adapter?.isEnabled == true

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val r = result ?: return
            // 未连接时 device.name 常为 null，必须优先取广播包里的名字
            val name = r.scanRecord?.deviceName?.takeIf { it.isNotBlank() }
                ?: try { r.device.name } catch (_: Exception) { null }
            Log.d("JkBmsBle", "scan: name=$name addr=${r.device.address} rssi=${r.rssi}")
            if (name == null) return
            // 先不过滤 JK/BMS，列出所有有名字的 BLE 设备，用户自行选 JK- 开头的
            found[r.device.address] = DeviceItem(name, r.device.address, r.rssi)
            onDeviceList?.invoke(found.values.toList())
        }
        override fun onScanFailed(errorCode: Int) {
            Log.d("JkBmsBle", "scan failed: $errorCode")
            onStatus?.invoke("扫描失败：$errorCode")
        }
    }

    fun startScan() {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) { onStatus?.invoke("蓝牙不可用/未开启"); return }
        if (scanning) return
        scanning = true
        found.clear()
        onDeviceList?.invoke(emptyList())
        onStatus?.invoke("扫描中…")
        scanner.startScan(
            null,
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            scanCallback
        )
        handler.postDelayed({
            stopScan()
            onStatus?.invoke(if (found.isEmpty()) "未发现设备" else "发现 ${found.size} 个设备")
        }, 10000)
    }

    fun stopScan() {
        if (!scanning) return
        scanning = false
        try { adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Exception) {}
    }

    fun connect(address: String) {
        stopScan()
        val device = try { adapter?.getRemoteDevice(address) } catch (_: Exception) { null }
        if (device == null) { onStatus?.invoke("设备地址无效"); return }
        onStatus?.invoke("连接中…")
        gatt = device.connectGatt(context, false, gattCallback)
    }

    fun disconnect() {
        handler.removeCallbacksAndMessages(null)
        try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
        gatt = null; notifyChar = null; rx.clear()
        onStatus?.invoke("已断开")
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                onStatus?.invoke("已连接，发现服务…")
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                onStatus?.invoke("连接已断开")
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(SERVICE_UUID)
            val ch = service?.getCharacteristic(CHAR_UUID)
            if (ch == null) { onStatus?.invoke("未找到 0xFFE0/0xFFE1"); return }
            notifyChar = ch
            g.setCharacteristicNotification(ch, true)
            val cccd = ch.getDescriptor(CCCD_UUID)
            if (cccd != null) {
                if (Build.VERSION.SDK_INT >= 33) {
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(cccd)
                }
            }
            onStatus?.invoke("已连接")
            scheduleRequest()
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            feed(value)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            feed(ch.value ?: return)
        }
    }

    private fun scheduleRequest() {
        handler.removeCallbacksAndMessages(null)
        val runnable = object : Runnable {
            override fun run() {
                write(JkProtocol.REQUEST_CELL_INFO)
                handler.postDelayed(this, 1500)
            }
        }
        handler.postDelayed(runnable, 500)
    }

    private fun write(bytes: ByteArray) {
        val g = gatt ?: return
        val ch = notifyChar ?: return
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(ch, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            } else {
                @Suppress("DEPRECATION")
                ch.value = bytes
                @Suppress("DEPRECATION")
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                g.writeCharacteristic(ch)
            }
        } catch (_: Exception) {}
    }

    private fun feed(value: ByteArray) {
        for (b in value) rx.add(b)
        while (true) {
            var start = -1
            for (i in 0..rx.size - 4) {
                if (rx[i] == 0x55.toByte() && rx[i + 1] == 0xAA.toByte() &&
                    rx[i + 2] == 0xEB.toByte() && rx[i + 3] == 0x90.toByte()
                ) { start = i; break }
            }
            if (start < 0) { if (rx.size > 4096) rx.clear(); return }
            if (start > 0) repeat(start) { if (rx.isNotEmpty()) rx.removeAt(0) }
            if (rx.size < 6) return
            val total = 5 + ((rx[4].toInt() and 0xFF) + 1)
            if (rx.size < total) return
            val frame = ByteArray(total) { rx[it] }
            repeat(total) { if (rx.isNotEmpty()) rx.removeAt(0) }
            JkProtocol.parseCellInfo(frame, is32S)?.let { onData?.invoke(it) }
        }
    }
}
