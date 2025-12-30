package com.heyu.safetybelt.regulator.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.heyu.safetybelt.databinding.FragmentProfileBinding

class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadUserInfo()
    }

    private fun loadUserInfo() {
        // The user info is passed from LoginActivity via MainActivity's intent
        // If Activity is recreated by system, try to restore from MainApplication
        val name = activity?.intent?.getStringExtra("user_name")
            ?: com.heyu.safetybelt.application.MainApplication.getInstance().currentWorkerName
            ?: "N/A"
        val employeeId = activity?.intent?.getStringExtra("employee_id")
            ?: com.heyu.safetybelt.application.MainApplication.getInstance().currentEmployeeId
            ?: "N/A"

        binding.nameTextView.text = name
        binding.employeeIdTextView.text = employeeId
        
        // Log for debugging
        android.util.Log.d("RegulatorProfileFragment", "Loaded user info - name: $name, employeeId: $employeeId")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
