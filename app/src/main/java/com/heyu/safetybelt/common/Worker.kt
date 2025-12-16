package com.heyu.safetybelt.common

import cn.leancloud.LCObject
import cn.leancloud.annotation.LCClassName

/**
 * @property name 工人姓名, 对应云端 'name' 字段。
 * @property employeeId 工人工号, 对应云端 'employeeId' 字段。
 */
@LCClassName("Worker")
class Worker : LCObject() {
    var name: String?
        get() = getString("name")
        set(value) {
            put("name", value)
        }

    var employeeId: String?
        get() = getString("employeeId")
        set(value) {
            put("employeeId", value)
        }
}