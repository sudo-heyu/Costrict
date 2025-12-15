package com.heyu.safetybelt.operator.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.heyu.safetybelt.R
import com.heyu.safetybelt.operator.model.AlarmDetail
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AlarmDetailDialogFragment(private val alarmList: List<AlarmDetail>) : DialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_alarm_detail, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        view.findViewById<View>(R.id.btnClose).setOnClickListener { dismiss() }
        
        val recyclerView = view.findViewById<RecyclerView>(R.id.rvAlarmDetails)
        recyclerView.layoutManager = LinearLayoutManager(context)
        
        if (alarmList.isEmpty()) {
            view.findViewById<View>(R.id.tvEmpty).visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        } else {
            view.findViewById<View>(R.id.tvEmpty).visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
            recyclerView.adapter = AlarmDetailAdapter(alarmList)
        }
    }
    
    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.95).toInt(), // 加宽一点以适应更多列
            (resources.displayMetrics.heightPixels * 0.7).toInt()
        )
    }
}

class AlarmDetailAdapter(private val list: List<AlarmDetail>) : 
    RecyclerView.Adapter<AlarmDetailAdapter.ViewHolder>() {

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvTime: TextView = itemView.findViewById(R.id.tvAlarmTime)
        val tvSensor: TextView = itemView.findViewById(R.id.tvAlarmSensor)
        val tvType: TextView = itemView.findViewById(R.id.tvAlarmType)
        val tvDuration: TextView = itemView.findViewById(R.id.tvAlarmDuration)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_alarm_detail, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = list[position]
        holder.tvTime.text = item.getTimeString()
        holder.tvSensor.text = item.sensorName
        holder.tvType.text = item.alarmType
        holder.tvDuration.text = item.getDurationString()
        
        // 偶数行设置不同背景色，增加可读性
        if (position % 2 == 1) {
            holder.itemView.setBackgroundColor(0xFFF5F5F5.toInt())
        } else {
            holder.itemView.setBackgroundColor(0xFFFFFFFF.toInt())
        }
    }

    override fun getItemCount(): Int = list.size
}
