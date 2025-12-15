package com.heyu.safetybelt.operator.data

import cn.leancloud.LCObject
import cn.leancloud.annotation.LCClassName

/**
 * 工人信息数据模型
 *
 * 该类对应 LeanCloud 云端数据库中的 "Worker" 表。
 * 它继承自 LCObject，用于方便地操作云端数据。
 *
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