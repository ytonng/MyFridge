package com.example.myfridge

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.example.myfridge.databinding.ActivitySignUpBinding
import com.example.myfridge.viewmodel.SignUpViewModel
import kotlinx.coroutines.launch

class SignUpActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySignUpBinding
    private val viewModel: SignUpViewModel by viewModels()
    // Google Sign-In removed from SignUpActivity

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySignUpBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Google sign-up removed from layout; email/password only

        setupObservers()
        setupClickListeners()
    }

    private fun setupObservers() {
        lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                // Show/hide loading
                binding.loadingProgressBar.isVisible = state.isLoading
                binding.signupSignup.isEnabled = !state.isLoading

                // Handle input field errors
                binding.signupUsernameLayout.error = state.usernameError
                binding.signupUsernameLayout.errorIconDrawable = null
                binding.signupEmailLayout.error = state.emailError
                binding.signupEmailLayout.errorIconDrawable = null
                binding.signupPasswordLayout.error = state.passwordError
                binding.signupPasswordLayout.errorIconDrawable = null
                binding.signupConfirmpasswordLayout.error = state.confirmPasswordError
                binding.signupConfirmpasswordLayout.errorIconDrawable = null

                // Handle general error messages
                state.errorMessage?.let { error ->
                    Toast.makeText(this@SignUpActivity, error, Toast.LENGTH_LONG).show()

                    // If it's a duplicate email error, focus on email field for easy correction
                    if (error.contains("already exists", ignoreCase = true)) {
                        binding.signupEmailInput.requestFocus()
                        binding.signupEmailInput.selectAll()
                    }

                    viewModel.clearError()
                }

                // Handle success
                if (state.isSuccess) {
                    Toast.makeText(
                        this@SignUpActivity,
                        "Account created successfully! Please check your email for verification.",
                        Toast.LENGTH_LONG
                    ).show()
                    // Navigate to login screen after email/password signup
                    navigateToLogin()
                }
            }
        }
    }

    private fun setupClickListeners() {
        // Sign up button click
        binding.signupSignup.setOnClickListener {
            val username = binding.signupUsernameInput.text.toString()
            val email = binding.signupEmailInput.text.toString()
            val password = binding.signupPasswordInput.text.toString()
            val confirmPassword = binding.signupConfirmpasswordInput.text.toString()

            viewModel.signUp(username, email, password, confirmPassword)
        }

        // Navigate to login
        binding.signupLogin.setOnClickListener {
            navigateToLogin()
        }

        // Removed Google sign up action
    }

    private fun navigateToLogin() {
        val intent = Intent(this, LoginActivity::class.java)
        startActivity(intent)
        finish() // Optional: finish current activity
    }

    private fun navigateToDashboard(username: String) {
        val intent = Intent(this, BottomNavHostActivity::class.java)
        intent.putExtra("USERNAME", username)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    // Google sign-in method removed

    override fun onDestroy() {
        super.onDestroy()
        viewModel.resetState()
    }
}