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

data class ForgetPasswordUIState(
    val isLoading: Boolean = false,
    val isResetEmailSent: Boolean = false,
    val isPasswordResetSuccess: Boolean = false,
    val errorMessage: String? = null,
    val emailError: String? = null,
    val passwordError: String? = null,
    val confirmPasswordError: String? = null,
    val currentEmail: String? = null,
    val isInResetMode: Boolean = false, // true when user comes from email link
    val statusMessage: String? = null
)

class ForgetPasswordViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(ForgetPasswordUIState())
    val uiState: StateFlow<ForgetPasswordUIState> = _uiState.asStateFlow()

    fun initializeWithToken(email: String?, token: String?) {
        // If we have both email and token from deep link, we're in reset mode
        if (!email.isNullOrEmpty() && !token.isNullOrEmpty()) {
            _uiState.value = _uiState.value.copy(
                isInResetMode = true,
                currentEmail = email
            )
            Log.d("ForgetPassword", "Initialized in reset mode with email: $email")
        }
    }

    fun sendPasswordResetEmail(email: String) {
        // Reset previous errors
        _uiState.value = _uiState.value.copy(
            emailError = null,
            errorMessage = null,
            statusMessage = null
        )

        // Validate email
        if (!validateEmail(email)) {
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)

            try {
                // First, check if the email exists by attempting to send reset email
                SupabaseClient.client.auth.resetPasswordForEmail(
                    email = email.trim(),
                    redirectUrl = "com.example.myfridge://reset-password" // Your app's deep link
                )

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isResetEmailSent = true,
                    currentEmail = email.trim(),
                    statusMessage = "Password reset link has been sent to $email. Please check your email.",
                    errorMessage = null
                )

                Log.d("ForgetPassword", "Reset email sent successfully to: $email")

            } catch (e: RestException) {
                Log.e("ForgetPassword", "RestException during password reset", e)

                val errorMessage = when {
                    e.message?.contains("not found", ignoreCase = true) == true ->
                        "No account found with this email address."
                    e.message?.contains("rate limit", ignoreCase = true) == true ->
                        "Too many attempts. Please try again later."
                    e.message?.contains("invalid", ignoreCase = true) == true ->
                        "Invalid email address format."
                    else -> "Failed to send reset email: ${e.message}"
                }

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = errorMessage
                )
            } catch (e: Exception) {
                Log.e("ForgetPassword", "General exception during password reset", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "An unexpected error occurred: ${e.message}"
                )
            }
        }
    }

    fun updatePassword(newPassword: String, confirmPassword: String) {
        // Reset previous errors
        _uiState.value = _uiState.value.copy(
            passwordError = null,
            confirmPasswordError = null,
            errorMessage = null,
            statusMessage = null
        )

        // Validate passwords
        if (!validatePasswords(newPassword, confirmPassword)) {
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)

            try {
                // Update the user's password
                SupabaseClient.client.auth.updateUser {
                    password = newPassword
                }

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isPasswordResetSuccess = true,
                    statusMessage = "Password updated successfully! Redirecting to login...",
                    errorMessage = null
                )

                Log.d("ForgetPassword", "Password updated successfully")

            } catch (e: RestException) {
                Log.e("ForgetPassword", "RestException during password update", e)

                val errorMessage = when {
                    e.message?.contains("expired", ignoreCase = true) == true ->
                        "Reset link has expired. Please request a new one."
                    e.message?.contains("invalid", ignoreCase = true) == true ->
                        "Invalid or expired reset link. Please request a new one."
                    else -> "Failed to update password: ${e.message}"
                }

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = errorMessage
                )
            } catch (e: Exception) {
                Log.e("ForgetPassword", "General exception during password update", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "An unexpected error occurred: ${e.message}"
                )
            }
        }
    }

    private fun validateEmail(email: String): Boolean {
        return when {
            email.trim().isEmpty() -> {
                _uiState.value = _uiState.value.copy(emailError = "Email is required")
                false
            }
            !android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches() -> {
                _uiState.value = _uiState.value.copy(emailError = "Please enter a valid email address")
                false
            }
            else -> true
        }
    }

    private fun validatePasswords(password: String, confirmPassword: String): Boolean {
        var isValid = true

        // Password validation
        when {
            password.isEmpty() -> {
                _uiState.value = _uiState.value.copy(passwordError = "Password is required")
                isValid = false
            }
            password.length < 8 -> {
                _uiState.value = _uiState.value.copy(passwordError = "Password must be at least 8 characters")
                isValid = false
            }
        }

        // Confirm password validation
        when {
            confirmPassword.isEmpty() -> {
                _uiState.value = _uiState.value.copy(confirmPasswordError = "Please confirm your password")
                isValid = false
            }
            password != confirmPassword -> {
                _uiState.value = _uiState.value.copy(confirmPasswordError = "Passwords do not match")
                isValid = false
            }
        }

        return isValid
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(
            errorMessage = null,
            statusMessage = null
        )
    }

    fun resetState() {
        _uiState.value = ForgetPasswordUIState()
    }
}