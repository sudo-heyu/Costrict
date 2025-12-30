package com.heyu.safetybelt.common

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.heyu.safetybelt.R

/**
 * 系统级通知管理器，专门处理锁屏通知显示
 */
object NotificationHelper {
    
    private const val LOCKSCREEN_CHANNEL_ID = "lockscreen_channel"
    private const val LOCKSCREEN_NOTIFICATION_ID = 1001
    
    /**
     * 创建专门用于锁屏显示的通道
     */
    fun createLockscreenNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                LOCKSCREEN_CHANNEL_ID,
                "锁屏安全监控",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "安全带卫士锁屏监控通知"
                enableLights(true)
                lightColor = android.graphics.Color.RED
                enableVibration(false)
                setShowBadge(true)
                setSound(null, null)
                // 关键：确保锁屏可见
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                // 允许绕过免打扰
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setBypassDnd(true)
                }
                // 设置为系统级重要通知
                setImportance(NotificationManager.IMPORTANCE_HIGH)
            }
            
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    /**
     * 显示锁屏通知
     */
    fun showLockscreenNotification(context: Context, title: String, content: String, isRegulator: Boolean = false) {
        createLockscreenNotificationChannel(context)
        
        val intent = if (isRegulator) {
            Intent(context, com.heyu.safetybelt.regulator.activity.MainActivityRegulator::class.java)
        } else {
            Intent(context, com.heyu.safetybelt.operator.activity.MainActivityOperator::class.java)
        }
        
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val notification = NotificationCompat.Builder(context, LOCKSCREEN_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setLargeIcon(android.graphics.BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher))
            .setContentIntent(pendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_MAX) // 最高优先级
            .setCategory(NotificationCompat.CATEGORY_ALARM) // 告警类别，确保显示
            .setOngoing(true)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())
            .setOnlyAlertOnce(false)
            // 关键：确保在Android 15+上显示
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                }
            }
            // 添加扩展样式
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText(content + "\n\n点击返回应用查看详细状态"))
            .build()
        
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(LOCKSCREEN_NOTIFICATION_ID, notification)
    }
    
    /**
     * 检查并请求系统通知权限
     */
    fun checkNotificationPolicyAccess(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            return notificationManager.isNotificationPolicyAccessGranted
        }
        return true
    }
    
    /**
     * 引导用户开启通知权限
     */
    fun requestNotificationPolicyAccess(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
        }
    }
    
    /**
     * 检查系统通知设置
     */
    fun checkSystemNotificationSettings(context: Context): String {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val issues = mutableListOf<String>()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = notificationManager.getNotificationChannel(LOCKSCREEN_CHANNEL_ID)
            if (channel != null) {
                if (channel.importance < NotificationManager.IMPORTANCE_HIGH) {
                    issues.add("通知渠道重要性不够高")
                }
                if (channel.lockscreenVisibility != Notification.VISIBILITY_PUBLIC) {
                    issues.add("锁屏可见性未设置为公开")
                }
            } else {
                issues.add("锁屏通知通道未创建")
            }
        }
        
        // 检查免打扰模式
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (notificationManager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) {
                issues.add("设备处于免打扰模式")
            }
        }
        
        return if (issues.isEmpty()) "系统通知设置正常" else "检测到问题: ${issues.joinToString(", ")}"
    }
    
    /**
     * 更新锁屏通知内容
     */
    fun updateLockscreenNotification(context: Context, title: String, content: String, isRegulator: Boolean = false) {
        showLockscreenNotification(context, title, content, isRegulator)
    }
    
    /**
     * 取消锁屏通知
     */
    fun cancelLockscreenNotification(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(LOCKSCREEN_NOTIFICATION_ID)
    }
}