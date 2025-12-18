package com.heyu.safetybelt.operator.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import cn.leancloud.LCException
import cn.leancloud.LCObject
import cn.leancloud.LCQuery
import cn.leancloud.LCUser
import cn.leancloud.livequery.LCLiveQuery
import cn.leancloud.livequery.LCLiveQueryEventHandler
import cn.leancloud.livequery.LCLiveQuerySubscribeCallback
import com.heyu.safetybelt.R
import com.heyu.safetybelt.common.AlarmEvent
import com.heyu.safetybelt.operator.model.DeviceScanResult
import com.heyu.safetybelt.common.WorkSession
import io.reactivex.Observer
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable
import java.util.Collections
import java.util.Date
import java.util.LinkedList
import java.util.Locale
import java.util.Queue
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.collections.get

@SuppressLint("MissingPermission")
class BleService : Service(), TextToSpeech.OnInitListener {

    private enum class ConnectionStatus { CONNECTING, CONNECTED, DISCONNECTED, FAILED, TIMEOUT, RECONNECTING }
    private enum class SensorStatus { NORMAL, SINGLE_HOOK, ALARM, UNKNOWN }

    private data class DeviceState(
        val device: DeviceScanResult,
        var gatt: BluetoothGatt? = null,
        var connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED,
        var sensorStatus: SensorStatus = SensorStatus.UNKNOWN,
        var sensorType: Int? = null,
        var isAbnormalSignal: Boolean = false,
        var reconnectAttempts: Int = 0,
        val subscriptionQueue: Queue<BluetoothGattCharacteristic> = LinkedList(),
        var singleHookTimer: Runnable? = null,
        var singleHookTimerExpired: Boolean = false
    )

    private val deviceStates = ConcurrentHashMap<String, DeviceState>()
    private val compositeDisposable = CompositeDisposable()
    private var liveQuery: LCLiveQuery? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val reconnectHandler = Handler(Looper.getMainLooper())

    private lateinit var tts: TextToSpeech
    private var isTtsInitialized = false
    private val alarmMessageQueue = Collections.synchronizedList(mutableListOf<String>())
    private var currentAlarmIndex = 0
    @Volatile private var isAlarmLoopRunning = false
    @Volatile private var isMuted = false
    private val muteRunnable = Runnable { isMuted = false }
    private var isShuttingDown = false

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale.CHINESE)
            if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                isTtsInitialized = true
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { if (utteranceId == ALARM_UTTERANCE_ID) mainHandler.postDelayed({ playNextAlarmInQueue() }, 500) }
                    override fun onError(utteranceId: String?) { if (utteranceId == ALARM_UTTERANCE_ID) mainHandler.postDelayed({ playNextAlarmInQueue() }, 500) }
                })
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this, this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification = Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("安全带卫士")
            .setContentText("正在后台保护您的作业安全")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
        startForeground(NOTIFICATION_ID, notification)

        when (intent?.action) {
            ACTION_CONNECT_DEVICES -> {
                val devices = intent.getParcelableArrayListExtra<DeviceScanResult>(EXTRA_DEVICES)
                val newSessionId = intent.getStringExtra(EXTRA_SESSION_ID)
                if (newSessionId != null && newSessionId != currentSessionId) {
                    currentSessionId = newSessionId
                    startLiveQuery(newSessionId)
                    cleanupConnectionsAndState(false)
                    updateWorkSession(isOnline = true)
                }
                devices?.forEach { if (!deviceStates.containsKey(it.deviceAddress)) { deviceStates[it.deviceAddress] = DeviceState(it); connectWithTimeout(it.deviceAddress) } }
            }
            ACTION_DISCONNECT_ALL -> disconnectAllDevices(true)
            ACTION_CONNECT_SPECIFIC -> {
                val devices = intent.getParcelableArrayListExtra<DeviceScanResult>(EXTRA_DEVICES)
                devices?.forEach { if (!deviceStates.containsKey(it.deviceAddress)) { deviceStates[it.deviceAddress] = DeviceState(it); connectWithTimeout(it.deviceAddress) } }
            }
            ACTION_DISCONNECT_SPECIFIC -> {
                intent.getStringArrayListExtra(EXTRA_DEVICES_TO_DISCONNECT)?.forEach { disconnectSpecificDevice(it, true) }
                evaluateOverallStatus()
            }
            ACTION_REQUEST_ALL_STATUSES -> broadcastAllStatuses()
            ACTION_RETRY_CONNECTION -> {
                val addr = intent.getStringExtra(EXTRA_DEVICE_ADDRESS)
                if (addr != null && deviceStates.containsKey(addr)) { deviceStates[addr]?.reconnectAttempts = 0; connectWithTimeout(addr) }
            }
            ACTION_MUTE_ALARMS -> {
                isMuted = true
                mainHandler.removeCallbacks(muteRunnable)
                mainHandler.postDelayed(muteRunnable, MUTE_DURATION_MS)
                if (isTtsInitialized) tts.stop()
            }
            ACTION_RESET_ADMIN_ALERT -> resetAdminAlert()
        }
        return START_REDELIVER_INTENT
    }

    private fun broadcastAllStatuses() {
        deviceStates.values.forEach { state ->
            when (state.connectionStatus) {
                ConnectionStatus.CONNECTED -> {
                    broadcastConnectionState(state.device.deviceAddress, true)
                    when (state.sensorStatus) {
                        SensorStatus.NORMAL -> broadcastStatus(state.device.deviceAddress, "正常", "GREEN", true)
                        SensorStatus.SINGLE_HOOK -> broadcastStatus(state.device.deviceAddress, "单挂", "BLACK", true)
                        SensorStatus.ALARM -> broadcastStatus(state.device.deviceAddress, "异常", "RED", true)
                        else -> broadcastStatus(state.device.deviceAddress, "等待数据", "YELLOW", false)
                    }
                }
                ConnectionStatus.CONNECTING, ConnectionStatus.RECONNECTING -> broadcastStatus(state.device.deviceAddress, "正在连接...", "BLUE", true)
                ConnectionStatus.FAILED -> broadcastStatus(state.device.deviceAddress, "重连失败", "YELLOW", true)
                ConnectionStatus.TIMEOUT -> broadcastStatus(state.device.deviceAddress, "超时", "YELLOW", true)
                else -> broadcastStatus(state.device.deviceAddress, "已断开", "GRAY", false)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isTtsInitialized) { tts.stop(); tts.shutdown() }
        disconnectAllDevices(false)
        stopLiveQuery()
        reconnectHandler.removeCallbacksAndMessages(null)
        compositeDisposable.clear()
        currentSessionId = null
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (isShuttingDown) return
        isShuttingDown = true
        Log.d(TAG, "onTaskRemoved called, finalizing cloud state for session $currentSessionId")
        
        if (!currentSessionId.isNullOrEmpty()) {
            val latch = CountDownLatch(1)
            Thread {
                try {
                    endSessionSynchronously("离线")
                    Log.d(TAG, "Final session update completed successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Final session update error: ${e.message}")
                } finally {
                    latch.countDown()
                }
            }.start()

            try {
                // 阻塞主线程最多 3.5 秒，强制给网络请求留出发送时间
                if (!latch.await(3500, TimeUnit.MILLISECONDS)) {
                    Log.w(TAG, "Final session update timed out")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Final session update interrupted", e)
            }
        }
        
        cleanupConnectionsAndState(true)
        super.onTaskRemoved(rootIntent)
        stopSelf()
    }

    private fun endSessionSynchronously(statusText: String) {
        val sid = currentSessionId ?: return
        Log.d(TAG, "Attempting to end session $sid synchronously with status: $statusText")
        
        // 1. 更新 WorkSession 表 (云端)
        val session = LCObject.createWithoutData("WorkSession", sid)
        session.put("currentStatus", statusText)
        session.put("isOnline", false)
        session.put("endTime", Date())
        for (i in 1..6) session.put("sensor${i}Status", "未连接")
        
        // 2. 更新 User 表的 isOnline 状态 (云端)
        val user = LCUser.getCurrentUser()
        user?.put("isOnline", false)
        
        try {
            // 第一重保险：saveEventually 将修改存入本地数据库
            session.saveEventually()
            user?.saveEventually()
            
            // 第二重保险：阻塞式 save() 确保立即上传
            session.save()
            Log.d(TAG, "WorkSession $sid updated successfully in cloud")
            
            user?.save()
            Log.d(TAG, "User ${user?.objectId} isOnline set to false successfully in cloud")
        } catch (e: Exception) {
            Log.e(TAG, "Final sync save failed, relying on saveEventually. Error: ${e.message}")
        }
        
        LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(ACTION_SESSION_ENDED))
    }

    private fun startLiveQuery(sessionId: String) {
        stopLiveQuery()
        val query = LCQuery<WorkSession>("WorkSession").whereEqualTo("objectId", sessionId)
        liveQuery = LCLiveQuery.initWithQuery(query)
        liveQuery?.setEventHandler(object : LCLiveQueryEventHandler() {
            override fun onObjectUpdated(obj: LCObject?, updateKeyList: MutableList<String>?) {
                if (obj != null && updateKeyList?.contains("adminAlert") == true) fetchWorkSessionAndCheckAlert(obj.objectId)
            }
        })
        liveQuery?.subscribeInBackground(object : LCLiveQuerySubscribeCallback() {
            override fun done(e: LCException?) { if (e != null) Log.e(TAG, "LiveQuery 订阅失败", e) }
        })
    }

    private fun stopLiveQuery() { liveQuery?.unsubscribeInBackground(object : LCLiveQuerySubscribeCallback() { override fun done(e: LCException?) {} }); liveQuery = null }

    private fun fetchWorkSessionAndCheckAlert(sessionId: String) {
        LCQuery<WorkSession>("WorkSession").getInBackground(sessionId).subscribe(object : Observer<WorkSession> {
            override fun onSubscribe(d: Disposable) { compositeDisposable.add(d) }
            override fun onNext(session: WorkSession) { if (session.getBoolean("adminAlert")) sendAdminAlertBroadcast() }
            override fun onError(e: Throwable) {}
            override fun onComplete() {}
        })
    }

    private fun sendAdminAlertBroadcast() { LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(ACTION_SHOW_ADMIN_ALERT)); triggerVibration() }

    private fun resetAdminAlert() {
        val sid = currentSessionId ?: return
        val session = LCObject.createWithoutData("WorkSession", sid)
        session.put("adminAlert", false)
        session.saveInBackground().subscribe(object : Observer<LCObject> {
            override fun onSubscribe(d: Disposable) { compositeDisposable.add(d) }
            override fun onNext(t: LCObject) {} override fun onError(e: Throwable) {} override fun onComplete() {}
        })
    }

    private fun cleanupConnectionsAndState(clearLocalSession: Boolean) {
        mainHandler.removeCallbacksAndMessages(null)
        reconnectHandler.removeCallbacksAndMessages(null)
        deviceStates.values.forEach { it.gatt?.close(); broadcastConnectionState(it.device.deviceAddress, false); it.singleHookTimer?.let { mainHandler.removeCallbacks(it) } }
        deviceStates.clear()
        stopAlarmCycle()
        if (clearLocalSession) currentSessionId = null
    }

    private fun disconnectSpecificDevice(address: String, notifyUi: Boolean) {
        val state = deviceStates.remove(address)
        if (state != null) {
            state.gatt?.disconnect(); state.gatt?.close(); broadcastConnectionState(address, false)
            if (notifyUi) broadcastStatus(address, "已移除", "GRAY", false)
        }
        checkAllDevicesDisconnected()
    }

    private fun disconnectAllDevices(isManual: Boolean) {
        val addresses = deviceStates.keys.toList()
        if (isManual && !currentSessionId.isNullOrEmpty()) endSessionSynchronously("手动断开")
        cleanupConnectionsAndState(true)
        stopLiveQuery()
        if (isManual) addresses.forEach { broadcastStatus(it, "待连接", "GRAY", false) }
        stopSelf()
    }

    private fun connectWithTimeout(address: String) {
        val state = deviceStates[address] ?: return
        broadcastStatus(address, "正在连接...", "BLUE", true)
        state.connectionStatus = ConnectionStatus.CONNECTING
        state.gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) state.device.device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                     else state.device.device.connectGatt(this, false, gattCallback)
        mainHandler.postDelayed({
            if (state.connectionStatus == ConnectionStatus.CONNECTING) {
                state.connectionStatus = ConnectionStatus.TIMEOUT; state.gatt?.close()
                broadcastStatus(address, "超时", "YELLOW", true); broadcastConnectionState(address, false); scheduleReconnect(address)
            }
        }, CONNECTION_TIMEOUT_MS)
    }

    private fun scheduleReconnect(address: String) {
        val state = deviceStates[address] ?: return
        if (state.reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            state.connectionStatus = ConnectionStatus.FAILED; broadcastStatus(address, "重连失败", "YELLOW", true); broadcastConnectionState(address, false); checkAllDevicesDisconnected(); return
        }
        state.reconnectAttempts++; state.connectionStatus = ConnectionStatus.RECONNECTING
        broadcastStatus(address, "正在重连...", "BLUE", true)
        reconnectHandler.postDelayed({ connectWithTimeout(address) }, RECONNECT_DELAY_MS)
    }

    private fun checkAllDevicesDisconnected() {
        val hasAnyActive = deviceStates.values.any { it.connectionStatus == ConnectionStatus.CONNECTED || it.connectionStatus == ConnectionStatus.CONNECTING || it.connectionStatus == ConnectionStatus.RECONNECTING }
        if (!hasAnyActive && !currentSessionId.isNullOrEmpty()) {
            endSessionSynchronously(if (deviceStates.isEmpty()) "设备离线" else "设备失联")
            cleanupConnectionsAndState(true); stopLiveQuery(); stopSelf()
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val address = gatt.device.address; mainHandler.removeCallbacksAndMessages(null)
            val state = deviceStates[address] ?: return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    state.connectionStatus = ConnectionStatus.CONNECTED; broadcastConnectionState(address, true); state.reconnectAttempts = 0
                    mainHandler.post { state.gatt?.discoverServices() }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) handleDisconnection(address, "已断开", "GRAY", false)
            } else handleDisconnection(address, "连接失败", "RED", false)
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val state = deviceStates[gatt.device.address] ?: return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                state.subscriptionQueue.clear()
                gatt.getService(HEARTBEAT_SERVICE_UUID)?.getCharacteristic(HEARTBEAT_CHARACTERISTIC_UUID)?.let { state.subscriptionQueue.add(it) }
                gatt.getService(SENSOR_DATA_SERVICE_UUID)?.getCharacteristic(SENSOR_DATA_CHARACTERISTIC_UUID)?.let { state.subscriptionQueue.add(it) }
                processNextSubscription(gatt.device.address)
            } else handleDisconnection(gatt.device.address, "服务发现失败", "RED", false)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) processNextSubscription(gatt.device.address)
            else handleDisconnection(gatt.device.address, "订阅失败", "RED", false)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val state = deviceStates[gatt.device.address] ?: return
            when (characteristic.uuid) {
                HEARTBEAT_CHARACTERISTIC_UUID -> parseHeartbeatData(characteristic.value)?.let { broadcastHeartbeat(gatt.device.address, it) }
                SENSOR_DATA_CHARACTERISTIC_UUID -> parseSensorData(characteristic.value)?.let { parsed ->
                    val isFirst = state.sensorType == null
                    if (isFirst) state.sensorType = parsed.sensorType
                    if (isFirst || state.isAbnormalSignal != parsed.isAbnormal) {
                        state.isAbnormalSignal = parsed.isAbnormal; evaluateOverallStatus()
                    }
                }
            }
        }
    }

    private fun handleDisconnection(address: String, statusText: String, color: String, fromUser: Boolean) {
        val state = deviceStates[address] ?: return
        state.gatt?.close(); state.connectionStatus = ConnectionStatus.DISCONNECTED; broadcastConnectionState(address, false)
        state.sensorStatus = SensorStatus.UNKNOWN; broadcastStatus(address, statusText, color, false); evaluateOverallStatus()
        if (!fromUser) scheduleReconnect(address)
    }

    private fun processNextSubscription(address: String) {
        val state = deviceStates[address] ?: return
        val char = state.subscriptionQueue.poll() ?: run { broadcastStatus(address, "已就绪", "GREEN", false); return }
        state.gatt?.setCharacteristicNotification(char, true)
        mainHandler.postDelayed({
            char.getDescriptor(CCCD_UUID)?.let { it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; state.gatt?.writeDescriptor(it) }
        }, 200)
    }

    private fun evaluateOverallStatus() {
        val active = deviceStates.values.filter { it.connectionStatus == ConnectionStatus.CONNECTED && it.sensorType != null }
        val abnormalHookTypes = active.filter { it.isAbnormalSignal && it.sensorType != 5 }.mapNotNull { it.sensorType?.minus(1) }.toSet()
        val isSingle = SINGLE_HOOK_COMBINATIONS.contains(abnormalHookTypes)

        deviceStates.values.forEach { state ->
            val prev = state.sensorStatus
            state.sensorStatus = if (state.connectionStatus != ConnectionStatus.CONNECTED) SensorStatus.UNKNOWN
                else when { !state.isAbnormalSignal -> SensorStatus.NORMAL; state.sensorType == 5 -> SensorStatus.ALARM; isSingle -> if (state.singleHookTimerExpired) SensorStatus.ALARM else SensorStatus.SINGLE_HOOK; else -> SensorStatus.ALARM }
            if (prev != state.sensorStatus) updateDeviceUiAndTimers(state)
        }

        val partStatuses = Array(6) { i ->
            val devices = deviceStates.values.filter { it.sensorType == i + 1 }
            if (devices.isEmpty()) SensorStatus.UNKNOWN else if (devices.any { it.sensorStatus == SensorStatus.ALARM }) SensorStatus.ALARM else if (devices.any { it.sensorStatus == SensorStatus.SINGLE_HOOK }) SensorStatus.SINGLE_HOOK else if (devices.all { it.sensorStatus == SensorStatus.NORMAL }) SensorStatus.NORMAL else SensorStatus.UNKNOWN
        }
        val overall = when { partStatuses.any { it == SensorStatus.ALARM } -> "异常"; partStatuses.any { it == SensorStatus.SINGLE_HOOK } -> "单挂"; deviceStates.isEmpty() -> "已断开"; else -> "正常" }
        updateWorkSession(status = overall, partStatuses = partStatuses); updateAlarmQueueAndCycle()
    }

    private fun updateDeviceUiAndTimers(state: DeviceState) {
        when (state.sensorStatus) {
            SensorStatus.NORMAL -> { cancelSingleHookTimer(state); broadcastStatus(state.device.deviceAddress, "正常", "GREEN", true) }
            SensorStatus.SINGLE_HOOK -> { startSingleHookTimer(state); broadcastStatus(state.device.deviceAddress, "单挂", "BLACK", true) }
            SensorStatus.ALARM -> { state.singleHookTimer?.let { mainHandler.removeCallbacks(it) }; state.singleHookTimer = null; broadcastStatus(state.device.deviceAddress, "异常", "RED", true); logAlarmToCloud(state.device.deviceAddress, state.sensorType) }
            else -> {}
        }
    }

    private fun startSingleHookTimer(state: DeviceState) { if (state.singleHookTimer != null) return; state.singleHookTimer = Runnable { state.singleHookTimerExpired = true; state.singleHookTimer = null; evaluateOverallStatus() }; mainHandler.postDelayed(state.singleHookTimer!!, SINGLE_HOOK_TIMEOUT_MS) }
    private fun cancelSingleHookTimer(state: DeviceState) { state.singleHookTimerExpired = false; state.singleHookTimer?.let { mainHandler.removeCallbacks(it) }; state.singleHookTimer = null }
    private fun stopAlarmCycle() { isAlarmLoopRunning = false; synchronized(alarmMessageQueue) { alarmMessageQueue.clear() }; currentAlarmIndex = 0; if (isTtsInitialized) tts.stop() }
    private fun playNextAlarmInQueue() {
        if (!isAlarmLoopRunning || isMuted || !isTtsInitialized) return
        val msg = synchronized(alarmMessageQueue) { if (alarmMessageQueue.isEmpty()) { isAlarmLoopRunning = false; return }; if (currentAlarmIndex >= alarmMessageQueue.size) currentAlarmIndex = 0; alarmMessageQueue[currentAlarmIndex++] }
        triggerVibration(); val params = Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, ALARM_UTTERANCE_ID) }; tts.speak(msg, TextToSpeech.QUEUE_FLUSH, params, ALARM_UTTERANCE_ID)
    }

    private fun updateAlarmQueueAndCycle() {
        val newMsgs = deviceStates.values.filter { it.sensorStatus == SensorStatus.ALARM }.mapNotNull { SENSOR_TYPE_NAMES[it.sensorType]?.plus(" 异常") }
        val shouldStart = !isAlarmLoopRunning && newMsgs.isNotEmpty()
        synchronized(alarmMessageQueue) { val had = alarmMessageQueue.isNotEmpty(); alarmMessageQueue.clear(); alarmMessageQueue.addAll(newMsgs); isAlarmLoopRunning = alarmMessageQueue.isNotEmpty(); if (!had && isAlarmLoopRunning) LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(ACTION_SHOW_ALARM_DIALOG).apply { putStringArrayListExtra(EXTRA_ALARM_MESSAGES, ArrayList(newMsgs)) }) }
        if (shouldStart) { currentAlarmIndex = 0; playNextAlarmInQueue() } else if (!isAlarmLoopRunning) stopAlarmCycle()
    }

    private fun parseHeartbeatData(data: ByteArray): HeartbeatInfo? { if (data.size != 5 || data[4] != data.slice(0..3).sumOf { it.toInt() and 0xFF }.toByte()) return null; val id = String.format("%04X", ((data[2].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)); val bat = when (val b = data[3].toInt() and 0xFF) { 0xFF -> "充电中"; in 0..100 -> "$b%"; else -> "未知" }; return HeartbeatInfo(id, bat) }
    private fun parseSensorData(data: ByteArray): ParsedSensorData? { if (data.size != 6 || data[5] != data.slice(0..4).sumOf { it.toInt() and 0xFF }.toByte()) return null; val type = data[1].toInt() and 0xFF; if (type !in 1..6) return null; return ParsedSensorData(type, (data[4].toInt() and 0xFF) == 1) }
    private fun logAlarmToCloud(deviceAddress: String, sensorType: Int?) {
        val sid = currentSessionId ?: return
        val user = LCUser.getCurrentUser() ?: return
        AlarmEvent().apply { this.sessionId = sid; this.worker = user; this.deviceAddress = deviceAddress; this.sensorType = sensorType ?: 0; this.alarmType = "异常"; this.timestamp = Date() }.saveInBackground().subscribe(object : Observer<LCObject> { override fun onSubscribe(d: Disposable) { compositeDisposable.add(d) } override fun onNext(t: LCObject) {} override fun onError(e: Throwable) {} override fun onComplete() {} })
    }

    private fun updateWorkSession(status: String? = null, isOnline: Boolean? = null, partStatuses: Array<SensorStatus>? = null, shouldEndSession: Boolean = false) {
        val sid = currentSessionId ?: return
        val session = LCObject.createWithoutData("WorkSession", sid)
        var save = false
        status?.let { session.put("currentStatus", it); if (it == "异常") session.increment("totalAlarmCount", 1); save = true }
        isOnline?.let { session.put("isOnline", it); save = true }
        if (shouldEndSession) { session.put("endTime", Date()); save = true }
        partStatuses?.let { for (i in it.indices) { val s = when (it[i]) { SensorStatus.NORMAL -> "正常"; SensorStatus.SINGLE_HOOK -> "单挂"; SensorStatus.ALARM -> "异常"; else -> "未连接" }; session.put("sensor${i + 1}Status", s) }; save = true }
        if (save) { if (shouldEndSession) try { session.saveEventually() } catch (e: Exception) {} else session.saveInBackground().subscribe(object : Observer<LCObject> { override fun onSubscribe(d: Disposable) { compositeDisposable.add(d) } override fun onNext(t: LCObject) {} override fun onError(e: Throwable) {} override fun onComplete() {} }) }
    }

    private fun triggerVibration() { if (isMuted) return; val v = getSystemService(VIBRATOR_SERVICE) as Vibrator; if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) v.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE)) else v.vibrate(500) }
    private fun broadcastStatus(deviceAddress: String, text: String, color: String, showIcon: Boolean) { LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(ACTION_STATUS_UPDATE).apply { putExtra(EXTRA_DEVICE_ADDRESS, deviceAddress); putExtra(EXTRA_TEXT, text); putExtra(EXTRA_COLOR, color); putExtra(EXTRA_SHOW_ICON, showIcon) }) }
    private fun broadcastHeartbeat(deviceAddress: String, info: HeartbeatInfo) { LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(ACTION_HEARTBEAT_UPDATE).apply { putExtra(EXTRA_DEVICE_ADDRESS, deviceAddress); putExtra(EXTRA_SENSOR_ID, info.sensorIdHex); putExtra(EXTRA_BATTERY, info.batteryStatus) }) }
    private fun broadcastConnectionState(address: String, isConnected: Boolean) { LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(ACTION_CONNECTION_STATE_UPDATE).apply { putExtra(EXTRA_DEVICE_ADDRESS, address); putExtra(EXTRA_IS_CONNECTED, isConnected) }) }
    private fun createNotificationChannel() { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(NOTIFICATION_CHANNEL_ID, "安全带后台服务", NotificationManager.IMPORTANCE_DEFAULT)) }

    data class HeartbeatInfo(val sensorIdHex: String, val batteryStatus: String)
    data class ParsedSensorData(val sensorType: Int, val isAbnormal: Boolean)

    companion object {
        @Volatile var currentSessionId: String? = null
        const val ACTION_CONNECT_DEVICES = "com.heyu.safetybeltoperators.ACTION_CONNECT_DEVICES"
        const val ACTION_DISCONNECT_ALL = "com.heyu.safetybeltoperators.ACTION_DISCONNECT_ALL"
        const val ACTION_RETRY_CONNECTION = "com.heyu.safetybeltoperators.ACTION_RETRY_CONNECTION"
        const val ACTION_MUTE_ALARMS = "com.heyu.safetybeltoperators.ACTION_MUTE_ALARMS"
        const val ACTION_RESET_ADMIN_ALERT = "com.heyu.safetybeltoperators.ACTION_RESET_ADMIN_ALERT"
        const val ACTION_SHOW_ADMIN_ALERT = "com.heyu.safetybeltoperators.ACTION_SHOW_ADMIN_ALERT"
        const val ACTION_CONNECT_SPECIFIC = "com.heyu.safetybeltoperators.ACTION_CONNECT_SPECIFIC"
        const val ACTION_DISCONNECT_SPECIFIC = "com.heyu.safetybeltoperators.ACTION_DISCONNECT_SPECIFIC"
        const val ACTION_CONNECTION_STATE_UPDATE = "com.heyu.safetybeltoperators.ACTION_CONNECTION_STATE_UPDATE"
        const val ACTION_REQUEST_ALL_STATUSES = "com.heyu.safetybeltoperators.ACTION_REQUEST_ALL_STATUSES"
        const val ACTION_SESSION_ENDED = "com.heyu.safetybeltoperators.ACTION_SESSION_ENDED"
        const val EXTRA_DEVICES = "com.heyu.safetybeltoperators.EXTRA_DEVICES"
        const val EXTRA_DEVICES_TO_DISCONNECT = "com.heyu.safetybeltoperators.EXTRA_DEVICES_TO_DISCONNECT"
        const val EXTRA_SESSION_ID = "com.heyu.safetybeltoperators.EXTRA_SESSION_ID"
        const val EXTRA_IS_CONNECTED = "com.heyu.safetybeltoperators.EXTRA_IS_CONNECTED"
        const val ACTION_STATUS_UPDATE = "com.heyu.safetybeltoperators.ACTION_STATUS_UPDATE"
        const val ACTION_HEARTBEAT_UPDATE = "com.heyu.safetybeltoperators.ACTION_HEARTBEAT_UPDATE"
        const val ACTION_SHOW_ALARM_DIALOG = "com.heyu.safetybeltoperators.ACTION_SHOW_ALARM_DIALOG"
        const val EXTRA_DEVICE_ADDRESS = "com.heyu.safetybeltoperators.EXTRA_DEVICE_ADDRESS"
        const val EXTRA_TEXT = "com.heyu.safetybeltoperators.EXTRA_TEXT"
        const val EXTRA_COLOR = "com.heyu.safetybeltoperators.EXTRA_COLOR"
        const val EXTRA_SHOW_ICON = "com.heyu.safetybeltoperators.EXTRA_SHOW_ICON"
        const val EXTRA_SENSOR_ID = "com.heyu.safetybeltoperators.EXTRA_SENSOR_ID"
        const val EXTRA_BATTERY = "com.heyu.safetybeltoperators.EXTRA_BATTERY"
        const val EXTRA_ALARM_MESSAGES = "com.heyu.safetybeltoperators.EXTRA_ALARM_MESSAGES"
        private const val NOTIFICATION_CHANNEL_ID = "BleServiceChannel"; private const val NOTIFICATION_ID = 1; private const val TAG = "BleService"
        private const val CONNECTION_TIMEOUT_MS = 30000L; private const val RECONNECT_DELAY_MS = 2000L; private const val MAX_RECONNECT_ATTEMPTS = 3
        private const val SINGLE_HOOK_TIMEOUT_MS = 20000L; private const val MUTE_DURATION_MS = 60000L; private const val ALARM_UTTERANCE_ID = "com.heyu.safetybeltoperators.ALARM_CYCLE_UTTERANCE"
        val HEARTBEAT_SERVICE_UUID: UUID = UUID.fromString("00001233-0000-1000-8000-00805f9b34fb"); val HEARTBEAT_CHARACTERISTIC_UUID: UUID = UUID.fromString("00001235-0000-1000-8000-00805f9b34fb")
        val SENSOR_DATA_SERVICE_UUID: UUID = UUID.fromString("00005677-0000-1000-8000-00805f9b34fb"); val SENSOR_DATA_CHARACTERISTIC_UUID: UUID = UUID.fromString("00005679-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val SENSOR_TYPE_NAMES = mapOf(1 to "后背绳高挂", 2 to "后背绳小钩", 3 to "围杆带环抱", 4 to "围杆带钩", 5 to "胸扣", 6 to "后背绳大钩")
        val SINGLE_HOOK_COMBINATIONS = setOf(setOf(0), setOf(1), setOf(2), setOf(3), setOf(5), setOf(3, 5), setOf(1, 2), setOf(0, 2), setOf(0, 1))
    }
}
