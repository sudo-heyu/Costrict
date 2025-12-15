package com.heyu.safetybelt.operator.adapter

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.heyu.safetybelt.R
import com.heyu.safetybelt.operator.bean.DeviceScanResult
import com.heyu.safetybelt.operator.util.MonitoringUtil

class ScanDeviceAdapter(
    private val onDeviceClicked: (DeviceScanResult) -> Unit
) : RecyclerView.Adapter<ScanDeviceAdapter.ViewHolder>() {

    private val devices = mutableListOf<DeviceScanResult>()
    private val sensorNameMap = mapOf(
        1 to "后背绳高挂低用",
        2 to "后背绳小挂钩",
        3 to "围杆带环抱",
        4 to "围杆带小挂钩",
        5 to "胸扣",
        6 to "后背绳大挂钩"
    )

    @SuppressLint("NotifyDataSetChanged")
    fun updateList(newDevices: List<DeviceScanResult>) {
        devices.clear()
        devices.addAll(newDevices)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.list_item_scan, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(devices[position])
    }

    override fun getItemCount(): Int = devices.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameTextView: TextView = itemView.findViewById(R.id.device_name)
        private val addressTextView: TextView = itemView.findViewById(R.id.device_address)
        private val signalTextView: TextView = itemView.findViewById(R.id.device_signal)
        private val addButton: ImageButton = itemView.findViewById(R.id.add_button)

        fun bind(device: DeviceScanResult) {
            val sensorNumber = device.bestName.split(" ").getOrNull(1)?.split("_")?.getOrNull(0)?.toIntOrNull()
            val chineseName = sensorNameMap[sensorNumber] ?: "未知设备"
            val displayName = "$chineseName   ${device.bestName}"
            nameTextView.text = displayName

            addressTextView.text = device.device.address

            val signalStrength = MonitoringUtil.getSignalStrengthString(device.rssi)
            val signalColor = MonitoringUtil.getSignalStrengthColor(signalStrength)

            signalTextView.text = signalStrength
            signalTextView.setTextColor(signalColor)

            addButton.setOnClickListener { onDeviceClicked(device) }
        }
    }
}
