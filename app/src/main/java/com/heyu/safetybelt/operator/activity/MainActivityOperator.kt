package com.heyu.safetybelt.operator.activity

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.heyu.safetybelt.R
import com.heyu.safetybelt.application.MainApplication
import com.heyu.safetybelt.operator.fragment.WorkRecordFragment
import com.heyu.safetybelt.operator.model.WorkRecordManager
import com.heyu.safetybelt.operator.fragment.ProfileFragment
import com.heyu.safetybelt.operator.fragment.SafetybeltFragment
import com.heyu.safetybelt.operator.service.BleService

class MainActivityOperator : AppCompatActivity() {
    private lateinit var bottomNavigation: BottomNavigationView
    private val tag = "MainActivity"

    private var workerName: String? = null
    private var employeeId: String? = null
    internal var workerObjectId: String? = null

    private var abnormalStateDialog: AlertDialog? = null

    private val abnormalStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_ABNORMAL_STATE) {
                val abnormalParts = intent.getStringArrayListExtra(EXTRA_ABNORMAL_PARTS)
                if (!abnormalParts.isNullOrEmpty()) {
                    showAbnormalStateDialog(abnormalParts)
                }
            }
        }
    }

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            Toast.makeText(this, "权限已授予", Toast.LENGTH_SHORT).show()
        } else {
            showGoToSettingsDialog()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 拦截返回键，实现"大退"功能（将应用移至后台而不销毁 Activity）
        onBackPressedDispatcher.addCallback(this) {
            moveTaskToBack(true)
        }

        // 优先从savedInstanceState恢复用户信息，如果没有则从MainApplication恢复
        if (savedInstanceState != null) {
            workerName = savedInstanceState.getString("workerName")
                ?: MainApplication.getInstance().currentWorkerName
            employeeId = savedInstanceState.getString("employeeId")
                ?: MainApplication.getInstance().currentEmployeeId
            workerObjectId = savedInstanceState.getString("workerObjectId")
                ?: MainApplication.getInstance().currentWorkerObjectId
            Log.d(tag, "从savedInstanceState恢复用户信息: $workerName, $employeeId, $workerObjectId")
        } else {
            // 从intent获取用户信息，如果不存在则从MainApplication恢复
            workerName = intent.getStringExtra("workerName")
                ?: MainApplication.getInstance().currentWorkerName
            employeeId = intent.getStringExtra("employeeId")
                ?: MainApplication.getInstance().currentEmployeeId
            workerObjectId = intent.getStringExtra("workerObjectId")
                ?: MainApplication.getInstance().currentWorkerObjectId
            Log.d(tag, "从intent获取用户信息: $workerName, $employeeId, $workerObjectId")
        }

        // 保存到MainApplication以防止Activity重建时数据丢失
        MainApplication.getInstance().currentWorkerName = workerName
        MainApplication.getInstance().currentEmployeeId = employeeId
        MainApplication.getInstance().currentWorkerObjectId = workerObjectId
        MainApplication.getInstance().currentUserType = "worker"

        // 验证用户信息
        if (workerObjectId == null) {
            Log.e("MainActivityOperator", "无法获取用户ID，退出应用")
            Toast.makeText(this, "无法获取用户信息，请重新登录", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        WorkRecordManager.forceFinishAllActiveRecords(this)

        setContentView(R.layout.activity_main_worker)

        bottomNavigation = findViewById(R.id.bottom_navigation)
        setupBottomNavigation()

        // 当从后台返回时，savedInstanceState不会为null，不要重置导航状态
        // 只有在首次启动时才设置默认导航
        if (savedInstanceState == null) {
            bottomNavigation.selectedItemId = R.id.navigation_detection
        } else {
            // 从系统恢复时，确保底部导航栏的状态与当前显示的Fragment一致
            Log.d(tag, "Restoring from savedInstanceState, checking current state")
            
            // 重要：从系统恢复时，不要重置导航状态，让系统自动管理
            // 确保FragmentManager中的Fragment能够正确显示
            val currentFragment = supportFragmentManager.findFragmentById(R.id.fragment_container)
            if (currentFragment != null) {
                Log.d(tag, "Fragment already exists: ${currentFragment.javaClass.simpleName}")
            } else {
                Log.d(tag, "No fragment found, need to restore")
            }
        }

        checkAndRequestPermissions()
    }
    
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // 保存当前导航状态
        outState.putInt("current_nav_item", bottomNavigation.selectedItemId)
        // 保存用户信息
        outState.putString("workerName", workerName)
        outState.putString("employeeId", employeeId)
        outState.putString("workerObjectId", workerObjectId)
        Log.d(tag, "Saving instance state - nav: ${bottomNavigation.selectedItemId}, user: $workerName, $employeeId, $workerObjectId")
    }
    
    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        val navItem = savedInstanceState.getInt("current_nav_item", R.id.navigation_detection)
        Log.d(tag, "Restoring instance state with nav item: $navItem")
    }

    override fun onResume() {
        super.onResume()
        val intentFilter = IntentFilter(ACTION_ABNORMAL_STATE)
        LocalBroadcastManager.getInstance(this).registerReceiver(abnormalStateReceiver, intentFilter)
    }

    override fun onPause() {
        super.onPause()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(abnormalStateReceiver)
        abnormalStateDialog?.dismiss() // Dismiss to prevent window leaks
    }

    private fun showAbnormalStateDialog(abnormalParts: List<String>) {
        if (abnormalStateDialog?.isShowing == true) {
            return
        }

        val dialogView = layoutInflater.inflate(R.layout.dialog_abnormal_state, null)
        val dialogMessage = dialogView.findViewById<TextView>(R.id.dialog_message)
        val dialogButton = dialogView.findViewById<Button>(R.id.dialog_button)

        val message = "以下部位出现异常：\n" + abnormalParts.joinToString("\n") { "- $it" }
        dialogMessage.text = message

        abnormalStateDialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialogButton.setOnClickListener {
            abnormalStateDialog?.dismiss()
        }

        abnormalStateDialog?.show()
    }

    private fun checkAndRequestPermissions() {
        val requiredPermissions = mutableListOf<String>()
        requiredPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requiredPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
            requiredPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        // Android 13+ 需要通知权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requiredPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isNotEmpty()) {
            requestPermissionsLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun showGoToSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle("权限提醒")
            .setMessage("应用的核心扫描功能需要蓝牙及位置权限。请在系统设置中手动开启。")
            .setPositiveButton("去开启") { dialog, _ ->
                dialog.dismiss()
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                intent.data = Uri.fromParts("package", packageName, null)
                startActivity(intent)
            }
            .setNegativeButton("取消") { dialog, _ ->
                dialog.dismiss()
                Toast.makeText(this, "权限被拒绝，应用即将退出", Toast.LENGTH_LONG).show()
                finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun setupBottomNavigation() {
        bottomNavigation.setOnItemSelectedListener { item ->
            val transaction = supportFragmentManager.beginTransaction()

            // 查找现有的 Fragment，而不是依赖局部变量
            var safetybeltFragment = supportFragmentManager.findFragmentByTag("detection")
            var workRecordFragment = supportFragmentManager.findFragmentByTag("work_record")
            var profileFragment = supportFragmentManager.findFragmentByTag("profile")

            // 先隐藏所有已存在的 Fragment
            supportFragmentManager.fragments.forEach { transaction.hide(it) }

            when (item.itemId) {
                R.id.navigation_detection -> {
                    if (safetybeltFragment == null) {
                        safetybeltFragment = SafetybeltFragment()
                        transaction.add(R.id.fragment_container, safetybeltFragment!!, "detection")
                    } else {
                        transaction.show(safetybeltFragment!!)
                    }
                }
                R.id.navigation_work_record -> {
                    if (workRecordFragment == null) {
                        workRecordFragment = WorkRecordFragment()
                        transaction.add(R.id.fragment_container, workRecordFragment!!, "work_record")
                    } else {
                        transaction.show(workRecordFragment!!)
                    }
                }
                R.id.navigation_profile -> {
                    if (profileFragment == null) {
                        profileFragment = ProfileFragment().apply {
                            arguments = Bundle().apply {
                                putString("workerName", workerName)
                                putString("employeeId", employeeId)
                                putString("workerObjectId", workerObjectId)
                            }
                        }
                        transaction.add(R.id.fragment_container, profileFragment!!, "profile")
                    } else {
                        transaction.show(profileFragment!!)
                    }
                }
            }
            transaction.commitAllowingStateLoss()
            true
        }
        
        // 重要：不要在设置时触发selectedItemId，让系统自动管理状态
        // 这样在从后台恢复时，不会重置Fragment状态
    }
    
    /**
     * 从导航状态恢复Fragment（系统重建Activity时使用）
     */
    private fun restoreFragmentsFromNavigation() {
        // 再次检查，避免重复创建
        val existingFragment = supportFragmentManager.findFragmentById(R.id.fragment_container)
        if (existingFragment != null) {
            Log.d(tag, "Fragment already exists, skipping restoration")
            return
        }
        
        val transaction = supportFragmentManager.beginTransaction()
        val selectedItemId = bottomNavigation.selectedItemId
        
        when (selectedItemId) {
            R.id.navigation_detection -> {
                val fragment = SafetybeltFragment()
                transaction.add(R.id.fragment_container, fragment, "detection")
            }
            R.id.navigation_work_record -> {
                val fragment = WorkRecordFragment()
                transaction.add(R.id.fragment_container, fragment, "work_record")
            }
            R.id.navigation_profile -> {
                val fragment = ProfileFragment().apply {
                    arguments = Bundle().apply {
                        putString("workerName", workerName)
                        putString("employeeId", employeeId)
                        putString("workerObjectId", workerObjectId)
                    }
                }
                transaction.add(R.id.fragment_container, fragment, "profile")
            }
        }
        transaction.commitAllowingStateLoss()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 应用被销毁时，确保服务也收到通知更新云端状态
        try {
            val intent = Intent(this, BleService::class.java)
            stopService(intent)
        } catch (e: Exception) {
            // 忽略错误，因为应用可能已经在销毁过程中
        }
    }

    companion object {
        const val ACTION_ABNORMAL_STATE = "com.heyu.safetybeltoperators.ACTION_ABNORMAL_STATE"
        const val EXTRA_ABNORMAL_PARTS = "com.heyu.safetybeltoperators.EXTRA_ABNORMAL_PARTS"
    }
}
