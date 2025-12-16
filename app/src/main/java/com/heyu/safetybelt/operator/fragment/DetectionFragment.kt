package com.heyu.safetybelt.operator.fragment

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
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
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.CompositeDisposable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class DetectionFragment : Fragment() {

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
    private val rx3CompositeDisposable = CompositeDisposable()
    private val rx2CompositeDisposable = io.reactivex.disposables.CompositeDisposable()

    private lateinit var sharedPreferences: SharedPreferences
    private val gson = Gson()

    private lateinit var xykDeviceNameTextView: TextView
    private lateinit var xykRemoveButton: ImageButton
    private lateinit var hbsTitleTextView: TextView
    private lateinit var wgdTitleTextView: TextView

    private var isScanning = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDetectionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        sharedPreferences = requireActivity().getSharedPreferences("DeviceLists", Context.MODE_PRIVATE)

        setupUI()
        loadDeviceLists()
        updateAllStatusLists()

        checkAndRequestPermissions() // This will trigger the automatic scan
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopBleScan() // Only stop scanning, do not clear data
        rx3CompositeDisposable.clear()
        rx2CompositeDisposable.clear()
        _binding = null
    }

    private fun saveDeviceLists() {
        with(sharedPreferences.edit()) {
            putString("hbs_devices", gson.toJson(hbsDevices))
            putString("wgd_devices", gson.toJson(wgdDevices))
            putString("xyk_devices", gson.toJson(xykDevices))
            apply()
        }
    }

    private fun loadDeviceLists() {
        val typeHbs = object : TypeToken<CopyOnWriteArrayList<DeviceScanResult>>() {}.type
        sharedPreferences.getString("hbs_devices", null)?.let { hbsDevices = gson.fromJson(it, typeHbs) }

        val typeWgd = object : TypeToken<CopyOnWriteArrayList<DeviceScanResult>>() {}.type
        sharedPreferences.getString("wgd_devices", null)?.let { wgdDevices = gson.fromJson(it, typeWgd) }

        val typeXyk = object : TypeToken<CopyOnWriteArrayList<DeviceScanResult>>() {}.type
        sharedPreferences.getString("xyk_devices", null)?.let { xykDevices = gson.fromJson(it, typeXyk) }
    }

    private fun addDeviceToCategory(device: DeviceScanResult) {
        val deviceAddress = device.device.address
        val alreadySelected = hbsDevices.any { it.device.address == deviceAddress } ||
                wgdDevices.any { it.device.address == deviceAddress } ||
                xykDevices.any { it.device.address == deviceAddress }

        if (alreadySelected) {
            activity?.runOnUiThread {
                Toast.makeText(context, "设备 ${device.bestName} 已选择", Toast.LENGTH_SHORT).show()
            }
            return
        }

        val previousHbsCount = hbsDevices.size
        val previousWgdCount = wgdDevices.size
        val previousXykCount = xykDevices.size

        when (getSensorTypeFromDeviceName(device.bestName)) {
            1, 2, 6 -> hbsDevices.add(device)
            3, 4 -> wgdDevices.add(device)
            5 -> {
                if (xykDevices.isEmpty()) {
                    xykDevices.add(device)
                } else {
                    activity?.runOnUiThread {
                        Toast.makeText(context, "胸腰扣设备只能选择一个", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            else -> activity?.runOnUiThread {
                Toast.makeText(context, "未知设备类型: ${device.bestName}", Toast.LENGTH_SHORT).show()
            }
        }

        val wasAdded = hbsDevices.size > previousHbsCount ||
                wgdDevices.size > previousWgdCount ||
                xykDevices.size > previousXykCount

        if (wasAdded) {
            scannedDevicesMap.remove(device.device.address)
            // DO NOT remove from deviceLastSeen, as this timestamp is needed to prevent it from being immediately removed.
            updateScanResultsUI()
        }

        updateAllStatusLists()
        saveDeviceLists()
    }

    private fun handleRemoveFromHbs(device: DeviceScanResult) {
        hbsDevices.remove(device)
        updateAllStatusLists()
        saveDeviceLists()
    }

    private fun handleRemoveFromWgd(device: DeviceScanResult) {
        wgdDevices.remove(device)
        updateAllStatusLists()
        saveDeviceLists()
    }

    private fun handleRemoveFromXyk() {
        if (xykDevices.isNotEmpty()) {
            xykDevices.clear()
            updateAllStatusLists()
            saveDeviceLists()
        }
    }

    private fun updateAllStatusLists() {
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

        // --- Modified Button Logic ---
        binding.scanButton.text = "确定"
        binding.scanButton.backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.darkblue)
        binding.scanButton.setOnClickListener { stopBleScan(navigateToMonitoring = true) }
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

            val scanUpdateDisposable = Observable.interval(5, 5, TimeUnit.SECONDS)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe { updateScanResultsUI() }
            rx3CompositeDisposable.add(scanUpdateDisposable)
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopBleScan(navigateToMonitoring: Boolean = false) {
        if (isScanning) {
            bleScanner?.stopScan(bleScanCallback)
            isScanning = false
            rx3CompositeDisposable.clear()
        }

        if (navigateToMonitoring) {
            saveDevicesToCloud()
            val monitoringFragment = MonitoringFragment()
            val bundle = Bundle()
            bundle.putParcelableArrayList("hbs_devices", ArrayList(hbsDevices))
            bundle.putParcelableArrayList("wgd_devices", ArrayList(wgdDevices))
            bundle.putParcelableArrayList("xyk_devices", ArrayList(xykDevices))
            monitoringFragment.arguments = bundle

            parentFragmentManager.beginTransaction()
                .replace(R.id.safetybelt_fragment_container, monitoringFragment)
                .addToBackStack(null)
                .commit()
        }
    }

    private fun saveDevicesToCloud() {
        val allDevices = mutableListOf<DeviceScanResult>()
        allDevices.addAll(hbsDevices)
        allDevices.addAll(wgdDevices)
        allDevices.addAll(xykDevices)

        if (allDevices.isEmpty()) {
            return
        }

        val devicesToSave = allDevices.map { scanResult ->
            val device = Device()
            device.macAddress = scanResult.device.address
            device.bestName = scanResult.bestName
            device.sensorType = getSensorTypeFromDeviceName(scanResult.bestName) ?: 0
            device
        }

        devicesToSave.forEach { device ->
            device.saveInBackground().subscribe(object : io.reactivex.Observer<LCObject> {
                override fun onSubscribe(d: io.reactivex.disposables.Disposable) {
                    rx2CompositeDisposable.add(d)
                }
                override fun onNext(t: LCObject) {
                    Log.d("DetectionFragment", "Device ${device.bestName} saved successfully.")
                }
                override fun onError(e: Throwable) {
                    Log.e("DetectionFragment", "Failed to save device ${device.bestName}", e)
                    activity?.runOnUiThread {
                        Toast.makeText(context, "保存设备 ${device.bestName} 失败", Toast.LENGTH_SHORT).show()
                    }
                }
                override fun onComplete() {}
            })
        }
        Toast.makeText(context, "设备保存任务已启动", Toast.LENGTH_SHORT).show()
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
                Toast.makeText(requireContext(), "蓝牙扫描失败，错误码: $errorCode", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun processScanResult(result: ScanResult) {
        val deviceAddress = result.device.address
        val deviceName = result.scanRecord?.deviceName
        val rssi = result.rssi

        Log.d("DetectionFragment", "Device Found: Name='${deviceName ?: "null"}', Address='$deviceAddress'")

        if (deviceName.isNullOrEmpty() || !deviceName.contains("Sensor", ignoreCase = true)) {
            return
        }

        // Always update the last seen timestamp for any relevant device that is found.
        deviceLastSeen[deviceAddress] = System.currentTimeMillis()

        // Check if the device is already in one of the selected lists.
        val isAlreadySelected = hbsDevices.any { it.device.address == deviceAddress } ||
                                wgdDevices.any { it.device.address == deviceAddress } ||
                                xykDevices.any { it.device.address == deviceAddress }

        if (isAlreadySelected) {
            // If it's selected, it should not be in the scan list. Remove if present and ignore.
            if (scannedDevicesMap.containsKey(deviceAddress)) {
                scannedDevicesMap.remove(deviceAddress)
            }
            return
        }

        val existingDevice = scannedDevicesMap[deviceAddress]
        if (existingDevice != null) {
            if (rssi > existingDevice.rssi) {
                existingDevice.rssi = rssi
            }
            // deviceLastSeen is already updated above.
        } else {
            scannedDevicesMap[deviceAddress] = DeviceScanResult(result.device, rssi, deviceName)
            // deviceLastSeen is already updated above.
        }
    }

    private fun updateScanResultsUI() {
        val currentTime = System.currentTimeMillis()

        // Remove stale devices from the scan list
        scannedDevicesMap.entries.removeIf { (address, _) ->
            (currentTime - (deviceLastSeen[address] ?: currentTime)) > 5000
        }

        val sortedList = scannedDevicesMap.values.sortedByDescending { it.rssi }
        scanAdapter.updateList(sortedList)

        // Check selected lists for stale devices and remove them
        var listChanged = false
        val staleDevicePredicate: (DeviceScanResult) -> Boolean = {
            (currentTime - (deviceLastSeen[it.device.address] ?: 0)) > 5000 // 5-second timeout
        }

        if (hbsDevices.removeIf(staleDevicePredicate)) listChanged = true
        if (wgdDevices.removeIf(staleDevicePredicate)) listChanged = true
        if (xykDevices.removeIf(staleDevicePredicate)) listChanged = true

        if (listChanged) {
            activity?.runOnUiThread {
                updateAllStatusLists()
                saveDeviceLists()
                Toast.makeText(context, "已自动移除离线设备", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun getSensorTypeFromDeviceName(deviceName: String): Int? {
        return deviceName.split(" ").getOrNull(1)?.split("_")?.getOrNull(0)?.toIntOrNull()
    }
}
