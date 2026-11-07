# 极空（JK）BMS 监控 App（Android）

> project_23（2026-11-07 重排后新建）

## 目标
替代官方 App 的低可读性界面：把「剩余电量 / 剩余续航(km) / 总压 / 电流 / 功率 / 电芯电压 / 温度 / 保护状态」做成**信息分层、一眼可见**的 Dashboard。

## 技术栈
- Kotlin + Jetpack Compose（MIUIX / HyperOS 风格：大圆角卡片、层次化字号、状态色）
- BLE：`android.bluetooth.le`（Service `0xFFE0` / Characteristic `0xFFE1`，notify）
- 协议：JK02_24S / JK02_32S（按固件 sw 版本自适应；参考 `syssi/esphome-jk-bms`）
- 续航：`剩余续航 = 满电预设里程(默认100km, 可改) × SOC%`

## 功能
- 首版（只读）：SOC、剩余续航、电压/电流/功率、电芯电压网格（最高/最低/压差）、温度、保护位、循环/SOH/满充容量
- 后续：充放电 MOS 控制、保护参数设置、多设备切换

## 目录
- `src/` — Android 工程（Gradle + app/）
- `docs/` — JK 协议笔记、界面截图、真机联调记录

## 状态
2026-11-07：项目初始化，UI 方案已确认（见 docs 设计稿）。
