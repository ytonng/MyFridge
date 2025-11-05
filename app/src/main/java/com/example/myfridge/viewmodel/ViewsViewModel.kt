package com.example.myfridge.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myfridge.data.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.postgrest
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

@Serializable
data class PairedDevice(
    val id: Long,
    val fridge_id: Long,
    val device_serial: String,
    val stream_name: String,
    val region: String? = "ap-southeast-1",
    val created_at: String? = null,
)

@Serializable
data class FridgeInfo(
    val id: Long,
    val name: String?,
    val serial_number: String
)

@Serializable
data class FridgeMemberResponse(
    val fridge_id: Long,
    val user_id: String
)

data class QRCodeData(
    val token: String,
    val expiresInSeconds: Int
)

data class ViewsUIState(
    val isLoading: Boolean = false,
    val fridgeName: String = "Loading...",
    val currentFridgeId: Long? = null,
    val pairedDevices: List<String> = emptyList(),
    val qrCodeData: QRCodeData? = null,
    val playbackUrl: String? = null,
    val errorMessage: String? = null,
    val successMessage: String? = null
)

class ViewsViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(ViewsUIState())
    val uiState: StateFlow<ViewsUIState> = _uiState.asStateFlow()

    private val auth get() = SupabaseClient.client.auth

    fun refreshFridge() {
        loadCurrentFridge()
    }

    fun loadCurrentFridge() {
        _uiState.value = _uiState.value.copy(isLoading = true)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val userId = auth.currentUserOrNull()?.id
                if (userId == null) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        fridgeName = "No fridge found",
                        errorMessage = "Please join a fridge first"
                    )
                    return@launch
                }

                // Resolve current fridge: prefer user metadata current_fridge_id, fallback to first membership
                val metaFridgeId: Long? = try {
                    val metaVal = auth.currentUserOrNull()?.userMetadata?.get("current_fridge_id")
                    when (metaVal) {
                        is Number -> metaVal.toLong()
                        is String -> metaVal.toLongOrNull()
                        else -> metaVal?.toString()?.toLongOrNull()
                    }
                } catch (_: Exception) { null }

                val fridgeId: Long = if (metaFridgeId != null) {
                    metaFridgeId
                } else {
                    val fridgeResponse = SupabaseClient.client.postgrest["fridge_members"]
                        .select {
                            filter { eq("user_id", userId) }
                            limit(1)
                        }
                    val fridgeMembers = fridgeResponse.decodeList<FridgeMemberResponse>()
                    if (fridgeMembers.isEmpty()) {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            fridgeName = "No fridge found",
                            errorMessage = "Please join a fridge first"
                        )
                        return@launch
                    }
                    fridgeMembers[0].fridge_id
                }

                val fridgeInfoResponse = SupabaseClient.client.postgrest["fridge"]
                    .select {
                        filter {
                            eq("id", fridgeId)
                        }
                    }

                val fridges = fridgeInfoResponse.decodeList<FridgeInfo>()
                val fridgeName = fridges.firstOrNull()?.name ?: "Fridge #$fridgeId"

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    fridgeName = fridgeName,
                    currentFridgeId = fridgeId
                )

                // Load cameras for this fridge
                loadFridgeCameras(fridgeId)

            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    fridgeName = "Error loading fridge",
                    errorMessage = "Error: ${e.message}"
                )
            }
        }
    }

    private suspend fun loadFridgeCameras(fridgeId: Long) {
        try {
            val response = SupabaseClient.client.postgrest["paired_devices"]
                .select {
                    filter {
                        eq("fridge_id", fridgeId)
                    }
                }

            val devices = response.decodeList<PairedDevice>()
            val serials = devices.map { it.device_serial }

            _uiState.value = _uiState.value.copy(
                pairedDevices = serials,
                successMessage = if (serials.isEmpty()) "No cameras paired to this fridge" else null
            )

        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(
                errorMessage = "Error fetching cameras: ${e.message}"
            )
        }
    }

    fun generatePairingQRCode() {
        val fridgeId = _uiState.value.currentFridgeId
        if (fridgeId == null) {
            _uiState.value = _uiState.value.copy(
                errorMessage = "No fridge selected"
            )
            return
        }

        _uiState.value = _uiState.value.copy(isLoading = true)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val session = auth.currentSessionOrNull()
                val accessToken = session?.accessToken

                if (accessToken.isNullOrBlank()) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = "Session expired. Please sign in again."
                    )
                    return@launch
                }

                val httpResponse = SupabaseClient.client.functions.invoke(
                    function = "createPairingToken",
                    body = buildJsonObject { put("fridge_id", fridgeId) },
                    headers = Headers.build {
                        append(HttpHeaders.ContentType, "application/json")
                        append(HttpHeaders.Authorization, "Bearer $accessToken")
                    }
                )

                val bodyText = httpResponse.bodyAsText()
                val result = JSONObject(bodyText)
                val token = result.optString("token")
                val expiresInSeconds = result.optInt("expires_in_seconds", 300)

                if (token.isNotEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        qrCodeData = QRCodeData(token, expiresInSeconds)
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = "Error generating QR: ${result.optString("error", bodyText)}"
                    )
                }

            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "Error: ${e.message}"
                )
            }
        }
    }

    fun clearQRCodeData() {
        _uiState.value = _uiState.value.copy(qrCodeData = null)
        // Refresh camera list after pairing
        _uiState.value.currentFridgeId?.let { fridgeId ->
            viewModelScope.launch(Dispatchers.IO) {
                loadFridgeCameras(fridgeId)
            }
        }
    }

    fun fetchAndPlayStream(deviceSerial: String) {
        if (deviceSerial.isEmpty()) {
            _uiState.value = _uiState.value.copy(
                errorMessage = "Please select a device"
            )
            return
        }

        _uiState.value = _uiState.value.copy(isLoading = true)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val requestBody = JSONObject().apply {
                    put("device_serial", deviceSerial)
                }.toString()

                val url = URL("https://w2d82zw6t0.execute-api.ap-southeast-1.amazonaws.com/prod/get-stream-url")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.outputStream.use { it.write(requestBody.toByteArray()) }

                val responseText = conn.inputStream.bufferedReader().readText()
                val jsonResponse = JSONObject(responseText)
                val playbackUrl = jsonResponse.optString("playback_url")

                if (playbackUrl.isNotEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        playbackUrl = playbackUrl
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = "Invalid playback URL"
                    )
                }

            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "Error: ${e.message}"
                )
            }
        }
    }

    fun clearPlaybackUrl() {
        _uiState.value = _uiState.value.copy(playbackUrl = null)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    fun clearSuccessMessage() {
        _uiState.value = _uiState.value.copy(successMessage = null)
    }
}