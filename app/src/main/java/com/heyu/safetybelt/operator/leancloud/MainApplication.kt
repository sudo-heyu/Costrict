package com.heyu.safetybelt.operator.leancloud

import android.app.Application
import cn.leancloud.LCObject
import cn.leancloud.LeanCloud
import com.heyu.safetybelt.operator.data.AlarmEvent
import com.heyu.safetybelt.operator.data.Device
import com.heyu.safetybelt.operator.data.WorkSession
import com.heyu.safetybelt.operator.data.Worker

class MainApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // 注册子类
        LCObject.registerSubclass(Worker::class.java)
        LCObject.registerSubclass(Device::class.java)
        LCObject.registerSubclass(AlarmEvent::class.java)
        LCObject.registerSubclass(WorkSession::class.java)
        LeanCloud.initialize(
            this,
            "brtmPCOc4XTcd1INHf2uIaXC-gzGzoHsz",
            "Ta5ML0ryOxHTlV8qLFSENFb2",
            "https://brtmpcoc.lc-cn-n1-shared.com" //
        )
    }
}
