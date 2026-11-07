package com.jkbms.monitor.model

/**
 * 一次 BMS 读数（只读）。
 * 单位：电压 V、电流 A（正=充电，负=放电）、温度 °C、容量 Ah。
 */
data class BmsData(
    val socPercent: Float = 0f,
    val remainingAh: Float = 0f,
    val fullAh: Float = 0f,
    val totalVoltage: Float = 0f,
    val current: Float = 0f,
    val mosTemp: Float = 0f,
    val temp1: Float = 0f,
    val temp2: Float = 0f,
    val cellVoltages: List<Float> = emptyList(),
    val cycleCount: Int = 0,
    val sohPercent: Float = 0f,
    val charging: Boolean = false,
    val discharging: Boolean = false,
    val balancing: Boolean = false,
    val protectionActive: Boolean = false,
) {
    val power: Float get() = totalVoltage * current
    val cellCount: Int get() = cellVoltages.size
    val maxCell: Float get() = cellVoltages.maxOrNull() ?: 0f
    val minCell: Float get() = cellVoltages.minOrNull() ?: 0f
    val deltaMv: Int get() = ((maxCell - minCell) * 1000f).toInt()
    val maxCellIndex: Int get() = cellVoltages.indexOf(maxCell).let { if (it >= 0) it + 1 else 0 }
    val minCellIndex: Int get() = cellVoltages.indexOf(minCell).let { if (it >= 0) it + 1 else 0 }
    val statusText: String get() = when {
        charging -> "充电中"
        discharging -> "放电中"
        else -> "待机"
    }
}

/** 剩余续航 = 满电预设里程 × SOC% */
fun remainingRangeKm(socPercent: Float, fullRangeKm: Float): Float =
    (socPercent.coerceIn(0f, 100f) / 100f) * fullRangeKm
