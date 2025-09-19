package com.example.myfridge.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import io.github.jan.supabase.auth.auth
import com.example.myfridge.data.SupabaseClient
import android.util.Log

data class DashboardUIState(
    val username: String? = null,
    val isAuthenticated: Boolean = false,
    val authCheckCompleted: Boolean = false,
    val isLoggedOut: Boolean = false,
    val errorMessage: String? = null
)

class DashboardViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUIState())
    val uiState: StateFlow<DashboardUIState> = _uiState.asStateFlow()

    init {
        checkAuthentication()
    }

    fun loadCurrentUser() {
        viewModelScope.launch {
            try {
                val user = SupabaseClient.client.auth.currentUserOrNull()

                if (user != null) {
                    // Get username from user metadata
                    val username = user.userMetadata?.get("username")?.toString()
                        ?: user.email?.substringBefore("@") // Fallback to email prefix
                        ?: "User"

                    _uiState.value = _uiState.value.copy(
                        username = username,
                        isAuthenticated = true,
                        authCheckCompleted = true
                    )

                    Log.d("Dashboard", "User loaded: ${user.email}, username: $username")
                } else {
                    _uiState.value = _uiState.value.copy(
                        isAuthenticated = false,
                        authCheckCompleted = true,
                        errorMessage = "No authenticated user found"
                    )
                }
            } catch (e: Exception) {
                Log.e("Dashboard", "Error loading current user", e)
                _uiState.value = _uiState.value.copy(
                    isAuthenticated = false,
                    authCheckCompleted = true,
                    errorMessage = "Failed to load user information"
                )
            }
        }
    }

    fun setUsername(username: String) {
        _uiState.value = _uiState.value.copy(
            username = username,
            isAuthenticated = true,
            authCheckCompleted = true
        )
    }

    fun checkAuthentication() {
        viewModelScope.launch {
            try {
                val user = SupabaseClient.client.auth.currentUserOrNull()

                _uiState.value = _uiState.value.copy(
                    isAuthenticated = user != null,
                    authCheckCompleted = true
                )

                if (user == null) {
                    Log.d("Dashboard", "No authenticated user found")
                } else {
                    Log.d("Dashboard", "User is authenticated: ${user.email}")
                }
            } catch (e: Exception) {
                Log.e("Dashboard", "Error checking authentication", e)
                _uiState.value = _uiState.value.copy(
                    isAuthenticated = false,
                    authCheckCompleted = true
                )
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            try {
                SupabaseClient.client.auth.signOut()

                _uiState.value = _uiState.value.copy(
                    username = null,
                    isAuthenticated = false,
                    isLoggedOut = true,
                    authCheckCompleted = true
                )

                Log.d("Dashboard", "User logged out successfully")
            } catch (e: Exception) {
                Log.e("Dashboard", "Error during logout", e)
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Failed to logout: ${e.message}"
                )
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }
}