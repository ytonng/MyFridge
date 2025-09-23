package com.example.myfridge.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.exceptions.RestException
import com.example.myfridge.data.SupabaseClient
import android.util.Log

data class ForgetPasswordUIState(
    val isLoading: Boolean = false,
    val isResetEmailSent: Boolean = false,
    val errorMessage: String? = null,
    val emailError: String? = null,
    val statusMessage: String? = null
)

class ForgetPasswordViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(ForgetPasswordUIState())
    val uiState: StateFlow<ForgetPasswordUIState> = _uiState.asStateFlow()

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
                // Send password reset email with the correct redirect URL
                SupabaseClient.client.auth.resetPasswordForEmail(
                    email = email.trim(),
                    redirectUrl = "myfridge://resetpassword"
                )

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isResetEmailSent = true,
                    statusMessage = "Password reset link has been sent to ${email.trim()}. Please check your email.",
                    errorMessage = null
                )

                Log.d("ForgetPassword", "Reset email sent successfully to: ${email.trim()}")

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