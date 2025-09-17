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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class SignUpUIState(
    val isLoading: Boolean = false,
    val isSuccess: Boolean = false,
    val errorMessage: String? = null,
    val emailError: String? = null,
    val passwordError: String? = null,
    val confirmPasswordError: String? = null,
    val usernameError: String? = null
)

class SignUpViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(SignUpUIState())
    val uiState: StateFlow<SignUpUIState> = _uiState.asStateFlow()

    fun signUp(username: String, email: String, password: String, confirmPassword: String) {
        // Reset previous errors
        _uiState.value = _uiState.value.copy(
            emailError = null,
            passwordError = null,
            confirmPasswordError = null,
            usernameError = null,
            errorMessage = null
        )

        // Validate inputs
        if (!validateInputs(username, email, password, confirmPassword)) {
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)

            try {
                // Perform sign up with metadata
                SupabaseClient.client.auth.signUpWith(Email) {
                    this.email = email.trim()
                    this.password = password
                    this.data = buildJsonObject {
                        put("username", username.trim())
                    }
                }
                // Retrieve the user after signup
                val user = SupabaseClient.client.auth.currentUserOrNull()

                if (user == null) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isSuccess = false,
                        errorMessage = "An account with this email address already exists."
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isSuccess = true,
                        errorMessage = null
                    )
                }

            } catch (e: RestException) {
                Log.e("SignUp", "RestException during signup", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "Authentication failed: ${e.message}"
                )
            } catch (e: Exception) {
                Log.e("SignUp", "General exception during signup", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "An unexpected error occurred: ${e.message}"
                )
            }
        }
    }

    private fun validateInputs(username: String, email: String, password: String, confirmPassword: String): Boolean {
        var isValid = true

        // Username validation
        if (username.trim().isEmpty()) {
            _uiState.value = _uiState.value.copy(usernameError = "Username is required")
            isValid = false
        } else if (username.trim().length < 3) {
            _uiState.value = _uiState.value.copy(usernameError = "Username must be at least 3 characters")
            isValid = false
        } else if (username.trim().length > 30) {
            _uiState.value = _uiState.value.copy(usernameError = "Username must be less than 30 characters")
            isValid = false
        }

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
        } else if (password.length < 8) {
            _uiState.value = _uiState.value.copy(passwordError = "Password must be at least 8 characters")
            isValid = false
        }

        // Confirm password validation
        if (confirmPassword.isEmpty()) {
            _uiState.value = _uiState.value.copy(confirmPasswordError = "Please confirm your password")
            isValid = false
        } else if (password != confirmPassword) {
            _uiState.value = _uiState.value.copy(confirmPasswordError = "Passwords do not match")
            isValid = false
        }

        return isValid
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    fun resetState() {
        _uiState.value = SignUpUIState()
    }
}