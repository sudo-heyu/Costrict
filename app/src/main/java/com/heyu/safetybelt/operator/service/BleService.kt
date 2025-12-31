package com.heyu.safetybelt.operator.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import com.heyu.safetybelt.operator.activity.MainActivityOperator
import com.heyu.safetybelt.common.NotificationHelper
import io.reactivex.Observer
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable
import java.util.Collections
import androidx.core.app.NotificationCompat
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
    private enum class ConnectionStatus {
        CONNECTING, CONNECTED, DISCONNECTED, FAILED, TIMEOUT, RECONNECTING
    }
    private enum class SensorStatus {
        NORMAL, SINGLE_HOOK, ALARM, UNKNOWN
    }
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
    // 蹦蹦猪专用隔离 Handler，防止心跳被 mainHandler.removeCallbacksAndMessages 清掉
    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private lateinit var tts: TextToSpeech
    private var isTtsInitialized = false
    private val alarmMessageQueue = Collections.synchronizedList(mutableListOf<String>())
    private var currentAlarmIndex = 0
    @Volatile
    private var isAlarmLoopRunning = false
    @Volatile
    private var isMuted = false
    private val muteRunnable = Runnable { isMuted = false }
    private var isShuttingDown = false
    // 蹦蹦猪高詹子慧心跳逻辑：每 5 秒给云端报个平安
    private val HEARTBEAT_INTERVAL_MS = 5000L
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            val sid = currentSessionId
            if (!sid.isNullOrEmpty() && !isShuttingDown) {
                Log.d(TAG, "蹦蹦猪心跳开始上报... SID: $sid !!")
                try {
                    val session = LCObject.createWithoutData("WorkSession", sid)
                    session.put("lastHeartbeat", Date())
                    session.saveInBackground().subscribe(object : Observer<LCObject> {
                        override fun onSubscribe(d: Disposable) {
                            compositeDisposable.add(d)
                        }
                        override fun onNext(t: LCObject) {
                            Log.d(TAG, "蹦蹦猪云端打卡成功")
                        }
                        override fun onError(e: Throwable) {
                            val exception = e as? Exception ?: Exception(e)
                            Log.e(TAG, " 蹦蹦猪，云端打卡失败: ${exception.message}")
                            // 心跳失败时，尝试使用 saveEventually 作为后备
                            try {
                                val fallbackSession = LCObject.createWithoutData("WorkSession", sid)
                                fallbackSession.put("lastHeartbeat", Date())
                                fallbackSession.saveEventually()
                                Log.d(TAG, "心跳失败后备方案已启动")
                            } catch (fallbackException: Exception) {
                                Log.e(TAG, "心跳后备方案也失败: ${fallbackException.message}")
                            }
                        }
                        override fun onComplete() {}
                    })
                } catch (e: Exception) {
                    Log.e(TAG, "Heartbeat failed: ${e.message}")
                }
                heartbeatHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
            } else {
                Log.e(TAG, "心跳已停止: sid=$sid, isShuttingDown=$isShuttingDown")
            }
        }
    }
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale.CHINESE)
            if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                isTtsInitialized = true
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == ALARM_UTTERANCE_ID) {
                            mainHandler.postDelayed({ playNextAlarmInQueue() }, 500)
                        }
                    }
                    override fun onError(utteranceId: String?) {
                        if (utteranceId == ALARM_UTTERANCE_ID) {
                            mainHandler.postDelayed({ playNextAlarmInQueue() }, 500)
                        }
                    }
                })
            }
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this, this)

        // 恢复之前保存的sessionId
        val sharedPrefs = getSharedPreferences("BleService_Backup", Context.MODE_PRIVATE)
        val savedSessionId = sharedPrefs.getString("active_session_id", null)
        if (savedSessionId != null) {
            currentSessionId = savedSessionId
            Log.d(TAG, "恢复保存的sessionId: $savedSessionId")
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()

        when (intent?.action) {
            ACTION_CONNECT_DEVICES -> {
                val devices = intent.getParcelableArrayListExtra<DeviceScanResult>(EXTRA_DEVICES)
                val newSessionId = intent.getStringExtra(EXTRA_SESSION_ID)
                if (newSessionId != null && newSessionId != currentSessionId) {
                    currentSessionId = newSessionId
                    getSharedPreferences("BleService_Backup", Context.MODE_PRIVATE)
                        .edit().putString("active_session_id", newSessionId).apply()
                    cleanupConnectionsAndState(false)
                    heartbeatHandler.removeCallbacks(heartbeatRunnable)
                    heartbeatHandler.post(heartbeatRunnable)
                    startLiveQuery(newSessionId)
                    updateWorkSession(isOnline = true, lastHeartbeat = Date())
                }
                devices?.forEach {
                    if (!deviceStates.containsKey(it.deviceAddress)) {
                        deviceStates[it.deviceAddress] = DeviceState(it)
                        connectWithTimeout(it.deviceAddress)
                    }
                }
            }
            ACTION_DISCONNECT_ALL -> {
                disconnectAllDevices(true)
            }
            ACTION_CONNECT_SPECIFIC -> {
                val devices = intent.getParcelableArrayListExtra<DeviceScanResult>(EXTRA_DEVICES)
                devices?.forEach {
                    if (!deviceStates.containsKey(it.deviceAddress)) {
                        deviceStates[it.deviceAddress] = DeviceState(it)
                        connectWithTimeout(it.deviceAddress)
                    }
                }
            }
            ACTION_DISCONNECT_SPECIFIC -> {
                intent.getStringArrayListExtra(EXTRA_DEVICES_TO_DISCONNECT)?.forEach {
                    disconnectSpecificDevice(it, true)
                }
                evaluateOverallStatus()
            }
            ACTION_REQUEST_ALL_STATUSES -> broadcastAllStatuses()
            ACTION_RETRY_CONNECTION -> {
                val addr = intent.getStringExtra(EXTRA_DEVICE_ADDRESS)
                if (addr != null && deviceStates.containsKey(addr)) {
                    deviceStates[addr]?.reconnectAttempts = 0
                    connectWithTimeout(addr)
                }
            }
            ACTION_MUTE_ALARMS -> {
                isMuted = true
                mainHandler.removeCallbacks(muteRunnable)
                mainHandler.postDelayed(muteRunnable, MUTE_DURATION_MS)
                if (isTtsInitialized) tts.stop()
            }
            ACTION_RESET_ADMIN_ALERT -> resetAdminAlert()
        }

        updateNotification()
        return START_NOT_STICKY
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
                ConnectionStatus.CONNECTING, ConnectionStatus.RECONNECTING -> broadcastStatus(
                    state.device.deviceAddress,
                    "正在连接...",
                    "BLUE",
                    true
                )
                ConnectionStatus.FAILED -> broadcastStatus(state.device.deviceAddress, "重连失败", "YELLOW", true)
                ConnectionStatus.TIMEOUT -> broadcastStatus(state.device.deviceAddress, "超时", "YELLOW", true)
                else -> broadcastStatus(state.device.deviceAddress, "已断开", "GRAY", false)
            }
        }
    }
    override fun onDestroy() {
        Log.e(TAG, "BleService onDestroy called. isShuttingDown: $isShuttingDown")

        // 确保云端状态被更新为离线，无论是否已经在关闭过程中
        currentSessionId?.let { sessionId ->
            if (!isShuttingDown) {
                // 如果还没有开始关闭过程，执行紧急同步
                performEmergencyCloudSyncInTaskRemoved(sessionId)
            } else {
                // 如果已经在关闭过程中，至少尝试更新状态
                updateCloudStatusForOffline(sessionId)
            }
        }

        super.onDestroy()
        if (isTtsInitialized) {
            tts.stop()
            tts.shutdown()
        }
        disconnectAllDevices(false)
        stopLiveQuery()
        reconnectHandler.removeCallbacksAndMessages(null)
        compositeDisposable.clear()
        currentSessionId = null
        // 确保所有蓝牙连接都被断开
        deviceStates.values.forEach { state ->
            state.gatt?.disconnect()
            state.gatt?.close()
        }
        deviceStates.clear()
    }
    override fun onTaskRemoved(rootIntent: Intent?) {
        val sid = currentSessionId
        Log.e(TAG, "BleService onTaskRemoved! Triggering last-will for session: $sid")
        if (!sid.isNullOrEmpty()) {
            isShuttingDown = true
            Log.d(TAG, "onTaskRemoved called, finalizing cloud state for session $sid")

            // 立即执行紧急同步，不惜一切代价尝试发送数据
            performEmergencyCloudSyncInTaskRemoved(sid)
        }
        cleanupConnectionsAndState(true)
        // 确保所有蓝牙连接都被断开
        deviceStates.values.forEach { state ->
            state.gatt?.disconnect()
            state.gatt?.close()
        }
        super.onTaskRemoved(rootIntent)
        stopSelf()
    }
    private fun endSessionInternal(statusText: String, isBlocking: Boolean) {
        val sid = currentSessionId ?: return
        Log.d(TAG, "Ending session $sid with status: $statusText, blocking: $isBlocking")
        val session = LCObject.createWithoutData("WorkSession", sid)
        session.put("currentStatus", statusText)
        session.put("isOnline", false)
        session.put("endTime", Date())
        for (i in 1..6) {
            session.put("sensor${i}Status", "未连接")
        }
        val user = LCUser.getCurrentUser()
        user?.put("isOnline", false)
        if (isBlocking) {
            try {
                session.save()
                user?.save()
            } catch (e: Exception) {
                Log.e(TAG, "Sync save failed: ${e.message}")
                try {
                    session.saveEventually()
                    user?.saveEventually()
                } catch (ignore: Exception) {
                }
            }
        } else {
            session.saveInBackground().subscribe(object : Observer<LCObject> {
                override fun onSubscribe(d: Disposable) {
                    compositeDisposable.add(d)
                }
                override fun onNext(t: LCObject) {}
                override fun onError(e: Throwable) {
                    val exception = e as? Exception ?: Exception(e)
                    Log.e(TAG, "Async session save failed: ${exception.message}")
                }
                override fun onComplete() {}
            })
            user?.saveInBackground()?.subscribe(object : Observer<LCObject> {
                override fun onSubscribe(d: Disposable) {
                    compositeDisposable.add(d)
                }
                override fun onNext(t: LCObject) {}
                override fun onError(e: Throwable) {
                    val exception = e as? Exception ?: Exception(e)
                    Log.e(TAG, "Async user save failed: ${exception.message}")
                }
                override fun onComplete() {}
            })
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(ACTION_SESSION_ENDED))
    }
    private fun endSessionSynchronously(statusText: String, isBlocking: Boolean) {
        endSessionInternal(statusText, isBlocking)
    }
    private fun performEmergencyCloudSync() {
        val sessionIdToSave = currentSessionId
        if (isShuttingDown || sessionIdToSave.isNullOrEmpty()) return
        isShuttingDown = true
        Log.e(TAG, "!! EMERGENCY SYNC START !! Session: $sessionIdToSave")
        val oldPolicy = android.os.StrictMode.getThreadPolicy()
        android.os.StrictMode.setThreadPolicy(
            android.os.StrictMode.ThreadPolicy.Builder()
                .permitAll()
                .build()
        )
        try {
            val session = LCObject.createWithoutData("WorkSession", sessionIdToSave)
            session.put("isOnline", false)
            session.put("currentStatus", "手动断开")
            session.put("endTime", Date())
            for (i in 1..6) {
                session.put("sensor${i}Status", "未连接")
            }
            val user = LCUser.getCurrentUser()
            user?.put("isOnline", false)
            session.save()
            user?.save()
            Thread.sleep(1000)
            Log.e(TAG, "!! EMERGENCY SYNC SUCCESS !!")
        } catch (e: Exception) {
            Log.e(TAG, "!! EMERGENCY SYNC FAILED, using saveEventually !!")
            try {
                val fb = LCObject.createWithoutData("WorkSession", sessionIdToSave)
                fb.put("isOnline", false)
                fb.put("currentStatus", "手动断开")
                fb.put("endTime", Date())
                fb.saveEventually()
            } catch (ignore: Exception) {
            }
        } finally {
            android.os.StrictMode.setThreadPolicy(oldPolicy)
        }
    }
    /**
     * 专门用于 onTaskRemoved 的紧急同步方法
     * 核心思路：立即、同步地执行一次网络请求，不惜一切代价尝试发送数据
     */
    private fun performEmergencyCloudSyncInTaskRemoved(sessionId: String) {
        Log.e(TAG, "!! EMERGENCY SYNC IN TASK REMOVED START !! Session: $sessionId")

        // 设置关闭标志，防止其他操作干扰
        isShuttingDown = true

        // 允许网络访问，忽略严格模式限制
        val oldPolicy = android.os.StrictMode.getThreadPolicy()
        android.os.StrictMode.setThreadPolicy(
            android.os.StrictMode.ThreadPolicy.Builder()
                .permitAll()
                .build()
        )

        try {
            // 创建倒计时锁，确保同步执行完成
            val latch = CountDownLatch(1)
            var syncSuccess = false

            // 更新工作会话状态
            val session = LCObject.createWithoutData("WorkSession", sessionId)
            session.put("currentStatus", "应用被清除")
            session.put("isOnline", false)
            session.put("endTime", Date())
            // 更新所有传感器状态为未连接
            for (i in 1..6) {
                session.put("sensor${i}Status", "未连接")
            }

            // 更新用户在线状态
            val user = LCUser.getCurrentUser()
            user?.put("isOnline", false)

            // 使用同步保存，确保立即执行
            session.saveInBackground().subscribe(object : Observer<LCObject> {
                override fun onSubscribe(d: Disposable) {
                    // 不需要添加到 compositeDisposable，因为服务即将销毁
                }

                override fun onNext(t: LCObject) {
                    syncSuccess = true
                    Log.e(TAG, "!! SESSION SYNC SUCCESS !!")
                    latch.countDown()
                }

                override fun onError(e: Throwable) {
                    Log.e(TAG, "!! SESSION SYNC FAILED: ${e.message} !!")
                    latch.countDown()
                }

                override fun onComplete() {}
            })

            // 等待同步完成（最多等待3秒）
            try {
                latch.await(3, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                Log.e(TAG, "!! SYNC INTERRUPTED !!")
            }

            // 如果同步失败，尝试 saveEventually 作为后备方案
            if (!syncSuccess) {
                Log.e(TAG, "!! FALLING BACK TO SAVE EVENTUALLY !!")
                try {
                    val fallbackSession = LCObject.createWithoutData("WorkSession", sessionId)
                    fallbackSession.put("isOnline", false)
                    fallbackSession.put("currentStatus", "应用被清除")
                    fallbackSession.put("endTime", Date())
                    fallbackSession.saveEventually()

                    user?.saveEventually()

                    Log.e(TAG, "!! SAVE EVENTUALLY INITIATED !!")
                } catch (fallbackException: Exception) {
                    Log.e(TAG, "!! FALLBACK SAVE ALSO FAILED: ${fallbackException.message} !!")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "!! EMERGENCY SYNC EXCEPTION: ${e.message} !!", e)
            // 最后的后备方案：至少尝试 saveEventually
            try {
                val lastFallback = LCObject.createWithoutData("WorkSession", sessionId)
                lastFallback.put("isOnline", false)
                lastFallback.saveEventually()
            } catch (ignore: Exception) {
                Log.e(TAG, "!! LAST RESORT FAILED !!")
            }
        } finally {
            // 恢复原始策略
            android.os.StrictMode.setThreadPolicy(oldPolicy)
            Log.e(TAG, "!! EMERGENCY SYNC IN TASK REMOVED COMPLETED !!")
        }
    }
    private fun updateCloudStatusForOffline(sessionId: String) {
        Log.d(TAG, "Updating cloud status for offline session: $sessionId")

        // 确保网络策略允许网络访问
        val oldPolicy = android.os.StrictMode.getThreadPolicy()
        android.os.StrictMode.setThreadPolicy(
            android.os.StrictMode.ThreadPolicy.Builder().permitAll().build()
        )

        try {
            // 更新工作会话状态
            val session = LCObject.createWithoutData("WorkSession", sessionId)
            session.put("currentStatus", "离线")
            session.put("isOnline", false)
            session.put("endTime", Date())
            // 更新所有传感器状态为未连接
            for (i in 1..6) {
                session.put("sensor${i}Status", "未连接")
            }

            // 更新用户在线状态
            val user = LCUser.getCurrentUser()
            user?.put("isOnline", false)

            // 使用 saveEventually 确保即使应用被终止也会尝试保存数据
            session.saveEventually()
            user?.saveEventually()

            Log.d(TAG, "Cloud status update initiated for offline session: $sessionId")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to update cloud status for offline: ${e.message}", e)
            // 如果直接调用失败，至少尝试 saveEventually
            try {
                val fallbackSession = LCObject.createWithoutData("WorkSession", sessionId)
                fallbackSession.put("isOnline", false)
                fallbackSession.saveEventually()
            } catch (fallbackException: Exception) {
                Log.e(TAG, "Fallback save also failed: ${fallbackException.message}", fallbackException)
            }
        } finally {
            // 恢复原始策略
            android.os.StrictMode.setThreadPolicy(oldPolicy)
        }
    }

    private fun startLiveQuery(sessionId: String) {
        stopLiveQuery()
        val query = LCQuery<WorkSession>("WorkSession").whereEqualTo("objectId", sessionId)
        liveQuery = LCLiveQuery.initWithQuery(query)
        liveQuery?.setEventHandler(object : LCLiveQueryEventHandler() {
            override fun onObjectUpdated(obj: LCObject?, updateKeyList: MutableList<String>?) {
                if (obj != null && updateKeyList?.contains("adminAlert") == true) {
                    fetchWorkSessionAndCheckAlert(obj.objectId)
                }
            }
        })
        liveQuery?.subscribeInBackground(object : LCLiveQuerySubscribeCallback() {
            override fun done(e: LCException?) {
                if (e != null) Log.e(TAG, "LiveQuery 订阅失败", e)
            }
        })
    }
    private fun stopLiveQuery() {
        liveQuery?.unsubscribeInBackground(object : LCLiveQuerySubscribeCallback() {
            override fun done(e: LCException?) {}
        })
        liveQuery = null
    }
    private fun fetchWorkSessionAndCheckAlert(sessionId: String) {
        LCQuery<WorkSession>("WorkSession").getInBackground(sessionId).subscribe(object : Observer<WorkSession> {
            override fun onSubscribe(d: Disposable) {
                compositeDisposable.add(d)
            }
            override fun onNext(session: WorkSession) {
                if (session.getBoolean("adminAlert")) {
                    sendAdminAlertBroadcast()
                }
            }
            override fun onError(e: Throwable) {
                val exception = e as? Exception ?: Exception(e)
            }
            override fun onComplete() {}
        })
    }
    private fun sendAdminAlertBroadcast() {
        LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(ACTION_SHOW_ADMIN_ALERT))
        triggerVibration()
    }
    private fun resetAdminAlert() {
        val sid = currentSessionId ?: run { return }
        val session = LCObject.createWithoutData("WorkSession", sid)
        session.put("adminAlert", false)
        session.saveInBackground().subscribe(object : Observer<LCObject> {
            override fun onSubscribe(d: Disposable) {
                compositeDisposable.add(d)
            }
            override fun onNext(t: LCObject) {}
            override fun onError(e: Throwable) {
                val exception = e as? Exception ?: Exception(e)
            }
            override fun onComplete() {}
        })
    }
    private fun cleanupConnectionsAndState(clearLocalSession: Boolean) {
        mainHandler.removeCallbacksAndMessages(null)
        reconnectHandler.removeCallbacksAndMessages(null)
        deviceStates.values.forEach {
            it.gatt?.close()
            broadcastConnectionState(it.device.deviceAddress, false)
            it.singleHookTimer?.let { timer -> mainHandler.removeCallbacks(timer) }
        }
        deviceStates.clear()
        stopAlarmCycle()
        if (clearLocalSession) {
            currentSessionId = null
        }
    }
    private fun disconnectSpecificDevice(address: String, notifyUi: Boolean) {
        val state = deviceStates.remove(address)
        if (state != null) {
            state.gatt?.disconnect()
            state.gatt?.close()
            broadcastConnectionState(address, false)
            if (notifyUi) {
                broadcastStatus(address, "已移除", "GRAY", false)
            }
        }
        checkAllDevicesDisconnected()
    }
    private fun disconnectAllDevices(isManual: Boolean) {
        val addresses = deviceStates.keys.toList()
        if (isManual && !currentSessionId.isNullOrEmpty()) {
            endSessionInternal("手动断开", false)
        }
        cleanupConnectionsAndState(true)
        stopLiveQuery()
        if (isManual) {
            addresses.forEach { broadcastStatus(it, "待连接", "GRAY", false) }
        }
        stopSelf()
    }
    private fun connectWithTimeout(address: String) {
        val state = deviceStates[address] ?: return
        broadcastStatus(address, "正在连接...", "BLUE", true)
        state.connectionStatus = ConnectionStatus.CONNECTING
        state.gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            state.device.device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            state.device.device.connectGatt(this, false, gattCallback)
        }
        mainHandler.postDelayed({
            if (state.connectionStatus == ConnectionStatus.CONNECTING) {
                state.connectionStatus = ConnectionStatus.TIMEOUT
                state.gatt?.close()
                broadcastStatus(address, "超时", "YELLOW", true)
                broadcastConnectionState(address, false)
                scheduleReconnect(address)
            }
        }, CONNECTION_TIMEOUT_MS)
    }
    private fun scheduleReconnect(address: String) {
        val state = deviceStates[address] ?: return
        if (state.reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            state.connectionStatus = ConnectionStatus.FAILED
            broadcastStatus(address, "重连失败", "YELLOW", true)
            broadcastConnectionState(address, false)
            checkAllDevicesDisconnected()
            return
        }
        state.reconnectAttempts++
        state.connectionStatus = ConnectionStatus.RECONNECTING
        broadcastStatus(address, "正在重连...", "BLUE", true)
        reconnectHandler.postDelayed({ connectWithTimeout(address) }, RECONNECT_DELAY_MS)
    }
    private fun checkAllDevicesDisconnected() {
        val hasAnyActive = deviceStates.values.any {
            it.connectionStatus == ConnectionStatus.CONNECTED ||
                    it.connectionStatus == ConnectionStatus.CONNECTING ||
                    it.connectionStatus == ConnectionStatus.RECONNECTING
        }
        if (!hasAnyActive && !currentSessionId.isNullOrEmpty()) {
            endSessionSynchronously(if (deviceStates.isEmpty()) "设备离线" else "设备失联", false)
            cleanupConnectionsAndState(true)
            stopLiveQuery()
            stopSelf()
        }
    }
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val address = gatt.device.address
            mainHandler.removeCallbacksAndMessages(null)
            val state = deviceStates[address] ?: return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    state.connectionStatus = ConnectionStatus.CONNECTED
                    broadcastConnectionState(address, true)
                    state.reconnectAttempts = 0
                    mainHandler.post { state.gatt?.discoverServices() }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    handleDisconnection(address, "已断开", "GRAY", false)
                }
            } else {
                handleDisconnection(address, "连接失败", "RED", false)
            }
            mainHandler.post {
                this@BleService.updateNotification()
            }
        }
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val state = deviceStates[gatt.device.address] ?: return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                state.subscriptionQueue.clear()
                gatt.getService(HEARTBEAT_SERVICE_UUID)
                    ?.getCharacteristic(HEARTBEAT_CHARACTERISTIC_UUID)
                    ?.let { state.subscriptionQueue.add(it) }
                gatt.getService(SENSOR_DATA_SERVICE_UUID)
                    ?.getCharacteristic(SENSOR_DATA_CHARACTERISTIC_UUID)
                    ?.let { state.subscriptionQueue.add(it) }
                processNextSubscription(gatt.device.address)
            } else {
                handleDisconnection(gatt.device.address, "服务发现失败", "RED", false)
            }
        }
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                processNextSubscription(gatt.device.address)
            } else {
                handleDisconnection(gatt.device.address, "订阅失败", "RED", false)
            }
        }
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val state = deviceStates[gatt.device.address] ?: return
            when (characteristic.uuid) {
                HEARTBEAT_CHARACTERISTIC_UUID -> {
                    parseHeartbeatData(characteristic.value)?.let {
                        broadcastHeartbeat(gatt.device.address, it)
                    }
                }
                SENSOR_DATA_CHARACTERISTIC_UUID -> {
                    parseSensorData(characteristic.value)?.let { parsed ->
                        val isFirst = state.sensorType == null
                        if (isFirst) {
                            state.sensorType = parsed.sensorType
                        }
                        if (isFirst || state.isAbnormalSignal != parsed.isAbnormal) {
                            state.isAbnormalSignal = parsed.isAbnormal
                            evaluateOverallStatus()
                        }
                    }
                }
            }
        }
    }
    private fun handleDisconnection(address: String, statusText: String, color: String, fromUser: Boolean) {
        val state = deviceStates[address] ?: return
        state.gatt?.close()
        state.connectionStatus = ConnectionStatus.DISCONNECTED
        broadcastConnectionState(address, false)
        state.sensorStatus = SensorStatus.UNKNOWN
        broadcastStatus(address, statusText, color, false)
        evaluateOverallStatus()
        if (!fromUser) {
            scheduleReconnect(address)
        }
    }
    private fun processNextSubscription(address: String) {
        val state = deviceStates[address] ?: run { return }
        val char = state.subscriptionQueue.poll() ?: run {
            broadcastStatus(address, "正常", "GREEN", true)
            return
        }
        state.gatt?.setCharacteristicNotification(char, true)
        mainHandler.postDelayed({
            char.getDescriptor(CCCD_UUID)?.let {
                it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                state.gatt?.writeDescriptor(it)
            }
        }, 200)
    }
    private fun evaluateOverallStatus() {
        val active = deviceStates.values.filter { it.connectionStatus == ConnectionStatus.CONNECTED && it.sensorType != null }
        val abnormalHookTypes = active.filter { it.isAbnormalSignal && it.sensorType != 5 }
            .mapNotNull { it.sensorType?.minus(1) }
            .toSet()
        val isSingle = SINGLE_HOOK_COMBINATIONS.contains(abnormalHookTypes)
        deviceStates.values.forEach { state ->
            val prev = state.sensorStatus
            state.sensorStatus = if (state.connectionStatus != ConnectionStatus.CONNECTED) {
                SensorStatus.UNKNOWN
            } else {
                when {
                    !state.isAbnormalSignal -> SensorStatus.NORMAL
                    state.sensorType == 5 -> SensorStatus.ALARM
                    isSingle -> if (state.singleHookTimerExpired) SensorStatus.ALARM else SensorStatus.SINGLE_HOOK
                    else -> SensorStatus.ALARM
                }
            }
            if (prev != state.sensorStatus) {
                updateDeviceUiAndTimers(state)
            }
        }
        val partStatuses = Array(6) { i ->
            val devices = deviceStates.values.filter { it.sensorType == i + 1 }
            if (devices.isEmpty()) {
                SensorStatus.UNKNOWN
            } else if (devices.any { it.sensorStatus == SensorStatus.ALARM }) {
                SensorStatus.ALARM
            } else if (devices.any { it.sensorStatus == SensorStatus.SINGLE_HOOK }) {
                SensorStatus.SINGLE_HOOK
            } else if (devices.all { it.sensorStatus == SensorStatus.NORMAL }) {
                SensorStatus.NORMAL
            } else {
                SensorStatus.UNKNOWN
            }
        }
        val overall = when {
            partStatuses.any { it == SensorStatus.ALARM } -> "异常"
            partStatuses.any { it == SensorStatus.SINGLE_HOOK } -> "单挂"
            deviceStates.isEmpty() -> "已断开"
            else -> "正常"
        }
        updateWorkSession(status = overall, partStatuses = partStatuses)
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
            else -> {}
        }
    }
    private fun startSingleHookTimer(state: DeviceState) {
        if (state.singleHookTimer != null) return
        state.singleHookTimer = Runnable {
            state.singleHookTimerExpired = true
            state.singleHookTimer = null
            evaluateOverallStatus()
        }
        mainHandler.postDelayed(state.singleHookTimer!!, SINGLE_HOOK_TIMEOUT_MS)
    }
    private fun cancelSingleHookTimer(state: DeviceState) {
        state.singleHookTimerExpired = false
        state.singleHookTimer?.let { mainHandler.removeCallbacks(it) }
        state.singleHookTimer = null
    }
    private fun stopAlarmCycle() {
        isAlarmLoopRunning = false
        synchronized(alarmMessageQueue) {
            alarmMessageQueue.clear()
        }
        currentAlarmIndex = 0
        if (isTtsInitialized) {
            tts.stop()
        }
    }
    private fun playNextAlarmInQueue() {
        if (!isAlarmLoopRunning || isMuted || !isTtsInitialized) return
        val msg = synchronized(alarmMessageQueue) {
            if (alarmMessageQueue.isEmpty()) {
                isAlarmLoopRunning = false
                return
            }
            if (currentAlarmIndex >= alarmMessageQueue.size) {
                currentAlarmIndex = 0
            }
            alarmMessageQueue[currentAlarmIndex++]
        }
        triggerVibration()
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, ALARM_UTTERANCE_ID)
        }
        tts.speak(msg, TextToSpeech.QUEUE_FLUSH, params, ALARM_UTTERANCE_ID)
    }
    private fun updateAlarmQueueAndCycle() {
        val newMsgs = deviceStates.values
            .filter { it.sensorStatus == SensorStatus.ALARM }
            .mapNotNull { SENSOR_TYPE_NAMES[it.sensorType]?.plus(" 异常") }
        val shouldStart = !isAlarmLoopRunning && newMsgs.isNotEmpty()
        synchronized(alarmMessageQueue) {
            val had = alarmMessageQueue.isNotEmpty()
            alarmMessageQueue.clear()
            alarmMessageQueue.addAll(newMsgs)
            isAlarmLoopRunning = alarmMessageQueue.isNotEmpty()
            if (!had && isAlarmLoopRunning) {
                LocalBroadcastManager.getInstance(this).sendBroadcast(
                    Intent(ACTION_SHOW_ALARM_DIALOG).apply {
                        putStringArrayListExtra(EXTRA_ALARM_MESSAGES, ArrayList(newMsgs))
                    }
                )
            }
        }
        if (shouldStart) {
            currentAlarmIndex = 0
            playNextAlarmInQueue()
        } else if (!isAlarmLoopRunning) {
            stopAlarmCycle()
        }
    }
    private fun parseHeartbeatData(data: ByteArray): HeartbeatInfo? {
        if (data.size != 5 || data[4] != data.slice(0..3).sumOf { it.toInt() and 0xFF }.toByte()) {
            return null
        }
        val id = String.format("%04X", ((data[2].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF))
        val bat = when (val b = data[3].toInt() and 0xFF) {
            0xFF -> "充电中"
            in 0..100 -> "$b%"
            else -> "未知"
        }
        return HeartbeatInfo(id, bat)
    }
    private fun parseSensorData(data: ByteArray): ParsedSensorData? {
        if (data.size != 6 || data[5] != data.slice(0..4).sumOf { it.toInt() and 0xFF }.toByte()) {
            return null
        }
        val type = data[1].toInt() and 0xFF
        if (type !in 1..6) {
            return null
        }
        return ParsedSensorData(type, (data[4].toInt() and 0xFF) == 1)
    }
    private fun logAlarmToCloud(deviceAddress: String, sensorType: Int?) {
        val sid = currentSessionId ?: return
        val user = LCUser.getCurrentUser() ?: return
        AlarmEvent().apply {
            this.sessionId = sid
            this.worker = user
            this.deviceAddress = deviceAddress
            this.sensorType = sensorType ?: 0
            this.alarmType = "异常"
            this.timestamp = Date()
        }.saveInBackground().subscribe(object : Observer<LCObject> {
            override fun onSubscribe(d: Disposable) {
                compositeDisposable.add(d)
            }
            override fun onNext(t: LCObject) {}
            override fun onError(e: Throwable) {
                val exception = e as? Exception ?: Exception(e)
            }
            override fun onComplete() {}
        })
    }
    private fun updateWorkSession(
        status: String? = null,
        isOnline: Boolean? = null,
        partStatuses: Array<SensorStatus>? = null,
        shouldEndSession: Boolean = false,
        lastHeartbeat: Date? = null
    ) {
        val sid = currentSessionId ?: return
        val session = LCObject.createWithoutData("WorkSession", sid)
        var save = false
        status?.let {
            session.put("currentStatus", it)
            if (it == "异常") {
                session.increment("totalAlarmCount", 1)
            }
            save = true
        }
        isOnline?.let {
            session.put("isOnline", it)
            save = true
        }
        if (shouldEndSession) {
            session.put("endTime", Date())
            save = true
        }
        lastHeartbeat?.let {
            session.put("lastHeartbeat", it)
            save = true
        }
        partStatuses?.let {
            for (i in it.indices) {
                val s = when (it[i]) {
                    SensorStatus.NORMAL -> "正常"
                    SensorStatus.SINGLE_HOOK -> "单挂"
                    SensorStatus.ALARM -> "异常"
                    else -> "未连接"
                }
                session.put("sensor${i + 1}Status", s)
            }
            save = true
        }
        if (save) {
            if (shouldEndSession) {
                try {
                    session.saveEventually()
                } catch (e: Exception) {
                }
            } else {
                session.saveInBackground().subscribe(object : Observer<LCObject> {
                    override fun onSubscribe(d: Disposable) {
                        compositeDisposable.add(d)
                    }
                    override fun onNext(t: LCObject) {}
                    override fun onError(e: Throwable) {
                        val exception = e as? Exception ?: Exception(e)
                    }
                    override fun onComplete() {}
                })
            }
        }
    }
    private fun triggerVibration() {
        if (isMuted) return
        val v = getSystemService(VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            v.vibrate(500)
        }
    }
    private fun broadcastStatus(deviceAddress: String, text: String, color: String, showIcon: Boolean) {
        LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(ACTION_STATUS_UPDATE).apply {
            putExtra(EXTRA_DEVICE_ADDRESS, deviceAddress)
            putExtra(EXTRA_TEXT, text)
            putExtra(EXTRA_COLOR, color)
            putExtra(EXTRA_SHOW_ICON, showIcon)
        })
    }
    private fun broadcastHeartbeat(deviceAddress: String, info: HeartbeatInfo) {
        LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(ACTION_HEARTBEAT_UPDATE).apply {
            putExtra(EXTRA_DEVICE_ADDRESS, deviceAddress)
            putExtra(EXTRA_SENSOR_ID, info.sensorIdHex)
            putExtra(EXTRA_BATTERY, info.batteryStatus)
        })
    }
    private fun broadcastConnectionState(address: String, isConnected: Boolean) {
        LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(ACTION_CONNECTION_STATE_UPDATE).apply {
            putExtra(EXTRA_DEVICE_ADDRESS, address)
            putExtra(EXTRA_IS_CONNECTED, isConnected)
        })
    }
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, "安全带后台服务", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "安全带卫士后台保护服务通知"
                enableLights(true)
                lightColor = android.graphics.Color.RED
                enableVibration(false)
                setShowBadge(true)
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun updateNotification() {
        try {
            val connectedCount = deviceStates.values.count { it.connectionStatus == ConnectionStatus.CONNECTED }
            val totalCount = deviceStates.size

            val notificationText = if (totalCount > 0) {
                "已连接 $connectedCount/$totalCount 个设备，正在监控安全带状态"
            } else {
                "正在后台保护您的作业安全"
            }
            val notificationIntent = Intent(this, MainActivityOperator::class.java)
            val pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("安全带卫士")
                .setContentText(notificationText)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pendingIntent)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(true)
                .build()
            startForeground(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "更新通知失败: ${e.message}")
        }
    }
    data class HeartbeatInfo(val sensorIdHex: String, val batteryStatus: String)
    data class ParsedSensorData(val sensorType: Int, val isAbnormal: Boolean)
    companion object {
        @Volatile
        var currentSessionId: String? = null
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
        private const val NOTIFICATION_CHANNEL_ID = "BleServiceChannel"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "BleService"
        private const val CONNECTION_TIMEOUT_MS = 30000L
        private const val RECONNECT_DELAY_MS = 2000L
        private const val MAX_RECONNECT_ATTEMPTS = 3
        private const val SINGLE_HOOK_TIMEOUT_MS = 20000L
        private const val MUTE_DURATION_MS = 60000L
        private const val ALARM_UTTERANCE_ID = "com.heyu.safetybeltoperators.ALARM_CYCLE_UTTERANCE"
        val HEARTBEAT_SERVICE_UUID: UUID = UUID.fromString("00001233-0000-1000-8000-00805f9b34fb")
        val HEARTBEAT_CHARACTERISTIC_UUID: UUID = UUID.fromString("00001235-0000-1000-8000-00805f9b34fb")
        val SENSOR_DATA_SERVICE_UUID: UUID = UUID.fromString("00005677-0000-1000-8000-00805f9b34fb")
        val SENSOR_DATA_CHARACTERISTIC_UUID: UUID = UUID.fromString("00005679-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val SENSOR_TYPE_NAMES = mapOf(
            1 to "后背绳高挂",
            2 to "后背绳小钩",
            3 to "围杆带环抱",
            4 to "围杆带钩",
            5 to "腰扣",
            6 to "后背绳大钩"
        )
        val SINGLE_HOOK_COMBINATIONS = setOf(
            setOf(0),
            setOf(1),
            setOf(2),
            setOf(3),
            setOf(5),
            setOf(3, 5),
            setOf(1, 2),
            setOf(0, 2),
            setOf(0, 1)
        )
    }
}
