package com.heyu.safetybelt.regulator.model

import cn.leancloud.LCObject
import cn.leancloud.annotation.LCClassName

@LCClassName("Worker")
class Worker : LCObject() {
    val name: String
        get() = getString("name")

    val workerNumber: String
        get() = getString("workerNumber")
}
