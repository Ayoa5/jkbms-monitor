package com.jkbms.monitor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.jkbms.monitor.model.BmsData
import com.jkbms.monitor.ui.AppTheme
import com.jkbms.monitor.ui.DashboardScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sample = BmsData(
            socPercent = 65.9f,
            remainingAh = 112.4f,
            fullAh = 170f,
            totalVoltage = 52.31f,
            current = -23.7f,
            mosTemp = 32.1f,
            temp1 = 28.5f,
            temp2 = 29.0f,
            cellVoltages = List(16) { i -> 3.279f + (i % 5) * 0.003f },
            cycleCount = 128,
            sohPercent = 98f,
            discharging = true,
        )
        setContent {
            AppTheme {
                DashboardScreen(data = sample, fullRangeKm = 100f)
            }
        }
    }
}
