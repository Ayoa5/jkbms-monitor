package com.jkbms.monitor.protocol

import com.jkbms.monitor.model.BmsData

/**
 * JK-BMS JK02 协议解析（只读，cell-info 帧 0x02）。
 * 参考 syssi/esphome-jk-bms 的 offset 表：
 *   - 帧头 55 AA EB 90, 随后 len(1) cmd(1)，数据从 offset 6 开始
 *   - 电芯电压: 6 + 2*i (mV, u16)
 *   - 总压: 118 (u32, 0.001V)
 *   - 电流: 126 (i32, 0.001A, 正=充电)
 *   - 温度1: 130 / 温度2: 132 / MOS: 134 (u16, 0.1°C, 0xFFFF=无)
 *   - 保护位: 138 (u32)
 *   - 均衡电流: 140 (i32, 0.001A)
 *   - SOC: 142 (u8, %)
 *   - 剩余容量: 146 (u32, 0.001Ah)
 *   - 循环次数: 158 (u32)
 *   - SOH: 162 (u8, %)
 *   - 充/放 FET: 166/167 (u8)
 * JK02_32S（固件 sw>=11）在以上 offset 基础上整体 +32。
 */
object JkProtocol {

    /** JK02 请求 cell-info 帧（20 字节定长）：AA 55 90 EB | 96 | 00 | value(4) | 填充(9) | CRC。
     *  命令码是 0x96（COMMAND_CELL_INFO），不是 0x02（0x02 是响应帧的记录类型）。 */
    val REQUEST_CELL_INFO: ByteArray = buildRequestFrame(0x96)
    val REQUEST_DEVICE_INFO: ByteArray = buildRequestFrame(0x97)

    private fun buildRequestFrame(cmd: Int): ByteArray {
        val f = ByteArray(20)
        f[0] = 0xAA.toByte(); f[1] = 0x55; f[2] = 0x90.toByte(); f[3] = 0xEB.toByte()
        f[4] = cmd.toByte()
        f[5] = 0x00
        var crc = 0
        for (i in 0 until 19) crc = (crc + (f[i].toInt() and 0xFF)) and 0xFF
        f[19] = crc.toByte()
        return f
    }

    /** 从缓存里尝试拼出一帧：返回 (完整帧, 剩余未消费字节)。 */
    fun extractFrame(buffer: ByteArray): ByteArray? {
        var i = 0
        while (i + 3 < buffer.size) {
            if (buffer[i] == 0x55.toByte() && buffer[i + 1] == 0xAA.toByte() &&
                buffer[i + 2] == 0xEB.toByte() && buffer[i + 3] == 0x90.toByte()
            ) {
                if (i + 5 >= buffer.size) return null
                val len = (buffer[i + 4].toInt() and 0xFF) + 1 // 长度字节 + 自身
                val end = i + 5 + len // 5 = 头4 + len1
                if (end <= buffer.size) {
                    return buffer.copyOfRange(i, end)
                }
                return null
            }
            i++
        }
        return null
    }

    /** 解析 cell-info 帧。is32S: 固件 sw>=11 走 32S offset (+32)。 */
    fun parseCellInfo(frame: ByteArray, is32S: Boolean? = null): BmsData? {
        val off = if (is32S ?: (frame.size > 250)) 32 else 0
        if (frame.size < 190 + off) return null
        val cmd = frame[4].toInt() and 0xFF
        if (cmd != 0x02) return null

        val cells = ArrayList<Float>(32)
        for (i in 0 until 32) {
            val p = 6 + 2 * i
            if (p + 1 >= frame.size) break
            val mv = u16(frame, p)
            if (mv <= 0 || mv >= 0xFFFF) break
            cells.add(mv / 1000f)
        }
        if (cells.isEmpty()) return null

        val totalV = u32(frame, 118 + off) / 1000f
        val current = i32(frame, 126 + off) / 1000f
        val t1 = tempAt(frame, 130 + off)
        val t2 = tempAt(frame, 132 + off)
        val mos = tempAt(frame, 134 + off)
        val protoBits = u32(frame, 138 + off)
        val soc = frame.getOrZero(141 + off).toInt() and 0xFF
        val remainAh = u32(frame, 142 + off) / 1000f
        val cycles = u32(frame, 150 + off)
        val soh = frame.getOrZero(158 + off).toInt() and 0xFF
        val chargeFet = frame.getOrZero(166 + off).toInt() and 0xFF
        val dischargeFet = frame.getOrZero(167 + off).toInt() and 0xFF
        val balanceCurrent = i32(frame, 140 + off) / 1000f

        return BmsData(
            socPercent = soc.toFloat(),
            remainingAh = remainAh,
            fullAh = if (soh > 0) remainAh * 100f / soh else 0f,
            totalVoltage = totalV,
            current = current,
            mosTemp = mos,
            temp1 = t1,
            temp2 = t2,
            cellVoltages = cells,
            cycleCount = cycles.toInt(),
            sohPercent = soh.toFloat(),
            charging = current > 0.3f,
            discharging = current < -0.3f,
            balancing = balanceCurrent > 0.05f,
            protectionActive = protoBits != 0L,
        )
    }

    private fun u16(b: ByteArray, i: Int): Int =
        ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

    private fun u32(b: ByteArray, i: Int): Long =
        ((b[i].toLong() and 0xFF) shl 24) or
            ((b[i + 1].toLong() and 0xFF) shl 16) or
            ((b[i + 2].toLong() and 0xFF) shl 8) or
            (b[i + 3].toLong() and 0xFF)

    private fun i32(b: ByteArray, i: Int): Int = u32(b, i).toInt()

    private fun tempAt(b: ByteArray, i: Int): Float {
        val raw = u16(b, i)
        return if (raw == 0xFFFF || raw == 0) 0f else raw / 10f
    }

    private fun ByteArray.getOrZero(i: Int): Byte = if (i < size) this[i] else 0
}
