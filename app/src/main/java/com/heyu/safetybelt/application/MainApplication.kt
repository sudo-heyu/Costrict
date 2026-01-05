package com.heyu.safetybelt.application

import android.app.Application
import cn.leancloud.LCObject
import cn.leancloud.LeanCloud
import com.heyu.safetybelt.common.AlarmEvent
import com.heyu.safetybelt.common.Device
//import com.heyu.safetybelt.regulator.model.WorkSession
//import com.heyu.safetybelt.regulator.model.Worker

class MainApplication : Application() {
    // 保存当前用户信息，防止Activity重建时数据丢失
    var currentWorkerName: String? = null
    var currentEmployeeId: String? = null
    var currentWorkerObjectId: String? = null
    var currentUserType: String? = null // "worker" 或 "regulator"
    

    var isInMonitoringMode: Boolean = false// 保存当前Fragment状态，防止Activity重建时丢失监控界面

    companion object {
        private var instance: MainApplication? = null
        
        fun getInstance(): MainApplication = instance!!
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 安监人员端注册子类 (Best Practice)
//        LCObject.registerSubclass(Worker::class.java)
//        LCObject.registerSubclass(WorkSession::class.java)
        //工人端注册子类
        LCObject.registerSubclass(com.heyu.safetybelt.common.Worker::class.java)
        LCObject.registerSubclass(Device::class.java)
        LCObject.registerSubclass(AlarmEvent::class.java)
        LCObject.registerSubclass(com.heyu.safetybelt.common.WorkSession::class.java)

        // 初始化 LeanCloud SDK
        LeanCloud.initialize(
            this,
            "brtmPCOc4XTcd1INHf2uIaXC-gzGzoHsz",
            "Ta5ML0ryOxHTlV8qLFSENFb2",
            "https://brtmpcoc.lc-cn-n1-shared.com"
        )
    }
}