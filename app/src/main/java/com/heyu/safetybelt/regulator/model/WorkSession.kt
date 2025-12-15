package com.heyu.safetybelt.regulator.model

import cn.leancloud.LCObject
import cn.leancloud.annotation.LCClassName
import java.util.Date

@LCClassName("WorkSession")
class WorkSession : LCObject() {
    val worker: Worker?
        get() = getLCObject("worker")

    val startTime: Date?
        get() = getDate("startTime")

    val endTime: Date?
        get() = getDate("endTime")

    val latestStatus: String
        get() = getString("latestStatus")

    val latestStatusUpdatedAt: Date?
        get() = getDate("latestStatusUpdatedAt")

    val isCompleted: Boolean
        get() = getBoolean("isCompleted")

    val sensor1Status: String?
        get() = getString("sensor1Status")

    val sensor2Status: String?
        get() = getString("sensor2Status")

    val sensor3Status: String?
        get() = getString("sensor3Status")

    val sensor4Status: String?
        get() = getString("sensor4Status")

    val sensor5Status: String?
        get() = getString("sensor5Status")

    val sensor6Status: String?
        get() = getString("sensor6Status")
}
