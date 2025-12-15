package com.heyu.safetybelt.operator.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.heyu.safetybelt.operator.model.AlarmDetail
import com.heyu.safetybelt.operator.model.WorkRecord

object WorkRecordManager {
    private const val PREF_NAME = "safety_belt_prefs"
    private const val KEY_RECORDS = "work_records"
    private val gson = Gson()

    // 获取所有记录
    fun getAllRecords(context: Context): MutableList<WorkRecord> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_RECORDS, null) ?: return mutableListOf()

        val type = object : TypeToken<MutableList<WorkRecord>>() {}.type
        val list: MutableList<WorkRecord> = gson.fromJson(json, type) ?: mutableListOf()
        
        // 注意：这里不再依赖 60秒 超时检查，而是通过 MainActivity 启动时强制清理
        // 保留此逻辑作为双重保障（比如 Application 未重启但状态异常）
        if (list.isNotEmpty()) {
            val lastRecord = list[0]
            if (lastRecord.endTime == null) {
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastRecord.lastActiveTime > 60000) {
                    val fallbackTime = if (lastRecord.lastActiveTime > 0) lastRecord.lastActiveTime else currentTime
                    
                    lastRecord.safeAlarmList.forEach { 
                         if (it.endTime == null) it.endTime = fallbackTime 
                    }

                    val updatedRecord = lastRecord.copy(endTime = fallbackTime)
                    list[0] = updatedRecord
                    saveRecords(context, list)
                }
            }
        }
        
        return list
    }

    // 强制结束所有未完成的记录 (用于 App 启动时清理异常状态)
    fun forceFinishAllActiveRecords(context: Context) {
        val list = getAllRecords(context) // 这里会触发上面的超时检查，但我们要强制处理
        var hasChanges = false
        
        for (i in list.indices) {
            val record = list[i]
            if (record.endTime == null) {
                // 强制结束
                val fallbackTime = if (record.lastActiveTime > 0) record.lastActiveTime else System.currentTimeMillis()
                
                // 结束该记录下的所有未结束报警
                record.safeAlarmList.forEach {
                    if (it.endTime == null) it.endTime = fallbackTime
                }

                // 更新记录
                list[i] = record.copy(endTime = fallbackTime)
                hasChanges = true
            }
        }

        if (hasChanges) {
            saveRecords(context, list)
        }
    }

    // 保存记录列表到本地
    private fun saveRecords(context: Context, list: List<WorkRecord>) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val json = gson.toJson(list)
        prefs.edit().putString(KEY_RECORDS, json).apply()
    }

    // 删除某条记录
    fun deleteRecord(context: Context, recordId: String) {
        val list = getAllRecords(context)
        val iterator = list.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().id == recordId) {
                iterator.remove()
                saveRecords(context, list)
                return
            }
        }
    }

    // 开始工作：添加一条新记录
    fun startNewWork(context: Context) {
        val list = getAllRecords(context)

        // 检查上一条记录是否正常结束
        val lastRecord = list.firstOrNull()
        if (lastRecord != null && lastRecord.endTime == null) {
            val fallbackTime = if (lastRecord.lastActiveTime > 0) lastRecord.lastActiveTime else System.currentTimeMillis()
            val updatedLastRecord = lastRecord.copy(endTime = fallbackTime)
            list[0] = updatedLastRecord
        }

        // 创建新记录
        val currentTime = System.currentTimeMillis()
        val newRecord = WorkRecord(
            startTime = currentTime,
            lastActiveTime = currentTime
        )

        list.add(0, newRecord)
        saveRecords(context, list)
    }

    // 更新最后活跃时间
    fun updateLastActiveTime(context: Context) {
        // 为了避免递归调用 getAllRecords 触发逻辑，我们直接操作 SP
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_RECORDS, null) ?: return
        val type = object : TypeToken<MutableList<WorkRecord>>() {}.type
        val list: MutableList<WorkRecord> = gson.fromJson(json, type) ?: mutableListOf()

        if (list.isEmpty()) return

        val currentRecord = list[0]
        if (currentRecord.endTime == null) {
            currentRecord.lastActiveTime = System.currentTimeMillis()
            saveRecords(context, list)
        }
    }

    // 实时添加报警详情
    fun addAlarmDetail(context: Context, sensorName: String, alarmType: String) {
        val list = getAllRecords(context)
        if (list.isEmpty()) return

        val currentRecord = list[0]
        if (currentRecord.endTime == null) {
            val newAlarm = AlarmDetail(
                timestamp = System.currentTimeMillis(),
                sensorName = sensorName,
                alarmType = alarmType
            )
            currentRecord.safeAlarmList.add(0, newAlarm)
            currentRecord.alertCount = currentRecord.safeAlarmList.size
            currentRecord.lastActiveTime = System.currentTimeMillis()
            
            saveRecords(context, list)
        }
    }

    // 结束特定传感器的报警
    fun endAlarmForSensor(context: Context, sensorName: String) {
        val list = getAllRecords(context)
        if (list.isEmpty()) return

        val currentRecord = list[0]
        if (currentRecord.endTime == null) {
            val alarmList = currentRecord.safeAlarmList
            val targetAlarm = alarmList.find { it.sensorName == sensorName && it.endTime == null }
            
            if (targetAlarm != null) {
                targetAlarm.endTime = System.currentTimeMillis()
                currentRecord.lastActiveTime = System.currentTimeMillis()
                saveRecords(context, list)
            }
        }
    }

    // 兼容旧方法
    fun updateCurrentWork(context: Context, alertCount: Int) {
        val list = getAllRecords(context)
        if (list.isEmpty()) return

        val currentRecord = list[0]
        if (currentRecord.endTime == null) {
            val updatedRecord = currentRecord.copy(
                alertCount = alertCount,
                lastActiveTime = System.currentTimeMillis()
            )
            list[0] = updatedRecord
            saveRecords(context, list)
        }
    }

    // 结束工作
    fun stopCurrentWork(context: Context, alertCount: Int) {
        val list = getAllRecords(context)
        if (list.isEmpty()) return

        val currentRecord = list[0]
        if (currentRecord.endTime == null) {
            val currentTime = System.currentTimeMillis()
            currentRecord.safeAlarmList.forEach { 
                if (it.endTime == null) it.endTime = currentTime 
            }

            val updatedRecord = currentRecord.copy(
                endTime = currentTime,
                lastActiveTime = currentTime,
                alertCount = alertCount
            )
            list[0] = updatedRecord
            saveRecords(context, list)
        }
    }
}
