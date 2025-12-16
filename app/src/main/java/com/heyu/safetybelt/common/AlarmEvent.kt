package com.heyu.safetybelt.common

import cn.leancloud.LCObject
import cn.leancloud.LCUser
import cn.leancloud.annotation.LCClassName
import java.util.Date

@LCClassName("AlarmEvent")
class AlarmEvent : LCObject() {

    // 关联到 _User 表
    var worker: LCUser?
        get() = getLCObject("worker") as? LCUser
        set(value) {
            value?.let { put("worker", it) }
        }

    // 关联到本次作业会话
    var sessionId: String?
        get() = getString("sessionId")
        set(value) {
            value?.let { put("sessionId", it) }
        }

    // 告警发生精确时间
    var timestamp: Date?
        get() = getDate("timestamp")
        set(value) {
            value?.let { put("timestamp", it) }
        }

    // 具体的告警类型 (如 "异常", "单挂")
    var alarmType: String?
        get() = getString("alarmType")
        set(value) {
            value?.let { put("alarmType", it) }
        }

    // 触发告警的设备 MAC 地址
    var deviceAddress: String?
        get() = getString("deviceAddress")
        set(value) {
            value?.let { put("deviceAddress", it) }
        }

    // 触发告警的传感器类型 (1-6)
    var sensorType: Int?
        get() = getNumber("sensorType")?.toInt()
        set(value) {
            value?.let { put("sensorType", it) }
        }

    // 告警是否已解除 (默认为 false)
    var resolved: Boolean?
        get() = getBoolean("resolved")
        set(value) {
            value?.let { put("resolved", it) }
        }

    // 告警解除时间 (可后续更新)
    var resolvedTimestamp: Date?
        get() = getDate("resolvedTimestamp")
        set(value) {
            value?.let { put("resolvedTimestamp", it) }
        }
}