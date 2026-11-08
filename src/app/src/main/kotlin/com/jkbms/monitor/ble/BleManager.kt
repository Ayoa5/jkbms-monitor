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
 *   连接后先 requestMtu(517)（JK 响应帧 200~300 字节），CCCD 订阅成功后再每 1.5s 发请求帧。
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
    var onLog: ((String) -> Unit)? = null
    /** JK-BMS 握手密码（设备密码，用户实测 335058）。 */
    var handshakePassword: String = "335058"

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val handler = Handler(Looper.getMainLooper())
    private val found = LinkedHashMap<String, DeviceItem>()
    private val rx = ArrayList<Byte>()
    private var gatt: BluetoothGatt? = null
    private var notifyChar: BluetoothGattCharacteristic? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var scanning = false

    private fun log(msg: String) {
        Log.d("JkBmsBle", msg)
        onLog?.invoke(msg)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val r = result ?: return
            val name = r.scanRecord?.deviceName?.takeIf { it.isNotBlank() }
                ?: try { r.device.name } catch (_: Exception) { null }
            if (name == null) return
            found[r.device.address] = DeviceItem(name, r.device.address, r.rssi)
            onDeviceList?.invoke(found.values.toList())
            log("扫描到 $name ${r.device.address}")
        }
        override fun onScanFailed(errorCode: Int) {
            log("扫描失败 $errorCode")
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
        log("开始扫描")
        scanner.startScan(
            null,
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            scanCallback
        )
        handler.postDelayed({
            stopScan()
            onStatus?.invoke(if (found.isEmpty()) "未发现设备" else "发现 ${found.size} 个设备")
            log("扫描结束，共 ${found.size} 个")
        }, 5000)
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
        log("连接 $address")
        gatt = device.connectGatt(context, false, gattCallback)
    }

    fun disconnect() {
        handler.removeCallbacksAndMessages(null)
        try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
        gatt = null; notifyChar = null; rx.clear()
        onStatus?.invoke("已断开")
        log("手动断开")
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            log("连接状态 newState=$newState status=$status")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                onStatus?.invoke("已连接，请求 MTU…")
                try { g.requestMtu(517) } catch (_: Exception) {}
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                handler.removeCallbacksAndMessages(null)
                onStatus?.invoke("连接已断开")
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            log("MTU=$mtu status=$status")
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            log("服务发现 status=$status")
            val service = g.getService(SERVICE_UUID)
            service?.characteristics?.forEach { c -> log("char ${c.uuid} props=${c.properties}") }
            val ch = service?.getCharacteristic(CHAR_UUID)
            log("service=${service != null} char=${ch != null}")
            if (ch == null) { onStatus?.invoke("未找到 0xFFE0/0xFFE1"); log("未找到特征"); return }
            notifyChar = ch
            writeChar = service?.characteristics?.firstOrNull {
                (it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0
            } ?: ch
            log("writeChar=${writeChar?.uuid}")
            val ok = g.setCharacteristicNotification(ch, true)
            log("setNotification=$ok")
            val cccd = ch.getDescriptor(CCCD_UUID)
            log("CCCD=${cccd != null}")
            if (cccd != null) {
                if (Build.VERSION.SDK_INT >= 33) {
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(cccd)
                }
            } else {
                onStatus?.invoke("已连接")
                scheduleRequest()
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            log("CCCD 写入 status=$status")
            if (d.uuid == CCCD_UUID) {
                onStatus?.invoke("已连接")
                scheduleRequest()
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            log("notify ${value.size}B: ${hex(value, 24)}")
            handleHandshake(value)
            feed(value)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val v = ch.value ?: return
            log("notify ${v.size}B: ${hex(v, 24)}")
            handleHandshake(v)
            feed(v)
        }
    }

    private fun scheduleRequest() {
        handler.removeCallbacksAndMessages(null)
        val runnable = object : Runnable {
            override fun run() {
                // 先 0x97，300ms 后 0x96，1.5s 一轮（比 2.5s 更快刷新）
                write(JkProtocol.REQUEST_DEVICE_INFO)
                handler.postDelayed({ write(JkProtocol.REQUEST_CELL_INFO) }, 300)
                handler.postDelayed(this, 1500)
            }
        }
        handler.postDelayed(runnable, 400)
    }

    private fun write(bytes: ByteArray) {
        val g = gatt ?: return
        val ch = writeChar ?: notifyChar ?: return
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(ch, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            } else {
                @Suppress("DEPRECATION")
                ch.value = bytes
                @Suppress("DEPRECATION")
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                g.writeCharacteristic(ch)
            }
            log("写请求(${ch.uuid.toString().takeLast(8)}) ${hex(bytes, 20)}")
        } catch (e: Exception) {
            log("写请求失败 ${e.message}")
        }
    }

    /** JK-BMS 要求握手：BMS 发 AA 55 90 EB C8 01 01… 时原样回写前 20 字节。 */
    private fun handleHandshake(value: ByteArray) {
        if (value.size >= 6 &&
            value[0] == 0xAA.toByte() && value[1] == 0x55.toByte() &&
            value[2] == 0x90.toByte() && value[3] == 0xEB.toByte()
        ) {
            // 握手帧需带设备密码：AA 55 90 EB C8 01 01 + 12B 密码区 + CRC
            val f = ByteArray(20)
            f[0] = 0xAA.toByte(); f[1] = 0x55; f[2] = 0x90.toByte(); f[3] = 0xEB.toByte()
            f[4] = 0xC8.toByte(); f[5] = 0x01; f[6] = 0x01
            val pwd = handshakePassword.toByteArray(Charsets.US_ASCII)
            for (i in pwd.indices) { if (7 + i < 19) f[7 + i] = pwd[i] }
            var crc = 0
            for (i in 0 until 19) crc = (crc + (f[i].toInt() and 0xFF)) and 0xFF
            f[19] = crc.toByte()
            write(f)
            log("回握手+密码 ${hex(f, 20)}")
        }
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
            val total = 7 + (rx[5].toInt() and 0xFF)
            if (rx.size < total) return
            val frame = ByteArray(total) { rx[it] }
            repeat(total) { if (rx.isNotEmpty()) rx.removeAt(0) }
            log("帧 ${frame.size}B type=${frame.getOrNull(4)} ${hex(frame, 20)}")
            JkProtocol.parseCellInfo(frame)?.let { d ->
                log("解析 V=%.2f I=%.1f SOC=%d rem=%.1f cyc=%d soh=%d cells=%d".format(
                    d.totalVoltage, d.current, d.socPercent.toInt(), d.remainingAh, d.cycleCount, d.sohPercent.toInt(), d.cellCount))
                onData?.invoke(d)
            }
        }
    }

    private fun hex(b: ByteArray, limit: Int): String {
        val n = minOf(b.size, limit)
        val sb = StringBuilder()
        for (i in 0 until n) sb.append("%02X".format(b[i]))
        if (b.size > limit) sb.append("…(${b.size}B)")
        return sb.toString()
    }
}
