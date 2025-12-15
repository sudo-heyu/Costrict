package com.heyu.safetybelt.operator.fragment

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import cn.leancloud.LCObject
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.heyu.safetybelt.databinding.DialogEditTextBinding
import com.heyu.safetybelt.databinding.FragmentProfileWorkerBinding
import io.reactivex.Observer
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable

/**
 * 个人中心页面。
 * 负责展示用户的姓名和工号，并提供修改功能。
 */
class ProfileFragment : Fragment() {

    private val TAG = "ProfileFragment"
    // 使用 CompositeDisposable 来统一管理所有异步操作（如网络请求）的生命周期，防止内存泄漏。
    private val compositeDisposable = CompositeDisposable()

    private var _binding: FragmentProfileWorkerBinding? = null
    private val binding get() = _binding!!

    // 从 MainActivity 传递过来的，用于在云端定位要更新的 Worker 对象。
    private var workerObjectId: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // 使用 ViewBinding 加载此 Fragment 的布局文件
        _binding = FragmentProfileWorkerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 处理窗口内边距，避免UI与系统状态栏或导航栏重叠
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // 从 arguments 中获取由 MainActivity 传递过来的工人信息
        val workerName = arguments?.getString("workerName")
        val employeeId = arguments?.getString("employeeId")
        workerObjectId = arguments?.getString("workerObjectId")

        // 将信息显示在 TextView 上
        binding.tvWorkerName.text = workerName
        binding.tvEmployeeId.text = employeeId

        // 设置编辑按钮的点击事件
        binding.ibEditName.setOnClickListener {
            showEditDialog("姓名", "name", binding.tvWorkerName.text.toString())
        }

        binding.ibEditEmployeeId.setOnClickListener {
            showEditDialog("工号", "employeeId", binding.tvEmployeeId.text.toString())
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // 当视图被销毁时，中断所有正在进行的异步操作，以避免内存泄漏
        compositeDisposable.clear()
        // 清理 binding 引用
        _binding = null
    }

    /**
     * 显示一个用于修改信息的自定义对话框。
     * @param title 对话框的标题（如“修改姓名”）。
     * @param key 要在云端更新的字段名（如“name”）。
     * @param currentValue 当前显示的值，用于在输入框中预填充。
     */
    private fun showEditDialog(title: String, key: String, currentValue: String) {
        val builder = MaterialAlertDialogBuilder(requireContext())
        builder.setTitle("修改$title")

        // 使用 ViewBinding 加载自定义的对话框布局
        val dialogBinding = DialogEditTextBinding.inflate(layoutInflater)
        val input = dialogBinding.etDialogInput
        input.setText(currentValue)
        // 将光标移动到文本末尾，以获得更好的用户体验
        input.setSelection(input.text.length)

        builder.setView(dialogBinding.root)

        // 先将监听器设为null，这样点击按钮后对话框不会自动关闭，方便我们进行输入验证
        builder.setPositiveButton("保存", null)
        builder.setNegativeButton("取消") { dialog, _ -> dialog.cancel() }

        val dialog = builder.create()

        // 必须先显示对话框，然后才能获取其内部的按钮
        dialog.show()

        // 现在，获取“保存”按钮并重写其点击监听器
        val positiveButton = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
        positiveButton.setOnClickListener {
            val newValue = input.text.toString().trim()
            if (newValue.isBlank()) {
                // 如果输入为空，则显示错误提示，并保持对话框开启
                input.error = "内容不能为空"
            } else {
                // 如果输入有效
                if (newValue != currentValue) {
                    updateWorkerInfo(key, newValue) // 调用函数将新值更新到云端
                }
                // 只有在输入有效时才关闭对话框
                dialog.dismiss()
            }
        }
    }

    /**
     * 将修改后的工人信息更新到 LeanCloud 云端。
     * @param key 要更新的字段名。
     * @param value 要更新的新值。
     */
    private fun updateWorkerInfo(key: String, value: String) {
        if (workerObjectId == null) {
            Toast.makeText(context, "无法更新，用户ID丢失", Toast.LENGTH_SHORT).show()
            return
        }

        // 通过 ObjectId 创建一个指向云端“Worker”表中特定对象的引用
        val workerToUpdate = LCObject.createWithoutData("Worker", workerObjectId!!)
        // 设置要更新的字段和值
        workerToUpdate.put(key, value)

        // 异步执行保存（更新）操作
        workerToUpdate.saveInBackground().subscribe(object : Observer<LCObject> {
            override fun onSubscribe(d: Disposable) {
                // 将这个异步操作添加到 compositeDisposable 中，以便在 Fragment 销毁时能够统一取消
                compositeDisposable.add(d)
            }

            override fun onNext(savedObject: LCObject) {
                Toast.makeText(context, "更新成功", Toast.LENGTH_SHORT).show()
                // 确保在主线程更新UI
                activity?.runOnUiThread {
                    if (key == "name") {
                        binding.tvWorkerName.text = value
                    } else if (key == "employeeId") {
                        binding.tvEmployeeId.text = value
                    }
                }
            }

            override fun onError(e: Throwable) {
                Toast.makeText(context, "更新失败，请稍后重试", Toast.LENGTH_SHORT).show()
            }

            override fun onComplete() {}
        })
    }
}
