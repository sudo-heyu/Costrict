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
import com.heyu.safetybelt.operator.fragment.WorkRecordFragment
import com.heyu.safetybelt.operator.model.WorkRecordManager
import com.heyu.safetybelt.operator.fragment.ProfileFragment
import com.heyu.safetybelt.operator.fragment.SafetybeltFragment

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

        // 拦截返回键，实现“大退”功能（将应用移至后台而不销毁 Activity）
        onBackPressedDispatcher.addCallback(this) {
            moveTaskToBack(true)
        }

        workerName = intent.getStringExtra("workerName")
        employeeId = intent.getStringExtra("employeeId")
        workerObjectId = intent.getStringExtra("workerObjectId")

        WorkRecordManager.forceFinishAllActiveRecords(this)

        setContentView(R.layout.activity_main_worker)

        bottomNavigation = findViewById(R.id.bottom_navigation)
        setupBottomNavigation()

        if (savedInstanceState == null) {
            bottomNavigation.selectedItemId = R.id.navigation_detection
        }

        checkAndRequestPermissions()
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
            transaction.commit()
            true
        }
    }

    companion object {
        const val ACTION_ABNORMAL_STATE = "com.heyu.safetybeltoperators.ACTION_ABNORMAL_STATE"
        const val EXTRA_ABNORMAL_PARTS = "com.heyu.safetybeltoperators.EXTRA_ABNORMAL_PARTS"
    }
}
