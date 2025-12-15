package com.heyu.safetybelt.operator.data

import cn.leancloud.LCObject
import cn.leancloud.LCUser
import cn.leancloud.annotation.LCClassName
import java.util.Date

@LCClassName("WorkSession")
class WorkSession : LCObject() {

    // 关联到 _User 表的具体作业人员
    var worker: LCUser?
        get() = getLCObject("worker") as? LCUser
        set(value) {
            value?.let { put("worker", it) }
        }

    // 作业开始时间
    var startTime: Date?
        get() = getDate("startTime")
        set(value) {
            value?.let { put("startTime", it) }
        }

    // 作业结束时间
    var endTime: Date?
        get() = getDate("endTime")
        set(value) {
            value?.let { put("endTime", it) }
        }

    // 总时长，单位：秒
    var duration: Number?
        get() = getNumber("duration")
        set(value) {
            value?.let { put("duration", it) }
        }
    
    // 本次作业总告警次数
    var totalAlarmCount: Number?
        get() = getNumber("totalAlarmCount")
        set(value) {
            value?.let { put("totalAlarmCount", it) }
        }

    // 是否在线
    var isOnline: Boolean
        get() = getBoolean("isOnline")
        set(value) {
            put("isOnline", value)
        }

    // 当前作业状态
    var currentStatus: String?
        get() = getString("currentStatus")
        set(value) {
            value?.let { put("currentStatus", it) }
        }


    var sensor1Status: String?
        get() = getString("sensor1Status")
        set(value) {
            value?.let { put("sensor1Status", it) }
        }


    var sensor2Status: String?
        get() = getString("sensor2Status")
        set(value) {
            value?.let { put("sensor2Status", it) }
        }


    var sensor3Status: String?
        get() = getString("sensor3Status")
        set(value) {
            value?.let { put("sensor3Status", it) }
        }


    var sensor4Status: String?
        get() = getString("sensor4Status")
        set(value) {
            value?.let { put("sensor4Status", it) }
        }

    var sensor5Status: String?
        get() = getString("sensor5Status")
        set(value) {
            value?.let { put("sensor5Status", it) }
        }


    var sensor6Status: String?
        get() = getString("sensor6Status")
        set(value) {
            value?.let { put("sensor6Status", it) }
        }

    // 本次作业关联的设备列表
    var deviceList: List<*>?
        get() = getList("deviceList")
        set(value) {
            value?.let { put("deviceList", it) }
        }
}
