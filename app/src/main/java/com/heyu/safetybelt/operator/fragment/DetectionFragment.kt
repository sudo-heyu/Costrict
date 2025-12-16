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
import android.os.VibrationEffect
import android.os.Vibrator
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
    // Use RxJava2 CompositeDisposable for LeanCloud SDK compatibility
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
        sharedPreferences.edit().clear().apply()

        setupUI()
        loadDeviceLists()
        updateAllStatusLists()

        checkAndRequestPermissions()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopBleScan()
        hbsDevices.clear()
        wgdDevices.clear()
        xykDevices.clear()
        sharedPreferences.edit().clear().apply()
        rx3CompositeDisposable.clear()
        rx2CompositeDisposable.clear()
        _binding = null
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
        binding.scanButton.setOnClickListener { toggleScan() }
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
        }
    }

    private fun toggleScan() {
        if (isScanning) {
            stopBleScan(navigateToMonitoring = true)
        } else {
            val vibrator = requireContext().getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(50)
                }
            }
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
            binding.scanButton.text = "确定"
            binding.scanButton.backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.darkblue)
            bleScanner?.startScan(null, scanSettings, bleScanCallback)

            val scanUpdateDisposable = Observable.interval(2, 2, TimeUnit.SECONDS)
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
            binding.scanButton.text = "开始扫描"
            binding.scanButton.backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.lightblue)
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
            val sensorType = scanResult.bestName.split(" ").getOrNull(1)?.split("_")?.getOrNull(0)?.toIntOrNull()
            device.sensorType = sensorType ?: 0
            device
        }

        devicesToSave.forEach { device ->
            device.saveInBackground().subscribe(object : io.reactivex.Observer<LCObject> {
                override fun onSubscribe(d: io.reactivex.disposables.Disposable) {
                    // Correctly add the RxJava2 Disposable to the RxJava2 CompositeDisposable
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
                stopBleScan()
            }
        }
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    private fun processScanResult(result: ScanResult) {
        val scanRecord = result.scanRecord
        val bestName = result.device.name ?: scanRecord?.deviceName
        if (bestName.isNullOrBlank()) return

        val regex = "^Sensor [1-6]_[0-9a-fA-F]{4}$".toRegex()
        if (!bestName.matches(regex)) {
            return
        }

        val deviceAddress = result.device.address
        if (isDeviceAlreadySelected(deviceAddress)) {
            return
        }

        deviceLastSeen[deviceAddress] = System.currentTimeMillis()

        val existingDevice = scannedDevicesMap[deviceAddress]
        if (existingDevice != null) {
            existingDevice.rssi = result.rssi
        } else {
            scannedDevicesMap[deviceAddress] = DeviceScanResult(result.device, result.rssi, bestName)
        }
    }

    private fun isDeviceAlreadySelected(address: String): Boolean {
        val isInHbs = hbsDevices.any { it.device.address == address }
        val isInWgd = wgdDevices.any { it.device.address == address }
        val isInXyk = xykDevices.any { it.device.address == address }
        return isInHbs || isInWgd || isInXyk
    }

    private fun updateScanResultsUI() {
        val now = System.currentTimeMillis()
        val iterator = deviceLastSeen.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            // 如果设备超过3秒未被再次发现，则移除
            if (now - entry.value > 3000) {
                iterator.remove()
                scannedDevicesMap.remove(entry.key)
            }
        }

        val deviceList = scannedDevicesMap.values.sortedByDescending { it.rssi }
        scanAdapter.updateList(deviceList)
    }

    private fun addDeviceToCategory(device: DeviceScanResult) {
        val sensorType = device.bestName.split(" ").getOrNull(1)?.split("_")?.getOrNull(0)?.toIntOrNull()

        when (sensorType) {
            1, 2, 6 -> addToHbs(device) // HBS
            3, 4 -> addToWgd(device)    // WGD
            5 -> addToXyk(device)       // XYK
            else -> {
                Toast.makeText(requireContext(), "无法识别的设备类型: ${device.bestName}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun addToHbs(device: DeviceScanResult) {
        if (!hbsDevices.any { it.device.address == device.device.address }) {
            hbsDevices.add(device)
            scannedDevicesMap.remove(device.device.address)
            deviceLastSeen.remove(device.device.address)
            updateAllStatusLists()
            saveDeviceLists()
        }
    }

    private fun addToWgd(device: DeviceScanResult) {
        if (!wgdDevices.any { it.device.address == device.device.address }) {
            wgdDevices.add(device)
            scannedDevicesMap.remove(device.device.address)
            deviceLastSeen.remove(device.device.address)
            updateAllStatusLists()
            saveDeviceLists()
        }
    }

    private fun addToXyk(device: DeviceScanResult) {
        if (xykDevices.isNotEmpty()) {
            val oldDevice = xykDevices.first()
            scannedDevicesMap[oldDevice.device.address] = oldDevice
            deviceLastSeen[oldDevice.device.address] = System.currentTimeMillis()
        }
        xykDevices.clear()
        xykDevices.add(device)
        scannedDevicesMap.remove(device.device.address)
        deviceLastSeen.remove(device.device.address)
        updateAllStatusLists()
        saveDeviceLists()
    }

    private fun handleRemoveFromHbs(device: DeviceScanResult) {
        hbsDevices.remove(device)
        scannedDevicesMap[device.device.address] = device
        deviceLastSeen[device.device.address] = System.currentTimeMillis()
        updateAllStatusLists()
        saveDeviceLists()
    }

    private fun handleRemoveFromWgd(device: DeviceScanResult) {
        wgdDevices.remove(device)
        scannedDevicesMap[device.device.address] = device
        deviceLastSeen[device.device.address] = System.currentTimeMillis()
        updateAllStatusLists()
        saveDeviceLists()
    }

    private fun handleRemoveFromXyk() {
        if (xykDevices.isNotEmpty()) {
            val device = xykDevices.first()
            xykDevices.clear()
            scannedDevicesMap[device.device.address] = device
            deviceLastSeen[device.device.address] = System.currentTimeMillis()
            updateAllStatusLists()
            saveDeviceLists()
        }
    }

    private fun updateAllStatusLists() {
        updateScanResultsUI()

        hbsAdapter.updateList(hbsDevices.toList())
        hbsTitleTextView.text = "后背绳 (${hbsDevices.size})"

        wgdAdapter.updateList(wgdDevices.toList())
        wgdTitleTextView.text = "围杆带 (${wgdDevices.size})"

        updateXykDevice()
    }

    private fun updateXykDevice() {
        if (xykDevices.isNotEmpty()) {
            val device = xykDevices.first()
            xykDeviceNameTextView.text = device.bestName
            xykDeviceNameTextView.visibility = View.VISIBLE
            xykRemoveButton.visibility = View.VISIBLE
        } else {
            xykDeviceNameTextView.visibility = View.GONE
            xykRemoveButton.visibility = View.GONE
        }
    }

    private fun saveDeviceLists() {
        val editor = sharedPreferences.edit()
        editor.putString("hbs_devices", gson.toJson(hbsDevices))
        editor.putString("wgd_devices", gson.toJson(wgdDevices))
        editor.putString("xyk_devices", gson.toJson(xykDevices))
        editor.apply()
    }

    private fun loadDeviceLists() {
        val hbsJson = sharedPreferences.getString("hbs_devices", null)
        val wgdJson = sharedPreferences.getString("wgd_devices", null)
        val xykJson = sharedPreferences.getString("xyk_devices", null)

        val listType = object : TypeToken<CopyOnWriteArrayList<DeviceScanResult>>() {}.type

        hbsJson?.let { hbsDevices = gson.fromJson(it, listType) }
        wgdJson?.let { wgdDevices = gson.fromJson(it, listType) }
        xykJson?.let { xykDevices = gson.fromJson(it, listType) }
    }
}
