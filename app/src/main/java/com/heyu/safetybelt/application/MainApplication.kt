package com.heyu.safetybelt.application

import android.app.Application
import cn.leancloud.LCObject
import cn.leancloud.LeanCloud
import com.heyu.safetybelt.operator.data.AlarmEvent
import com.heyu.safetybelt.operator.data.Device
import com.heyu.safetybelt.regulator.model.WorkSession
import com.heyu.safetybelt.regulator.model.Worker

class MainApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // 安监人员端注册子类 (Best Practice)
        LCObject.registerSubclass(Worker::class.java)
        LCObject.registerSubclass(WorkSession::class.java)
        //工人端注册子类
        LCObject.registerSubclass(com.heyu.safetybelt.operator.data.Worker::class.java)
        LCObject.registerSubclass(Device::class.java)
        LCObject.registerSubclass(AlarmEvent::class.java)
        LCObject.registerSubclass(com.heyu.safetybelt.operator.data.WorkSession::class.java)

        // 初始化 LeanCloud SDK
        LeanCloud.initialize(
            this,
            "brtmPCOc4XTcd1INHf2uIaXC-gzGzoHsz",
            "Ta5ML0ryOxHTlV8qLFSENFb2",
            "https://brtmpcoc.lc-cn-n1-shared.com"
        )
    }
}