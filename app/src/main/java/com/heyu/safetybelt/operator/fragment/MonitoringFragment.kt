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

    private val configuredDevices = ConcurrentHashMap<String, DeviceScanResult>()
    private val viewHolders = mutableMapOf<Int, DeviceViewHolder>()
    private val addressToTypeAndSlotMap = mutableMapOf<String, Pair<Int, Int>>()

    private var disconnectButton: Button? = null

    private var currentSessionId: String? = null
    private val compositeDisposable = CompositeDisposable()

    private val countdownHandler = Handler(Looper.getMainLooper())
    private val countdownTimers = mutableMapOf<Pair<Int, Int>, Runnable>()
    private val countdownValues = mutableMapOf<Pair<Int, Int>, Int>()

    private val alarmingDevices = ConcurrentHashMap.newKeySet<String>()
    private var currentSessionAlarmCount = 0
    private var isAlarmDialogShowing = false
    private var isAdminAlarmDialogShowing = false // Flag specifically for Admin Alarm

    private val serviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null || !isAdded) return

            when (intent.action) {
                BleService.ACTION_SHOW_ADMIN_ALERT -> {
                    if (!isAdminAlarmDialogShowing) {
                        showAdminAlertDialog()
                    }
                }
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
                                WorkRecordManager.addAlarmDetail(requireContext(), SENSOR_TYPES[sensorType] ?: "未知传感器", statusText)
                            }
                        } else {
                            if (alarmingDevices.remove(address)) {
                                WorkRecordManager.endAlarmForSensor(requireContext(), SENSOR_TYPES[sensorType] ?: "未知传感器")
                            }
                        }
                    } else { // ACTION_HEARTBEAT_UPDATE
                        val sensorId = intent.getStringExtra(BleService.EXTRA_SENSOR_ID)
                        val battery = intent.getStringExtra(BleService.EXTRA_BATTERY)
                        updateHeartbeatView(sensorType, slotIndex, "$sensorId ($battery)")
                    }

                    WorkRecordManager.updateLastActiveTime(requireContext())
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
        buildFullDeviceLayout()
        setupButtons()

        binding.backButton.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        val filter = IntentFilter().apply {
            addAction(BleService.ACTION_STATUS_UPDATE)
            addAction(BleService.ACTION_HEARTBEAT_UPDATE)
            addAction(BleService.ACTION_SHOW_ALARM_DIALOG)
            addAction(BleService.ACTION_SHOW_ADMIN_ALERT)
        }
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(serviceReceiver, filter)
    }

    override fun onResume() {
        super.onResume()
        // When the fragment becomes visible, ask the service for the latest status of all devices.
        val intent = Intent(requireContext(), BleService::class.java).apply {
            action = BleService.ACTION_REQUEST_ALL_STATUSES
        }
        requireContext().startService(intent)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(serviceReceiver)
        countdownHandler.removeCallbacksAndMessages(null)
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        compositeDisposable.clear()
    }

    fun updateDevices(newDevices: List<DeviceScanResult>) {
        if (!isAdded) return

        val newDeviceMap = newDevices.associateBy { it.deviceAddress }
        val oldDeviceMap = configuredDevices

        val devicesToRemove = oldDeviceMap.keys.filter { it !in newDeviceMap.keys }
        val devicesToAdd = newDeviceMap.filter { it.key !in oldDeviceMap.keys }.values.toList()

        if (devicesToRemove.isNotEmpty()) {
            devicesToRemove.forEach { address ->
                configuredDevices.remove(address)
                val typeAndSlot = addressToTypeAndSlotMap.remove(address)
                if (typeAndSlot != null) {
                    val (sensorType, _) = typeAndSlot
                    updateDeviceRow(sensorType, null)
                }
            }
        }

        if (devicesToAdd.isNotEmpty()) {
            devicesToAdd.forEach { device ->
                configuredDevices[device.deviceAddress] = device
                val sensorType = getSensorTypeFromDeviceName(device.bestName)
                if (sensorType != null) {
                    addressToTypeAndSlotMap[device.deviceAddress] = sensorType to 0 
                    updateDeviceRow(sensorType, device)
                }
            }
        }
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

    private fun buildFullDeviceLayout() {
        val passedDevices = getAllDevicesFromArgs()
        val deviceMap = passedDevices.associateBy { it.deviceAddress }
        configuredDevices.clear()
        configuredDevices.putAll(deviceMap)

        binding.fixedColumnContainer.removeAllViews()
        binding.scrollableDataContainer.removeAllViews()
        viewHolders.clear()
        addressToTypeAndSlotMap.clear()

        for (sensorType in 1..6) {
            val deviceForType = configuredDevices.values.find { getSensorTypeFromDeviceName(it.bestName) == sensorType }
            addDeviceRow(sensorType, deviceForType)
            if (deviceForType != null) {
                addressToTypeAndSlotMap[deviceForType.deviceAddress] = sensorType to 0 
            }
        }
    }

    private fun addDeviceRow(sensorType: Int, device: DeviceScanResult?) {
        val inflater = LayoutInflater.from(requireContext())

        val nameView = inflater.inflate(R.layout.list_item_monitoring_fixed, binding.fixedColumnContainer, false) as TextView
        nameView.text = SENSOR_TYPES[sensorType] ?: "未知设备"
        binding.fixedColumnContainer.addView(nameView)

        val rowView = inflater.inflate(R.layout.list_item_monitoring, binding.scrollableDataContainer, false)
        val btName1 = rowView.findViewById<TextView>(R.id.item_sensor_bt_name_1)
        val status1 = rowView.findViewById<TextView>(R.id.item_sensor_status_1)
        val slot1VH = SensorSlotViewHolder(btName1, status1)

        binding.scrollableDataContainer.addView(rowView)
        viewHolders[sensorType] = DeviceViewHolder(nameView, slot1VH)

        updateDeviceRow(sensorType, device)
    }

    private fun updateDeviceRow(sensorType: Int, device: DeviceScanResult?) {
        val vh = viewHolders[sensorType] ?: return
        val slot1VH = vh.slot1

        if (device != null) {
            slot1VH.btNameView.text = device.bestName
            slot1VH.statusView.text = "待连接"
            slot1VH.statusView.setTextColor(Color.GRAY)
        } else {
            slot1VH.btNameView.text = "---"
            slot1VH.statusView.text = "未配置"
            slot1VH.statusView.setTextColor(Color.LTGRAY)
        }
        updateStatusView(sensorType, 0, slot1VH.statusView.text.toString(), slot1VH.statusView.currentTextColor, false)
    }

    private fun setupButtons() {
        val buttonContainer = binding.buttonContainerLayout
        buttonContainer.removeAllViews()

        val disconnectButtonDrawable = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; setColor(Color.parseColor("#9E9E9E")); cornerRadius = 50f }

        disconnectButton = Button(requireContext()).apply {
            text = "断开所有连接"
            background = disconnectButtonDrawable
            setTextColor(Color.WHITE)
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER }
            setOnClickListener { showDisconnectConfirmationDialog() }
        }

        buttonContainer.addView(disconnectButton)
    }

    private fun showDisconnectConfirmationDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("断开所有连接")
            .setMessage("您确定要断开所有设备的连接吗？这将结束本次工作记录。")
            .setPositiveButton("确定") { _, _ ->
                // 立即触发返回逻辑，提高响应感知速度
                parentFragmentManager.popBackStack()

                // 在后台执行断开连接和保存记录的操作，使用 ApplicationContext 防止 Fragment 销毁导致 Context 失效
                val appContext = requireContext().applicationContext
                val serviceIntent = Intent(appContext, BleService::class.java).apply { action = BleService.ACTION_DISCONNECT_ALL }
                appContext.startService(serviceIntent)

                // 异步处理耗时的磁盘操作（如果有必要，WorkRecordManager 内部也可以改为异步）
                Thread {
                    WorkRecordManager.stopCurrentWork(appContext, currentSessionAlarmCount)
                }.start()
                
                alarmingDevices.clear()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showAlarmDialog(messages: List<String>) {
        if (!isAdded || messages.isEmpty()) return
        isAlarmDialogShowing = true
        val messageText = messages.joinToString("\n")
        AlertDialog.Builder(requireContext())
            .setTitle("安全警报")
            .setMessage(messageText)
            .setIcon(R.drawable.ic_dialog_warning)
            .setPositiveButton("我已知晓") { dialog, _ -> dialog.dismiss() }
            .setOnDismissListener { isAlarmDialogShowing = false }
            .show()
    }

    private fun showAdminAlertDialog() {
        if (!isAdded || isAdminAlarmDialogShowing) return
        isAdminAlarmDialogShowing = true
        AlertDialog.Builder(requireContext())
            .setTitle("远程警报")
            .setMessage("安监员向您发送了警报提醒，请立即检查安全带！")
            .setIcon(R.drawable.ic_dialog_warning)
            .setCancelable(false)
            .setPositiveButton("收到") { dialog, _ ->
                val intent = Intent(BleService.ACTION_RESET_ADMIN_ALERT)
                LocalBroadcastManager.getInstance(requireContext()).sendBroadcast(intent)
                dialog.dismiss()
            }
            .setOnDismissListener { isAdminAlarmDialogShowing = false }
            .show()
    }

    private fun handleDisconnectUI() {
        configuredDevices.keys.forEach { address ->
             addressToTypeAndSlotMap[address]?.let { (type, slot) ->
                updateStatusView(type, slot, "待连接", Color.GRAY, false)
             }
        }
        disconnectButton?.visibility = View.GONE
    }

    private fun updateStatusView(sensorType: Int, slotIndex: Int, text: String, color: Int, showIcon: Boolean) {
        if (!isAdded) return
        val slotViewHolder = viewHolders[sensorType]?.slot1 ?: return

        slotViewHolder.statusView.text = text
        slotViewHolder.statusView.setTextColor(color)
        slotViewHolder.statusView.setOnClickListener(null)

        var iconResId = 0
        val device = configuredDevices.values.find { (addressToTypeAndSlotMap[it.deviceAddress]?.first == sensorType) }

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
}
