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
import java.util.concurrent.TimeUnit

class WorkHistoryAdapter(private var workSessions: List<LCObject>) : RecyclerView.Adapter<WorkHistoryAdapter.ViewHolder>() {

    private val dateFormatter = SimpleDateFormat("yyyy年M月d日", Locale.getDefault())
    private val timeFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())

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

        // Set Date and Times
        holder.dateText.text = startTime?.let { dateFormatter.format(it) } ?: "日期未知"
        holder.startTimeText.text = context.getString(R.string.work_history_start_time, startTime?.let { timeFormatter.format(it) } ?: "--:--")
        holder.endTimeText.text = context.getString(R.string.work_history_end_time, endTime?.let { timeFormatter.format(it) } ?: "进行中")

        // Set Duration
        if (startTime != null && endTime != null) {
            if (endTime.after(startTime)) {
                val durationMillis = endTime.time - startTime.time
                val hours = TimeUnit.MILLISECONDS.toHours(durationMillis)
                val minutes = TimeUnit.MILLISECONDS.toMinutes(durationMillis) % 60
                holder.durationText.text = context.getString(R.string.work_history_duration, hours, minutes)
            } else {
                holder.durationText.text = context.getString(R.string.work_history_duration_unknown)
            }
        } else if (startTime != null) {
            val durationMillis = System.currentTimeMillis() - startTime.time
            val hours = TimeUnit.MILLISECONDS.toHours(durationMillis)
            val minutes = TimeUnit.MILLISECONDS.toMinutes(durationMillis) % 60
            holder.durationText.text = context.getString(R.string.work_history_duration, hours, minutes)
        } else {
            holder.durationText.text = context.getString(R.string.work_history_duration_unknown)
        }

        // Set Device Alert
        holder.deviceAlertText.text = context.getString(R.string.work_history_device_alert, if (hasDeviceAlert) "是" else "否")
        holder.deviceAlertText.setTextColor(
            if (hasDeviceAlert) ContextCompat.getColor(context, R.color.status_danger)
            else ContextCompat.getColor(context, R.color.text_secondary)
        )

        // Set Admin Alert
        holder.adminAlertText.text = context.getString(R.string.work_history_admin_alert, if (isAdminAlert) "是" else "否")
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
