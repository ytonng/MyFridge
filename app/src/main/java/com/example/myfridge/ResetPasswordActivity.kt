package com.example.myfridge

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.myfridge.databinding.ActivityResetPasswordBinding
import com.example.myfridge.viewmodel.ResetPasswordViewModel
import kotlinx.coroutines.launch

class ResetPasswordActivity : AppCompatActivity() {

    private lateinit var binding: ActivityResetPasswordBinding
    private val viewModel: ResetPasswordViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityResetPasswordBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Apply window insets
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setupObservers()
        setupClickListeners()

        // Handle deep link when activity is created
        handleDeepLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent) {
        viewModel.handleDeepLink(intent.data)
    }

    private fun setupObservers() {
        lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                // Show/hide loading
                binding.resetpasswordReset.isEnabled = !state.isLoading

                // Handle input field errors
                binding.resetpasswordPasswordLayout.error = state.passwordError
                binding.resetpasswordPasswordLayout.errorIconDrawable = null
                binding.resetpasswordConfirmpasswordLayout.error = state.confirmPasswordError
                binding.resetpasswordConfirmpasswordLayout.errorIconDrawable = null

                // Handle status messages
                state.statusMessage?.let { message ->
                    Toast.makeText(this@ResetPasswordActivity, message, Toast.LENGTH_SHORT).show()
                }

                // Handle error messages
                state.errorMessage?.let { error ->
                    Toast.makeText(this@ResetPasswordActivity, error, Toast.LENGTH_LONG).show()

                    // If it's an invalid/expired link error, navigate back
                    if (error.contains("invalid", ignoreCase = true) ||
                        error.contains("expired", ignoreCase = true) ||
                        error.contains("request a new", ignoreCase = true)) {
                        navigateToForgetPassword()
                        return@collect
                    }

                    viewModel.clearError()
                }

                // Handle success
                if (state.isSuccess) {
                    // Navigate to login after successful reset
                    navigateToLogin()
                }

                // Handle invalid reset link (no deep link data)
                if (!state.isValidResetLink && state.errorMessage != null) {
                    finish()
                }
            }
        }
    }

    private fun setupClickListeners() {
        binding.resetpasswordReset.setOnClickListener {
            val password = binding.resetpasswordPasswordInput.text.toString()
            val confirmPassword = binding.resetpasswordConfirmpasswordInput.text.toString()

            viewModel.resetPassword(password, confirmPassword)
        }
    }

    private fun navigateToLogin() {
        val intent = Intent(this, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun navigateToForgetPassword() {
        val intent = Intent(this, ForgetPasswordActivity::class.java)
        startActivity(intent)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.resetState()
    }
}