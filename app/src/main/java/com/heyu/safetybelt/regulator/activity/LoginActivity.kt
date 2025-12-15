package com.heyu.safetybelt.regulator.activity

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import cn.leancloud.LCObject
import cn.leancloud.LCQuery
import com.heyu.safetybelt.databinding.ActivityLoginBinding
import io.reactivex.Observer
import io.reactivex.disposables.Disposable

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)


        binding.saveButton.setOnClickListener {
            val name = binding.nameEditText.text.toString().trim()
            val employeeId = binding.employeeIdEditText.text.toString().trim()

            if (name.isNotEmpty() && employeeId.isNotEmpty()) {
                loginOrRegisterRegulator(name, employeeId)
            } else {
                Toast.makeText(this, "姓名和工号不能为空", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loginOrRegisterRegulator(name: String, employeeId: String) {
        val query = LCQuery<LCObject>("Regulator")
        query.whereEqualTo("name", name)
        query.whereEqualTo("employeeId", employeeId)
        query.findInBackground().subscribe(object : Observer<List<LCObject>> {
            override fun onSubscribe(d: Disposable) {}

            override fun onNext(regulators: List<LCObject>) {
                if (regulators.isNotEmpty()) {
                    // Regulator with same name and employeeId already exists, proceed to login
                    runOnUiThread {
                        Toast.makeText(this@LoginActivity, "登录成功", Toast.LENGTH_SHORT).show()
                        navigateToMain(name, employeeId)
                    }
                } else {
                    // No regulator found, create a new one (register)
                    val regulator = LCObject("Regulator")
                    regulator.put("name", name)
                    regulator.put("employeeId", employeeId)

                    regulator.saveInBackground().subscribe(object : Observer<LCObject> {
                        override fun onSubscribe(d: Disposable) {}

                        override fun onNext(t: LCObject) {
                            // Save successful
                            runOnUiThread {
                                Toast.makeText(this@LoginActivity, "注册并登录成功", Toast.LENGTH_SHORT).show()
                                navigateToMain(name, employeeId)
                            }
                        }

                        override fun onError(e: Throwable) {
                            // Save failed
                            Log.e("LoginActivity", "Failed to save regulator info", e)
                            runOnUiThread {
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
                    Toast.makeText(this@LoginActivity, "登录或注册失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }

            override fun onComplete() {}
        })
    }

    private fun navigateToMain(name: String, employeeId: String) {
        val intent = Intent(this@LoginActivity, MainActivityRegulator::class.java)
        intent.putExtra("user_name", name)
        intent.putExtra("employee_id", employeeId)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}
