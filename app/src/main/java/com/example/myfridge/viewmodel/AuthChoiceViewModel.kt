package com.example.myfridge.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myfridge.data.SupabaseClient
import com.example.myfridge.data.model.FridgeRow
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.exceptions.RestException
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class AuthChoiceUIState(
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null,
    val shouldNavigate: Boolean = false
)

class AuthChoiceViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(AuthChoiceUIState())
    val uiState: StateFlow<AuthChoiceUIState> = _uiState.asStateFlow()

    private var lastErrorMessage: String? = null
    fun getErrorMessage(): String? = lastErrorMessage

    fun createFridge(defaultName: String = "My Fridge") {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null, successMessage = null, shouldNavigate = false)

            val user = SupabaseClient.client.auth.currentUserOrNull()
            if (user == null) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "Please verify your email and login before creating a fridge."
                )
                return@launch
            }

            val serial = generateSerial()
            try {
                val payload = buildJsonObject {
                    put("serial_number", serial)
                    // Removed owner_id to align with many-to-many schema via fridge_members
                    put("name", defaultName)
                }
                SupabaseClient.client.postgrest
                    .from("fridge")
                    .insert(payload)

                val rows = SupabaseClient.client.postgrest
                    .from("fridge")
                    .select {
                        filter { eq("serial_number", serial) }
                        limit(1)
                    }
                    .decodeList<FridgeRow>()

                val fridgeId = rows.firstOrNull()?.id
                if (fridgeId != null) {
                    val membership = buildJsonObject {
                        put("user_id", user.id.toString())
                        put("fridge_id", fridgeId)
                    }
                    SupabaseClient.client.postgrest
                        .from("fridge_members")
                        .insert(membership)
                }

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    successMessage = "Fridge created! Serial: $serial",
                    shouldNavigate = true
                )
            } catch (e: Exception) {
                val debugMsg = when (e) {
                    is RestException -> "Supabase error (status=${e.statusCode}): ${e.message ?: e.toString()}"
                    else -> "${e::class.simpleName}: ${e.message ?: e.toString()}"
                }
                lastErrorMessage = debugMsg
                Log.e("AuthChoice", "Create fridge failed: $debugMsg", e)

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "Failed to create fridge: ${e.message ?: "unknown error"}"
                )
            }
        }
    }

    fun joinFridge(serial: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null, successMessage = null, shouldNavigate = false)

            val user = SupabaseClient.client.auth.currentUserOrNull()
            if (user == null) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "Please verify your email and login before joining a fridge."
                )
                return@launch
            }

            try {
                val rows = SupabaseClient.client.postgrest
                    .from("fridge")
                    .select {
                        filter { eq("serial_number", serial.trim()) }
                        limit(1)
                    }
                    .decodeList<FridgeRow>()

                if (rows.isEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = "Fridge not found. Please check the serial number."
                    )
                    return@launch
                }

                val fridgeId = rows.first().id

                val membership = buildJsonObject {
                    put("user_id", user.id.toString())
                    put("fridge_id", fridgeId)
                }
                SupabaseClient.client.postgrest
                    .from("fridge_members")
                    .insert(membership)

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    successMessage = "Joined fridge successfully!",
                    shouldNavigate = true
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "Failed to join fridge: ${e.message}"
                )
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    fun consumeNavigation() {
        _uiState.value = _uiState.value.copy(shouldNavigate = false)
    }

    private fun generateSerial(length: Int = 8): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray()
        return (1..length)
            .map { chars.random() }
            .joinToString("")
    }
}