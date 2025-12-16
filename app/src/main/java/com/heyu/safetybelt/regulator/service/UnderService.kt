package com.heyu.safetybelt.regulator.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import cn.leancloud.LCException
import cn.leancloud.LCObject
import cn.leancloud.LCQuery
import cn.leancloud.livequery.LCLiveQuery
import cn.leancloud.livequery.LCLiveQueryEventHandler
import cn.leancloud.livequery.LCLiveQuerySubscribeCallback
import com.heyu.safetybelt.R
import com.heyu.safetybelt.common.WorkSession
import com.heyu.safetybelt.common.Worker
import com.heyu.safetybelt.common.WorkerStatus
import io.reactivex.Observer
import io.reactivex.disposables.Disposable
import java.util.Date

class UnderService : Service() {

    interface WorkerListListener {
        fun onWorkerListUpdated(workerList: List<WorkerStatus>)
        fun onWorkerNotFound()
        fun onWorkerAlreadyExists()
    }

    private var listener: WorkerListListener? = null
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): UnderService = this@UnderService
    }

    private var liveQuery: LCLiveQuery? = null
    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshWorkerList()
            refreshHandler.postDelayed(this, 20000)
        }
    }

    private val vibrationHandler = Handler(Looper.getMainLooper())
    private var isVibrating = false
    private val vibrationRunnable = object : Runnable {
        override fun run() {
            vibrate()
            vibrationHandler.postDelayed(this, 1500)
        }
    }
    private val abnormalWorkers = mutableSetOf<String>()
    private var cachedWorkerList: MutableList<WorkerStatus> = mutableListOf()

    override fun onCreate() {
        super.onCreate()
        startForegroundService()
        startAutoRefresh()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopContinuousVibration()
        liveQuery?.unsubscribeInBackground(object : LCLiveQuerySubscribeCallback() {
            override fun done(e: LCException?) { /* do nothing */ }
        })
        stopAutoRefresh()
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    fun setWorkerListListener(listener: WorkerListListener) {
        this.listener = listener
        this.listener?.onWorkerListUpdated(cachedWorkerList)
    }

    fun removeWorkerListListener() {
        this.listener = null
    }

    fun getWorkerList(): List<WorkerStatus> = cachedWorkerList.toList()

    fun removeWorker(workerId: String) {
        val removed = cachedWorkerList.removeAll { it.workerId == workerId }
        if (removed) {
            removeAbnormalWorker(workerId)
            notifyListener()
            restartLiveQuery()
        }
    }

    fun removeAllWorkers() {
        cachedWorkerList.clear()
        abnormalWorkers.clear()
        stopContinuousVibration()
        notifyListener()
        restartLiveQuery()
    }

    fun findAndAddWorker(workerName: String, workerNumber: String) {
        if (cachedWorkerList.any { it.workerName == workerName && it.workerNumber == workerNumber }) {
            listener?.onWorkerAlreadyExists()
            return
        }

        val workerQuery = LCQuery<Worker>("Worker").whereEqualTo("name", workerName).whereEqualTo("employeeId", workerNumber)
        workerQuery.firstInBackground.subscribe(object : Observer<Worker> {
            override fun onSubscribe(d: Disposable) {}
            override fun onNext(worker: Worker) {
                val sessionQuery = LCQuery<WorkSession>("WorkSession").whereEqualTo("worker", worker).whereEqualTo("isOnline", true).orderByDescending("updatedAt")
                sessionQuery.firstInBackground.subscribe(object : Observer<WorkSession> {
                    override fun onSubscribe(d: Disposable) {}
                    override fun onNext(session: WorkSession) { addWorkerToList(worker, session, true) }
                    override fun onError(e: Throwable) { addWorkerToList(worker, null, false) }
                    override fun onComplete() {}
                })
            }
            override fun onError(e: Throwable) { listener?.onWorkerNotFound() }
            override fun onComplete() {}
        })
    }

    private fun addWorkerToList(worker: Worker, session: WorkSession?, isOnline: Boolean) {
        val workerId = worker.objectId
        if (cachedWorkerList.none { it.workerId == workerId }) {
            val status = session?.getString("currentStatus") ?: "离线"
            if (status.contains("异常")) addAbnormalWorker(workerId)

            val workerStatus = WorkerStatus(
                workerId = workerId,
                workerName = worker.getString("name") ?: "",
                workerNumber = worker.getString("employeeId") ?: "",
                status = status,
                lastUpdatedAt = session?.updatedAt ?: Date(),
                isOnline = isOnline,
                sensor1Status = session?.getString("sensor1Status"),
                sensor2Status = session?.getString("sensor2Status"),
                sensor3Status = session?.getString("sensor3Status"),
                sensor4Status = session?.getString("sensor4Status"),
                sensor5Status = session?.getString("sensor5Status"),
                sensor6Status = session?.getString("sensor6Status"),
                sessionId = session?.objectId // 修复：添加 sessionId
            )
            cachedWorkerList.add(workerStatus)
            notifyListener()
            restartLiveQuery()
        } else {
            listener?.onWorkerAlreadyExists()
        }
    }

    private fun notifyListener() {
        Handler(Looper.getMainLooper()).post { listener?.onWorkerListUpdated(cachedWorkerList.toList()) }
    }

    private fun startForegroundService() {
        val channelId = "UnderServiceChannel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Under Service", NotificationManager.IMPORTANCE_DEFAULT)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
        val notification: Notification = Notification.Builder(this, channelId)
            .setContentTitle("SafetyBeltRegulator")
            .setContentText("Monitoring service is running.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, notification)
        }
    }

    private fun startAutoRefresh() {
        stopAutoRefresh()
        refreshHandler.postDelayed(refreshRunnable, 20000)
    }

    private fun stopAutoRefresh() {
        refreshHandler.removeCallbacks(refreshRunnable)
    }

    private fun restartLiveQuery() {
        liveQuery?.unsubscribeInBackground(object : LCLiveQuerySubscribeCallback() {
            override fun done(e: LCException?) { startLiveQuery() }
        }) ?: startLiveQuery()
    }

    private fun startLiveQuery() {
        if (cachedWorkerList.isEmpty()) {
            liveQuery?.unsubscribeInBackground(null)
            liveQuery = null
            return
        }
        val workerPointers = cachedWorkerList.map { LCObject.createWithoutData("Worker", it.workerId) }
        val query = LCQuery<WorkSession>("WorkSession").whereContainedIn("worker", workerPointers)
        liveQuery = LCLiveQuery.initWithQuery(query)

        liveQuery?.setEventHandler(object : LCLiveQueryEventHandler() {
            override fun onObjectUpdated(obj: LCObject?, updateKeyList: MutableList<String>?) { if (obj is WorkSession) fetchSessionAndRefresh(obj.objectId) }
            override fun onObjectEnter(obj: LCObject?, updateKeyList: MutableList<String>?) { if (obj is WorkSession) fetchSessionAndRefresh(obj.objectId) }
            override fun onObjectLeave(obj: LCObject?, updateKeyList: MutableList<String>?) { if (obj is WorkSession) refreshWorkerList() }
        })

        liveQuery?.subscribeInBackground(object : LCLiveQuerySubscribeCallback() {
            override fun done(e: LCException?) {
                if (e == null) Log.d("UnderService", "LiveQuery 订阅成功！")
                else Log.e("UnderService", "LiveQuery 订阅失败", e)
            }
        })
    }

    private fun startContinuousVibration() {
        if (!isVibrating) {
            isVibrating = true
            vibrationHandler.post(vibrationRunnable)
        }
    }

    private fun stopContinuousVibration() {
        if (isVibrating) {
            isVibrating = false
            vibrationHandler.removeCallbacks(vibrationRunnable)
        }
    }

    private fun addAbnormalWorker(workerId: String) {
        if (abnormalWorkers.add(workerId) && abnormalWorkers.size == 1) startContinuousVibration()
    }

    private fun removeAbnormalWorker(workerId: String) {
        if (abnormalWorkers.remove(workerId) && abnormalWorkers.isEmpty()) stopContinuousVibration()
    }

    private fun fetchSessionAndRefresh(sessionId: String) {
        val query = LCQuery<WorkSession>("WorkSession").include("worker")
        query.getInBackground(sessionId).subscribe(object : Observer<WorkSession> {
            override fun onSubscribe(d: Disposable) {}
            override fun onNext(session: WorkSession) { updateWorkerStatusFromFullSession(session, true) }
            override fun onError(e: Throwable) { Log.e("UnderService", "Fetch session failed: ${e.message}") }
            override fun onComplete() {}
        })
    }

    private fun updateWorkerStatusFromFullSession(session: WorkSession, isOnline: Boolean) {
        val worker = session.getLCObject<Worker>("worker") ?: return
        val workerId = worker.objectId
        val index = cachedWorkerList.indexOfFirst { it.workerId == workerId }
        if (index != -1) {
            cachedWorkerList[index] = createUpdatedStatus(cachedWorkerList[index], session, isOnline)
            notifyListener()
        }
    }

    private fun createUpdatedStatus(oldStatus: WorkerStatus, session: WorkSession, isOnline: Boolean): WorkerStatus {
        val workerId = oldStatus.workerId
        return if (isOnline) {
            val status = session.getString("currentStatus") ?: "在线-正常"
            if (status.contains("异常")) addAbnormalWorker(workerId) else removeAbnormalWorker(workerId)
            oldStatus.copy(
                status = status,
                lastUpdatedAt = session.updatedAt,
                isOnline = true,
                sensor1Status = session.getString("sensor1Status"),
                sensor2Status = session.getString("sensor2Status"),
                sensor3Status = session.getString("sensor3Status"),
                sensor4Status = session.getString("sensor4Status"),
                sensor5Status = session.getString("sensor5Status"),
                sensor6Status = session.getString("sensor6Status"),
                sessionId = session.objectId // 修复：添加 sessionId
            )
        } else {
            removeAbnormalWorker(workerId)
            oldStatus.copy(status = "离线", lastUpdatedAt = Date(), isOnline = false, sessionId = null) // 修复：清空 sessionId
        }
    }

    private fun refreshWorkerList() {
        if (cachedWorkerList.isEmpty()) return
        val workerPointers = cachedWorkerList.map { LCObject.createWithoutData("Worker", it.workerId) }
        val sessionQuery = LCQuery<WorkSession>("WorkSession")
            .whereContainedIn("worker", workerPointers)
            .whereEqualTo("isOnline", true)
            .include("worker")
            .orderByDescending("updatedAt")
            .setCachePolicy(LCQuery.CachePolicy.NETWORK_ONLY)

        sessionQuery.findInBackground().subscribe(object : Observer<List<WorkSession>> {
            override fun onSubscribe(d: Disposable) {}
            override fun onNext(sessions: List<WorkSession>) {
                val latestSessionMap = sessions
                    .filter { it.getLCObject<Worker>("worker") != null }
                    .distinctBy { it.getLCObject<Worker>("worker")!!.objectId }
                    .associateBy { it.getLCObject<Worker>("worker")!!.objectId }

                val updatedStatusList = cachedWorkerList.map { oldStatus ->
                    latestSessionMap[oldStatus.workerId]?.let { createUpdatedStatus(oldStatus, it, true) } 
                        ?: oldStatus.copy(status = "离线", isOnline = false, sessionId = null).also { removeAbnormalWorker(oldStatus.workerId) } // 修复：清空 sessionId
                }
                cachedWorkerList.clear()
                cachedWorkerList.addAll(updatedStatusList)
                notifyListener()
            }
            override fun onError(e: Throwable) { Log.e("UnderService", "Refresh failed: ${e.message}") }
            override fun onComplete() {}
        })
    }

    private fun vibrate() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        vibrator?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                it.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                it.vibrate(500)
            }
        }
    }
}