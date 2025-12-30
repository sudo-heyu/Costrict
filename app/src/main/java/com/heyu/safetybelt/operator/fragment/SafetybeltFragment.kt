package com.heyu.safetybelt.operator.fragment

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.heyu.safetybelt.R
import com.heyu.safetybelt.application.MainApplication
import com.heyu.safetybelt.operator.service.BleService

/**
 * “安全带检测”模块的导航管家 (Container Fragment)。
 *
 * 它的唯一职责是作为其内部所有子页面（如 DetectionFragment, MonitoringFragment）的“导航主舞台”。
 * 这种设计模式，使得 MainActivity 可以通过 show/hide 的方式来管理顶层标签页，
 * 同时保留每个标签页内部自己的导航状态，从而解决了界面切换时的状态丢失和混乱问题。
 */
class SafetybeltFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // 加载此“导航管家”的布局文件，该布局文件包含一个 FrameLayout 作为子 Fragment 的容器。
        return inflater.inflate(R.layout.fragment_safetybelt, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 关键改进：检查容器是否已经持有 Fragment
        val currentChild = childFragmentManager.findFragmentById(R.id.safetybelt_fragment_container)
        
        Log.d("SafetybeltFragment", "onViewCreated - savedInstanceState: ${savedInstanceState != null}, currentChild: ${currentChild?.javaClass?.simpleName}")
        
        // 检查监控模式和会话状态
        val isInMonitoring = MainApplication.getInstance().isInMonitoringMode
        val currentSessionId = BleService.currentSessionId
        
        Log.d("SafetybeltFragment", "监控模式: $isInMonitoring, SessionId: $currentSessionId")

        if (savedInstanceState == null && currentChild == null) {
            // 首次创建且没有已有Fragment时，检查是否需要显示监控界面
            if (isInMonitoring && currentSessionId != null) {
                Log.d("SafetybeltFragment", "首次创建但需要显示监控界面")
                val monitoringFragment = MonitoringFragment()
                childFragmentManager.beginTransaction()
                    .replace(R.id.safetybelt_fragment_container, monitoringFragment, "monitoring")
                    .commit()
            } else {
                Log.d("SafetybeltFragment", "首次创建，显示DetectionFragment")
                childFragmentManager.beginTransaction()
                    .replace(R.id.safetybelt_fragment_container, DetectionFragment())
                    .commit()
            }
        } else if (savedInstanceState != null) {
            // 从系统恢复时，检查是否需要恢复监控界面
            Log.d("SafetybeltFragment", "从系统恢复，当前Child: ${currentChild?.javaClass?.simpleName}")
            
            if (currentChild == null && isInMonitoring && currentSessionId != null) {
                Log.d("SafetybeltFragment", "需要恢复监控界面")
                val monitoringFragment = MonitoringFragment()
                childFragmentManager.beginTransaction()
                    .replace(R.id.safetybelt_fragment_container, monitoringFragment, "monitoring")
                    .commit()
            } else {
                Log.d("SafetybeltFragment", "保持现有Fragment状态")
            }
        } else if (currentChild == null) {
            // currentChild为null但savedInstanceState也为null（Activity被系统杀死重建）
            Log.d("SafetybeltFragment", "Activity重建但无保存状态，检查监控模式")
            if (isInMonitoring && currentSessionId != null) {
                val monitoringFragment = MonitoringFragment()
                childFragmentManager.beginTransaction()
                    .replace(R.id.safetybelt_fragment_container, monitoringFragment, "monitoring")
                    .commit()
            } else {
                childFragmentManager.beginTransaction()
                    .replace(R.id.safetybelt_fragment_container, DetectionFragment())
                    .commit()
            }
        }
        // 如果currentChild不为null，说明Fragment已经存在，保持现状
    }
}
