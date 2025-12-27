package com.heyu.safetybelt.regulator.activity

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.heyu.safetybelt.R
import com.heyu.safetybelt.databinding.ActivityMainRegulatorBinding
import com.heyu.safetybelt.regulator.service.UnderService

class MainActivityRegulator : AppCompatActivity() {

    private lateinit var binding: ActivityMainRegulatorBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 拦截返回键，实现“大退”功能（将应用移至后台而不销毁 Activity）
        onBackPressedDispatcher.addCallback(this) {
            moveTaskToBack(true)
        }

        binding = ActivityMainRegulatorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navView: BottomNavigationView = binding.navView

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController

        navView.setupWithNavController(navController)

        // 启动后台服务
        val serviceIntent = Intent(this, UnderService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }
}
