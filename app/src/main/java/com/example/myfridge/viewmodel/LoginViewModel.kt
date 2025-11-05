package com.example.myfridge.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.IDToken
import io.github.jan.supabase.exceptions.RestException
import com.example.myfridge.data.SupabaseClient
import android.util.Log
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns

data class LoginUIState(
    val isLoading: Boolean = false,
    val isSuccess: Boolean = false,
    val errorMessage: String? = null,
    val emailError: String? = null,
    val passwordError: String? = null,
    val username: String? = null,
    val shouldNavigateToAuthChoice: Boolean = false
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

                // Determine if the user already has a fridge
                val metaVal = user?.userMetadata?.get("current_fridge_id")
                val metaId: Long? = when (metaVal) {
                    is Number -> metaVal.toLong()
                    is String -> metaVal.toLongOrNull()
                    else -> metaVal?.toString()?.toLongOrNull()
                }
                var hasFridge = metaId != null
                if (!hasFridge && user != null) {
                    // Fallback to membership lookup
                    try {
                        val memberships = SupabaseClient.client.postgrest.from("fridge_members")
                            .select(columns = Columns.list("fridge_id")) {
                                filter { eq("user_id", user.id.toString()) }
                                limit(1)
                            }
                            .decodeList<Map<String, Long>>()
                        val foundId = memberships.firstOrNull()?.get("fridge_id")
                        hasFridge = foundId != null
                        // Optionally cache for faster future checks
                        if (foundId != null) {
                            try {
                                SupabaseClient.client.auth.updateUser {
                                    data = buildJsonObject { put("current_fridge_id", foundId) }
                                }
                            } catch (_: Exception) { /* ignore */ }
                        }
                    } catch (_: Exception) {
                        hasFridge = false
                    }
                }

                // Ensure a profile row exists for this user
                try {
                    if (user != null) {
                        val existing = SupabaseClient.client.postgrest
                            .from("profiles")
                            .select(columns = Columns.list("user_id")) {
                                filter { eq("user_id", user.id.toString()) }
                                limit(1)
                            }
                            .decodeList<Map<String, String>>()
                            .firstOrNull()

                        if (existing == null) {
                            val initialUsername = user.userMetadata?.get("username")?.toString()
                                ?: user.email?.substringBefore("@")
                                ?: "User"
                            val initialAvatar = user.userMetadata?.get("avatar_url")?.toString()

                            val payload = buildJsonObject {
                                put("user_id", user.id.toString())
                                put("username", initialUsername)
                                if (!initialAvatar.isNullOrBlank()) put("avatar_url", initialAvatar)
                            }
                            SupabaseClient.client.postgrest.from("profiles").insert(payload)
                        }
                    }
                } catch (e: Exception) {
                    Log.w("LoginVM", "Failed to ensure profile on email login: ${e.message}")
                }

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isSuccess = true,
                    username = username,
                    shouldNavigateToAuthChoice = !hasFridge,
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

    fun signInWithGoogle(idToken: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            
            try {
                SupabaseClient.client.auth.signInWith(IDToken) {
                    this.idToken = idToken
                    provider = Google
                }
                
                val user = SupabaseClient.client.auth.currentUserOrNull()
                if (user != null) {
                    // Tag user metadata with auth provider for UI logic
                    try {
                        SupabaseClient.client.auth.updateUser {
                            data = buildJsonObject { put("auth_provider", "google") }
                        }
                    } catch (e: Exception) {
                        Log.w("LoginVM", "Failed to tag auth_provider: ${e.message}")
                    }

                    val username = user.userMetadata?.get("full_name")?.toString() 
                        ?: user.email?.substringBefore("@") 
                        ?: "User"
                    // Determine if the user already has a fridge
                    val metaVal = user.userMetadata?.get("current_fridge_id")
                    val metaId: Long? = when (metaVal) {
                        is Number -> metaVal.toLong()
                        is String -> metaVal.toLongOrNull()
                        else -> metaVal?.toString()?.toLongOrNull()
                    }
                    var hasFridge = metaId != null
                    if (!hasFridge) {
                        // Fallback to membership lookup
                        try {
                            val memberships = SupabaseClient.client.postgrest.from("fridge_members")
                                .select(columns = Columns.list("fridge_id")) {
                                    filter { eq("user_id", user.id.toString()) }
                                    limit(1)
                                }
                                .decodeList<Map<String, Long>>()
                            val foundId = memberships.firstOrNull()?.get("fridge_id")
                            hasFridge = foundId != null
                            // Optionally cache for faster future checks
                            if (foundId != null) {
                                try {
                                    SupabaseClient.client.auth.updateUser {
                                        data = buildJsonObject { put("current_fridge_id", foundId) }
                                    }
                                } catch (_: Exception) { /* ignore */ }
                            }
                        } catch (_: Exception) {
                            hasFridge = false
                        }
                    }

                    // Ensure a profile row exists for this user (Google)
                    try {
                        val existing = SupabaseClient.client.postgrest
                            .from("profiles")
                            .select(columns = Columns.list("user_id")) {
                                filter { eq("user_id", user.id.toString()) }
                                limit(1)
                            }
                            .decodeList<Map<String, String>>()
                            .firstOrNull()

                        if (existing == null) {
                            val initialUsername = user.userMetadata?.get("full_name")?.toString()
                                ?: user.email?.substringBefore("@")
                                ?: "User"
                            val initialAvatar = user.userMetadata?.get("avatar_url")?.toString()

                            val payload = buildJsonObject {
                                put("user_id", user.id.toString())
                                put("username", initialUsername)
                                if (!initialAvatar.isNullOrBlank()) put("avatar_url", initialAvatar)
                            }
                            SupabaseClient.client.postgrest.from("profiles").insert(payload)
                        }
                    } catch (e: Exception) {
                        Log.w("LoginVM", "Failed to ensure profile on Google login: ${e.message}")
                    }
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isSuccess = true,
                        username = username,
                        shouldNavigateToAuthChoice = !hasFridge
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = "Failed to authenticate with Google"
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "Google sign-in failed: ${e.message}"
                )
            }
        }
    }
}