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
import kotlin.collections.get

@SuppressLint("MissingPermission")
class BleService : Service(), TextToSpeech.OnInitListener {

    // --- Data Classes for State Management ---
    private enum class ConnectionStatus { CONNECTING, CONNECTED, DISCONNECTED, FAILED, TIMEOUT, RECONNECTING }
    private enum class SensorStatus { NORMAL, SINGLE_HOOK, ALARM, UNKNOWN }

    private data class DeviceState(
        val device: DeviceScanResult,
        var gatt: BluetoothGatt? = null,
        var connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED,
        var sensorStatus: SensorStatus = SensorStatus.UNKNOWN,
        var sensorType: Int? = null, // 1-6
        var isAbnormalSignal: Boolean = false,
        var reconnectAttempts: Int = 0,
        val subscriptionQueue: Queue<BluetoothGattCharacteristic> = LinkedList(),
        var singleHookTimer: Runnable? = null,
        var singleHookTimerExpired: Boolean = false
    )

    // --- State Management ---
    private val deviceStates = ConcurrentHashMap<String, DeviceState>() // The primary state holder
    private var currentSessionId: String? = null
    private val compositeDisposable = CompositeDisposable()
    
    // --- LiveQuery Management ---
    private var liveQuery: LCLiveQuery? = null

    // --- Handlers ---
    private val mainHandler = Handler(Looper.getMainLooper())
    private val reconnectHandler = Handler(Looper.getMainLooper())

    // --- TTS, Vibration & Alarm Loop Properties ---
    private lateinit var tts: TextToSpeech
    private var isTtsInitialized = false
    private val alarmMessageQueue = Collections.synchronizedList(mutableListOf<String>())
    private var currentAlarmIndex = 0
    @Volatile private var isAlarmLoopRunning = false
    @Volatile private var isMuted = false
    private val muteRunnable = Runnable { isMuted = false }

    // --- Service Lifecycle & Setup ---

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale.CHINESE)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "TTS Error: Chinese language is not supported.")
            } else {
                isTtsInitialized = true
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { if (utteranceId == ALARM_UTTERANCE_ID) mainHandler.postDelayed({ playNextAlarmInQueue() }, 500) }
                    override fun onError(utteranceId: String?) { if (utteranceId == ALARM_UTTERANCE_ID) mainHandler.postDelayed({ playNextAlarmInQueue() }, 500) }
                })
            }
        } else {
            Log.e(TAG, "TTS initialization failed.")
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
                
                if (newSessionId != null) {
                    currentSessionId = newSessionId
                    startLiveQuery(newSessionId)
                }
                
                cleanupConnectionsAndState()
                updateWorkSession(isOnline = true)
                devices?.forEach { device ->
                    deviceStates[device.deviceAddress] = DeviceState(device)
                    connectWithTimeout(device.deviceAddress)
                }
            }
            ACTION_DISCONNECT_ALL -> {
                disconnectAllDevices(true)
            }
            ACTION_RETRY_CONNECTION -> {
                val addressToRetry = intent.getStringExtra(EXTRA_DEVICE_ADDRESS)
                if (addressToRetry != null && deviceStates.containsKey(addressToRetry)) {
                    Log.d(TAG, "Received retry request for $addressToRetry")
                    deviceStates[addressToRetry]?.reconnectAttempts = 0
                    connectWithTimeout(addressToRetry)
                }
            }
            ACTION_MUTE_ALARMS -> {
                isMuted = true
                mainHandler.removeCallbacks(muteRunnable)
                mainHandler.postDelayed(muteRunnable, MUTE_DURATION_MS)
                if (this::tts.isInitialized) tts.stop()
            }
            ACTION_RESET_ADMIN_ALERT -> {
                resetAdminAlert()
            }
        }

        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        super.onDestroy()
        if (this::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        disconnectAllDevices(false)
        stopLiveQuery()
        reconnectHandler.removeCallbacksAndMessages(null)
        compositeDisposable.clear()
    }

    // --- LiveQuery & Remote Alert ---

    private fun startLiveQuery(sessionId: String) {
        stopLiveQuery() // Ensure previous subscription is cleared

        val query = LCQuery<WorkSession>("WorkSession").whereEqualTo("objectId", sessionId)
        liveQuery = LCLiveQuery.initWithQuery(query)
        
        liveQuery?.setEventHandler(object : LCLiveQueryEventHandler() {
            override fun onObjectUpdated(obj: LCObject?, updateKeyList: MutableList<String>?) {
                if (obj == null) return
                // Check if adminAlert field is updated to true
                if (updateKeyList?.contains("adminAlert") == true) {
                    // Fetch the latest object to get the actual value, avoiding race conditions or incomplete data
                    fetchWorkSessionAndCheckAlert(obj.objectId)
                }
            }
        })

        liveQuery?.subscribeInBackground(object : LCLiveQuerySubscribeCallback() {
            override fun done(e: LCException?) {
                if (e == null) {
                    Log.d(TAG, "LiveQuery subscribed successfully for session: $sessionId")
                } else {
                    Log.e(TAG, "LiveQuery subscription failed", e)
                }
            }
        })
    }

    private fun stopLiveQuery() {
        liveQuery?.unsubscribeInBackground(object : LCLiveQuerySubscribeCallback() {
            override fun done(e: LCException?) {
                Log.d(TAG, "LiveQuery unsubscribed")
            }
        })
        liveQuery = null
    }

    private fun fetchWorkSessionAndCheckAlert(sessionId: String) {
        val query = LCQuery<WorkSession>("WorkSession")
        query.getInBackground(sessionId).subscribe(object : Observer<WorkSession> {
            override fun onSubscribe(d: Disposable) { compositeDisposable.add(d) }
            override fun onNext(session: WorkSession) {
                if (session.getBoolean("adminAlert")) {
                    sendAdminAlertBroadcast()
                }
            }
            override fun onError(e: Throwable) {
                Log.e(TAG, "Failed to fetch WorkSession for alert check", e)
            }
            override fun onComplete() {}
        })
    }

    private fun sendAdminAlertBroadcast() {
        val intent = Intent(ACTION_SHOW_ADMIN_ALERT)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        // Optionally trigger local vibration/sound here as well
        triggerVibration()
    }

    private fun resetAdminAlert() {
        if (currentSessionId.isNullOrEmpty()) return
        
        val session = LCObject.createWithoutData("WorkSession", currentSessionId!!)
        session.put("adminAlert", false)
        session.saveInBackground().subscribe(object : Observer<LCObject> {
            override fun onSubscribe(d: Disposable) { compositeDisposable.add(d) }
            override fun onNext(t: LCObject) { Log.d(TAG, "Admin alert reset successfully") }
            override fun onError(e: Throwable) { Log.e(TAG, "Failed to reset admin alert", e) }
            override fun onComplete() {}
        })
    }

    // --- Connection Management ---

    private fun cleanupConnectionsAndState() {
        mainHandler.removeCallbacksAndMessages(null)
        reconnectHandler.removeCallbacksAndMessages(null)
        deviceStates.values.forEach { state ->
            state.gatt?.close()
            state.singleHookTimer?.let { mainHandler.removeCallbacks(it) }
        }
        deviceStates.clear()
        stopAlarmCycle()
    }

    private fun disconnectAllDevices(isManual: Boolean) {
        val addresses = deviceStates.keys.toList()
        cleanupConnectionsAndState()
        stopLiveQuery() // Also stop LiveQuery when disconnecting

        if (isManual) {
            updateWorkSession(isOnline = false, status = "手动断开", shouldEndSession = true)
            addresses.forEach { broadcastStatus(it, "待连接", "GRAY", false) }
        } else {
            updateWorkSession(isOnline = false, status = "离线", shouldEndSession = true)
        }
    }

    private fun connectWithTimeout(address: String) {
        val state = deviceStates[address] ?: return
        broadcastStatus(address, "正在连接...", "BLUE", true)
        state.connectionStatus = ConnectionStatus.CONNECTING

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            state.gatt = state.device.device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            @Suppress("DEPRECATION")
            state.gatt = state.device.device.connectGatt(this, false, gattCallback)
        }

        val timeoutRunnable = Runnable {
            if (state.connectionStatus == ConnectionStatus.CONNECTING) {
                Log.w(TAG, "Connection timeout for $address")
                state.connectionStatus = ConnectionStatus.TIMEOUT
                state.gatt?.close()
                broadcastStatus(address, "超时", "YELLOW", true)
                scheduleReconnect(address)
            }
        }
        mainHandler.postDelayed(timeoutRunnable, CONNECTION_TIMEOUT_MS)
    }

    private fun scheduleReconnect(address: String) {
        val state = deviceStates[address] ?: return
        if (state.reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "Max reconnect attempts reached for $address. Stopping.")
            broadcastStatus(address, "重连失败", "YELLOW", true)
            state.connectionStatus = ConnectionStatus.FAILED
            checkAllDevicesDisconnected()
            return
        }

        state.reconnectAttempts++
        state.connectionStatus = ConnectionStatus.RECONNECTING
        broadcastStatus(address, "正在重连...", "BLUE", true)

        val reconnectRunnable = Runnable { connectWithTimeout(address) }
        reconnectHandler.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS)
    }

    private fun checkAllDevicesDisconnected() {
        val allDisconnected = deviceStates.values.all {
            it.connectionStatus == ConnectionStatus.DISCONNECTED || it.connectionStatus == ConnectionStatus.FAILED
        }
        if (allDisconnected) {
            Log.d(TAG, "All devices are disconnected. Ending work session.")
            updateWorkSession(isOnline = false, status = "设备离线", shouldEndSession = true)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val address = gatt.device.address
            mainHandler.removeCallbacksAndMessages(null) // Cancel connection timeout
            val state = deviceStates[address]

            if (state == null) {
                gatt.close()
                return
            }

            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.d(TAG, "Successfully connected to $address")
                    state.connectionStatus = ConnectionStatus.CONNECTED
                    state.reconnectAttempts = 0
                    mainHandler.post { state.gatt?.discoverServices() }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    handleDisconnection(address, "已断开", "GRAY")
                }
            } else {
                Log.e(TAG, "GATT Error for $address. Status: $status")
                handleDisconnection(address, "连接失败", "RED")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val state = deviceStates[gatt.device.address]
            if (status != BluetoothGatt.GATT_SUCCESS || state == null) {
                handleDisconnection(gatt.device.address, "服务发现失败", "RED")
                return
            }
            state.subscriptionQueue.clear()
            gatt.getService(HEARTBEAT_SERVICE_UUID)?.getCharacteristic(HEARTBEAT_CHARACTERISTIC_UUID)?.let { state.subscriptionQueue.add(it) }
            gatt.getService(SENSOR_DATA_SERVICE_UUID)?.getCharacteristic(SENSOR_DATA_CHARACTERISTIC_UUID)?.let { state.subscriptionQueue.add(it) }
            processNextSubscription(gatt.device.address)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                processNextSubscription(gatt.device.address)
            } else {
                Log.w(TAG, "Descriptor write failed for ${gatt.device.address}")
                handleDisconnection(gatt.device.address, "订阅失败", "RED")
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val address = gatt.device.address
            val state = deviceStates[address] ?: return

            when (characteristic.uuid) {
                HEARTBEAT_CHARACTERISTIC_UUID -> {
                    parseHeartbeatData(characteristic.value)?.let { broadcastHeartbeat(address, it) }
                }
                SENSOR_DATA_CHARACTERISTIC_UUID -> {
                    parseSensorData(characteristic.value)?.let { parsedData ->
                        val isFirstData = state.sensorType == null
                        if (isFirstData) {
                            state.sensorType = parsedData.sensorType
                        }

                        if (isFirstData || state.isAbnormalSignal != parsedData.isAbnormal) {
                            state.isAbnormalSignal = parsedData.isAbnormal
                            evaluateOverallStatus()
                        }
                    }
                }
            }
        }
    }

    private fun handleDisconnection(address: String, statusText: String, color: String) {
        val state = deviceStates[address]
        state?.gatt?.close()
        if (state != null) {
            state.connectionStatus = ConnectionStatus.DISCONNECTED
            state.sensorStatus = SensorStatus.UNKNOWN
            broadcastStatus(address, statusText, color, false)
            evaluateOverallStatus()
            scheduleReconnect(address)
        }
    }

    private fun processNextSubscription(address: String) {
        val state = deviceStates[address] ?: return
        val characteristic = state.subscriptionQueue.poll()

        if (characteristic == null) {
            broadcastStatus(address, "等待数据", "YELLOW", false)
            // Do not set sensorStatus to UNKNOWN here, let evaluateOverallStatus handle it
            return
        }

        state.gatt?.setCharacteristicNotification(characteristic, true)
        mainHandler.postDelayed({
            characteristic.getDescriptor(CCCD_UUID)?.let { descriptor ->
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                state.gatt?.writeDescriptor(descriptor)
            }
        }, 200)
    }

    // --- Status Evaluation ---

    private fun evaluateOverallStatus() {
        val activeStates = deviceStates.values.filter { it.connectionStatus == ConnectionStatus.CONNECTED && it.sensorType != null }
        val abnormalHookSensorTypes = activeStates.filter { it.isAbnormalSignal && it.sensorType != 5 }.mapNotNull { it.sensorType?.minus(1) }.toSet()
        val isSingleHookState = SINGLE_HOOK_COMBINATIONS.contains(abnormalHookSensorTypes)

        activeStates.forEach { state ->
            val previousStatus = state.sensorStatus
            state.sensorStatus = when {
                !state.isAbnormalSignal -> SensorStatus.NORMAL
                state.sensorType == 5 -> SensorStatus.ALARM
                isSingleHookState -> if (state.singleHookTimerExpired) SensorStatus.ALARM else SensorStatus.SINGLE_HOOK
                else -> SensorStatus.ALARM
            }

            if (previousStatus != state.sensorStatus) {
                updateDeviceUiAndTimers(state)
            }
        }

        val partStatuses = Array(6) { SensorStatus.UNKNOWN }
        val groupedByPart = deviceStates.values.groupBy { it.sensorType }

        for (partNumber in 1..6) {
            val devicesForPart = groupedByPart[partNumber]
            if (devicesForPart.isNullOrEmpty()) {
                partStatuses[partNumber - 1] = SensorStatus.UNKNOWN
                continue
            }

            partStatuses[partNumber - 1] = when {
                devicesForPart.any { it.sensorStatus == SensorStatus.ALARM } -> SensorStatus.ALARM
                devicesForPart.any { it.sensorStatus == SensorStatus.SINGLE_HOOK } -> SensorStatus.SINGLE_HOOK
                devicesForPart.all { it.sensorStatus == SensorStatus.NORMAL } -> SensorStatus.NORMAL
                else -> SensorStatus.UNKNOWN
            }
        }

        val overallStatusString = when {
            partStatuses.any { it == SensorStatus.ALARM } -> "异常"
            partStatuses.any { it == SensorStatus.SINGLE_HOOK } -> "单挂"
            else -> "正常"
        }

        updateWorkSession(status = overallStatusString, partStatuses = partStatuses)
        updateAlarmQueueAndCycle()
    }

    private fun updateDeviceUiAndTimers(state: DeviceState) {
        when (state.sensorStatus) {
            SensorStatus.NORMAL -> {
                cancelSingleHookTimer(state)
                broadcastStatus(state.device.deviceAddress, "正常", "GREEN", true)
            }
            SensorStatus.SINGLE_HOOK -> {
                startSingleHookTimer(state)
                broadcastStatus(state.device.deviceAddress, "单挂", "BLACK", true)
            }
            SensorStatus.ALARM -> {
                state.singleHookTimer?.let { mainHandler.removeCallbacks(it) }
                state.singleHookTimer = null
                broadcastStatus(state.device.deviceAddress, "异常", "RED", true)
                logAlarmToCloud(state.device.deviceAddress, state.sensorType)
            }
            SensorStatus.UNKNOWN -> { /* No immediate UI update */ }
        }
    }

    private fun startSingleHookTimer(state: DeviceState) {
        if (state.singleHookTimer != null) return
        val runnable = Runnable {
            Log.d(TAG, "Single hook timer expired for ${state.device.deviceAddress}. Re-evaluating.")
            state.singleHookTimerExpired = true
            state.singleHookTimer = null
            evaluateOverallStatus()
        }
        state.singleHookTimer = runnable
        mainHandler.postDelayed(runnable, SINGLE_HOOK_TIMEOUT_MS)
    }

    private fun cancelSingleHookTimer(state: DeviceState) {
        state.singleHookTimerExpired = false
        state.singleHookTimer?.let { mainHandler.removeCallbacks(it) }
        state.singleHookTimer = null
    }

    // --- Alarm & TTS Management ---

    private fun stopAlarmCycle() {
        isAlarmLoopRunning = false
        synchronized(alarmMessageQueue) { alarmMessageQueue.clear() }
        currentAlarmIndex = 0
        if (this::tts.isInitialized) tts.stop()
    }

    private fun playNextAlarmInQueue() {
        if (!isAlarmLoopRunning || isMuted || !isTtsInitialized) return
        var messageToSpeak: String? = null
        synchronized(alarmMessageQueue) {
            if (alarmMessageQueue.isEmpty()) {
                isAlarmLoopRunning = false
                return
            }
            if (currentAlarmIndex >= alarmMessageQueue.size) currentAlarmIndex = 0
            messageToSpeak = alarmMessageQueue[currentAlarmIndex]
            currentAlarmIndex++
        }
        messageToSpeak?.let {
            triggerVibration()
            val params = Bundle()
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, ALARM_UTTERANCE_ID)
            tts.speak(it, TextToSpeech.QUEUE_FLUSH, params, ALARM_UTTERANCE_ID)
        }
    }

    private fun updateAlarmQueueAndCycle() {
        val newAlarmMessages = deviceStates.values
            .filter { it.sensorStatus == SensorStatus.ALARM }
            .mapNotNull { SENSOR_TYPE_NAMES[it.sensorType]?.plus(" 异常") }

        val shouldStartNow = !isAlarmLoopRunning && newAlarmMessages.isNotEmpty()

        synchronized(alarmMessageQueue) {
            alarmMessageQueue.clear()
            alarmMessageQueue.addAll(newAlarmMessages)
            isAlarmLoopRunning = alarmMessageQueue.isNotEmpty()
        }

        if (shouldStartNow) {
            currentAlarmIndex = 0
            playNextAlarmInQueue()
            val intent = Intent(ACTION_SHOW_ALARM_DIALOG).apply {
                putStringArrayListExtra(EXTRA_ALARM_MESSAGES, ArrayList(newAlarmMessages))
            }
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        } else if (!isAlarmLoopRunning) {
            stopAlarmCycle()
        }
    }

    // --- Data Parsing & Cloud Interaction ---

    private fun parseHeartbeatData(data: ByteArray): HeartbeatInfo? {
        if (data.size != 5 || data[4] != data.slice(0..3).sumOf { it.toInt() and 0xFF }.toByte()) return null
        val sensorIdHex = String.format("%04X", ((data[2].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF))
        val batteryStatus = when (val byte = data[3].toInt() and 0xFF) {
            0xFF -> "充电中"
            in 0..100 -> "$byte%"
            else -> "未知"
        }
        return HeartbeatInfo(sensorIdHex, batteryStatus)
    }

    private fun parseSensorData(data: ByteArray): ParsedSensorData? {
        if (data.size != 6 || data[5] != data.slice(0..4).sumOf { it.toInt() and 0xFF }.toByte()) return null
        val sensorType = data[1].toInt() and 0xFF
        if (sensorType !in 1..6) return null
        val isAbnormal = (data[4].toInt() and 0xFF) == 1
        return ParsedSensorData(sensorType, isAbnormal)
    }

    private fun logAlarmToCloud(deviceAddress: String, sensorType: Int?) {
        if (currentSessionId.isNullOrEmpty() || sensorType == null) return
        val currentUser = LCUser.getCurrentUser() ?: return
        AlarmEvent().apply {
            this.sessionId = currentSessionId!!
            this.worker = currentUser
            this.deviceAddress = deviceAddress
            this.sensorType = sensorType
            this.alarmType = "异常"
            this.timestamp = Date()
        }.saveInBackground().subscribe(object : Observer<LCObject> {
            override fun onSubscribe(d: Disposable) { compositeDisposable.add(d) }
            override fun onNext(t: LCObject) { Log.d(TAG, "Successfully saved AlarmEvent.") }
            override fun onError(e: Throwable) { Log.e(TAG, "Failed to save AlarmEvent.", e) }
            override fun onComplete() {}
        })
    }

    private fun updateWorkSession(status: String? = null, isOnline: Boolean? = null, partStatuses: Array<SensorStatus>? = null, shouldEndSession: Boolean = false) {
        if (currentSessionId.isNullOrEmpty()) return
        val sessionToUpdate = LCObject.createWithoutData("WorkSession", currentSessionId!!)
        var shouldSave = false

        status?.let {
            sessionToUpdate.put("currentStatus", it)
            if (it == "异常") sessionToUpdate.increment("totalAlarmCount", 1)
            shouldSave = true
        }
        isOnline?.let {
            sessionToUpdate.put("isOnline", it)
            shouldSave = true
        }
        if (shouldEndSession) {
            sessionToUpdate.put("endTime", Date())
            shouldSave = true
        }
        partStatuses?.let {
            if (it.size == 6) {
                for (i in it.indices) {
                    val statusString = when (it[i]) {
                        SensorStatus.NORMAL -> "正常"
                        SensorStatus.SINGLE_HOOK -> "单挂"
                        SensorStatus.ALARM -> "异常"
                        SensorStatus.UNKNOWN -> "未连接"
                    }
                    sessionToUpdate.put("sensor${i + 1}Status", statusString)
                }
                shouldSave = true
            }
        }

        if (shouldSave) {
            sessionToUpdate.saveInBackground().subscribe(object : Observer<LCObject> {
                override fun onSubscribe(d: Disposable) { compositeDisposable.add(d) }
                override fun onNext(t: LCObject) { Log.d(TAG, "Successfully updated WorkSession.") }
                override fun onError(e: Throwable) { Log.e(TAG, "Failed to update WorkSession.", e) }
                override fun onComplete() {}
            })
        }
    }

    // --- Utility & Broadcast ---

    private fun triggerVibration() {
        if (isMuted) return
        val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(500)
        }
    }

    private fun broadcastStatus(deviceAddress: String, text: String, color: String, showIcon: Boolean) {
        val intent = Intent(ACTION_STATUS_UPDATE).apply {
            putExtra(EXTRA_DEVICE_ADDRESS, deviceAddress)
            putExtra(EXTRA_TEXT, text)
            putExtra(EXTRA_COLOR, color)
            putExtra(EXTRA_SHOW_ICON, showIcon)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastHeartbeat(deviceAddress: String, info: HeartbeatInfo) {
        val intent = Intent(ACTION_HEARTBEAT_UPDATE).apply {
            putExtra(EXTRA_DEVICE_ADDRESS, deviceAddress)
            putExtra(EXTRA_SENSOR_ID, info.sensorIdHex)
            putExtra(EXTRA_BATTERY, info.batteryStatus)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "安全带后台服务",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    data class HeartbeatInfo(val sensorIdHex: String, val batteryStatus: String)
    data class ParsedSensorData(val sensorType: Int, val isAbnormal: Boolean)

    companion object {
        const val ACTION_CONNECT_DEVICES = "com.heyu.safetybeltoperators.ACTION_CONNECT_DEVICES"
        const val ACTION_DISCONNECT_ALL = "com.heyu.safetybeltoperators.ACTION_DISCONNECT_ALL"
        const val ACTION_RETRY_CONNECTION = "com.heyu.safetybeltoperators.ACTION_RETRY_CONNECTION"
        const val ACTION_MUTE_ALARMS = "com.heyu.safetybeltoperators.ACTION_MUTE_ALARMS"
        const val ACTION_RESET_ADMIN_ALERT = "com.heyu.safetybeltoperators.ACTION_RESET_ADMIN_ALERT" // New Action
        const val ACTION_SHOW_ADMIN_ALERT = "com.heyu.safetybeltoperators.ACTION_SHOW_ADMIN_ALERT" // New Action

        const val EXTRA_DEVICES = "com.heyu.safetybeltoperators.EXTRA_DEVICES"
        const val EXTRA_SESSION_ID = "com.heyu.safetybeltoperators.EXTRA_SESSION_ID"

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

        private const val NOTIFICATION_CHANNEL_ID = "BleServiceChannel"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "BleService"
        private const val CONNECTION_TIMEOUT_MS = 10000L
        private const val RECONNECT_DELAY_MS = 2000L
        private const val MAX_RECONNECT_ATTEMPTS = 3
        private const val SINGLE_HOOK_TIMEOUT_MS = 20000L
        private const val MUTE_DURATION_MS = 60000L // 1 minute
        private const val ALARM_UTTERANCE_ID = "com.heyu.safetybeltoperators.ALARM_CYCLE_UTTERANCE"

        val HEARTBEAT_SERVICE_UUID: UUID = UUID.fromString("00001233-0000-1000-8000-00805f9b34fb")
        val HEARTBEAT_CHARACTERISTIC_UUID: UUID = UUID.fromString("00001235-0000-1000-8000-00805f9b34fb")
        val SENSOR_DATA_SERVICE_UUID: UUID = UUID.fromString("00005677-0000-1000-8000-00805f9b34fb")
        val SENSOR_DATA_CHARACTERISTIC_UUID: UUID = UUID.fromString("00005679-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        val SENSOR_TYPE_NAMES = mapOf(1 to "后背绳高挂", 2 to "后背绳小钩", 3 to "围杆带环抱", 4 to "围杆带钩", 5 to "胸扣", 6 to "后背绳大钩")
        val SINGLE_HOOK_COMBINATIONS = setOf(
            setOf(0), setOf(1), setOf(2), setOf(3), setOf(5), // Single abnormalities
            setOf(3, 5), setOf(1, 2), setOf(0, 2), setOf(0, 1) // Valid dual abnormalities
        )
    }
}