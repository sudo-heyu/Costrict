package com.heyu.safetybelt.operator.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.heyu.safetybelt.R

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
        // 如果 childFragmentManager 已经有管理的 Fragment 了，说明是从后台切回来的，不要重置
        val currentChild = childFragmentManager.findFragmentById(R.id.safetybelt_fragment_container)

        if (savedInstanceState == null && currentChild == null) {
            childFragmentManager.beginTransaction()
                .replace(R.id.safetybelt_fragment_container, DetectionFragment())
                .commit()
        }
    }
}
