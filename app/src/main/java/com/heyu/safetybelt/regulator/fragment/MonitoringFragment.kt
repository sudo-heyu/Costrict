package com.heyu.safetybelt.regulator.fragment

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.IBinder
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.heyu.safetybelt.R
import com.heyu.safetybelt.common.WorkerStatus
import com.heyu.safetybelt.databinding.FragmentMonitoringBinding
import com.heyu.safetybelt.regulator.adapter.WorkerStatusAdapter
import com.heyu.safetybelt.regulator.service.UnderService

class MonitoringFragment : Fragment(), UnderService.WorkerListListener {

    private var _binding: FragmentMonitoringBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: WorkerStatusAdapter
    private var underService: UnderService? = null
    private var isServiceBound = false
    private var deleteIcon: Drawable? = null
    private val swipePaint = Paint().apply {
        color = Color.parseColor("#E57373") // 更柔和的红色
        isAntiAlias = true
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as UnderService.LocalBinder
            underService = binder.getService()
            isServiceBound = true
            underService?.setWorkerListListener(this@MonitoringFragment)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isServiceBound = false
            underService?.removeWorkerListListener()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        adapter = WorkerStatusAdapter(mutableListOf())
        deleteIcon = ContextCompat.getDrawable(requireContext(), android.R.drawable.ic_menu_delete)
        deleteIcon?.setTint(Color.WHITE)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMonitoringBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Setup Toolbar
        binding.toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                // Removed action_add_worker handling as requested
                R.id.action_clear_all -> {
                    showRemoveAllWorkersDialog()
                    true
                }
                else -> false
            }
        }

        binding.recyclerView.layoutManager = LinearLayoutManager(context)
        binding.recyclerView.adapter = adapter

        binding.fab.setOnClickListener { showAddWorkerDialog() }
        
        // 设置减号按钮点击事件
        binding.fabRemove.setOnClickListener { showRemoveWorkerSelectionDialog() }

        // Setup ItemTouchHelper for swipe-to-delete with confirmation
        val itemTouchHelperCallback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                return false
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.adapterPosition
                if (position == RecyclerView.NO_POSITION || position >= adapter.itemCount) {
                    return
                }

                val workerToDelete = adapter.getItems()[position]
                
                AlertDialog.Builder(requireContext())
                    .setTitle("移除工人")
                    .setMessage("确定要停止监控工人 ${workerToDelete.workerName} 吗？")
                    .setPositiveButton("确定") { _, _ ->
                        underService?.removeWorker(workerToDelete.workerId)
                        Snackbar.make(view, "已移除 ${workerToDelete.workerName}", Snackbar.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("取消") { dialog, _ ->
                        dialog.dismiss()
                        adapter.notifyItemChanged(position)
                    }
                    .setOnCancelListener { 
                        adapter.notifyItemChanged(position)
                    }
                    .show()
            }

            override fun onChildDraw(
                c: Canvas,
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
                    val itemView = viewHolder.itemView
                    
                    // Convert 12dp corner radius to pixels
                    val r = TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP, 
                        12f, 
                        resources.displayMetrics
                    )
                    
                    // Convert 8dp margin vertical to pixels
                    val marginV = TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP,
                        8f,
                        resources.displayMetrics
                    )
                    
                    if (dX < 0) { // Swiping to the left
                        // Draw the rounded background
                        val background = RectF(
                            itemView.right.toFloat() + dX,
                            itemView.top.toFloat() + marginV,
                            itemView.right.toFloat(),
                            itemView.bottom.toFloat() - marginV
                        )
                        c.drawRoundRect(background, r, r, swipePaint)

                        // Draw the icon
                        val iconMargin = (itemView.height - 2 * marginV - (deleteIcon?.intrinsicHeight ?: 0)) / 2
                        val iconTop = itemView.top + marginV + iconMargin
                        val iconBottom = iconTop + (deleteIcon?.intrinsicHeight ?: 0)
                        val iconRight = itemView.right - iconMargin
                        val iconLeft = iconRight - (deleteIcon?.intrinsicWidth ?: 0)

                        deleteIcon?.setBounds(
                            iconLeft.toInt(),
                            iconTop.toInt(),
                            iconRight.toInt(),
                            iconBottom.toInt()
                        )
                        deleteIcon?.draw(c)
                    }
                }
                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            }
        }

        val itemTouchHelper = ItemTouchHelper(itemTouchHelperCallback)
        itemTouchHelper.attachToRecyclerView(binding.recyclerView)

        binding.swipeRefreshLayout.setOnRefreshListener {
            underService?.getWorkerList()?.let { onWorkerListUpdated(it) }
            binding.swipeRefreshLayout.isRefreshing = false
        }

        updateEmptyViewVisibility()
    }

    override fun onStart() {
        super.onStart()
        Intent(activity, UnderService::class.java).also {
            activity?.bindService(it, connection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (isServiceBound) {
            activity?.unbindService(connection)
            isServiceBound = false
        }
        underService?.removeWorkerListListener()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onWorkerListUpdated(workerList: List<WorkerStatus>) {
        activity?.runOnUiThread {
            adapter.setItems(workerList)
            updateEmptyViewVisibility()
        }
    }

    override fun onWorkerNotFound() {
        activity?.runOnUiThread {
            val ctx = context
            if (isAdded && ctx != null) {
                AlertDialog.Builder(ctx)
                    .setTitle("添加失败")
                    .setMessage("未能在云端数据库中找到该工人（姓名或工号不匹配）。")
                    .setPositiveButton("确定", null)
                    .show()
            }
        }
    }
    override fun onWorkerLogout() {
        activity?.runOnUiThread {
            if (isAdded && context != null) {
                AlertDialog.Builder(requireContext())
                    .setTitle("添加失败")
                    .setMessage("该工人处于离线状态，未查找到相关在线作业。")
                    .setPositiveButton("确定", null)
                    .show()
            }
        }
    }

    override fun onWorkerAlreadyExists() {
        activity?.runOnUiThread {
            val ctx = context
            if (isAdded && ctx != null) {
                AlertDialog.Builder(ctx)
                    .setTitle("提示")
                    .setMessage("该工人已在监控列表中，无需重复添加。")
                    .setPositiveButton("确定", null)
                    .show()
            }
        }
    }

    private fun showAddWorkerDialog() {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_add_worker, null)
        val workerNameInput = dialogView.findViewById<EditText>(R.id.worker_name_input)
        val workerNumberInput = dialogView.findViewById<EditText>(R.id.worker_number_input)

        AlertDialog.Builder(requireContext())
            .setTitle("添加工人")
            .setView(dialogView)
            .setPositiveButton("添加") { _, _ ->
                val workerName = workerNameInput.text.toString().trim()
                val workerNumber = workerNumberInput.text.toString().trim()
                if (workerName.isNotEmpty() && workerNumber.isNotEmpty()) {
                    underService?.findAndAddWorker(workerName, workerNumber)
                } else {
                    Toast.makeText(context, "姓名和工号不能为空", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    private fun showRemoveAllWorkersDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("警告")
            .setMessage("确定要断开与所有工人的连接并清除列表吗？此操作无法撤销。")
            .setPositiveButton("清除所有") { _, _ ->
                underService?.removeAllWorkers()
                Snackbar.make(binding.root, "已清除所有工人", Snackbar.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showRemoveWorkerSelectionDialog() {
        val workerList = adapter.getItems()
        if (workerList.isEmpty()) {
            Toast.makeText(context, "当前没有监控的工人", Toast.LENGTH_SHORT).show()
            return
        }

        val workerNames = workerList.map { "${it.workerName} (${it.workerId})" }.toTypedArray()
        val selectedWorkers = mutableListOf<WorkerStatus>()

        AlertDialog.Builder(requireContext())
            .setTitle("选择要删除的工人")
            .setMultiChoiceItems(workerNames, null) { _, which, isChecked ->
                if (isChecked) {
                    selectedWorkers.add(workerList[which])
                } else {
                    selectedWorkers.remove(workerList[which])
                }
            }
            .setPositiveButton("删除选中") { _, _ ->
                if (selectedWorkers.isNotEmpty()) {
                    showRemoveWorkersConfirmationDialog(selectedWorkers)
                } else {
                    Toast.makeText(context, "请选择要删除的工人", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showRemoveWorkersConfirmationDialog(workersToRemove: List<WorkerStatus>) {
        val workerNames = workersToRemove.joinToString(", ") { it.workerName }
        
        AlertDialog.Builder(requireContext())
            .setTitle("确认删除")
            .setMessage("确定要停止监控以下工人吗？\n$workerNames")
            .setPositiveButton("确定") { _, _ ->
                workersToRemove.forEach { worker ->
                    underService?.removeWorker(worker.workerId)
                }
                val message = if (workersToRemove.size == 1) {
                    "已移除 ${workersToRemove[0].workerName}"
                } else {
                    "已移除 ${workersToRemove.size} 个工人"
                }
                Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun updateEmptyViewVisibility() {
        val hasWorkers = adapter.itemCount > 0
        binding.emptyView.visibility = if (hasWorkers) View.GONE else View.VISIBLE
        binding.recyclerView.visibility = if (hasWorkers) View.VISIBLE else View.GONE
        
        // 更新空状态提示文本
        binding.emptyView.text = if (hasWorkers) {
            ""
        } else {
            "点击右下角 '+' 添加要监控的工人\n或点击 '-' 移除已监控的工人"
        }
    }
}