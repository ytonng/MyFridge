package com.example.myfridge

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.myfridge.databinding.ActivityLoginBinding
import com.example.myfridge.viewmodel.LoginViewModel
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private val viewModel: LoginViewModel by viewModels()
    private lateinit var googleSignInClient: GoogleSignInClient
    
    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            account?.idToken?.let { idToken ->
                viewModel.signInWithGoogle(idToken)
            } ?: run {
                Toast.makeText(this, "Failed to get Google ID token", Toast.LENGTH_SHORT).show()
            }
        } catch (e: ApiException) {
            Log.e("LoginActivity", "Google sign in failed", e)
            Toast.makeText(this, "Google sign in failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Apply window insets
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Configure Google Sign-In
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)

        setupObservers()
        setupClickListeners()
    }

    private fun setupObservers() {
        lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                // Show/hide loading (you'll need to add a ProgressBar to your layout)
                // binding.loadingProgressBar?.isVisible = state.isLoading
                binding.loginLogin.isEnabled = !state.isLoading
                binding.loginContinuewithgoogle.isEnabled = !state.isLoading

                // Handle input field errors
                binding.loginEmailLayout.error = state.emailError
                binding.loginEmailLayout.errorIconDrawable = null
                binding.loginPasswordLayout.error = state.passwordError
                binding.loginPasswordLayout.errorIconDrawable = null

                // Handle general error messages
                state.errorMessage?.let { error ->
                    Toast.makeText(this@LoginActivity, error, Toast.LENGTH_LONG).show()

                    // If it's an invalid credentials error, focus on email field
                    if (error.contains("Invalid email or password", ignoreCase = true)) {
                        binding.loginEmailInput.requestFocus()
                        binding.loginEmailInput.selectAll()
                    }

                    viewModel.clearError()
                }

                // Handle success
                if (state.isSuccess) {
                    Toast.makeText(
                        this@LoginActivity,
                        "Login successful! Welcome back!",
                        Toast.LENGTH_SHORT
                    ).show()
                    if (state.shouldNavigateToAuthChoice) {
                        navigateToAuthChoice()
                    } else {
                        // Navigate to dashboard with username
                        navigateToDashboard(state.username ?: "User")
                    }
                }
            }
        }
    }

    private fun setupClickListeners() {
        // Login button click
        binding.loginLogin.setOnClickListener {
            val email = binding.loginEmailInput.text.toString()
            val password = binding.loginPasswordInput.text.toString()

            viewModel.login(email, password)
        }

        // Navigate to sign up
        binding.loginSignUp.setOnClickListener {
            val intent = Intent(this, SignUpActivity::class.java)
            startActivity(intent)
        }

        // Navigate to forgot password
        binding.loginForgetPassword.setOnClickListener {
            val intent = Intent(this, ForgetPasswordActivity::class.java)
            startActivity(intent)
        }

        // Google login button
        binding.loginContinuewithgoogle.setOnClickListener {
            signInWithGoogle()
        }
    }

    private fun signInWithGoogle() {
        // Sign out first to ensure the account chooser is shown every time
        googleSignInClient.signOut().addOnCompleteListener {
            val signInIntent = googleSignInClient.signInIntent
            googleSignInLauncher.launch(signInIntent)
        }
    }

    private fun navigateToAuthChoice() {
        val intent = Intent(this, AuthChoiceActivity::class.java)
        // Clear the activity stack so user can't go back to login
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun navigateToDashboard(username: String) {
        val intent = Intent(this, BottomNavHostActivity::class.java)
        intent.putExtra("USERNAME", username)
        startActivity(intent)

        // Clear the activity stack so user can't go back to login
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.resetState()
    }
}