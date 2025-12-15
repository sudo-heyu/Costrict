package com.heyu.safetybelt.regulator.model

import cn.leancloud.LCObject
import cn.leancloud.annotation.LCClassName
import java.util.Date

@LCClassName("StatusHistory")
class StatusHistory : LCObject() {
    val worker: Worker?
        get() = getLCObject("worker")

    val session: WorkSession?
        get() = getLCObject("session")

    val status: String
        get() = getString("status")

    val timestamp: Date?
        get() = getDate("timestamp")
}
