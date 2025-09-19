package com.example.myfridge.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.exceptions.RestException
import com.example.myfridge.data.SupabaseClient
import android.util.Log

data class LoginUIState(
    val isLoading: Boolean = false,
    val isSuccess: Boolean = false,
    val errorMessage: String? = null,
    val emailError: String? = null,
    val passwordError: String? = null,
    val username: String? = null
)

class LoginViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUIState())
    val uiState: StateFlow<LoginUIState> = _uiState.asStateFlow()

    fun login(email: String, password: String) {
        // Reset previous errors
        _uiState.value = _uiState.value.copy(
            emailError = null,
            passwordError = null,
            errorMessage = null
        )

        // Validate inputs
        if (!validateInputs(email, password)) {
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)

            try {
                // Perform login
                SupabaseClient.client.auth.signInWith(Email) {
                    this.email = email.trim()
                    this.password = password
                }

                // Get current user and username
                val user = SupabaseClient.client.auth.currentUserOrNull()
                val username = user?.userMetadata?.get("username")?.toString() ?: "User"

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isSuccess = true,
                    username = username,
                    errorMessage = null
                )

            } catch (e: RestException) {
                Log.e("Login", "RestException during login", e)
                val errorMessage = when {
                    e.message?.contains("invalid", ignoreCase = true) == true ->
                        "Invalid email or password. Please check your credentials."
                    e.message?.contains("not confirmed", ignoreCase = true) == true ->
                        "Please verify your email address before logging in."
                    e.message?.contains("too many", ignoreCase = true) == true ->
                        "Too many login attempts. Please try again later."
                    else -> "Login failed: ${e.message}"
                }

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = errorMessage
                )
            } catch (e: Exception) {
                Log.e("Login", "General exception during login", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "An unexpected error occurred: ${e.message}"
                )
            }
        }
    }

    private fun validateInputs(email: String, password: String): Boolean {
        var isValid = true

        // Email validation
        if (email.trim().isEmpty()) {
            _uiState.value = _uiState.value.copy(emailError = "Email is required")
            isValid = false
        } else if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()) {
            _uiState.value = _uiState.value.copy(emailError = "Please enter a valid email address")
            isValid = false
        }

        // Password validation
        if (password.isEmpty()) {
            _uiState.value = _uiState.value.copy(passwordError = "Password is required")
            isValid = false
        }

        return isValid
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    fun resetState() {
        _uiState.value = LoginUIState()
    }
}