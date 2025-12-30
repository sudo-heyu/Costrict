package com.heyu.safetybelt.operator.adapter

import android.app.AlertDialog
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.heyu.safetybelt.R
import com.heyu.safetybelt.operator.model.WorkRecordManager
import com.heyu.safetybelt.operator.fragment.AlarmDetailDialogFragment
import com.heyu.safetybelt.operator.model.WorkRecord

class WorkRecordAdapter(private var recordList: MutableList<WorkRecord>) :
    RecyclerView.Adapter<WorkRecordAdapter.RecordViewHolder>() {

    class RecordViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvDate: TextView = itemView.findViewById(R.id.tvDate)
        val tvTimeRange: TextView = itemView.findViewById(R.id.tvTimeRange)
        val tvDuration: TextView = itemView.findViewById(R.id.tvDuration)
        val tvAlertCount: TextView = itemView.findViewById(R.id.tvAlertCount)
        val btnDelete: ImageButton = itemView.findViewById(R.id.btnDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecordViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_work_record, parent, false)
        return RecordViewHolder(view)
    }

    override fun onBindViewHolder(holder: RecordViewHolder, position: Int) {
        val record = recordList[position]

        holder.tvDate.text = record.getDateString()
        holder.tvTimeRange.text = record.getTimeRangeString()
        holder.tvDuration.text = record.getDurationString()
        
        // 删除按钮逻辑
        holder.btnDelete.setOnClickListener {
            val context = holder.itemView.context
            AlertDialog.Builder(context)
                .setTitle("删除记录")
                .setMessage("确定要删除这条工作记录吗？")
                .setPositiveButton("删除") { _, _ ->
                    WorkRecordManager.deleteRecord(context, record.id)
                    recordList.removeAt(position)
                    notifyItemRemoved(position)
                    notifyItemRangeChanged(position, itemCount)
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // 显示真实的报警数量
        val count = record.getRealAlertCount()
        holder.tvAlertCount.text = "$count 次 >"
        
        if (count > 0) {
            holder.tvAlertCount.setTextColor(holder.itemView.context.resources.getColor(android.R.color.holo_red_dark, null))
        } else {
            holder.tvAlertCount.setTextColor(ContextCompat.getColor(holder.itemView.context, R.color.text_primary_dark))
        }
        
        // 无论次数是否大于0，都允许点击查看详情
        holder.tvAlertCount.setOnClickListener {
            val activity = holder.itemView.context as? AppCompatActivity
            if (activity != null) {
                val dialog = AlarmDetailDialogFragment(record.safeAlarmList)
                dialog.show(activity.supportFragmentManager, "AlarmDetailDialog")
            }
        }
    }

    override fun getItemCount(): Int = recordList.size

    fun updateData(newList: MutableList<WorkRecord>) {
        recordList = newList
        notifyDataSetChanged()
    }
}
