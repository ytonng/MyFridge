package com.example.myfridge

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class EmailConfirmActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleDeepLink(intent.data)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent.data)
    }

    private fun handleDeepLink(uri: Uri?) {
        if (uri == null || uri.scheme != "myfridge" || uri.host != "verify") {
            Toast.makeText(this, "Invalid verification link.", Toast.LENGTH_LONG).show()
            navigateToLogin()
            return
        }

        // At this point, verification has been confirmed by Supabase.
        // Most emails do not include session tokens; we simply inform and redirect.
        Toast.makeText(this, "Email verified successfully! Please log in.", Toast.LENGTH_LONG).show()
        navigateToLogin()
    }

    private fun navigateToLogin() {
        val intent = Intent(this, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}