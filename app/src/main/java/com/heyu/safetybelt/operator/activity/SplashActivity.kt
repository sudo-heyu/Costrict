package com.heyu.safetybelt.operator.activity

import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.heyu.safetybelt.R

//开屏界面，倒计时1s后跳转
class SplashActivity : AppCompatActivity() {
    //初始化countDownTimer
    private lateinit var countDownTimer: CountDownTimer
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_splash_worker)
        countDown()
    }

    private fun countDown() {
        //设置1s的倒计时，倒计时结束跳转到登录界面
        countDownTimer=object : CountDownTimer(1000,500){
            override fun onTick(p0: Long) {
            }
            override fun onFinish() {
                //跳转到登录页面
                startActivity(Intent(this@SplashActivity, LoginActivity::class.java))
                finish()
            }
        }.start()
    }
}