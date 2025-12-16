package com.heyu.safetybelt.operator.adapter

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.heyu.safetybelt.R
import com.heyu.safetybelt.operator.model.DeviceScanResult

/**
 * 用于在蓝牙扫描页面的上半部分，显示用户已选择并归类的设备列表的 RecyclerView 适配器。
 *
 * @param onDeviceClicked 当用户点击设备列表项中的“移除”按钮时触发的回调函数。
 */
class StatusDeviceAdapter(
    private val onDeviceClicked: (DeviceScanResult) -> Unit
) : RecyclerView.Adapter<StatusDeviceAdapter.ViewHolder>() {

    private val devices = mutableListOf<DeviceScanResult>()

    /**
     * 更新适配器中已选设备的列表。
     * 此方法会清空旧列表，并用新数据完全替换。
     * @param newDevices 最新的已选设备列表。
     */
    @SuppressLint("NotifyDataSetChanged")
    fun updateList(newDevices: List<DeviceScanResult>) {
        devices.clear()
        devices.addAll(newDevices)
        notifyDataSetChanged() // 通知 RecyclerView 刷新整个列表
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.list_item_status, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(devices[position])
    }

    override fun getItemCount(): Int = devices.size

    /**
     * ViewHolder 内部类，负责管理已选设备列表项的视图。
     */
    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameTextView: TextView = itemView.findViewById(R.id.device_name_text_view)
        private val removeButton: ImageButton = itemView.findViewById(R.id.remove_button)

        /**
         * 将一个已选设备的数据绑定到列表项的视图上。
         * @param device 要显示的设备扫描结果对象。
         */
        fun bind(device: DeviceScanResult) {
            // 显示设备名称
            nameTextView.text = device.bestName
            // 为“移除”按钮设置点击事件监听器
            removeButton.setOnClickListener { onDeviceClicked(device) }
        }
    }
}
