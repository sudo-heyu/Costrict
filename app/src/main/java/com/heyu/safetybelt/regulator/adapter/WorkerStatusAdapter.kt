package com.heyu.safetybelt.regulator.adapter

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import cn.leancloud.LCObject
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.heyu.safetybelt.R
import com.heyu.safetybelt.regulator.model.WorkerStatus
import io.reactivex.Observer
import io.reactivex.disposables.Disposable
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class WorkerStatusAdapter(
    private val workerStatusList: MutableList<WorkerStatus>,
    private val onItemClick: ((WorkerStatus) -> Unit)? = null
) : RecyclerView.Adapter<WorkerStatusAdapter.ViewHolder>() {

    private val handler = Handler(Looper.getMainLooper())
    private val timers = ConcurrentHashMap<ViewHolder, Runnable>()

    // 记录当前打开的对话框信息
    private var activeDialog: AlertDialog? = null
    private var activeWorkerId: String? = null
    private var activeDialogViews: List<TextView>? = null

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val workerName: TextView = view.findViewById(R.id.worker_name_text)
        val workerNumber: TextView = view.findViewById(R.id.worker_number_text)
        // 使用 Chip 来显示状态，替代原来的 View + TextView 组合
        val statusChip: Chip = view.findViewById(R.id.status_chip)
        val lastUpdated: TextView = view.findViewById(R.id.last_updated_text)
        val avatarIcon: ImageView = view.findViewById(R.id.avatar_icon)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_worker_status, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val workerStatus = workerStatusList[position]
        val context = holder.itemView.context
        
        holder.workerName.text = workerStatus.workerName
        holder.workerNumber.text = "工号: ${workerStatus.workerNumber}"

        val isOnline = workerStatus.status != "离线"

        // 根据状态设置 Chip 的颜色和文字
        holder.statusChip.text = workerStatus.status
        
        // 获取颜色资源
        val colorNormal = ContextCompat.getColor(context, R.color.status_normal)
        val colorWarning = ContextCompat.getColor(context, R.color.status_warning)
        val colorDanger = ContextCompat.getColor(context, R.color.status_danger)
        val colorOffline = ContextCompat.getColor(context, R.color.status_offline)
        val colorWhite = ContextCompat.getColor(context, R.color.white)

        when (workerStatus.status) {
            "正常", "在线-正常" -> {
                holder.statusChip.chipBackgroundColor = ColorStateList.valueOf(colorNormal)
                holder.avatarIcon.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#E8F5E9")) // 浅绿背景
                holder.avatarIcon.imageTintList = ColorStateList.valueOf(colorNormal)
            }
            "单挂" -> {
                holder.statusChip.chipBackgroundColor = ColorStateList.valueOf(colorWarning)
                holder.avatarIcon.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FFF3E0")) // 浅橙背景
                holder.avatarIcon.imageTintList = ColorStateList.valueOf(colorWarning)
            }
            "异常" -> {
                holder.statusChip.chipBackgroundColor = ColorStateList.valueOf(colorDanger)
                holder.avatarIcon.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FFEBEE")) // 浅红背景
                holder.avatarIcon.imageTintList = ColorStateList.valueOf(colorDanger)
            }
            else -> {
                holder.statusChip.text = "离线"
                holder.statusChip.chipBackgroundColor = ColorStateList.valueOf(colorOffline)
                holder.avatarIcon.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#F5F5F5")) // 浅灰背景
                holder.avatarIcon.imageTintList = ColorStateList.valueOf(Color.GRAY)
            }
        }
        
        // 确保文字颜色为白色
        holder.statusChip.setTextColor(colorWhite)

        // 计时器逻辑
        timers[holder]?.let { handler.removeCallbacks(it) }
        timers.remove(holder)

        if (isOnline && workerStatus.lastUpdatedAt != null) {
            val startTime = workerStatus.lastUpdatedAt.time
            val timerRunnable = object : Runnable {
                override fun run() {
                    val elapsedMillis = System.currentTimeMillis() - startTime
                    val millis = if (elapsedMillis < 0) 0 else elapsedMillis
                    
                    val hours = millis / (1000 * 60 * 60)
                    val minutes = (millis / (1000 * 60)) % 60
                    val seconds = (millis / 1000) % 60
                    holder.lastUpdated.text = String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
                    
                    handler.postDelayed(this, 1000)
                }
            }
            timers[holder] = timerRunnable
            handler.post(timerRunnable)
        } else {
            holder.lastUpdated.text = "--:--:--"
        }

        holder.itemView.setOnClickListener {
            onItemClick?.invoke(workerStatus) ?: showSensorDetailsDialog(holder, workerStatus)
        }
    }

    private fun showSensorDetailsDialog(holder: ViewHolder, workerStatus: WorkerStatus) {
        val context = holder.itemView.context
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_sensor_details, null)

        val sensor1 = dialogView.findViewById<TextView>(R.id.dialog_sensor_1)
        val sensor2 = dialogView.findViewById<TextView>(R.id.dialog_sensor_2)
        val sensor3 = dialogView.findViewById<TextView>(R.id.dialog_sensor_3)
        val sensor4 = dialogView.findViewById<TextView>(R.id.dialog_sensor_4)
        val sensor5 = dialogView.findViewById<TextView>(R.id.dialog_sensor_5)
        val sensor6 = dialogView.findViewById<TextView>(R.id.dialog_sensor_6)

        // 恢复：发送报警按钮逻辑
        val btnSendAlert = dialogView.findViewById<MaterialButton>(R.id.btn_send_alert)
        
        // 根据在线状态决定按钮是否可用
        btnSendAlert.isEnabled = workerStatus.isOnline
        if (!workerStatus.isOnline) {
            btnSendAlert.alpha = 0.5f
            btnSendAlert.text = "设备离线"
        }

        btnSendAlert.setOnClickListener {
            if (workerStatus.sessionId != null) {
                // 创建一个只包含 objectId 的 WorkSession 对象来更新
                val session = LCObject.createWithoutData("WorkSession", workerStatus.sessionId)
                session.put("adminAlert", true)
                
                // 禁用按钮防止重复点击
                btnSendAlert.isEnabled = false
                btnSendAlert.text = "发送中..."
                
                session.saveInBackground().subscribe(object : Observer<LCObject> {
                    override fun onSubscribe(d: Disposable) {}
                    override fun onNext(t: LCObject) {
                        handler.post {
                            Toast.makeText(context, "报警指令已发送", Toast.LENGTH_SHORT).show()
                            btnSendAlert.text = "已发送"
                            // 延迟两秒恢复，允许再次发送
                            handler.postDelayed({ 
                                if (activeDialog != null && activeDialog!!.isShowing) {
                                    btnSendAlert.isEnabled = true
                                    btnSendAlert.text = "发送远程报警"
                                }
                            }, 2000)
                        }
                    }
                    override fun onError(e: Throwable) {
                        handler.post {
                            Toast.makeText(context, "发送失败: ${e.message}", Toast.LENGTH_SHORT).show()
                            btnSendAlert.isEnabled = true
                            btnSendAlert.text = "发送远程报警"
                        }
                    }
                    override fun onComplete() {}
                })
            } else {
                Toast.makeText(context, "无法获取会话ID，请刷新后重试", Toast.LENGTH_SHORT).show()
            }
        }

        // 保存 TextView 列表以便后续更新
        val views = listOf(sensor1, sensor2, sensor3, sensor4, sensor5, sensor6)
        
        // 初始设置状态
        updateSensorViews(views, workerStatus)

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(workerStatus.workerName) 
            .setView(dialogView)
            .setPositiveButton("关闭", null)
            .create()
        
        dialog.setOnDismissListener {
            if (activeDialog == dialog) {
                activeDialog = null
                activeWorkerId = null
                activeDialogViews = null
            }
        }
        
        dialog.show()
        
        // 记录当前打开的对话框
        activeDialog = dialog
        activeWorkerId = workerStatus.workerId
        activeDialogViews = views
    }

    // 提取更新逻辑为独立方法
    private fun updateSensorViews(views: List<TextView>, status: WorkerStatus) {
        if (views.isEmpty()) return
        val context = views[0].context
        
        val statuses = listOf(
            status.sensor1Status, status.sensor2Status, status.sensor3Status,
            status.sensor4Status, status.sensor5Status, status.sensor6Status
        )
        
        val defaultStatus = "状态未知"

        for (i in views.indices) {
            val textView = views[i]
            val s = statuses.getOrNull(i)
            val finalStatus = s ?: defaultStatus
            textView.text = finalStatus
            
            // 根据状态设置颜色
            when (finalStatus) {
                "已扣好", "已挂好" -> {
                    textView.setTextColor(ContextCompat.getColor(context, R.color.status_normal))
                }
                "未扣好", "未挂好", "未挂" -> {
                    textView.setTextColor(ContextCompat.getColor(context, R.color.status_danger))
                }
                else -> {
                     textView.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                }
            }
        }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        timers[holder]?.let { handler.removeCallbacks(it) }
        timers.remove(holder)
    }

    override fun getItemCount() = workerStatusList.size

    fun addItem(workerStatus: WorkerStatus) {
        val existingIndex = workerStatusList.indexOfFirst { it.workerId == workerStatus.workerId }
        if (existingIndex != -1) {
            workerStatusList[existingIndex] = workerStatus
            notifyItemChanged(existingIndex)
        } else {
            workerStatusList.add(workerStatus)
            notifyItemInserted(workerStatusList.size - 1)
        }
        
        // 如果当前有打开的对话框，且对应这个工人的更新，则刷新对话框内容
        if (activeDialog != null && activeWorkerId == workerStatus.workerId && activeDialogViews != null) {
            updateSensorViews(activeDialogViews!!, workerStatus)
        }
    }

    fun setItems(newItems: List<WorkerStatus>) {
        cleanup()
        workerStatusList.clear()
        workerStatusList.addAll(newItems)
        notifyDataSetChanged()
        
        // 如果当前有打开的对话框，在新的列表中查找该工人并刷新内容
        if (activeDialog != null && activeWorkerId != null && activeDialogViews != null) {
            val updatedWorker = workerStatusList.find { it.workerId == activeWorkerId }
            if (updatedWorker != null) {
                updateSensorViews(activeDialogViews!!, updatedWorker)
            }
        }
    }

    fun getItems(): List<WorkerStatus> {
        return workerStatusList.toList()
    }

    fun cleanup() {
        timers.values.forEach { handler.removeCallbacks(it) }
        timers.clear()
    }
}
