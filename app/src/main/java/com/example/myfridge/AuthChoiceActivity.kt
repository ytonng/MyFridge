package com.example.myfridge

import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.example.myfridge.viewmodel.AuthChoiceViewModel
import kotlinx.coroutines.launch

class AuthChoiceActivity : AppCompatActivity() {
    private val viewModel: AuthChoiceViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_auth_choice)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val btnCreate = findViewById<MaterialButton>(R.id.choiceCreate)
        val btnJoin = findViewById<MaterialButton>(R.id.choiceJoin)

        btnCreate.setOnClickListener { createNewFridge() }
        btnJoin.setOnClickListener { promptJoinFridge() }

        lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                if (state.errorMessage != null) {
                    Toast.makeText(this@AuthChoiceActivity, state.errorMessage, Toast.LENGTH_LONG).show()
                    viewModel.clearError()
                }
                if (state.successMessage != null) {
                    Toast.makeText(this@AuthChoiceActivity, state.successMessage, Toast.LENGTH_SHORT).show()
                }
                if (state.shouldNavigate) {
                    navigateToMain()
                    viewModel.consumeNavigation()
                }
                btnCreate.isEnabled = !state.isLoading
                btnJoin.isEnabled = !state.isLoading
            }
        }
    }

    private fun createNewFridge() {
        viewModel.createFridge()
    }

    private fun promptJoinFridge() {
        val input = EditText(this)
        input.hint = "Enter fridge serial number"
        AlertDialog.Builder(this)
            .setTitle("Join Fridge")
            .setView(input)
            .setPositiveButton("Join") { _, _ ->
                val serial = input.text.toString().trim()
                joinFridge(serial)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun joinFridge(serial: String) {
        viewModel.joinFridge(serial)
    }

    private fun navigateToMain() {
        val intent = Intent(this, BottomNavHostActivity::class.java)
        startActivity(intent)
        finish()
    }
}