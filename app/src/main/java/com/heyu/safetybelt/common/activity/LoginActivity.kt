package com.heyu.safetybelt.common.activity

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import cn.leancloud.LCObject
import cn.leancloud.LCQuery
import cn.leancloud.LCUser
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.heyu.safetybelt.R
import com.heyu.safetybelt.common.activity.UserIdentity
import com.heyu.safetybelt.common.Worker
import com.heyu.safetybelt.databinding.ActivityLoginBinding
import com.heyu.safetybelt.operator.activity.MainActivityOperator
import com.heyu.safetybelt.regulator.activity.MainActivityRegulator
import io.reactivex.Observer
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var nameInput: EditText
    private lateinit var employeeIdInput: EditText
    private lateinit var loginButton: Button

    private lateinit var radioGroup: RadioGroup
    private lateinit var rbWorker: RadioButton
    private lateinit var rbSupervisor: RadioButton
    private lateinit var nameInputLayout: TextInputLayout
    private lateinit var employeeIdInputLayout: TextInputLayout
    private lateinit var nameEditText: TextInputEditText
    private lateinit var employeeIdEditText: TextInputEditText
    private lateinit var saveButton: Button
    private var currentIdentity = UserIdentity.WORKER
    private var isProcessing = false
    private val tag = "LoginActivity"
    private val disposables = CompositeDisposable()


    private fun setupTextWatchers() {
        // 统一的文本变化监听器
        val textWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val name = nameEditText.text.toString().trim()
                val employeeId = employeeIdEditText.text.toString().trim()
                saveButton.isEnabled = name.isNotEmpty() && employeeId.isNotEmpty()
            }
            override fun afterTextChanged(s: Editable?) {}
        }

        // 为两个输入框添加监听
        nameEditText.addTextChangedListener(textWatcher)
        employeeIdEditText.addTextChangedListener(textWatcher)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initViews()
        setupTextWatchers()
        
        // 恢复上一次选择的身份
        val sp = getSharedPreferences("LoginPrefs", Context.MODE_PRIVATE)
        val lastIdentity = sp.getString("last_identity", UserIdentity.WORKER.name)
        if (lastIdentity == UserIdentity.SUPERVISOR.name) {
            rbSupervisor.isChecked = true
            currentIdentity = UserIdentity.SUPERVISOR
            updateUIForSupervisor(true) // 传入 true 表示是首次加载
        } else {
            rbWorker.isChecked = true
            currentIdentity = UserIdentity.WORKER
            updateUIForWorker(true) // 传入 true 表示是首次加载
        }
        
        setupListeners()
    }
    private fun initViews() {
        radioGroup = findViewById(R.id.login_title)
        rbWorker = findViewById(R.id.rb_worker)
        rbSupervisor = findViewById(R.id.rb_supervisor)
        nameInputLayout = findViewById(R.id.name_input_layout)
        employeeIdInputLayout = findViewById(R.id.employee_id_input_layout)
        nameEditText = findViewById(R.id.name_edit_text)
        employeeIdEditText = findViewById(R.id.employee_id_edit_text)
        saveButton = findViewById(R.id.save_button)
    }
    private fun setupListeners() {
        // 身份选择监听
        radioGroup.setOnCheckedChangeListener { group, checkedId ->
            when (checkedId) {
                R.id.rb_worker -> {
                    currentIdentity = UserIdentity.WORKER
                    updateUIForWorker(false) // 手动切换不弹提醒
                }
                R.id.rb_supervisor -> {
                    currentIdentity = UserIdentity.SUPERVISOR
                    updateUIForSupervisor(false) // 手动切换不弹提醒
                }
            }
            // 保存当前选择的身份
            getSharedPreferences("LoginPrefs", Context.MODE_PRIVATE).edit()
                .putString("last_identity", currentIdentity.name)
                .apply()
        }

        // 保存按钮点击监听
        saveButton.setOnClickListener {
            if (isProcessing) return@setOnClickListener
            handleLogin()
        }
    }
    private fun updateUIForWorker(isFirstLoad: Boolean) {
        // 更新输入框提示
        nameInputLayout.hint = "作业人员姓名"
        employeeIdInputLayout.hint = "工号"

        // 加载保存的作业人员信息
        val sp = getSharedPreferences("LoginPrefs", Context.MODE_PRIVATE)
        val savedName = sp.getString("worker_name", "")
        val savedId = sp.getString("worker_id", "")
        
        nameEditText.setText(savedName)
        employeeIdEditText.setText(savedId)

        // 如果是首次进入且有保存的数据，弹出 Toast 提醒
        if (isFirstLoad && (!savedName.isNullOrEmpty() || !savedId.isNullOrEmpty())) {
            Toast.makeText(this, "已自动为您填入上次姓名与工号", Toast.LENGTH_SHORT).show()
        }

        // 设置输入类型
        nameEditText.inputType = InputType.TYPE_CLASS_TEXT
        employeeIdEditText.inputType = InputType.TYPE_CLASS_NUMBER

        // 焦点设置
        nameEditText.requestFocus()
        // 将光标移至末尾
        nameEditText.setSelection(nameEditText.text?.length ?: 0)

        saveButton.text = "登录"
    }
    private fun updateUIForSupervisor(isFirstLoad: Boolean) {
        // 更新输入框提示
        nameInputLayout.hint = "安监人员姓名"
        employeeIdInputLayout.hint = "工号"

        // 加载保存的安监人员信息
        val sp = getSharedPreferences("LoginPrefs", Context.MODE_PRIVATE)
        val savedName = sp.getString("supervisor_name", "")
        val savedId = sp.getString("supervisor_id", "")

        nameEditText.setText(savedName)
        employeeIdEditText.setText(savedId)

        // 如果是首次进入且有保存的数据，弹出 Toast 提醒
        if (isFirstLoad && (!savedName.isNullOrEmpty() || !savedId.isNullOrEmpty())) {
            Toast.makeText(this, "已自动为您填入上次姓名与工号", Toast.LENGTH_SHORT).show()
        }

        // 设置输入类型
        nameEditText.inputType = InputType.TYPE_CLASS_TEXT
        employeeIdEditText.inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_PASSWORD

        // 焦点设置
        nameEditText.requestFocus()
        // 将光标移至末尾
        nameEditText.setSelection(nameEditText.text?.length ?: 0)
    }

    private fun handleLogin() {
        if (isProcessing) return

        val name = nameEditText.text.toString().trim()
        val employeeId = employeeIdEditText.text.toString().trim()

        // 最终验证
        if (!validateFinalInputs(name, employeeId)) {
            return
        }

        // 开始处理
        isProcessing = true
        saveButton.isEnabled = false
        saveButton.text = "处理中..."

        // 保存当前输入的姓名和工号到本地
        val sp = getSharedPreferences("LoginPrefs", Context.MODE_PRIVATE)
        val editor = sp.edit()
        if (currentIdentity == UserIdentity.WORKER) {
            editor.putString("worker_name", name)
            editor.putString("worker_id", employeeId)
        } else {
            editor.putString("supervisor_name", name)
            editor.putString("supervisor_id", employeeId)
        }
        editor.apply()

        // 模拟网络请求延迟
        Handler().postDelayed({
            // 根据身份执行不同的登录逻辑
            when (currentIdentity) {
                UserIdentity.WORKER -> loginAndFindOrCreateWorker(name, employeeId)
                UserIdentity.SUPERVISOR -> loginOrRegisterRegulator(name, employeeId)
            }
        }, 100)
    }

    private fun validateFinalInputs(name: String, employeeId: String): Boolean {
        if (name.isEmpty()) {
            nameInputLayout.error = "请输入姓名"
            nameEditText.requestFocus()
            return false
        } else {
            nameInputLayout.error = null
        }

        if (employeeId.isEmpty()) {
            employeeIdInputLayout.error = when (currentIdentity) {
                UserIdentity.WORKER -> "请输入作业员工号"
                UserIdentity.SUPERVISOR -> "请输入安监员工号"
            }
            employeeIdEditText.requestFocus()
            return false
        } else {
            employeeIdInputLayout.error = null
        }

        return true
    }

    private fun loginOrRegisterRegulator(name: String, employeeId: String) {
        val query = LCQuery<LCObject>("Regulator")
        query.whereEqualTo("name", name)
        query.whereEqualTo("employeeId", employeeId)
        query.findInBackground().subscribe(object : Observer<List<LCObject>> {
            override fun onSubscribe(d: Disposable) {
                disposables.add(d)
            }

            override fun onNext(regulators: List<LCObject>) {
                if (regulators.isNotEmpty()) {
                    // Regulator with same name and employeeId already exists, proceed to login
                    runOnUiThread {
                        Toast.makeText(this@LoginActivity, "登录成功", Toast.LENGTH_SHORT).show()
                        navigateToMainRegulator(name, employeeId)
                    }
                } else {
                    // No regulator found, create a new one (register)
                    val regulator = LCObject("Regulator")
                    regulator.put("name", name)
                    regulator.put("employeeId", employeeId)

                    regulator.saveInBackground().subscribe(object : Observer<LCObject> {
                        override fun onSubscribe(d: Disposable) {
                            disposables.add(d)
                        }

                        override fun onNext(t: LCObject) {
                            // Save successful
                            runOnUiThread {
                                Toast.makeText(this@LoginActivity, "注册并登录成功", Toast.LENGTH_SHORT).show()
                                navigateToMainRegulator(name, employeeId)
                            }
                        }

                        override fun onError(e: Throwable) {
                            // Save failed
                            Log.e("LoginActivity", "Failed to save regulator info", e)
                            runOnUiThread {
                                isProcessing = false
                                saveButton.isEnabled = true
                                saveButton.text = "登录"
                                Toast.makeText(this@LoginActivity, "注册失败: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        }

                        override fun onComplete() {}
                    })
                }
            }

            override fun onError(e: Throwable) {
                // Query failed
                Log.e("LoginActivity", "Failed to query regulator info", e)
                runOnUiThread {
                    isProcessing = false
                    saveButton.isEnabled = true
                    saveButton.text = "登录"
                    Toast.makeText(this@LoginActivity, "登录或注册失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }

            override fun onComplete() {}
        })
    }

    private fun navigateToMainRegulator(name: String, employeeId: String) {
        val intent = Intent(this@LoginActivity, MainActivityRegulator::class.java)
        intent.putExtra("user_name", name)
        intent.putExtra("employee_id", employeeId)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }


    override fun onDestroy() {
        disposables.clear() // This will dispose all subscriptions
        super.onDestroy()
    }



    private fun loginAndFindOrCreateWorker(name: String, employeeId: String) {
//        showLoading(true)
        LCUser.logInAnonymously().subscribe(object : Observer<LCUser> {
            override fun onSubscribe(d: Disposable) {
                disposables.add(d)
            }

            override fun onNext(user: LCUser) {
                // No need to check for isFinishing here, as we are navigating away.
                Log.d(tag, "Anonymous login success. User ID: " + user.objectId)
                findOrCreateWorker(name, employeeId, user)
            }

            override fun onError(e: Throwable) {
                if (isFinishing || isDestroyed) {
                    return
                }
                runOnUiThread {
                    isProcessing = false
                    saveButton.isEnabled = true
                    saveButton.text = "登录"
                }
//                showLoading(false)
                Log.e(tag, "Anonymous login failed", e)
                Toast.makeText(this@LoginActivity, "登录失败: ${e.message}", Toast.LENGTH_LONG).show()
            }

            override fun onComplete() {}
        })
    }
    private fun findOrCreateWorker(name: String, employeeId: String, user: LCUser) {
        val query = LCQuery<LCObject>("Worker")
        query.whereEqualTo("name", name)
        query.whereEqualTo("employeeId", employeeId)
        query.findInBackground().subscribe(object : Observer<List<LCObject>> {
            override fun onSubscribe(d: Disposable) {
                disposables.add(d)
            }

            override fun onNext(workers: List<LCObject>) {
                if (isFinishing || isDestroyed) {
                    return
                }
                if (workers.isNotEmpty()) {
                    val existingWorker = workers[0]
                    Log.d(tag, "Found existing worker. ObjectId: ${existingWorker.objectId}")
                    navigateToMainOperator(name, employeeId, existingWorker.objectId)
                } else {
                    Log.d(tag, "Worker not found, creating a new one.")
                    val newWorker = Worker()
                    newWorker.name = name
                    newWorker.employeeId = employeeId
                    newWorker.put("user", user)
                    newWorker.saveInBackground().subscribe(object : Observer<LCObject> {
                        override fun onSubscribe(d: Disposable) {
                            disposables.add(d)
                        }

                        override fun onNext(savedWorker: LCObject) {
                            if (isFinishing || isDestroyed) {
                                return
                            }
                            Log.d(tag, "New worker saved successfully. ObjectId: ${savedWorker.objectId}")
                            navigateToMainOperator(name, employeeId, savedWorker.objectId)
                        }

                        override fun onError(e: Throwable) {
                            if (isFinishing || isDestroyed) {
                                return
                            }
                            runOnUiThread {
                                isProcessing = false
                                saveButton.isEnabled = true
                                saveButton.text = "登录"
                            }
//                            showLoading(false)
                            Log.e(tag, "Failed to save new worker", e)
                            Toast.makeText(this@LoginActivity, "创建新用户失败: ${e.message}", Toast.LENGTH_SHORT).show()
                        }

                        override fun onComplete() {}
                    })
                }
            }

            override fun onError(e: Throwable) {
                if (isFinishing || isDestroyed) {
                    return
                }
                runOnUiThread {
                    isProcessing = false
                    saveButton.isEnabled = true
                    saveButton.text = "登录"
                }
//                showLoading(false)
                Log.e(tag, "Failed to find worker", e)
                Toast.makeText(this@LoginActivity, "查找用户失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }

            override fun onComplete() {}
        })
    }
    private fun navigateToMainOperator(name: String, employeeId: String, workerObjectId: String) {
        val intent = Intent(this, MainActivityOperator::class.java).apply {
            putExtra("workerName", name)
            putExtra("employeeId", employeeId)
            putExtra("workerObjectId", workerObjectId)
        }
        startActivity(intent)
        finish()
    }

}
