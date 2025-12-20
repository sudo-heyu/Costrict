package com.heyu.safetybelt.regulator.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.heyu.safetybelt.R
import cn.leancloud.LCObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WorkHistoryAdapter(private var workSessions: List<LCObject>) : RecyclerView.Adapter<WorkHistoryAdapter.ViewHolder>() {

    private val dateFormatter = SimpleDateFormat("yyyy年M月d日", Locale.getDefault())
    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val dateText: TextView = view.findViewById(R.id.history_date_text)
        val startTimeText: TextView = view.findViewById(R.id.start_time_text)
        val endTimeText: TextView = view.findViewById(R.id.end_time_text)
        val durationText: TextView = view.findViewById(R.id.duration_text)
        val deviceAlertText: TextView = view.findViewById(R.id.device_alert_text)
        val adminAlertText: TextView = view.findViewById(R.id.admin_alert_text)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_work_history, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val session = workSessions[position]
        val context = holder.itemView.context

        val startTime = session.getDate("startTime")
        val endTime = session.getDate("endTime")
        val totalAlarmCount = session.getInt("totalAlarmCount")
        val isAdminAlert = session.getBoolean("adminAlert")

        val hasDeviceAlert = totalAlarmCount > 0

        // 1. 设置日期
        holder.dateText.text = startTime?.let { dateFormatter.format(it) } ?: "日期未知"
        
        // 2. 设置开始/结束时间 (强制格式化到秒)
        val startStr = startTime?.let { timeFormatter.format(it) } ?: "--:--:--"
        val endStr = endTime?.let { timeFormatter.format(it) } ?: "进行中"
        
        holder.startTimeText.text = "开始: $startStr"
        holder.endTimeText.text = "结束: $endStr"

        // 3. 优化时长计算逻辑：对齐到秒后再相减
        // 这样可以确保如果显示是 03秒 和 04秒，计算结果一定是 1秒，而不会因为毫秒差值产生 0秒的情况
        if (startTime != null) {
            val effectiveEndTime = endTime ?: Date()
            
            val startSeconds = startTime.time / 1000
            val endSeconds = effectiveEndTime.time / 1000
            val diffSeconds = endSeconds - startSeconds

            if (diffSeconds >= 0) {
                val h = diffSeconds / 3600
                val m = (diffSeconds % 3600) / 60
                val s = diffSeconds % 60

                val durationTextValue = when {
                    h > 0 -> "${h}小时${m}分钟${s}秒"
                    m > 0 -> "${m}分钟${s}秒"
                    else -> "${s}秒"
                }
                holder.durationText.text = "时长: $durationTextValue"
            } else {
                holder.durationText.text = "时长: 0秒"
            }
        } else {
            holder.durationText.text = "时长: 未知"
        }

        // 4. 报警状态
        holder.deviceAlertText.text = "发生报警: " + (if (hasDeviceAlert) "是" else "否")
        holder.deviceAlertText.setTextColor(
            if (hasDeviceAlert) ContextCompat.getColor(context, R.color.status_danger)
            else ContextCompat.getColor(context, R.color.text_secondary)
        )

        holder.adminAlertText.text = "远程报警: " + (if (isAdminAlert) "是" else "否")
        holder.adminAlertText.setTextColor(
            if (isAdminAlert) ContextCompat.getColor(context, R.color.status_danger)
            else ContextCompat.getColor(context, R.color.text_secondary)
        )
    }

    override fun getItemCount() = workSessions.size

    fun updateData(newSessions: List<LCObject>) {
        workSessions = newSessions
        notifyDataSetChanged()
    }
}
