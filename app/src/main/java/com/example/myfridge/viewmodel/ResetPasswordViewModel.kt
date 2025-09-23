package com.example.myfridge.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.exceptions.RestException
import com.example.myfridge.data.SupabaseClient
import android.net.Uri
import android.util.Log
import kotlin.time.ExperimentalTime

data class ResetPasswordUIState(
    val isLoading: Boolean = false,
    val isSuccess: Boolean = false,
    val isValidResetLink: Boolean = false,
    val errorMessage: String? = null,
    val passwordError: String? = null,
    val confirmPasswordError: String? = null,
    val statusMessage: String? = null
)

class ResetPasswordViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(ResetPasswordUIState())
    val uiState: StateFlow<ResetPasswordUIState> = _uiState.asStateFlow()

    @OptIn(ExperimentalTime::class)
    fun handleDeepLink(uri: Uri?) {
        if (uri == null) {
            _uiState.value = _uiState.value.copy(
                errorMessage = "Please use the reset link from your email."
            )
            return
        }

        if (uri.scheme != "myfridge" || uri.host != "resetpassword") {
            _uiState.value = _uiState.value.copy(
                errorMessage = "Invalid reset link. Please request a new one."
            )
            return
        }

        viewModelScope.launch {
            try {
                // Extract tokens from URL
                val accessToken = uri.getQueryParameter("access_token")
                    ?: extractFromFragment(uri, "access_token")
                val refreshToken = uri.getQueryParameter("refresh_token")
                    ?: extractFromFragment(uri, "refresh_token")
                val tokenType = uri.getQueryParameter("token_type")
                    ?: extractFromFragment(uri, "token_type")

                val errorParam = uri.getQueryParameter("error")
                val errorDescription = uri.getQueryParameter("error_description")

                // Check for errors first
                if (errorParam != null) {
                    _uiState.value = _uiState.value.copy(
                        errorMessage = "Reset link error: $errorDescription"
                    )
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

                        SupabaseClient.client.auth.importSession(userSession)

                        _uiState.value = _uiState.value.copy(
                            isValidResetLink = true,
                            statusMessage = "Reset link verified. Please enter your new password.",
                            errorMessage = null
                        )

                        Log.d("ResetPassword", "Reset link verified successfully")

                    } catch (e: Exception) {
                        Log.e("ResetPassword", "Error importing session", e)
                        _uiState.value = _uiState.value.copy(
                            errorMessage = "Invalid or expired reset link. Please request a new one."
                        )
                    }
                } else {
                    _uiState.value = _uiState.value.copy(
                        errorMessage = "Invalid reset link format. Please request a new one."
                    )
                }
            } catch (e: Exception) {
                Log.e("ResetPassword", "Error processing reset link", e)
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Error processing reset link: ${e.message}"
                )
            }
        }
    }

    private fun extractFromFragment(uri: Uri, paramName: String): String? {
        return uri.fragment?.let { fragment ->
            val fragmentParams = fragment.split("&")
            fragmentParams.find { it.startsWith("$paramName=") }?.substringAfter("=")
        }
    }

    fun resetPassword(newPassword: String, confirmPassword: String) {
        // Reset previous errors
        _uiState.value = _uiState.value.copy(
            passwordError = null,
            confirmPasswordError = null,
            errorMessage = null,
            statusMessage = null
        )

        // Check if we have a valid reset link
        if (!_uiState.value.isValidResetLink) {
            _uiState.value = _uiState.value.copy(
                errorMessage = "Invalid reset session. Please request a new reset link."
            )
            return
        }

        // Validate passwords
        if (!validatePasswords(newPassword, confirmPassword)) {
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)

            try {
                // Update the user's password using the established session
                SupabaseClient.client.auth.updateUser {
                    password = newPassword
                }

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isSuccess = true,
                    statusMessage = "Password reset successful!",
                    errorMessage = null
                )

                Log.d("ResetPassword", "Password reset successful")

                // Sign out to clear the reset session
                SupabaseClient.client.auth.signOut()

            } catch (e: RestException) {
                Log.e("ResetPassword", "RestException during password reset", e)

                val errorMessage = when {
                    e.message?.contains("expired", ignoreCase = true) == true ->
                        "Reset link has expired. Please request a new one."
                    e.message?.contains("invalid", ignoreCase = true) == true ->
                        "Invalid or expired reset link. Please request a new one."
                    else -> "Error updating password: ${e.message}. Please request a new reset link."
                }

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = errorMessage
                )
            } catch (e: Exception) {
                Log.e("ResetPassword", "General exception during password reset", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "An unexpected error occurred: ${e.message}"
                )
            }
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
        _uiState.value = ResetPasswordUIState()
    }
}