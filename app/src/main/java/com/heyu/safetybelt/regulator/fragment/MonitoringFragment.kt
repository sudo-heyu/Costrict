package com.heyu.safetybelt.regulator.fragment

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.heyu.safetybelt.R
import com.heyu.safetybelt.regulator.adapter.WorkerStatusAdapter
import com.heyu.safetybelt.databinding.FragmentMonitoringBinding
import com.heyu.safetybelt.common.WorkerStatus
import com.heyu.safetybelt.regulator.service.UnderService

class MonitoringFragment : Fragment(), UnderService.WorkerListListener {

    private var _binding: FragmentMonitoringBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: WorkerStatusAdapter
    private var underService: UnderService? = null
    private var isServiceBound = false

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

        binding.recyclerView.layoutManager = LinearLayoutManager(context)
        binding.recyclerView.adapter = adapter

        binding.fab.setOnClickListener { showAddWorkerDialog() }
        binding.fabRemove.setOnClickListener { showRemoveWorkerDialog() }
        binding.fabRemoveAll.setOnClickListener { showRemoveAllWorkersDialog() }

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
            Toast.makeText(context, "未找到该工人", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onWorkerAlreadyExists() {
        activity?.runOnUiThread {
            Toast.makeText(context, "该工人已在列表中", Toast.LENGTH_SHORT).show()
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

    private fun showRemoveWorkerDialog() {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_remove_worker_list, null)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.workers_recycler_view)
        val workers = underService?.getWorkerList() ?: emptyList()

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("选择要移除的工人")
            .setView(dialogView)
            .setNegativeButton("取消", null)
            .create()

        val removeAdapter = WorkerStatusAdapter(workers.toMutableList()) { worker ->
            underService?.removeWorker(worker.workerId)
            dialog.dismiss()
        }

        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.adapter = removeAdapter

        dialog.show()
    }

    private fun showRemoveAllWorkersDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("确认操作")
            .setMessage("确定要断开与所有工人的连接吗？")
            .setPositiveButton("确定") { _, _ ->
                underService?.removeAllWorkers()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun updateEmptyViewVisibility() {
        val hasWorkers = adapter.itemCount > 0
        binding.emptyView.visibility = if (hasWorkers) View.GONE else View.VISIBLE
        binding.recyclerView.visibility = if (hasWorkers) View.VISIBLE else View.GONE
        binding.fabRemove.visibility = if (hasWorkers) View.VISIBLE else View.GONE
        binding.fabRemoveAll.visibility = if (hasWorkers) View.VISIBLE else View.GONE
    }
}