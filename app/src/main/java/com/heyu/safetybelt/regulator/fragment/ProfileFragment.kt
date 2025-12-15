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
        val name = activity?.intent?.getStringExtra("user_name") ?: "N/A"
        val employeeId = activity?.intent?.getStringExtra("employee_id") ?: "N/A"

        binding.nameTextView.text = name
        binding.employeeIdTextView.text = employeeId
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
