package com.heyu.safetybelt.operator.activity

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import cn.leancloud.LCObject
import cn.leancloud.LCQuery
import cn.leancloud.LCUser
import com.heyu.safetybelt.R
import com.heyu.safetybelt.common.Worker
import io.reactivex.Observer
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable

class LoginActivity : AppCompatActivity() {

    private lateinit var nameInput: EditText
    private lateinit var employeeIdInput: EditText
    private lateinit var loginButton: Button
    private lateinit var loadingIndicator: ProgressBar
    private val tag = "LoginActivity"
    private val disposables = CompositeDisposable()

    companion object {
        var isNewLoginSession = false
    }

    private val textWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            val name = nameInput.text.toString().trim()
            val employeeId = employeeIdInput.text.toString().trim()
            loginButton.isEnabled = name.isNotEmpty() && employeeId.isNotEmpty()
        }

        override fun afterTextChanged(s: Editable?) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login_worker)

        nameInput = findViewById(R.id.name_input)
        employeeIdInput = findViewById(R.id.employee_id_input)
        loginButton = findViewById(R.id.login_button)
        loadingIndicator = findViewById(R.id.loading_indicator)

        nameInput.addTextChangedListener(textWatcher)
        employeeIdInput.addTextChangedListener(textWatcher)

        loginButton.setOnClickListener {
            val name = nameInput.text.toString().trim()
            val employeeId = employeeIdInput.text.toString().trim()
            loginAndFindOrCreateWorker(name, employeeId)
        }
    }

    override fun onDestroy() {
        disposables.clear() // This will dispose all subscriptions
        super.onDestroy()
    }

    private fun showLoading(isLoading: Boolean) {
        loadingIndicator.visibility = if (isLoading) View.VISIBLE else View.GONE
        nameInput.isEnabled = !isLoading
        employeeIdInput.isEnabled = !isLoading
        loginButton.isEnabled = if (isLoading) false else (nameInput.text.isNotBlank() && employeeIdInput.text.isNotBlank())
    }

    private fun loginAndFindOrCreateWorker(name: String, employeeId: String) {
        showLoading(true)
        LCUser.logInAnonymously().subscribe(object : Observer<LCUser> {
            override fun onSubscribe(d: Disposable) {
                disposables.add(d)
            }

            override fun onNext(user: LCUser) {
                Log.d(tag, "Anonymous login success. User ID: " + user.objectId)
                findOrCreateWorker(name, employeeId, user)
            }

            override fun onError(e: Throwable) {
                if (isFinishing || isDestroyed) return
                showLoading(false)
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
                 if (isFinishing || isDestroyed) return
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
                            if (isFinishing || isDestroyed) return
                            Log.d(tag, "New worker saved successfully. ObjectId: ${savedWorker.objectId}")
                            navigateToMainOperator(name, employeeId, savedWorker.objectId)
                        }

                        override fun onError(e: Throwable) {
                            if (isFinishing || isDestroyed) return
                            showLoading(false)
                            Log.e(tag, "Failed to save new worker", e)
                            Toast.makeText(this@LoginActivity, "创建新用户失败: ${e.message}", Toast.LENGTH_SHORT).show()
                        }

                        override fun onComplete() {}
                    })
                }
            }

            override fun onError(e: Throwable) {
                if (isFinishing || isDestroyed) return
                showLoading(false)
                Log.e(tag, "Failed to find worker", e)
                Toast.makeText(this@LoginActivity, "查找用户失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }

            override fun onComplete() {}
        })
    }

    private fun navigateToMainOperator(name: String, employeeId: String, workerObjectId: String) {
        isNewLoginSession = true // Set the static flag
        val intent = Intent(this, MainActivityOperator::class.java).apply {
            putExtra("workerName", name)
            putExtra("employeeId", employeeId)
            putExtra("workerObjectId", workerObjectId)
        }
        startActivity(intent)
        finish()
    }
}
