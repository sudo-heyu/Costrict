package com.heyu.safetybelt.regulator.model

import java.util.Date

data class WorkerStatus(
    val workerId: String,
    val workerName: String,
    val workerNumber: String,
    val status: String,
    val lastUpdatedAt: Date?,
    val isOnline: Boolean,
    val sensor1Status: String? = null,
    val sensor2Status: String? = null,
    val sensor3Status: String? = null,
    val sensor4Status: String? = null,
    val sensor5Status: String? = null,
    val sensor6Status: String? = null,
    var isExpanded: Boolean = false,
    val sessionId: String? = null // 新增 sessionId 字段
)
