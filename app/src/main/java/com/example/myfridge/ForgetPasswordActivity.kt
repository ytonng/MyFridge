package com.example.myfridge

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.example.myfridge.databinding.ActivityForgetPasswordBinding
import com.example.myfridge.viewmodel.ForgetPasswordViewModel
import kotlinx.coroutines.launch

class ForgetPasswordActivity : AppCompatActivity() {

    private lateinit var binding: ActivityForgetPasswordBinding
    private val viewModel: ForgetPasswordViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityForgetPasswordBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Apply window insets
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setupObservers()
        setupClickListeners()
    }

    private fun setupObservers() {
        lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                // Show/hide loading
                binding.forgetpasswordReset.isEnabled = !state.isLoading

                // Handle input field errors
                binding.forgetpasswordEmailLayout.error = state.emailError
                binding.forgetpasswordEmailLayout.errorIconDrawable = null

                // Handle status messages
                state.statusMessage?.let { message ->
                    Toast.makeText(this@ForgetPasswordActivity, message, Toast.LENGTH_LONG).show()
                }

                // Handle error messages
                state.errorMessage?.let { error ->
                    Toast.makeText(this@ForgetPasswordActivity, error, Toast.LENGTH_LONG).show()

                    // If it's an invalid email error, focus on email field
                    if (error.contains("not found", ignoreCase = true)) {
                        binding.forgetpasswordEmailInput.requestFocus()
                        binding.forgetpasswordEmailInput.selectAll()
                    }

                    viewModel.clearError()
                }
            }
        }
    }

    private fun setupClickListeners() {
        binding.forgetpasswordReset.setOnClickListener {
            val email = binding.forgetpasswordEmailInput.text.toString()
            viewModel.sendPasswordResetEmail(email)
        }

        binding.forgetpasswordLogin.setOnClickListener {
            navigateToLogin()
        }
    }

    private fun navigateToLogin() {
        val intent = Intent(this, LoginActivity::class.java)
        startActivity(intent)
        finish() // Optional: finish current activity
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.resetState()
    }
}