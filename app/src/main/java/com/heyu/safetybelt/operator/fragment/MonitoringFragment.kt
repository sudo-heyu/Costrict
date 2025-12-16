package com.heyu.safetybelt.operator.fragment

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import cn.leancloud.LCObject
import com.heyu.safetybelt.R
import com.heyu.safetybelt.operator.model.WorkRecordManager
import com.heyu.safetybelt.operator.activity.MainActivityOperator
import com.heyu.safetybelt.operator.model.DeviceScanResult
import com.heyu.safetybelt.common.WorkSession
import com.heyu.safetybelt.operator.service.BleService
import com.heyu.safetybelt.databinding.FragmentMonitoringWorkerBinding
import io.reactivex.disposables.CompositeDisposable
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

// Updated ViewHolder structure for the new layout
data class SensorSlotViewHolder(val btNameView: TextView, val statusView: TextView)
data class DeviceViewHolder(val nameView: TextView, val slot1: SensorSlotViewHolder)

class MonitoringFragment : Fragment() {

    private var _binding: FragmentMonitoringWorkerBinding? = null
    private val binding get() = _binding!!

    private val configuredDevices = mutableMapOf<Int, MutableList<DeviceScanResult>>()
    private val viewHolders = mutableMapOf<Int, DeviceViewHolder>()
    private val addressToTypeAndSlotMap = mutableMapOf<String, Pair<Int, Int>>()

    private var startButton: Button? = null
    private var disconnectButton: Button? = null

    private var currentSessionId: String? = null
    private val compositeDisposable = CompositeDisposable()

    private val countdownHandler = Handler(Looper.getMainLooper())
    private val countdownTimers = mutableMapOf<Pair<Int, Int>, Runnable>()
    private val countdownValues = mutableMapOf<Pair<Int, Int>, Int>()

    private val alarmingDevices = ConcurrentHashMap.newKeySet<String>()
    private var currentSessionAlarmCount = 0
    private var isAlarmDialogShowing = false

    private val serviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return

            when (intent.action) {
                BleService.ACTION_SHOW_ALARM_DIALOG -> {
                    if (!isAlarmDialogShowing) {
                        val messages = intent.getStringArrayListExtra(BleService.EXTRA_ALARM_MESSAGES)
                        if (!messages.isNullOrEmpty()) {
                            showAlarmDialog(messages)
                        }
                    }
                }
                BleService.ACTION_STATUS_UPDATE, BleService.ACTION_HEARTBEAT_UPDATE -> {
                    val address = intent.getStringExtra(BleService.EXTRA_DEVICE_ADDRESS) ?: return
                    val typeAndSlot = addressToTypeAndSlotMap[address]
                    if (typeAndSlot == null) {
                        Log.w(TAG, "Received update for a device not in the current configuration: $address")
                        return
                    }
                    val (sensorType, slotIndex) = typeAndSlot

                    if (intent.action == BleService.ACTION_STATUS_UPDATE) {
                        val statusText = intent.getStringExtra(BleService.EXTRA_TEXT) ?: ""
                        val colorName = intent.getStringExtra(BleService.EXTRA_COLOR) ?: "BLACK"
                        val showIcon = intent.getBooleanExtra(BleService.EXTRA_SHOW_ICON, false)

                        val color = when(colorName) {
                            "RED" -> Color.RED
                            "GREEN" -> Color.GREEN
                            "BLUE" -> Color.BLUE
                            "GRAY" -> Color.GRAY
                            "YELLOW" -> Color.YELLOW
                            else -> Color.BLACK
                        }

                        if (statusText == "单挂") {
                            startCountdown(sensorType, slotIndex)
                        } else {
                            stopCountdown(sensorType, slotIndex)
                            updateStatusView(sensorType, slotIndex, statusText, color, showIcon)
                        }

                        if (statusText == "异常") {
                            if (alarmingDevices.add(address)) {
                                currentSessionAlarmCount++
                                if (context != null) {
                                    val sensorName = SENSOR_TYPES[sensorType] ?: "未知传感器"
                                    WorkRecordManager.addAlarmDetail(context, sensorName, statusText)
                                }
                            }
                        } else {
                            if (alarmingDevices.remove(address)) {
                                if (context != null) {
                                    val sensorName = SENSOR_TYPES[sensorType] ?: "未知传感器"
                                    WorkRecordManager.endAlarmForSensor(context, sensorName)
                                }
                            }
                        }
                    } else { // ACTION_HEARTBEAT_UPDATE
                        val sensorId = intent.getStringExtra(BleService.EXTRA_SENSOR_ID)
                        val battery = intent.getStringExtra(BleService.EXTRA_BATTERY)
                        updateHeartbeatView(sensorType, slotIndex, "$sensorId ($battery)")
                    }

                    if (context != null) {
                        WorkRecordManager.updateLastActiveTime(context)
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "MonitoringFragment"
        private const val SINGLE_HOOK_COUNTDOWN_SECONDS = 20
        val SENSOR_TYPES = mapOf(1 to "后背绳高挂", 2 to "后背绳小钩", 3 to "围杆带环抱", 4 to "围杆带钩", 5 to "胸腰扣", 6 to "后背绳大钩")
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMonitoringWorkerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupDeviceList()
        setupButtons()
        binding.backButton.setOnClickListener { parentFragmentManager.popBackStack() }

        val filter = IntentFilter().apply {
            addAction(BleService.ACTION_STATUS_UPDATE)
            addAction(BleService.ACTION_HEARTBEAT_UPDATE)
            addAction(BleService.ACTION_SHOW_ALARM_DIALOG)
        }
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(serviceReceiver, filter)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(serviceReceiver)
        countdownHandler.removeCallbacksAndMessages(null)
        
        // The following lines are commented out to persist the session and connection
        // endWorkSession()
        // val serviceIntent = Intent(requireContext(), BleService::class.java).apply {
        //     action = BleService.ACTION_DISCONNECT_ALL
        // }
        // requireContext().startService(serviceIntent)

        compositeDisposable.clear()
        _binding = null
    }

    private fun startCountdown(sensorType: Int, slotIndex: Int) {
        val key = sensorType to slotIndex
        if (countdownTimers.containsKey(key)) return
        countdownValues[key] = SINGLE_HOOK_COUNTDOWN_SECONDS
        val runnable = object : Runnable {
            override fun run() {
                val remaining = countdownValues.getOrDefault(key, 0) - 1
                if (remaining >= 0) {
                    countdownValues[key] = remaining
                    updateStatusView(sensorType, slotIndex, "单挂 (${remaining}s)", Color.BLACK, true)
                    countdownHandler.postDelayed(this, 1000L)
                } else {
                    stopCountdown(sensorType, slotIndex)
                }
            }
        }
        countdownTimers[key] = runnable
        updateStatusView(sensorType, slotIndex, "单挂 (${SINGLE_HOOK_COUNTDOWN_SECONDS}s)", Color.BLACK, true)
        countdownHandler.postDelayed(runnable, 1000L)
    }

    private fun stopCountdown(sensorType: Int, slotIndex: Int) {
        val key = sensorType to slotIndex
        countdownTimers.remove(key)?.let { countdownHandler.removeCallbacks(it) }
        countdownValues.remove(key)
    }

    private fun setupDeviceList() {
        val passedDevices = getAllDevicesFromArgs()
        val deviceMap = passedDevices.groupBy { getSensorTypeFromDeviceName(it.bestName) }

        binding.fixedColumnContainer.removeAllViews()
        binding.scrollableDataContainer.removeAllViews()
        configuredDevices.clear()
        viewHolders.clear()
        addressToTypeAndSlotMap.clear()

        for (sensorType in 1..6) {
            val devicesForType = deviceMap[sensorType]?.take(1)
            if (!devicesForType.isNullOrEmpty()) {
                configuredDevices[sensorType] = devicesForType.toMutableList()
                devicesForType.forEachIndexed { index, device ->
                    addressToTypeAndSlotMap[device.deviceAddress] = sensorType to index
                }
            }
            addDeviceRow(sensorType, devicesForType)
        }
    }

    private fun addDeviceRow(sensorType: Int, devices: List<DeviceScanResult>?) {
        val inflater = LayoutInflater.from(requireContext())

        val nameView = inflater.inflate(R.layout.list_item_monitoring_fixed, binding.fixedColumnContainer, false) as TextView
        nameView.text = SENSOR_TYPES[sensorType] ?: "未知设备"
        binding.fixedColumnContainer.addView(nameView)

        val rowView = inflater.inflate(R.layout.list_item_monitoring, binding.scrollableDataContainer, false)
        val btName1 = rowView.findViewById<TextView>(R.id.item_sensor_bt_name_1)
        val status1 = rowView.findViewById<TextView>(R.id.item_sensor_status_1)

        val slot1VH = SensorSlotViewHolder(btName1, status1)

        val device1 = devices?.getOrNull(0)
        if (device1 != null) {
            slot1VH.btNameView.text = device1.bestName
            slot1VH.statusView.text = "待连接"
            slot1VH.statusView.setTextColor(Color.GRAY)
        } else {
            slot1VH.btNameView.text = "---"
            slot1VH.statusView.text = "未配置"
            slot1VH.statusView.setTextColor(Color.LTGRAY)
        }

        binding.scrollableDataContainer.addView(rowView)
        viewHolders[sensorType] = DeviceViewHolder(nameView, slot1VH)
    }

    private fun setupButtons() {
        if (configuredDevices.isEmpty()) {
            displayEmptyDeviceMessage()
            return
        }

        val buttonContainer = binding.buttonContainerLayout

        val startButtonDrawable = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; setColor(Color.parseColor("#2196F3")); cornerRadius = 50f }
        val disconnectButtonDrawable = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; setColor(Color.parseColor("#9E9E9E")); cornerRadius = 50f }

        startButton = Button(requireContext()).apply {
            text = "开始连接"
            background = startButtonDrawable
            setTextColor(Color.WHITE)
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER }
            setOnClickListener {
                it.visibility = View.GONE
                disconnectButton?.visibility = View.VISIBLE
                Toast.makeText(requireContext(), "正在启动云端作业...", Toast.LENGTH_SHORT).show()

                WorkRecordManager.startNewWork(requireContext())
                currentSessionAlarmCount = 0

                startWorkSession(
                    onSessionStarted = { sessionId ->
                        currentSessionId = sessionId
                        val allDevices = ArrayList(configuredDevices.values.flatten())
                        val serviceIntent = Intent(requireContext(), BleService::class.java).apply {
                            action = BleService.ACTION_CONNECT_DEVICES
                            putParcelableArrayListExtra(BleService.EXTRA_DEVICES, allDevices)
                            putExtra(BleService.EXTRA_SESSION_ID, currentSessionId)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            requireContext().startForegroundService(serviceIntent)
                        } else {
                            requireContext().startService(serviceIntent)
                        }
                        configuredDevices.forEach { (type, deviceList) ->
                            deviceList.forEachIndexed { index, _ ->
                                updateStatusView(type, index, "连接中...", Color.BLUE, true)
                            }
                        }
                        Toast.makeText(requireContext(), "云端作业已启动，正在连接设备。", Toast.LENGTH_SHORT).show()
                    },
                    onSessionFailed = { error ->
                        activity?.runOnUiThread {
                            Toast.makeText(requireContext(), "启动云端作业失败: ${error.message}", Toast.LENGTH_LONG).show()
                            handleDisconnectUI()
                        }
                    }
                )
            }
        }

        disconnectButton = Button(requireContext()).apply {
            text = "断开所有连接"
            visibility = View.GONE
            background = disconnectButtonDrawable
            setTextColor(Color.WHITE)
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER }
            setOnClickListener { showDisconnectConfirmationDialog() }
        }

        buttonContainer.addView(startButton)
        buttonContainer.addView(disconnectButton)
    }

    private fun startWorkSession(onSessionStarted: (sessionId: String) -> Unit, onSessionFailed: (error: Throwable) -> Unit) {
        val workerObjectId = (activity as? MainActivityOperator)?.workerObjectId
        if (workerObjectId == null) {
            onSessionFailed(IllegalStateException("未能获取到工人ID，请重新登录或重启应用。"))
            return
        }

        val deviceNameList = configuredDevices.values.flatten().map { it.bestName }
        val workSession = WorkSession().apply {
            put("worker", LCObject.createWithoutData("Worker", workerObjectId))
            startTime = Date()
            totalAlarmCount = 0
            isOnline = true
            currentStatus = "正在连接"
            put("deviceList", deviceNameList)
        }

        workSession.saveInBackground().subscribe(object : io.reactivex.Observer<LCObject> {
            override fun onSubscribe(d: io.reactivex.disposables.Disposable) { compositeDisposable.add(d) }
            override fun onNext(t: LCObject) { onSessionStarted(t.objectId) }
            override fun onError(e: Throwable) { onSessionFailed(e) }
            override fun onComplete() {}
        })
    }

    private fun endWorkSession() {
        if (currentSessionId == null) {
            Log.w(TAG, "endWorkSession called but currentSessionId is null.")
            return
        }

        val session = LCObject.createWithoutData("WorkSession", currentSessionId!!)
        session.put("endTime", Date())
        session.put("isOnline", false)
        session.put("totalAlarmCount", currentSessionAlarmCount)

        session.saveInBackground().subscribe(object : io.reactivex.Observer<LCObject> {
            override fun onSubscribe(d: io.reactivex.disposables.Disposable) { compositeDisposable.add(d) }
            override fun onNext(t: LCObject) {
                Log.d(TAG, "Successfully ended work session: ${t.objectId}")
            }
            override fun onError(e: Throwable) {
                Log.e(TAG, "Failed to end work session: $currentSessionId", e)
            }
            override fun onComplete() {
                currentSessionId = null
                currentSessionAlarmCount = 0
            }
        })
    }

    private fun showDisconnectConfirmationDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("断开连接")
            .setMessage("您确定要断开所有设备的连接吗？")
            .setPositiveButton("确定") { _, _ ->
                // End cloud session first
                endWorkSession()

                // Then disconnect BLE devices
                val serviceIntent = Intent(requireContext(), BleService::class.java).apply { action = BleService.ACTION_DISCONNECT_ALL }
                requireContext().startService(serviceIntent)

                WorkRecordManager.stopCurrentWork(requireContext(), currentSessionAlarmCount)
                alarmingDevices.clear()
                handleDisconnectUI()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showAlarmDialog(messages: List<String>) {
        if (!isAdded || messages.isEmpty()) return
        isAlarmDialogShowing = true

        val messageText = messages.joinToString("")

        AlertDialog.Builder(requireContext())
            .setTitle("安全警报")
            .setMessage(messageText)
            .setIcon(R.drawable.ic_dialog_warning)
            .setPositiveButton("我已知晓") { dialog, _ ->
                dialog.dismiss()
            }
            .setOnDismissListener { isAlarmDialogShowing = false }
            .show()
    }

    private fun handleDisconnectUI() {
        for ((sensorType, deviceList) in configuredDevices) {
            deviceList.forEachIndexed { index, _ ->
                updateStatusView(sensorType, index, "待连接", Color.GRAY, false)
            }
        }
        disconnectButton?.visibility = View.GONE
        startButton?.visibility = View.VISIBLE
    }

    private fun updateStatusView(sensorType: Int, slotIndex: Int, text: String, color: Int, showIcon: Boolean) {
        if (!isAdded) return
        val slotViewHolder = viewHolders[sensorType]?.slot1 ?: return


        slotViewHolder.statusView.text = text
        slotViewHolder.statusView.setTextColor(color)
        slotViewHolder.statusView.setOnClickListener(null)

        var iconResId = 0
        val device = configuredDevices[sensorType]?.getOrNull(slotIndex)

        if (text == "重连失败" && device != null) {
            iconResId = R.drawable.change
            slotViewHolder.statusView.setOnClickListener {
                if (!isAdded) return@setOnClickListener
                val serviceIntent = Intent(requireContext(), BleService::class.java).apply {
                    action = BleService.ACTION_RETRY_CONNECTION
                    putExtra(BleService.EXTRA_DEVICE_ADDRESS, device.deviceAddress)
                }
                requireContext().startService(serviceIntent)
                updateStatusView(sensorType, slotIndex, "连接中...", Color.BLUE, true)
            }
        } else if (showIcon) {
            iconResId = when {
                text.startsWith("单挂") -> R.drawable.ic_status_single_hook
                text == "正常" -> R.drawable.ic_status_normal
                text == "异常" -> R.drawable.ic_status_abnormal
                text == "未配置" -> R.drawable.ic_status_unconfigured
                text.contains("连接中") || text == "已连接" || text.contains("订阅中") -> R.drawable.ic_status_connecting
                text == "等待数据" -> R.drawable.ic_status_timeout
                else -> 0
            }
        }

        if (iconResId != 0) {
            ContextCompat.getDrawable(requireContext(), iconResId)?.let {
                val size = slotViewHolder.statusView.lineHeight
                it.setBounds(0, 0, size, size)
                slotViewHolder.statusView.setCompoundDrawables(it, null, null, null)
                slotViewHolder.statusView.compoundDrawablePadding = 16
            }
        } else {
            slotViewHolder.statusView.setCompoundDrawables(null, null, null, null)
        }
    }

    private fun updateHeartbeatView(sensorType: Int, slotIndex: Int, text: String) {
        if (!isAdded) return
        val slotViewHolder = viewHolders[sensorType]?.slot1 ?: return
        slotViewHolder.btNameView.text = text
    }

    private fun getSensorTypeFromDeviceName(deviceName: String): Int? = deviceName.split(" ").getOrNull(1)?.split("_")?.getOrNull(0)?.toIntOrNull()
    private fun getAllDevicesFromArgs(): List<DeviceScanResult> = arguments?.getParcelableArrayList<DeviceScanResult>("hbs_devices")?.plus(arguments?.getParcelableArrayList("wgd_devices") ?: emptyList())?.plus(arguments?.getParcelableArrayList("xyk_devices") ?: emptyList()) ?: emptyList()
    private fun displayEmptyDeviceMessage() {
        binding.tableBodyContainer.addView(TextView(requireContext()).apply {
            text = "未选择任何设备"
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
            setPadding(64, 64, 64, 64)
        })
    }
}
