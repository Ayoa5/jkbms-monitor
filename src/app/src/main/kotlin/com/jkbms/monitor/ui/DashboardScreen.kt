@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.jkbms.monitor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jkbms.monitor.BmsViewModel
import com.jkbms.monitor.model.BmsData
import com.jkbms.monitor.model.remainingRangeKm

private val Green = Color(0xFF1D9E75)
private val GreenDark = Color(0xFF0F6E56)
private val Red = Color(0xFFA32D2D)
private val Amber = Color(0xFFBA7517)
private val Gray = Color(0xFF888780)
private val Track = Color(0xFFEFEFEF)

private val CardShape = RoundedCornerShape(16.dp)

@Composable
private fun MiCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier,
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) { content() }
}

@Composable
fun DashboardScreen(vm: BmsViewModel) {
    val data = vm.data
    var showRangeDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("极空BMS") },
                actions = {
                    Text(vm.statusText, fontSize = 12.sp, color = Gray)
                    Spacer(Modifier.width(8.dp))
                    if (data != null) {
                        TextButton(onClick = { vm.disconnect() }) { Text("断开") }
                    } else {
                        TextButton(onClick = { vm.startScan() }) { Text("扫描") }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (data == null) {
                ConnectPanel(vm)
            } else {
                HeroCard(data, vm.fullRangeKm) { showRangeDialog = true }
                MetricRow(data)
                AvgCellRow(data)
                TempProtectRow(data)
                Footer(data)
            }
        }
    }

    if (showRangeDialog) {
        RangeDialog(vm) { showRangeDialog = false }
    }
}

@Composable
private fun ConnectPanel(vm: BmsViewModel) {
    MiCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            val connected = vm.statusText.contains("已连接") || vm.statusText.contains("发现服务") || vm.statusText.contains("等待")
            Text(if (connected) "已连接，等待数据…" else "未连接 BMS", fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Text(
                "点击右上角「扫描」，搜索附近名称含 JK / BMS 的设备，然后点「连接」。",
                fontSize = 12.sp,
                color = Gray,
            )
            if (vm.devices.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                vm.devices.forEach { d ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { vm.connect(d.address) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(d.name, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(d.address, fontSize = 11.sp, color = Gray)
                        }
                        Text("${d.rssi} dBm", fontSize = 11.sp, color = Gray)
                        Spacer(Modifier.width(10.dp))
                        Text("连接", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                    }
                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun RangeDialog(vm: BmsViewModel, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(vm.fullRangeKm.toInt().toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("满电续航里程") },
        text = {
            Column {
                Text("设置满电时能跑多少公里，剩余续航按 SOC 比例实时计算。", fontSize = 12.sp, color = Gray)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { input -> text = input.filter { it.isDigit() } },
                    label = { Text("满电可跑 km") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                text.toFloatOrNull()?.let { vm.setFullRange(it) }
                onDismiss()
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun HeroCard(data: BmsData, fullRangeKm: Float, onEditRange: () -> Unit) {
    val rangeKm = remainingRangeKm(data.socPercent, fullRangeKm)
    MiCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("剩余电量", fontSize = 11.sp, color = Gray)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("%.1f".format(data.socPercent), fontSize = 34.sp, fontWeight = FontWeight.Medium, color = GreenDark)
                        Text(" %", fontSize = 15.sp, color = Gray)
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text("剩余续航", fontSize = 11.sp, color = Gray)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("%.1f".format(rangeKm), fontSize = 34.sp, fontWeight = FontWeight.Medium, color = GreenDark)
                        Text(" km", fontSize = 15.sp, color = Gray)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Track)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth((data.socPercent / 100f).coerceIn(0f, 1f))
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Green)
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("%.1f / %.1f Ah".format(data.remainingAh, data.fullAh), fontSize = 12.sp, color = Gray)
                Text("%.2f kW".format(data.power / 1000f), fontSize = 12.sp, color = Gray)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "满电预设 %.0f km · 点此修改".format(fullRangeKm),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { onEditRange() },
            )
        }
    }
}

@Composable
private fun MetricRow(data: BmsData) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MetricCard("总压", "%.2f".format(data.totalVoltage), "V", Modifier.weight(1f))
        MetricCard("电流", "%.1f".format(data.current), "A", Modifier.weight(1f))
        MetricCard("MOS温度", "%.1f".format(data.mosTemp), "°C", Modifier.weight(1f))
    }
}

@Composable
private fun MetricCard(label: String, value: String, unit: String, modifier: Modifier) {
    MiCard(modifier = modifier) {
        Column(Modifier.padding(10.dp)) {
            Text(label, fontSize = 11.sp, color = Gray)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                Text(" $unit", fontSize = 11.sp, color = Gray)
            }
        }
    }
}

@Composable
private fun AvgCellRow(data: BmsData) {
    val avg = if (data.cellCount > 0) data.cellVoltages.average().toFloat() else 0f
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MetricCard("平均电芯电压", "%.3f".format(avg), "V", Modifier.weight(1f))
        MetricCard("最大压差", "${data.deltaMv}", "mV", Modifier.weight(1f))
        MiCard(modifier = Modifier.weight(1f)) {
            Column(Modifier.padding(10.dp)) {
                Text("状态", fontSize = 11.sp, color = Gray)
                Text(
                    data.statusText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = when {
                        data.charging -> Color(0xFF185FA5)
                        data.discharging -> GreenDark
                        else -> Gray
                    },
                )
            }
        }
    }
}

@Composable
private fun CellCard(data: BmsData) {
    MiCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("电芯电压 · ${data.cellCount}S", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Row {
                    Text("压差 ", fontSize = 11.sp, color = Gray)
                    Text("${data.deltaMv} mV", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Amber)
                }
            }
            Spacer(Modifier.height(10.dp))
            val rows = (data.cellCount + 3) / 4
            for (r in 0 until rows) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (c in 0 until 4) {
                        val idx = r * 4 + c
                        if (idx < data.cellCount) {
                            CellCell(idx, data.cellVoltages[idx], data.maxCell, data.minCell, Modifier.weight(1f))
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
                if (r < rows - 1) Spacer(Modifier.height(6.dp))
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Row {
                    Text("最高 ", fontSize = 11.sp, color = Gray)
                    Text("C${data.maxCellIndex} %.3fV".format(data.maxCell), fontSize = 11.sp, fontWeight = FontWeight.Medium, color = GreenDark)
                }
                Row {
                    Text("最低 ", fontSize = 11.sp, color = Gray)
                    Text("C${data.minCellIndex} %.3fV".format(data.minCell), fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Red)
                }
            }
        }
    }
}

@Composable
private fun CellCell(index: Int, v: Float, maxV: Float, minV: Float, modifier: Modifier) {
    val isMax = v >= maxV - 0.0005f
    val isMin = v <= minV + 0.0005f
    val bg = when {
        isMax -> Color(0xFFE1F5EE)
        isMin -> Color(0xFFFCEBEB)
        else -> Color(0xFFF5F5F5)
    }
    val fg = when {
        isMax -> GreenDark
        isMin -> Red
        else -> Color(0xFF2C2C2A)
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("C${index + 1}", fontSize = 11.sp, color = if (isMax) GreenDark else if (isMin) Red else Gray)
        Text("%.3f".format(v), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = fg)
    }
}

@Composable
private fun TempProtectRow(data: BmsData) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SmallStat("T1", "%.1f °C".format(data.temp1), Modifier.weight(1f))
        SmallStat("T2", "%.1f °C".format(data.temp2), Modifier.weight(1f))
        SmallStat("保护", if (data.protectionActive) "告警" else "正常", Modifier.weight(1f), if (data.protectionActive) Red else Green)
    }
}

@Composable
private fun SmallStat(label: String, value: String, modifier: Modifier, valueColor: Color = Color(0xFF2C2C2A)) {
    MiCard(modifier = modifier) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 11.sp, color = Gray)
            Text(value, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = valueColor)
        }
    }
}

@Composable
private fun Footer(data: BmsData) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("循环 ${data.cycleCount}", fontSize = 11.sp, color = Gray)
        Text("SOH %.0f%%".format(data.sohPercent), fontSize = 11.sp, color = Gray)
        Text("满充 %.1f Ah".format(data.fullAh), fontSize = 11.sp, color = Gray)
    }
}

@Composable
private fun LogPanel(logs: List<String>) {
    if (logs.isEmpty()) return
    MiCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("BLE 日志（${logs.size}）", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Gray)
            Spacer(Modifier.height(6.dp))
            logs.takeLast(22).forEach { line ->
                Text(line, fontSize = 10.sp, color = Gray)
            }
        }
    }
}
