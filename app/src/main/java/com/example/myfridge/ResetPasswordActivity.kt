package com.example.myfridge

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import com.google.android.material.textfield.TextInputEditText
import com.example.myfridge.data.SupabaseClient
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.launch

class ResetPasswordActivity : AppCompatActivity() {

    private val supabase by lazy { SupabaseClient.client }

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

            if (pass.isEmpty() || confirm.isEmpty()) {
                Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (pass != confirm) {
                Toast.makeText(this, "Passwords do not match", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            lifecycleScope.launch {
                try {
                    // Check if user is authenticated first
                    val currentUser = supabase.auth.currentUserOrNull()
                    if (currentUser == null) {
                        Toast.makeText(
                            this@ResetPasswordActivity,
                            "Authentication expired. Please request a new reset link.",
                            Toast.LENGTH_LONG
                        ).show()
                        finish()
                        return@launch
                    }

                    supabase.auth.updateUser {
                        password = pass
                    }
                    Toast.makeText(
                        this@ResetPasswordActivity,
                        "Password reset successful!",
                        Toast.LENGTH_SHORT
                    ).show()
                    startActivity(Intent(this@ResetPasswordActivity, LoginActivity::class.java))
                    finish()
                } catch (e: Exception) {
                    Toast.makeText(
                        this@ResetPasswordActivity,
                        "Error: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent) {
        val data: Uri? = intent.data
        if (data != null && data.scheme == "myfridge" && data.host == "resetpassword") {
            lifecycleScope.launch {
                try {
                    // Handle the authentication callback
                    supabase.auth.handleDeepLinks(intent)

                    Toast.makeText(
                        this@ResetPasswordActivity,
                        "Authentication successful. Please enter your new password.",
                        Toast.LENGTH_SHORT
                    ).show()
                } catch (e: Exception) {
                    Toast.makeText(
                        this@ResetPasswordActivity,
                        "Authentication failed: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                }
            }
        }
    }
}