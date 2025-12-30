package com.heyu.safetybelt.operator.fragment

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import cn.leancloud.LCObject
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.heyu.safetybelt.R
import com.heyu.safetybelt.operator.adapter.ScanDeviceAdapter
import com.heyu.safetybelt.operator.adapter.StatusDeviceAdapter
import com.heyu.safetybelt.operator.model.DeviceScanResult
import com.heyu.safetybelt.common.Device
import com.heyu.safetybelt.databinding.FragmentDetectionBinding
import com.heyu.safetybelt.operator.service.BleService
import com.heyu.safetybelt.common.WorkSession
import com.heyu.safetybelt.operator.activity.MainActivityOperator
import com.heyu.safetybelt.operator.model.WorkRecordManager
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.CompositeDisposable
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class DetectionFragment : Fragment() {

    // Safe storage class that does not contain unstable system objects.
    private data class SavedDeviceInfo(val address: String, val name: String)

    private var _binding: FragmentDetectionBinding? = null
    private val binding get() = _binding!!

    private val bluetoothAdapter: BluetoothAdapter? by lazy { BluetoothAdapter.getDefaultAdapter() }
    private val bleScanner by lazy { bluetoothAdapter?.bluetoothLeScanner }

    private val requestEnableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            checkAndPromptLocationServices()
        } else {
            Toast.makeText(requireContext(), "需要开启蓝牙才能扫描设备", Toast.LENGTH_SHORT).show()
        }
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.values.all { it }) {
                checkAndPromptLocationServices()
            } else {
                Toast.makeText(requireContext(), "必须授予所有权限才能使用蓝牙扫描功能", Toast.LENGTH_LONG).show()
            }
        }

    private lateinit var scanAdapter: ScanDeviceAdapter
    private lateinit var hbsAdapter: StatusDeviceAdapter
    private lateinit var wgdAdapter: StatusDeviceAdapter

    private var hbsDevices = CopyOnWriteArrayList<DeviceScanResult>()
    private var wgdDevices = CopyOnWriteArrayList<DeviceScanResult>()
    private var xykDevices = CopyOnWriteArrayList<DeviceScanResult>()

    private val scannedDevicesMap = ConcurrentHashMap<String, DeviceScanResult>()
    private val deviceLastSeen = ConcurrentHashMap<String, Long>()
    private val connectedDeviceAddresses = ConcurrentHashMap.newKeySet<String>()
    private val rx3CompositeDisposable = CompositeDisposable()
    private val rx2CompositeDisposable = io.reactivex.disposables.CompositeDisposable()

    private lateinit var sharedPreferences: SharedPreferences
    private val gson = Gson()
    private var currentWorkerId: String? = null // New: To track the current user.

    private lateinit var xykDeviceNameTextView: TextView
    private lateinit var xykRemoveButton: ImageButton
    private lateinit var hbsTitleTextView: TextView
    private lateinit var wgdTitleTextView: TextView

    private var isScanning = false
    private val MONITORING_FRAGMENT_TAG = "monitoring_fragment"

    private val connectionStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BleService.ACTION_CONNECTION_STATE_UPDATE) {
                val address = intent.getStringExtra(BleService.EXTRA_DEVICE_ADDRESS)
                val isConnected = intent.getBooleanExtra(BleService.EXTRA_IS_CONNECTED, false)
                if (address != null) {
                    if (isConnected) {
                        connectedDeviceAddresses.add(address)
                    } else {
                        connectedDeviceAddresses.remove(address)
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val filter = IntentFilter(BleService.ACTION_CONNECTION_STATE_UPDATE)
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(connectionStateReceiver, filter)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDetectionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val newWorkerId = requireActivity().intent.getStringExtra("workerObjectId")
        if (newWorkerId == null) {
            Toast.makeText(requireContext(), "错误: 无法获取用户ID", Toast.LENGTH_LONG).show()
            parentFragmentManager.popBackStack()
            return
        }

        if (currentWorkerId != newWorkerId) {
            Log.d("DetectionFragment", "New user detected. Old: $currentWorkerId, New: $newWorkerId. Resetting state.")
            currentWorkerId = newWorkerId
            sharedPreferences = requireActivity().getSharedPreferences("DeviceLists_$currentWorkerId", Context.MODE_PRIVATE)
            sharedPreferences.edit().clear().commit()
            hbsDevices.clear()
            wgdDevices.clear()
            xykDevices.clear()
        } else {
            Log.d("DetectionFragment", "Same user detected: $currentWorkerId. State preserved.")
        }

        setupUI()
        loadDeviceLists()
        updateAllStatusLists()
        checkAndRequestPermissions()
    }

    override fun onResume() {
        super.onResume()
        startBleScan()
    }

    override fun onDestroy() {
        super.onDestroy()
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(connectionStateReceiver)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopBleScan()
        rx3CompositeDisposable.clear()
        rx2CompositeDisposable.clear()
        _binding = null
    }

    private fun saveDeviceLists() {
        if (!::sharedPreferences.isInitialized) return

        fun deviceListToInfoList(list: List<DeviceScanResult>): List<SavedDeviceInfo> {
            return list.map { SavedDeviceInfo(it.device.address, it.bestName) }
        }

        with(sharedPreferences.edit()) {
            putString("hbs_devices", gson.toJson(deviceListToInfoList(hbsDevices)))
            putString("wgd_devices", gson.toJson(deviceListToInfoList(wgdDevices)))
            putString("xyk_devices", gson.toJson(deviceListToInfoList(xykDevices)))
            apply()
        }
    }

    private fun loadDeviceLists() {
        val adapter = bluetoothAdapter
        if (adapter == null) {
            Log.e("DetectionFragment", "Bluetooth adapter is null, cannot load devices.")
            return
        }
        if (!::sharedPreferences.isInitialized) {
            if(currentWorkerId != null) {
                sharedPreferences = requireActivity().getSharedPreferences("DeviceLists_$currentWorkerId", Context.MODE_PRIVATE)
            } else {
                return
            }
        }

        val type = object : TypeToken<List<SavedDeviceInfo>>() {}.type

        fun infoListToDeviceList(json: String?): CopyOnWriteArrayList<DeviceScanResult> {
            val savedList: List<SavedDeviceInfo> = gson.fromJson(json, type) ?: return CopyOnWriteArrayList()
            val deviceList = savedList.mapNotNull { info ->
                try {
                    val device = adapter.getRemoteDevice(info.address)
                    DeviceScanResult(device, -100, info.name) // Use a default RSSI as it's not relevant here
                } catch (e: IllegalArgumentException) {
                    Log.e("DetectionFragment", "Invalid Bluetooth address loaded from preferences: ${info.address}", e)
                    null
                }
            }
            return CopyOnWriteArrayList(deviceList)
        }

        hbsDevices = infoListToDeviceList(sharedPreferences.getString("hbs_devices", null))
        wgdDevices = infoListToDeviceList(sharedPreferences.getString("wgd_devices", null))
        xykDevices = infoListToDeviceList(sharedPreferences.getString("xyk_devices", null))
    }

    private fun addDeviceToCategory(device: DeviceScanResult) {
        val deviceAddress = device.device.address
        val alreadySelected = hbsDevices.any { it.device.address == deviceAddress } ||
                wgdDevices.any { it.device.address == deviceAddress } ||
                xykDevices.any { it.device.address == deviceAddress }

        if (alreadySelected) {
            activity?.runOnUiThread {
                if (isAdded) Toast.makeText(context, "设备 ${device.bestName} 已选择", Toast.LENGTH_SHORT).show()
            }
            return
        }

        when (getSensorTypeFromDeviceName(device.bestName)) {
            1, 2, 6 -> hbsDevices.add(device)
            3, 4 -> wgdDevices.add(device)
            5 -> {
                if (xykDevices.isEmpty()) {
                    xykDevices.add(device)
                } else {
                    activity?.runOnUiThread {
                        if (isAdded) Toast.makeText(context, "腰扣设备只能选择一个", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            else -> activity?.runOnUiThread {
                if (isAdded) Toast.makeText(context, "未知设备类型: ${device.bestName}", Toast.LENGTH_SHORT).show()
            }
        }
        scannedDevicesMap.remove(device.device.address)
        updateScanResultsUI()
        updateAllStatusLists()
        saveDeviceLists()
    }

    private fun handleRemoveFromHbs(device: DeviceScanResult) {
        hbsDevices.remove(device)
        updateAllStatusLists()
        saveDeviceLists()
        disconnectDeviceImmediately(device.device.address)
    }

    private fun handleRemoveFromWgd(device: DeviceScanResult) {
        wgdDevices.remove(device)
        updateAllStatusLists()
        saveDeviceLists()
        disconnectDeviceImmediately(device.device.address)
    }

    private fun handleRemoveFromXyk() {
        if (xykDevices.isNotEmpty()) {
            val address = xykDevices.first().device.address
            xykDevices.clear()
            updateAllStatusLists()
            saveDeviceLists()
            disconnectDeviceImmediately(address)
        }
    }

    private fun disconnectDeviceImmediately(address: String) {
        // Only attempt to disconnect if a session is actually active
        if (BleService.currentSessionId != null) {
            val intent = Intent(requireContext(), BleService::class.java).apply {
                action = BleService.ACTION_DISCONNECT_SPECIFIC
                putStringArrayListExtra(BleService.EXTRA_DEVICES_TO_DISCONNECT, arrayListOf(address))
            }
            requireContext().startService(intent)
        }
    }

    private fun updateAllStatusLists() {
        if (!isAdded || _binding == null) return
        updateHbsList()
        updateWgdList()
        updateXykList()
    }

    private fun updateHbsList() {
        hbsAdapter.updateList(hbsDevices.toList())
        hbsTitleTextView.text = "后背绳设备 (${hbsDevices.size})"
    }

    private fun updateWgdList() {
        wgdAdapter.updateList(wgdDevices.toList())
        wgdTitleTextView.text = "围杆带设备 (${wgdDevices.size})"
    }

    private fun updateXykList() {
        if (xykDevices.isNotEmpty()) {
            xykDeviceNameTextView.text = xykDevices.first().bestName
            xykDeviceNameTextView.visibility = View.VISIBLE
            xykRemoveButton.visibility = View.VISIBLE
        } else {
            xykDeviceNameTextView.text = "未选择"
            xykDeviceNameTextView.visibility = View.GONE
            xykRemoveButton.visibility = View.GONE
        }
    }

    private fun setupUI() {
        scanAdapter = ScanDeviceAdapter { device -> addDeviceToCategory(device) }
        binding.devicesRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.devicesRecyclerView.adapter = scanAdapter

        hbsAdapter = StatusDeviceAdapter { device -> handleRemoveFromHbs(device) }
        binding.hbsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.hbsRecyclerView.adapter = hbsAdapter

        wgdAdapter = StatusDeviceAdapter { device -> handleRemoveFromWgd(device) }
        binding.wgdRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.wgdRecyclerView.adapter = wgdAdapter

        xykDeviceNameTextView = binding.xykDeviceName
        xykRemoveButton = binding.xykRemoveButton
        hbsTitleTextView = binding.hbsStatusBoxTitle
        wgdTitleTextView = binding.wgdStatusBoxTitle

        xykRemoveButton.setOnClickListener { handleRemoveFromXyk() }

        binding.scanButton.text = "确定"
        binding.scanButton.backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.darkblue)
        binding.scanButton.setOnClickListener { navigateOrUpdateMonitoring() }
    }

    private fun navigateOrUpdateMonitoring() {
        stopBleScan()
        saveDeviceLists()

        val allSelectedDevices = ArrayList<DeviceScanResult>().apply {
            addAll(hbsDevices)
            addAll(wgdDevices)
            addAll(xykDevices)
        }

        if (allSelectedDevices.isEmpty()) {
            Toast.makeText(requireContext(), "请至少选择一个设备", Toast.LENGTH_SHORT).show()
            return
        }

        val currentSessionId = BleService.currentSessionId
        if (currentSessionId == null) {
            // Case 1: First time - Start Session and Connect
            Log.d("DetectionFragment", "No active session found. Starting new cloud work.")
            Toast.makeText(requireContext(), "正在启动云端作业...", Toast.LENGTH_SHORT).show()

            startWorkSession(
                onSessionStarted = { sessionId ->
                    WorkRecordManager.startNewWork(requireContext())

                    val serviceIntent = Intent(requireContext(), BleService::class.java).apply {
                        action = BleService.ACTION_CONNECT_DEVICES
                        putParcelableArrayListExtra(BleService.EXTRA_DEVICES, allSelectedDevices)
                        putExtra(BleService.EXTRA_SESSION_ID, sessionId)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        requireContext().startForegroundService(serviceIntent)
                    } else {
                        requireContext().startService(serviceIntent)
                    }

                    navigateToMonitoring(allSelectedDevices)
                },
                onSessionFailed = { error ->
                    activity?.runOnUiThread {
                        Toast.makeText(requireContext(), "启动云端作业失败: ${error.message}", Toast.LENGTH_LONG).show()
                    }
                }
            )
        } else {
            // Case 2: Session exists - Update and Navigate
            Log.d("DetectionFragment", "Active session found: $currentSessionId. Updating devices.")

            // Tell the service to connect any NEW devices in the list
            val serviceIntent = Intent(requireContext(), BleService::class.java).apply {
                action = BleService.ACTION_CONNECT_SPECIFIC
                putParcelableArrayListExtra(BleService.EXTRA_DEVICES, allSelectedDevices)
            }
            requireContext().startService(serviceIntent)

            val monitoringFragment = parentFragmentManager.findFragmentByTag(MONITORING_FRAGMENT_TAG) as? MonitoringFragment
            if (monitoringFragment != null && monitoringFragment.isAdded) {
                monitoringFragment.updateDevices(allSelectedDevices)
                parentFragmentManager.popBackStack()
            } else {
                navigateToMonitoring(allSelectedDevices)
            }
        }
    }

    private fun navigateToMonitoring(devices: ArrayList<DeviceScanResult>) {
        val newMonitoringFragment = MonitoringFragment().apply {
            arguments = Bundle().apply {
                putParcelableArrayList("hbs_devices", ArrayList(hbsDevices))
                putParcelableArrayList("wgd_devices", ArrayList(wgdDevices))
                putParcelableArrayList("xyk_devices", ArrayList(xykDevices))
            }
        }
        parentFragmentManager.beginTransaction()
            .replace(R.id.safetybelt_fragment_container, newMonitoringFragment, MONITORING_FRAGMENT_TAG)
            .addToBackStack(MONITORING_FRAGMENT_TAG)
            .commit()
    }

    private fun startWorkSession(onSessionStarted: (sessionId: String) -> Unit, onSessionFailed: (error: Throwable) -> Unit) {
        val workerObjectId = (activity as? MainActivityOperator)?.workerObjectId
        if (workerObjectId == null) {
            onSessionFailed(IllegalStateException("未能获取到工人ID，请重新登录或重启应用。"))
            return
        }

        val allDevices = ArrayList<DeviceScanResult>().apply {
            addAll(hbsDevices)
            addAll(wgdDevices)
            addAll(xykDevices)
        }
        val deviceNameList = allDevices.map { it.bestName }

        val workSession = WorkSession().apply {
            put("worker", LCObject.createWithoutData("Worker", workerObjectId))
            startTime = Date()
            totalAlarmCount = 0
            isOnline = true
            currentStatus = "正在连接"
            put("deviceList", deviceNameList)
        }

        workSession.saveInBackground().subscribe(object : io.reactivex.Observer<LCObject> {
            override fun onSubscribe(d: io.reactivex.disposables.Disposable) { rx2CompositeDisposable.add(d) }
            override fun onNext(t: LCObject) { onSessionStarted(t.objectId) }
            override fun onError(e: Throwable) { onSessionFailed(e) }
            override fun onComplete() {}
        })
    }


    private fun checkAndRequestPermissions() {
        val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        } else {
            checkAndPromptLocationServices()
        }
    }

    private fun checkAndPromptLocationServices() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            val locationManager = requireContext().getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
            val isGpsEnabled = locationManager?.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) == true
            val isNetworkEnabled = locationManager?.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER) == true

            if (!isGpsEnabled && !isNetworkEnabled) {
                AlertDialog.Builder(requireContext())
                    .setTitle("定位服务已关闭")
                    .setMessage("蓝牙扫描需要开启定位服务。")
                    .setPositiveButton("前往设置") { dialog, _ ->
                        startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                        dialog.dismiss()
                    }
                    .setNegativeButton("取消", null)
                    .show()
                return
            }
        }
        checkBluetoothStateAndScan()
    }

    private fun checkBluetoothStateAndScan() {
        val adapter = bluetoothAdapter
        if (adapter == null) {
            Toast.makeText(requireContext(), "该设备不支持蓝牙", Toast.LENGTH_LONG).show()
            return
        }

        if (adapter.isEnabled) {
            startBleScan()
        } else {
            requestEnableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBleScan() {
        if (!isScanning) {
            if (bleScanner == null) {
                Toast.makeText(requireContext(), "无法初始化蓝牙扫描器", Toast.LENGTH_SHORT).show()
                return
            }
            scannedDevicesMap.clear()
            deviceLastSeen.clear()
            scanAdapter.updateList(emptyList())

            val scanSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            isScanning = true
            bleScanner?.startScan(null, scanSettings, bleScanCallback)

            val scanUpdateDisposable = Observable.interval(2, 2, TimeUnit.SECONDS)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe { updateScanResultsUI() }
            rx3CompositeDisposable.add(scanUpdateDisposable)
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopBleScan() {
        if (isScanning) {
            bleScanner?.stopScan(bleScanCallback)
            isScanning = false
            rx3CompositeDisposable.clear()
        }
    }

    private val bleScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.let { processScanResult(it) }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { processScanResult(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            activity?.runOnUiThread {
                if (isAdded) Toast.makeText(requireContext(), "蓝牙扫描失败，错误码: $errorCode", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun processScanResult(result: ScanResult) {
        val deviceAddress = result.device.address
        val deviceName = result.scanRecord?.deviceName
        val rssi = result.rssi

        if (deviceName.isNullOrEmpty() || !deviceName.contains("Sensor", ignoreCase = true)) {
            return
        }

        deviceLastSeen[deviceAddress] = System.currentTimeMillis()

        val isAlreadySelected = hbsDevices.any { it.device.address == deviceAddress } ||
                wgdDevices.any { it.device.address == deviceAddress } ||
                xykDevices.any { it.device.address == deviceAddress }

        if (isAlreadySelected) {
            scannedDevicesMap.remove(deviceAddress)
            return
        }

        val existingDevice = scannedDevicesMap[deviceAddress]
        if (existingDevice != null) {
            if (rssi > existingDevice.rssi) {
                existingDevice.rssi = rssi
            }
        } else {
            scannedDevicesMap[deviceAddress] = DeviceScanResult(result.device, rssi, deviceName)
        }
    }

    private fun updateScanResultsUI() {
        if (!isAdded || _binding == null) {
            return
        }
        val currentTime = System.currentTimeMillis()

        scannedDevicesMap.entries.removeIf { (address, _) ->
            (currentTime - (deviceLastSeen[address] ?: currentTime)) > 5000
        }

        val sortedList = scannedDevicesMap.values.sortedByDescending { it.rssi }
        scanAdapter.updateList(sortedList)

        // Only auto-remove if NOT connected (don't remove devices we are currently using)
        var listChanged = false
        val staleDevicePredicate: (DeviceScanResult) -> Boolean = { device ->
            val notSeenRecently = (currentTime - (deviceLastSeen[device.device.address] ?: 0)) > 5000
            val notConnected = !connectedDeviceAddresses.contains(device.device.address)
            notSeenRecently && notConnected
        }

        if (hbsDevices.removeIf(staleDevicePredicate)) listChanged = true
        if (wgdDevices.removeIf(staleDevicePredicate)) listChanged = true
        if (xykDevices.removeIf(staleDevicePredicate)) listChanged = true

        if (listChanged) {
            activity?.runOnUiThread {
                if (isAdded) {
                    updateAllStatusLists()
                    saveDeviceLists()
                }
            }
        }
    }

    private fun getSensorTypeFromDeviceName(deviceName: String): Int? {
        return deviceName.split(" ").getOrNull(1)?.split("_")?.getOrNull(0)?.toIntOrNull()
    }
}
