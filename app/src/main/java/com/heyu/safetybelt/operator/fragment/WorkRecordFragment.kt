package com.heyu.safetybelt.operator.fragment

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.heyu.safetybelt.R
import com.heyu.safetybelt.operator.adapter.WorkRecordAdapter
import com.heyu.safetybelt.operator.model.WorkRecordManager
import com.heyu.safetybelt.operator.model.WorkRecord

class WorkRecordFragment : Fragment() {

    private lateinit var adapter: WorkRecordAdapter
    private lateinit var recyclerView: RecyclerView
    
    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (::adapter.isInitialized) {
                // 仅仅是通知列表刷新，因为 WorkRecord.getDurationString() 是根据当前时间动态计算的
                adapter.notifyDataSetChanged()
            }
            // 每秒刷新一次
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // 加载布局
        return inflater.inflate(R.layout.fragment_work_record, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 初始化 RecyclerView
        recyclerView = view.findViewById(R.id.rvWorkRecords)
        recyclerView.layoutManager = LinearLayoutManager(context)
    }

    /**
     * 每次界面显示时（包括从别的页面切回来），都重新读取本地数据。
     * 这样能保证看到最新的记录。
     */
    override fun onResume() {
        super.onResume()
        loadData()
        startTimer()
    }
    
    override fun onPause() {
        super.onPause()
        stopTimer()
    }

    /**
     * 当 Fragment 通过 FragmentTransaction.show() / hide() 切换时调用。
     * hidden = false 表示 Fragment 变为可见。
     */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            loadData()
            startTimer()
        } else {
            stopTimer()
        }
    }
    
    private fun startTimer() {
        stopTimer() // 防止重复
        handler.post(refreshRunnable)
    }
    
    private fun stopTimer() {
        handler.removeCallbacks(refreshRunnable)
    }

    private fun loadData() {
        // 从 WorkRecordManager 获取真实的本地数据
        context?.let { ctx ->
            val realData = WorkRecordManager.getAllRecords(ctx)

            // 更新列表
            if (!::adapter.isInitialized) {
                adapter = WorkRecordAdapter(realData)
                recyclerView.adapter = adapter
            } else {
                // 确保传递的是 MutableList
                adapter.updateData(realData)
            }
        }
    }
}
