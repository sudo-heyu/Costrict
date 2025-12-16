package com.heyu.safetybelt.regulator.adapter

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import cn.leancloud.LCObject
import cn.leancloud.LCQuery
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.heyu.safetybelt.R
import com.heyu.safetybelt.common.WorkerStatus
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
        val statusChip: Chip = view.findViewById(R.id.status_chip)
        val lastUpdated: TextView = view.findViewById(R.id.last_updated_text)
        val avatarIcon: ImageView = view.findViewById(R.id.avatar_icon)
        val sendAlertButton: Button = view.findViewById(R.id.button_send_alert)
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

        // Set OnClickListener for the whole item
        holder.itemView.setOnClickListener {
            onItemClick?.invoke(workerStatus) ?: showWorkerDetailDialog(holder, workerStatus)
        }

        // Set OnClickListener for the alert button
        holder.sendAlertButton.isEnabled = workerStatus.isOnline
        holder.sendAlertButton.setOnClickListener {
            sendAdminAlert(workerStatus, holder.sendAlertButton)
        }
    }

    private fun sendAdminAlert(workerStatus: WorkerStatus, button: Button) {
        val context = button.context
        if (workerStatus.sessionId != null) {
            val session = LCObject.createWithoutData("WorkSession", workerStatus.sessionId)
            session.put("adminAlert", true)
            
            button.isEnabled = false
            Toast.makeText(context, "正在发送报警指令...", Toast.LENGTH_SHORT).show()
            
            session.saveInBackground().subscribe(object : Observer<LCObject> {
                override fun onSubscribe(d: Disposable) {}
                override fun onNext(t: LCObject) {
                    handler.post {
                        Toast.makeText(context, "报警指令已发送给 ${workerStatus.workerName}", Toast.LENGTH_SHORT).show()
                        handler.postDelayed({ button.isEnabled = workerStatus.isOnline }, 2000)
                    }
                }
                override fun onError(e: Throwable) {
                    handler.post {
                        Toast.makeText(context, "发送失败: ${e.message}", Toast.LENGTH_SHORT).show()
                        button.isEnabled = workerStatus.isOnline
                    }
                }
                override fun onComplete() {}
            })
        } else {
            Toast.makeText(context, "无法获取会话ID，请刷新后重试", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showWorkerDetailDialog(holder: ViewHolder, workerStatus: WorkerStatus) {
        val context = holder.itemView.context
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_worker_detail_scrollable, null)

        // --- Sensor Status Setup ---
        val sensorViews = listOf<TextView>(
            dialogView.findViewById(R.id.dialog_sensor_1),
            dialogView.findViewById(R.id.dialog_sensor_2),
            dialogView.findViewById(R.id.dialog_sensor_3),
            dialogView.findViewById(R.id.dialog_sensor_4),
            dialogView.findViewById(R.id.dialog_sensor_5),
            dialogView.findViewById(R.id.dialog_sensor_6)
        )
        updateSensorViews(sensorViews, workerStatus)

        // --- Work History Setup ---
        // 找回丢失的逻辑：初始化历史记录列表和加载数据
        val workHistoryRecyclerView = dialogView.findViewById<RecyclerView>(R.id.work_history_recycler_view)
        val emptyHistoryView = dialogView.findViewById<TextView>(R.id.empty_history_view)
        workHistoryRecyclerView.layoutManager = LinearLayoutManager(context)
        val historyAdapter = WorkHistoryAdapter(emptyList())
        workHistoryRecyclerView.adapter = historyAdapter

        // 调用找回的方法加载数据
        fetchWorkHistory(workerStatus.workerId, historyAdapter, emptyHistoryView)

        // --- Dialog Creation ---
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
        
        // Track the active dialog for real-time updates
        activeDialog = dialog
        activeWorkerId = workerStatus.workerId
        activeDialogViews = sensorViews
    }

    // 找回丢失的方法：从 LeanCloud 获取历史记录
    private fun fetchWorkHistory(workerId: String, adapter: WorkHistoryAdapter, emptyView: View) {
        val workerPointer = LCObject.createWithoutData("_User", workerId)
        val query = LCQuery<LCObject>("WorkSession")
        query.whereEqualTo("worker", workerPointer)
        query.orderByDescending("startTime")
        query.limit = 50 // 限制显示最近50条
        query.findInBackground().subscribe(object : Observer<List<LCObject>> {
            override fun onSubscribe(d: Disposable) {}
            override fun onNext(sessions: List<LCObject>) {
                handler.post {
                    if (sessions.isEmpty()) {
                        emptyView.visibility = View.VISIBLE
                        adapter.updateData(emptyList())
                    } else {
                        emptyView.visibility = View.GONE
                        adapter.updateData(sessions)
                    }
                }
            }
            override fun onError(e: Throwable) {
                handler.post {
                    emptyView.visibility = View.VISIBLE
                    emptyView.findViewById<TextView>(R.id.empty_history_view).text = "加载历史记录失败"
                    Toast.makeText(emptyView.context, "历史记录加载失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onComplete() {}
        })
    }
    
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
            
            when (finalStatus) {
                "已扣好", "已挂好" -> textView.setTextColor(ContextCompat.getColor(context, R.color.status_normal))
                "未扣好", "未挂好", "未挂" -> textView.setTextColor(ContextCompat.getColor(context, R.color.status_danger))
                else -> textView.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            }
        }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        timers[holder]?.let { handler.removeCallbacks(it) }
        timers.remove(holder)
    }

    override fun getItemCount() = workerStatusList.size

    fun setItems(newItems: List<WorkerStatus>) {
        cleanup()
        workerStatusList.clear()
        workerStatusList.addAll(newItems)
        notifyDataSetChanged()
        
        if (activeDialog != null && activeWorkerId != null && activeDialogViews != null) {
            val updatedWorker = workerStatusList.find { it.workerId == activeWorkerId }
            if (updatedWorker != null) {
                updateSensorViews(activeDialogViews!!, updatedWorker)
            } else {
                // Worker is no longer in the list, close the dialog
                activeDialog?.dismiss()
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