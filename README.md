# SafetyBelt 智能安全带监测系统

> **致评委：如果您需要快速验证系统功能，请直接阅读下方的“🚀 快速测试指南 (APK)”，无需配置复杂的开发环境。**

---

## 🚀 快速测试指南 (APK)

为了方便评委进行快速评审，我们在项目根目录下预置了已编译好的 APK 安装包。

- **APK 文件路径**：`./safetybelt-v1.0-debug.apk` (位于项目根目录)
- **安装步骤**：
    1. 将 `safetybelt-v1.0-debug.apk` 拷贝至您的 Android 真机。
    2. 在手机文件管理器中点击该文件。
    3. 若系统提示“禁止安装未知来源应用”，请前往设置授予权限后继续安装。
    4. 安装完成后，启动应用。
- **权限授予**：
    - **初次启动**：请务必授予应用所需的 **蓝牙 (Nearby Devices)、位置信息（BLE扫描必需）、震动、通知** 等权限。
    - **Android 12+**：请在系统弹出提示时允许“寻找、连接并确定近旁设备的位置”。
- **测试准备**：
    - **硬件依赖**：本软件需要配套本团队研发的 **Ble 传感器** 一起使用。
    - **命名规范**：请确保待连接的传感器蓝牙名称符合 **`SensorA_xxxx`** 格式（例如：`SensorA_1234`）。系统会自动过滤非本项目协议的设备。
    - **工人端测试**：选择“工人端” -> 登录 -> 点击“开始扫描” -> 点击匹配的 `SensorA_xxxx` 设备进行连接。连接成功后即可查看实时拉力数据及报警逻辑。
    - **监管端测试**：选择“监管员端” -> 登录 -> 点击右上角“+”号添加正在作业的工人工号，实现远程状态看板监控。

---

## 🛠️ 项目基础信息

### 1. 项目名称
**SafetyBelt 智能安全带监测系统** (SafetyBelt Intelligent Monitoring System)
*该项目旨在通过 BLE 蓝牙通信技术与云端实时同步技术，解决高空作业人员安全带佩戴状态的实时监控与预警问题。*

### 2. 运行环境 (Environment)
*   **物理硬件 (Must-have)**:
    *   **Android 真机**: 由于 Android 模拟器（如 AVD, Genymotion）无法完整模拟低功耗蓝牙 (BLE) 扫描及连接行为，**必须使用支持蓝牙 4.0+ 的 Android 物理手机**进行测试。
    *   **专用传感器**: 需配合本团队定制的 BLE 传感器硬件。**识别规则**: 设备名称必须以 `SensorA_` 开头（格式：`SensorA_1234`）。
*   **软件版本 (Software)**:
    *   **操作系统**: Android 7.0 (Nougat, API 24) 及以上。推荐 Android 10.0+。
    *   **构建环境**: 
        *   Android Studio Hedgehog (2023.1.1) 或以上版本。
        *   JDK 17 (项目已集成 Java 17 语言特性支持)。
        *   Gradle 8.12.2 (对应 Gradle Wrapper 已包含在项目中)。

### 3. 依赖库及安装命令 (Dependencies)
项目采用 Gradle 集中化依赖管理，只需确保网络畅通，IDE 会自动下载并配置。
*   **云端存储与实时同步**: 
    *   `cn.leancloud:storage-android:8.2.28` (核心存储)
    *   `cn.leancloud:realtime-android:8.2.28` (LiveQuery 实时监控)
*   **反应式编程与异步流**: 
    *   `io.reactivex.rxjava3:rxjava:3.1.8`
    *   `io.reactivex.rxjava3:rxandroid:3.0.2`
*   **UI 交互与架构**:
    *   `com.google.android.material:material:1.12.0` (Material 3)
    *   `androidx.navigation:navigation-fragment-ktx:2.7.7`
*   **安装命令**:
    在项目根目录下，您可以通过终端执行以下命令进行依赖同步与构建：
    ```bash
    # 同步依赖并编译 Debug 版本
    ./gradlew assembleDebug
    ```

### 4. 详细运行步骤 (Step-by-Step Guide)
**第一步：源码导入**
1. 启动 Android Studio。
2. 选择 `File -> Open...`，定位到 `D:/Project_SourceCode` 文件夹并打开。
3. 建议项目路径不要包含中文字符，以防 Gradle 构建过程中出现乱码导致失败。

**第二步：项目配置与同步**
1. 等待 IDE 下方的 `Gradle Sync` 进度条完成。
2. **网络优化**：若因网络原因导致依赖下载失败（如 LeanCloud 库），请在 `settings.gradle` 中添加阿里云或华为云镜像，或者开启全局科学上网。
3. 检查控制台输出，确保出现 `BUILD SUCCESSFUL` 字样。

**第三步：真机调试设置**
1. 在 Android 手机上进入 `设置 -> 关于手机`，连续点击 `版本号` 7 次开启开发者选项。
2. 进入 `设置 -> 系统 -> 开发者选项`，开启 **USB 调试**。
3. 使用 USB 数据线连接电脑。在 Android Studio 顶部的设备下拉列表中选中您的手机。

**第四步：编译、运行与授权**
1. 点击工具栏的绿色三角形 **`Run 'app'`** 按钮。
2. 应用启动后，系统会先后请求 **定位权限** 和 **蓝牙扫描/连接权限**。
3. **关键操作**：请务必点击 **“允许”**，否则搜索不到任何 `SensorA_xxxx` 传感器。

---

## 📋 项目简介

**SafetyBelt**是一款专为工业安全设计的移动端智能监测系统。该系统通过蓝牙低功耗（BLE）技术连接安全带传感器，实时采集工人的作业状态，并利用 LeanCloud 云端同步技术实现远程监管。系统分为**操作端（Operator）**和**监管端（Regulator）**，确保安全生产数据实时可查、异常即时报警。

### 项目特色

- **双端架构设计**：操作端供作业人员使用，监管端供安全管理人员使用
- **实时蓝牙连接**：支持多设备同时连接，最多管理 6 路传感器
- **云端数据同步**：基于 LeanCloud LiveQuery 技术实现毫秒级状态同步
- **多重报警机制**：语音报警、震动提醒、UI 弹窗三种方式结合

---

## 🚀 核心功能

### 操作端（Operator）功能

| 功能模块 | 描述 |
|---------|------|
| **蓝牙设备扫描** | 自动搜索附近的 BLE 传感器设备，显示信号强度 |
| **多设备连接管理** | 支持 HBS（后背绳高挂低用）、WGD（围杆带）、XYK（胸扣）三类设备同时连接 |
| **实时状态监测** | 解析 6 路传感器数据，实时显示各传感器状态（正常/单挂/异常） |
| **异常状态报警** | 检测到未挂钩、异常受力等情况时触发语音和震动报警 |
| **作业记录管理** | 记录每次作业的开始/结束时间、持续时间、报警次数 |
| **个人中心** | 查看个人信息、历史作业数据统计 |

### 监管端（Regulator）功能

| 功能模块 | 描述 |
|---------|------|
| **工人实时监控** | 按姓名和工号查找并添加在线工人到监控列表 |
| **多工人状态看板** | 同时监控多个工人的作业状态和传感器详情 |
| **报警信息推送** | 收到工人端异常状态时自动震动提醒 |
| **远程报警指令** | 向异常工人发送监管报警提示 |
| **历史记录查询** | 查看指定工人的历史作业记录 |

---

## 📁 项目详细目录结构

```
app/src/main/java/com/heyu/safetybelt/
│
├── application/                        # 应用初始化
│   └── MainApplication.kt              # 全局入口，初始化 LeanCloud SDK
│
├── common/                             # 跨端公共模块
│   ├── activity/
│   │   ├── SplashActivity.kt           # 启动闪屏页
│   │   ├── UserIdentity.kt             # 身份选择页（工人/监管员）
│   │   └── LoginActivity.kt            # 统一登录页面
│   ├── AlarmEvent.kt                   # 报警事件记录模型
│   ├── Device.kt                       # 蓝牙设备信息模型
│   ├── SessionManager.kt               # 会话状态管理
│   ├── Worker.kt                       # 工人信息模型
│   ├── WorkerStatus.kt                 # 工人实时状态数据类
│   └── WorkSession.kt                  # 作业会话模型
│
├── operator/                           # 工人操作端
│   ├── activity/
│   │   ├── MainActivityOperator.kt      # 操作端主 Activity
│   │   ├── SplashActivity.kt           # 操作端启动页
│   │   └── LoginActivity.kt            # 操作端登录页
│   ├── fragment/
│   │   ├── SafetybeltFragment.kt       # 安全带检测模块容器
│   │   ├── DetectionFragment.kt        # 设备扫描和连接页面
│   │   ├── MonitoringFragment.kt       # 作业状态监控页面
│   │   ├── WorkRecordFragment.kt       # 作业记录页面
│   │   ├── ProfileFragment.kt          # 个人中心页面
│   │   └── AlarmDetailDialogFragment.kt # 报警详情弹窗
│   ├── service/
│   │   └── BleService.kt              # [核心] BLE 通信服务
│   ├── adapter/
│   │   ├── ScanDeviceAdapter.kt        # 扫描设备列表适配器
│   │   ├── StatusDeviceAdapter.kt      # 已连接设备状态适配器
│   │   └── WorkRecordAdapter.kt        # 作业记录列表适配器
│   ├── model/
│   │   ├── DeviceScanResult.kt         # 设备扫描结果数据类
│   │   ├── WorkRecord.kt               # 作业记录数据类
│   │   └── WorkRecordManager.kt        # 作业记录管理类
│   └── util/
│       └── MonitoringUtil.kt           # 监控工具类（信号强度等）
│
├── regulator/                          # 监管端
│   ├── activity/
│   │   ├── MainActivityRegulator.kt    # 监管端主 Activity
│   │   ├── SplashActivity.kt           # 监管端启动页
│   │   └── LoginActivity.kt            # 监管端登录页
│   ├── fragment/
│   │   ├── MonitoringFragment.kt       # 工人监控页面
│   │   └── ProfileFragment.kt          # 监管员个人中心
│   ├── service/
│   │   └── UnderService.kt             # [核心] 监控后台服务
│   └── adapter/
│       ├── WorkerStatusAdapter.kt      # 工人状态列表适配器
│       └── WorkHistoryAdapter.kt       # 历史记录适配器
│
└── res/                                # 资源文件
    ├── layout/                         # 布局文件（30+ 个）
    ├── menu/                           # 菜单文件
    ├── drawable/                       # 图标和drawable资源
    ├── values/                         # 值资源（colors.xml, strings.xml等）
    └── xml/                            # 配置文件（备份规则等）
```

---

## 🛠️ 技术栈

### 核心技术

| 技术 | 版本 | 用途 |
|-----|------|------|
| **Kotlin** | 2.0.21 | 主要开发语言 |
| **Android SDK** | compileSdk = 36 | Android 平台 |
| **Gradle** | 8.12.2 | 构建工具 |

### 依赖库

```kotlin
// Android Jetpack
androidx.core:core-ktx:1.13.1           // 核心扩展
androidx.activity:activity-ktx:1.9.0  // Activity 组件
androidx.navigation:navigation-fragment-ktx:2.7.7  // 导航组件

// UI 组件
androidx.appcompat:appcompat:1.6.1      // 兼容库
com.google.android.material:material:1.12.0  // Material Design
androidx.swiperefreshlayout:swiperefreshlayout:1.1.0  // 下拉刷新

 BLE 和硬件
Android Bluetooth Low Energy (BLE)     // 蓝牙低功耗服务

// 云服务
cn.leancloud:storage-android:8.2.28    // LeanCloud 存储
cn.leancloud:realtime-android:8.2.28   // LeanCloud 实时通信
cn.leancloud:storage-core:8.2.22       // LeanCloud 核心

// 异步处理
io.reactivex.rxjava3:rxjava:3.1.8       // RxJava3
io.reactivex.rxjava3:rxandroid:3.0.2    // RxJava3 Android
io.reactivex.rxjava2:rxandroid:2.1.1    // RxJava2（LeanCloud 依赖）

// 工具库
com.google.code.gson:gson:2.10.1       // JSON 解析
com.alibaba:fastjson:1.2.83             // JSON 处理（LeanCloud 依赖）
androidx.localbroadcastmanager:localbroadcastmanager:1.1.0  // 本地广播
```

### 架构组件

- **ViewBinding**：视图绑定，替代 findViewById
- **Fragment**：模块化 UI 组件
- **Navigation Component**：Fragment 导航管理
- **Foreground Service**：后台服务保持连接
- **LocalBroadcastManager**：组件间通信

---

## 🔑 核心技术实现

### 1. BleService - 蓝牙通信核心服务

**职责**：
- BLE 设备扫描与 GATT 连接管理
- 6 路传感器数据解析（HBS/WGD/XYK 三类设备）
- 心跳保持机制（每 60 秒上报云端）
- 异常状态检测与报警触发
- LeanCloud 实时数据同步

**关键特性**：
- 支持多设备并发连接
- 自动重连机制
- TextToSpeech 语音报警
- 震动反馈
- 通知栏常驻显示

### 2. UnderService - 监控后台服务

**职责**：
- 管理所有被监控工人的 LiveQuery 订阅
- 实时接收工人状态变化
- 异常工人震动提醒
- 工人列表缓存与更新
- 20 秒自动刷新机制

**关键特性**：
- Service Binding 与 Activity 通信
- 自定义 WorkerListListener 接口
- 批量工人管理

### 3. 数据模型设计

#### Worker（工人信息）
```kotlin
@LCClassName("Worker")
class Worker : LCObject() {
    var name: String?           // 工人姓名
    var employeeId: String?     // 工人工号
}
```

#### WorkSession（作业会话）
```kotlin
@LCClassName("WorkSession")
class WorkSession : LCObject() {
    var worker: LCUser?          // 关联工人用户
    var startTime: Date?        // 开始时间
    var endTime: Date?          // 结束时间
    var duration: Number?       // 持续时间（秒）
    var totalAlarmCount: Number? // 报警次数
    var isOnline: Boolean        // 是否在线
    var currentStatus: String?   // 当前状态
    var sensor1Status~sensor6Status: String?  // 6 个传感器状态
    var deviceList: List<*>?     // 设备列表
}
```

#### AlarmEvent（报警记录）
```kotlin
@LCClassName("AlarmEvent")
class AlarmEvent : LCObject() {
    var worker: LCUser?          // 关联工人
    var sessionId: String?        // 会话ID
    var timestamp: Date?         // 报警时间
    var alarmType: String?       // 报警类型（异常/单挂）
    var deviceAddress: String?   // 设备地址
    var sensorType: Int?         // 传感器类型（1-6）
    var resolved: Boolean?        // 是否已解决
}
```

### 4. 蓝牙设备连接流程

```
DetectionFragment → 扫描BLE设备
     ↓
ScanDeviceAdapter → 显示设备列表（含信号强度）
     ↓
用户点击设备 → BleService.connectWithTimeout()
     ↓
BluetoothGatt连接 → MTU协商
     ↓
enableNotification() → 启用特征值通知
     ↓
接收传感器数据 → 解析并更新状态
     ↓
上报LeanCloud → 监管端实时订阅
```

---

## 📦 部署与运行要求

### 环境要求

- **Android Studio**：Hedgehog (2023.1.1) 或更高版本
- **JDK**：11 或 17
- **Gradle**：8.12.2
- **最低 SDK**：API 24 (Android 7.0)
- **目标 SDK**：API 36 (Android 14)

### 硬件要求

- **蓝牙**：BLE 4.0 或更高版本
- **设备**：建议使用真机调试（BLE 模拟器不支持）

### 权限配置

应用需要以下权限：

```xml
<!-- 网络权限 -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

<!-- 蓝牙权限 -->
<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />

<!-- 位置权限（BLE 扫描需要） -->
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />

<!-- 后台服务 -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />

<!-- 震动 -->
<uses-permission android:name="android.permission.VIBRATE" />
```

### LeanCloud 配置

应用使用 LeanCloud 作为后端，配置位于 [`MainApplication.kt`](application/MainApplication.kt:25-30)：

```kotlin
LeanCloud.initialize(
    this,
    "brtmPCOc4XTcd1INHf2uIaXC-gzGzoHsz",  // App ID
    "Ta5ML0ryOxHTlV8qLFSENFb2",              // App Key
    "https://brtmpcoc.lc-cn-n1-shared.com"   // Server URL
)
```

### 构建步骤

1. **克隆项目**
```bash
git clone <repository-url>
cd SafetyBelt
```

2. **打开项目**：使用 Android Studio 打开项目

3. **配置 LeanCloud**：根据需要 modify [`MainApplication.kt`](application/MainApplication.kt:25-30) 中的配置

4. **运行项目**：
   - 连接 Android 设备 host 启动模拟器
   - 点击 Run 按钮（或 Shift + F10）

5. **生成 APK**：
```bash
./gradlew assembleDebug
# 输出文件：app/build/outputs/apk/debug/app-debug.apk
```

---

## 🎯 使用说明

### 操作端使用流程

1. **启动应用** → 选择"工人端"
2. **登录** → 输入工号和密码
3. **设备扫描** → 点击"开始扫描"
4. **连接设备** → 点击设备列表中的设备进行连接
5. **开始作业** → 所有设备连接后状态变为"正常"可开始作业
6. **状态监控** → 在监控页面可查看实时状态
7. **结束作业** → 退出应用或手动结束

### 监管端使用流程

1. **启动应用** → 选择"监管员端"
2. **登录** → 输入监管员账号和密码
3. **添加工人** → 点击 "+" 按钮输入工人姓名和工号
4. **实时监控** → 查看所有工人的实时状态
5. **查看详情** → 点击工人查看详细传感器数据和历史记录
6. **发送警报** → 对异常工人点击"报警"按钮

---

## 📊 传感器状态说明

| 状态 | 颜色 | 说明 |
|-----|------|------|
| **正常** | 🟢 绿色 | 所有挂钩正确连接，受力正常 |
| **单挂** | 🟡 黄色 | 某个传感器处于单挂状态 |
| **异常** | 🔴 红色 | 检测到异常受力或未挂钩 |
| **连接中** | 🔵 蓝色 | 正在建立蓝牙连接 |
| **离线** | ⚪ 灰色 | 设备未连接或已断开 |

### 6路传感器类型

| 编号 | 名称 | 说明 |
|-----|------|------|
| 1 | 后背绳高挂低用 | 主承重传感器 |
| 2 | 后背绳小挂钩 | 辅助挂钩传感器 |
| 3 | 围杆带环抱 | 围杆传感器 |
| 4 | 围杆带小挂钩 | 围杆辅助传感器 |
| 5 | 胸扣 | 胸部扣具传感器 |
| 6 | 后背绳大挂钩 | 主挂钩传感器 |

---

## 🐛 故障排除

### 常见问题

1. **BLE 扫描不到设备**
   - 检查蓝牙是否开启
   - 检查位置权限是否授予
   - 确认设备是否在 BLE 广播模式

2. **设备连接失败**
   - 检查设备电量
   - 检查设备是否被其他应用占用
   - 尝试重启蓝牙或应用

3. **云端同步失败**
   - 检查网络连接
   - 确认 LeanCloud 配置正确
   - 查看 Logcat 中的错误信息

4. **报警不工作**
   - 检查音量设置
   - 检查震动权限
   - 确认 TextTo 服务是否初始化

---

## 📝 开发规范

### 代码组织

- 使用 MVVM 模式进行架构设计
- Fragment 负责视图逻辑
- Service 负责业务逻辑
- Model 负责数据模型

### 命名规范

- 类名：大驼峰（PascalCase）
- 变量名：小驼峰（camelCase）
- 常量名：大写下划线分隔（UPPER_SNAKE_CASE）

### 注释规范

- 所有公开 API 必须添加 KDoc 注释
- 复杂逻辑必须添加行内注释
- 重要的业务逻辑添加 TODO/FIXME 标记

---

## 🔄 版本历史

| 版本 | 日期       | 说明 |
|-----|----------|------|
| 1.0 | 2026-1-4 | 初始版本发布 |

---

## 👥 团队与贡献

本项目由 Heyu Team 开发维护。

如有问题或建议，欢迎提交 Issue 或 Pull Request。

---
