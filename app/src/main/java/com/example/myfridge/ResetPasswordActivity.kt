package com.example.myfridge

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import com.example.myfridge.data.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.launch
import kotlin.time.ExperimentalTime

class ResetPasswordActivity : AppCompatActivity() {

    private val supabase by lazy { SupabaseClient.client }
    private var isValidResetLink = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reset_password)

        val passwordInput = findViewById<TextInputEditText>(R.id.resetpasswordPasswordInput)
        val confirmInput = findViewById<TextInputEditText>(R.id.resetpasswordConfirmpasswordInput)
        val resetBtn = findViewById<Button>(R.id.resetpasswordReset)

        // Handle deep link when activity is created
        handleDeepLink(intent)

        resetBtn.setOnClickListener {
            val pass = passwordInput.text.toString()
            val confirm = confirmInput.text.toString()

            if (!isValidResetLink) {
                Toast.makeText(this, "Invalid reset session. Please request a new reset link.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            if (pass.isEmpty() || confirm.isEmpty()) {
                Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (pass != confirm) {
                Toast.makeText(this, "Passwords do not match", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (pass.length < 8) {
                Toast.makeText(this, "Password must be at least 8 characters", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            resetPassword(pass)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    @OptIn(ExperimentalTime::class)
    private fun handleDeepLink(intent: Intent) {
        val data: Uri? = intent.data

        if (data != null && data.scheme == "myfridge" && data.host == "resetpassword") {
            lifecycleScope.launch {
                try {
                    // Extract tokens from URL
                    val accessToken = data.getQueryParameter("access_token")
                        ?: extractFromFragment(data, "access_token")
                    val refreshToken = data.getQueryParameter("refresh_token")
                        ?: extractFromFragment(data, "refresh_token")
                    val tokenType = data.getQueryParameter("token_type")
                        ?: extractFromFragment(data, "token_type")

                    val errorParam = data.getQueryParameter("error")
                    val errorDescription = data.getQueryParameter("error_description")

                    // Check for errors first
                    if (errorParam != null) {
                        Toast.makeText(
                            this@ResetPasswordActivity,
                            "Reset link error: $errorDescription",
                            Toast.LENGTH_LONG
                        ).show()
                        finish()
                        return@launch
                    }

                    // If we have tokens, establish the session
                    if (!accessToken.isNullOrEmpty() && !refreshToken.isNullOrEmpty()) {
                        try {
                            // Import the session using the tokens from the URL
                            val userSession = UserSession(
                                accessToken = accessToken,
                                refreshToken = refreshToken,
                                expiresIn = 3600, // 1 hour default
                                tokenType = tokenType ?: "Bearer",
                                user = null
                            )

                            supabase.auth.importSession(userSession)

                            isValidResetLink = true
                            Toast.makeText(
                                this@ResetPasswordActivity,
                                "Reset link verified. Please enter your new password.",
                                Toast.LENGTH_SHORT
                            ).show()

                        } catch (e: Exception) {
                            Toast.makeText(
                                this@ResetPasswordActivity,
                                "Invalid or expired reset link. Please request a new one.",
                                Toast.LENGTH_LONG
                            ).show()
                            finish()
                        }
                    } else {
                        Toast.makeText(
                            this@ResetPasswordActivity,
                            "Invalid reset link format. Please request a new one.",
                            Toast.LENGTH_LONG
                        ).show()
                        finish()
                    }
                } catch (e: Exception) {
                    Toast.makeText(
                        this@ResetPasswordActivity,
                        "Error processing reset link: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                }
            }
        } else {
            // If we get here without a deep link, it might be a direct launch
            if (data == null) {
                Toast.makeText(
                    this,
                    "Please use the reset link from your email.",
                    Toast.LENGTH_LONG
                ).show()
                finish()
            } else {
                Toast.makeText(
                    this,
                    "Invalid reset link. Please request a new one.",
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }
        }
    }

    private fun extractFromFragment(uri: Uri, paramName: String): String? {
        return uri.fragment?.let { fragment ->
            val fragmentParams = fragment.split("&")
            fragmentParams.find { it.startsWith("$paramName=") }?.substringAfter("=")
        }
    }

    private fun resetPassword(newPassword: String) {
        lifecycleScope.launch {
            try {
                // Update the user's password using the established session
                supabase.auth.updateUser {
                    password = newPassword
                }

                Toast.makeText(
                    this@ResetPasswordActivity,
                    "Password reset successful!",
                    Toast.LENGTH_SHORT
                ).show()

                // Sign out to clear the reset session
                supabase.auth.signOut()

                // Navigate back to login
                val intent = Intent(this@ResetPasswordActivity, LoginActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish()

            } catch (e: Exception) {
                Toast.makeText(
                    this@ResetPasswordActivity,
                    "Error updating password: ${e.message}. Please request a new reset link.",
                    Toast.LENGTH_LONG
                ).show()

                // Navigate back to forgot password
                startActivity(Intent(this@ResetPasswordActivity, ForgetPasswordActivity::class.java))
                finish()
            }
        }
    }
}