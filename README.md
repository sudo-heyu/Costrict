# SafetyBelt 智能安全带监测系统

## 项目简介
SafetyBelt（内部代号：zhudeApp）是一款专为工业安全设计的移动端监测系统。该系统通过蓝牙（BLE）连接安全带传感器，实时采集工人的作业状态，并利用云端同步技术实现远程监管。系统分为**操作端（Operator）**和**监管端（Regulator）**，确保安全生产数据实时可查、异常即时报警。

## 核心功能
*   **实时传感器监测**：通过 `BleService` 维持与蓝牙设备的连接，实时解析多达 6 路传感器的状态数据（如挂钩状态、受力状态等）。
*   **云端同步与实时订阅**：集成 LeanCloud 云端存储，利用 LiveQuery 技术实现监管端对操作端状态的秒级同步。
*   **多重预警保护机制**：
    *   **异常状态报警**：系统自动识别危险作业行为（如未挂钩、异常受力）并通过震动和 UI 弹窗双重提醒。
    *   **离线自动结转**：具备“遗嘱”逻辑，在应用异常关闭或任务移除时自动将最后状态同步至云端。
*   **工人与会话管理**：支持工号动态检索、历史作业记录查询以及在线会话生命周期管理。

## 项目详细目录结构

### 1. 核心源码 (`app/src/main/java/com/heyu/safetybelt/`)

#### **`application/`**
*   `MainApplication.kt`：应用全局入口，负责 LeanCloud SDK 初始化、离线数据策略配置及全局上下文维护。

#### **`common/` (跨端公共模块)**
*   **`activity/`**
    *   `SplashActivity.kt`：启动闪屏页。
    *   `UserIdentity.kt`：身份选择页（工人/监管员选择）。
    *   `LoginActivity.kt`：统一身份验证与登录逻辑。
*   **核心模型 (Models)**
    *   `Worker.kt`：工人信息实体，映射 LeanCloud `Worker` 类。
    *   `WorkSession.kt`：单次作业会话实体，记录开始/结束时间及最终状态。
    *   `AlarmEvent.kt`：报警记录实体，存储历史异常点。
    *   `Device.kt`：蓝牙设备基础信息封装。
    *   `WorkerStatus.kt`：传感器实时状态分发对象。
*   **管理类**
    *   `SessionManager.kt`：全局单例，管理用户登录态、会话 ID 及当前作业状态。

#### **`operator/` (工人/设备操作端)**
*   **`activity/`**
    *   `MainActivityOperator.kt`：操作端主页面容器。
*   **`fragment/`**
    *   `SafetybeltFragment.kt`：传感器核心交互视图（连接/断开/实时状态展示）。
    *   `DetectionFragment.kt`：作业环境实时检测逻辑展示。
    *   `MonitoringFragment.kt`：作业进行中的状态看板。
    *   `WorkRecordFragment.kt`：个人作业历史统计。
    *   `ProfileFragment.kt`：个人中心。
    *   `AlarmDetailDialogFragment.kt`：报警详情交互弹窗。
*   **`service/`**
    *   `BleService.kt`：**整个项目的核心**。负责 BLE 扫描、GATT 连接、MTU 协商、6 路传感器数据包解析、心跳维持及 LeanCloud 实时上报。
*   **`adapter/`**
    *   `ScanDeviceAdapter.kt`：蓝牙设备扫描列表适配器。
    *   `StatusDeviceAdapter.kt`：实时状态显示适配器。
    *   `WorkRecordAdapter.kt`：历史作业记录列表。
*   **`model/`**
    *   `WorkRecord.kt` / `WorkRecordManager.kt`：本地化记录与管理逻辑。
    *   `DeviceScanResult.kt`：扫描结果封装。
*   **`util/`**
    *   `MonitoringUtil.kt`：辅助计算工具类。

#### **`regulator/` (管理/监管端)**
*   **`activity/`**
    *   `MainActivityRegulator.kt`：监管端主页面容器。
*   **`fragment/`**
    *   `MonitoringFragment.kt`：监管控制台，支持实时搜索添加工人并订阅其状态。
    *   `ProfileFragment.kt`：监管员个人信息管理。
*   **`service/`**
    *   `UnderService.kt`：监管端核心服务。负责管理所有被监控工人的 LiveQuery 订阅，处理报警分发及列表实时更新逻辑。
*   **`adapter/`**
    *   `WorkerStatusAdapter.kt`：多工人实时监控列表适配器，包含状态动画与高亮提醒。
    *   `WorkHistoryAdapter.kt`：监管视角下的历史记录查询。

### 2. 资源文件 (`app/src/main/res/`)
*   **`layout/`**：包含 30 多个布局文件，包括 `activity_main_operator.xml`、`item_worker_status.xml`、`dialog_sensor_details.xml` 等。
*   **`menu/`**：定义了 `bottom_nav_menu_operator.xml` 和 `bottom_nav_menu_regulator.xml`。
*   **`xml/`**：
    *   `backup_rules.xml`：定义应用数据的云端备份行为。
    *   `data_extraction_rules.xml`：定义数据迁移规则。

## 技术栈
*   **核心语言**：Kotlin
*   **云后端**：LeanCloud (Storage, LiveQuery, RxJava SDK)
*   **硬件通讯**：Android Bluetooth Low Energy (BLE) API
*   **并发/异步**：RxJava 2/3, Kotlin Coroutines, Handler, CountDownLatch (用于服务生命周期同步), StrictMode
*   **架构组件**：Jetpack Navigation, ViewBinding, FragmentManager
*   **UI 库**：Material Design Components, SwipeRefreshLayout

## 部署与运行要求
1.  **API 配置**：在 `MainApplication.kt` 中填入 LeanCloud 的 `appId` 和 `appKey`。
2.  **硬件要求**：真机运行需具备蓝牙 4.0+ 支持。
3.  **核心权限**：
    *   `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE`
    *   `ACCESS_FINE_LOCATION`
4.  **构建环境**：Android Studio Hedgehog+, Gradle 8.2+, JDK 17。
