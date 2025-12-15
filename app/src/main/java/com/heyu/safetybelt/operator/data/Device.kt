package com.heyu.safetybelt.operator.data

import cn.leancloud.LCObject
import cn.leancloud.LCQuery
import cn.leancloud.annotation.LCClassName

@LCClassName("Device")
class Device : LCObject() {
    // 设备MAC地址
    var macAddress: String?
        get() = getString("macAddress")
        set(value) {
            put("macAddress", value ?: "")
        }

    // 传感器类型
    var sensorType: Int
        get() = getInt("sensorType")
        set(value) {
            put("sensorType", value)
        }

    // 设备蓝牙名称
    var bestName: String?
        get() = getString("bestName")
        set(value) {
            put("bestName", value ?: "")
        }

    // 关联的工人
    var worker: LCObject?
        get() = getLCObject("worker")
        set(value) {
            if (value != null) {
                put("worker", value)
            }
        }
        
    companion object {
        fun getQuery(): LCQuery<Device> {
            return getQuery(Device::class.java)
        }
    }
}
