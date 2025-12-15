package com.heyu.safetybelt.operator.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 报警详情数据模型
 */
data class AlarmDetail(
    val timestamp: Long,    // 报警开始时间
    val sensorName: String, // 传感器名称 (如: 后背绳高挂)
    val alarmType: String,  // 报警类型 (如: 异常报警)
    var endTime: Long? = null // 报警结束时间
) {
    fun getTimeString(): String {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    fun getDurationString(): String {
        if (endTime == null) return "持续中..."
        val diff = endTime!! - timestamp
        val seconds = diff / 1000
        return if (seconds < 60) {
            "${seconds}秒"
        } else {
            val mins = seconds / 60
            val secs = seconds % 60
            "${mins}分${secs}秒"
        }
    }
}

/**
 * 工作记录数据模型
 */
data class WorkRecord(
    val id: String = UUID.randomUUID().toString(),
    val startTime: Long,           // 工作开始时间戳
    var endTime: Long? = null,     // 工作结束时间戳 (null 表示进行中)
    var lastActiveTime: Long = startTime, // 最后活跃时间 (用于异常退出恢复)
    var alertCount: Int = 0,       // 报警记录数量 (兼容旧数据)
    // 使用可空类型以兼容旧数据反序列化
    var alarmList: MutableList<AlarmDetail>? = null 
) {
    // 确保 alarmList 不为空的辅助属性
    val safeAlarmList: MutableList<AlarmDetail>
        get() {
            if (alarmList == null) {
                alarmList = mutableListOf()
            }
            return alarmList!!
        }

    // 获取格式化的日期字符串 (如: 2023-10-27)
    fun getDateString(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        return sdf.format(Date(startTime))
    }

    // 获取格式化的时间范围字符串 (如: 08:00 - 17:30)
    fun getTimeRangeString(): String {
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        val startStr = sdf.format(Date(startTime))
        // 使用局部变量 capturing endTime 来解决 smart cast 问题
        val currentEnd = endTime
        val endStr = if (currentEnd != null) sdf.format(Date(currentEnd)) else "工作中..."
        return "$startStr - $endStr"
    }

    // 获取格式化的时长字符串 (如: 8小时 30分钟)
    fun getDurationString(): String {
        val end = endTime ?: System.currentTimeMillis()
        val diff = end - startTime
        
        val hours = diff / (1000 * 60 * 60)
        val minutes = (diff % (1000 * 60 * 60)) / (1000 * 60)
        // 如果在工作中，且时间很短，显示秒
        if (hours == 0L && minutes == 0L) {
             val seconds = diff / 1000
             return "${seconds}秒"
        }

        return "${hours}小时 ${minutes}分钟"
    }
    
    // 获取真实的报警数量 (优先使用列表大小)
    fun getRealAlertCount(): Int {
        return if (alarmList != null && alarmList!!.isNotEmpty()) alarmList!!.size else alertCount
    }
}
