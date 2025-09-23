package com.example.myfridge

import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import com.example.myfridge.data.SupabaseClient
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.launch

class ForgetPasswordActivity : AppCompatActivity() {

    private val supabase by lazy { SupabaseClient.client }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_forget_password)

        val emailInput = findViewById<TextInputEditText>(R.id.forgetpasswordEmailInput)
        val resetBtn = findViewById<Button>(R.id.forgetpasswordReset)

        resetBtn.setOnClickListener {
            val email = emailInput.text.toString().trim()

            if (email.isEmpty()) {
                Toast.makeText(this, "Please enter your email", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            lifecycleScope.launch {
                try {
                    supabase.auth.resetPasswordForEmail(
                        email = email,
                        redirectUrl = "myfridge://resetpassword"
                    )
                    Toast.makeText(
                        this@ForgetPasswordActivity,
                        "Reset link sent to $email",
                        Toast.LENGTH_SHORT
                    ).show()
                } catch (e: Exception) {
                    Toast.makeText(
                        this@ForgetPasswordActivity,
                        "Error: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
}