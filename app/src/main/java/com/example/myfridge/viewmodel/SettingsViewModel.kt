package com.example.myfridge.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myfridge.data.SupabaseClient
import com.example.myfridge.data.model.FridgeRow
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class SettingsUIState(
    val isLoading: Boolean = false,
    val email: String? = null,
    val username: String? = null,
    val avatarUrl: String? = null,
    val currentFridgeId: Long? = null,
    val currentFridgeName: String? = null,
    val memberUsernames: List<String> = emptyList(),
    val joinedFridges: List<FridgeSummary> = emptyList(),
    val fridgesWithMembers: List<FridgeWithMembers> = emptyList(),
    val successMessage: String? = null,
    val errorMessage: String? = null,
    val shouldNavigateToMyFridge: Boolean = false,
    val isLoggedOut: Boolean = false,
    val canChangePassword: Boolean = true
)

@Serializable
data class FridgeSummary(
    val id: Long,
    val name: String? = null,
    val serial_number: String? = null
)



data class FridgeWithMembers(
    val id: Long,
    val name: String,
    val serialNumber: String,
    val members: List<String>,
    val isCurrentFridge: Boolean = false
)

@Serializable
data class FridgeMemberRow(
    val user_id: String,
    val fridge_id: Long
)

// Device-related state is removed from SettingsViewModel

class SettingsViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(SettingsUIState())
    val uiState: StateFlow<SettingsUIState> = _uiState.asStateFlow()

    fun load() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null, successMessage = null)
            Log.d("SettingsVM", "load: start")
            try {
                val user = SupabaseClient.client.auth.currentUserOrNull()
                if (user == null) {
                    _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = "Please login first")
                    return@launch
                }

                val email = user.email
                Log.d("SettingsVM", "load: userId=${user.id}, email=${email}")
                // Parse username safely from user metadata
                val usernameMeta = user.userMetadata?.get("username") as? String
                Log.d("SettingsVM", "load: usernameMeta=${usernameMeta}")

                // Determine if password change should be visible based on auth provider
                // Prefer reliable signals from appMetadata.providers/appMetadata.provider; fallback to custom userMetadata.auth_provider
                val providerMeta = try {
                    user.userMetadata?.get("auth_provider")?.toString()
                } catch (_: Exception) { null }

                val isGoogleAuth: Boolean = try {
                    // appMetadata can include a single provider or a list of providers
                    val appMetaProvidersAny = try { user.appMetadata?.get("providers") } catch (_: Exception) { null }
                    val providersList: List<Any?>? = when (appMetaProvidersAny) {
                        is List<*> -> appMetaProvidersAny
                        is kotlinx.serialization.json.JsonArray -> appMetaProvidersAny.toList()
                        is String -> listOf(appMetaProvidersAny)
                        else -> null
                    }
                    val appMetaSingleProvider = try { user.appMetadata?.get("provider")?.toString() } catch (_: Exception) { null }

                    val providersContainsGoogle = providersList?.any { it?.toString()?.contains("google", ignoreCase = true) == true } == true
                    val isGoogleSingle = appMetaSingleProvider?.equals("google", ignoreCase = true) == true
                    val isGoogleCustomMeta = providerMeta?.equals("google", ignoreCase = true) == true
                    providersContainsGoogle || isGoogleSingle || isGoogleCustomMeta
                } catch (_: Exception) {
                    providerMeta?.equals("google", ignoreCase = true) == true
                }

                val canChangePassword = !isGoogleAuth

                // Fallback to profiles.username if metadata is missing
                val usernameDb: String? = try {
                    SupabaseClient.client.postgrest
                        .from("profiles")
                        .select(columns = Columns.list("username")) {
                            filter { eq("user_id", user.id.toString()) }
                            limit(1)
                        }
                        .decodeList<Map<String, String>>()
                        .firstOrNull()?.get("username")
                } catch (e: Exception) { null }
                Log.d("SettingsVM", "load: usernameDb=${usernameDb}")

                val resolvedUsername = usernameMeta
                    ?: usernameDb
                    ?: email?.substringBefore('@')
                Log.d("SettingsVM", "load: resolvedUsername=${resolvedUsername}")

                // Load avatarUrl from profiles if present
                val avatarUrl: String? = try {
                    SupabaseClient.client.postgrest
                        .from("profiles")
                        .select(columns = Columns.list("avatar_url")) {
                            filter { eq("user_id", user.id.toString()) }
                            limit(1)
                        }
                        .decodeList<Map<String, String>>()
                        .firstOrNull()?.get("avatar_url")
                } catch (e: Exception) { null }

                // Determine current fridge from metadata or first membership
                val currentFridgeId: Long? = try {
                    val metaVal = user.userMetadata?.get("current_fridge_id")
                    val metaId = try {
                        when (metaVal) {
                            is Number -> metaVal.toLong()
                            is String -> metaVal.toLongOrNull()
                            else -> metaVal?.toString()?.toLongOrNull()
                        }
                    } catch (_: Exception) { null }
                    Log.d(
                        "SettingsVM",
                        "load: meta current_fridge_id raw=${metaVal}, type=" + (metaVal?.let { it::class.java.name } ?: "<none>") + ", parsed=${metaId}"
                    )
                    metaId ?: SupabaseClient.client.postgrest.from("fridge_members")
                        .select(columns = Columns.list("fridge_id")) {
                            filter { eq("user_id", user.id.toString()) }
                            limit(1)
                        }
                        .decodeList<Map<String, Long>>()
                        .firstOrNull()?.get("fridge_id")
                } catch (e: Exception) {
                    Log.w("SettingsVM", "Failed resolving current fridge id: ${e.message}")
                    null
                }

                val currentFridgeName: String? = if (currentFridgeId != null) try {
                    SupabaseClient.client.postgrest.from("fridge")
                        .select(columns = Columns.list("id", "name")) {
                            filter { eq("id", currentFridgeId.toString()) }
                            limit(1)
                        }
                        .decodeList<FridgeSummary>()
                        .firstOrNull()?.name ?: "Fridge #$currentFridgeId"
                } catch (e: Exception) { "Fridge #$currentFridgeId" } else null
                Log.d("SettingsVM", "load: currentFridgeId=${currentFridgeId}, currentFridgeName=${currentFridgeName}")

                // Load member usernames for current fridge
                val members: List<String> = if (currentFridgeId != null) {
                    try {
                        val memberRows = SupabaseClient.client.postgrest.from("fridge_members")
                            .select(columns = Columns.list("user_id")) {
                                filter { eq("fridge_id", currentFridgeId.toString()) }
                            }
                            .decodeList<Map<String, String>>()
                            .mapNotNull { it["user_id"] }

                        if (memberRows.isNotEmpty()) {
                            SupabaseClient.client.postgrest.from("profiles")
                                .select(columns = Columns.list("user_id", "username")) {
                                    filter { isIn("user_id", memberRows) }
                                }
                                .decodeList<Map<String, String>>()
                                .map { it["username"] ?: "User" }
                        } else emptyList()
                    } catch (e: Exception) {
                        Log.w("SettingsVM", "Failed loading members: ${e.message}")
                        emptyList()
                    }
                } else emptyList()
                Log.d("SettingsVM", "load: membersCount=${members.size}")

                // Load all joined fridges (names)
                val joinedFridges: List<FridgeSummary> = try {
                    val fridgeIds = SupabaseClient.client.postgrest
                        .from("fridge_members")
                        .select(columns = Columns.list("fridge_id")) {
                            filter { eq("user_id", user.id.toString()) }
                        }
                        .decodeList<Map<String, Long>>()
                        .mapNotNull { it["fridge_id"] }

                    if (fridgeIds.isNotEmpty()) {
                        SupabaseClient.client.postgrest
                            .from("fridge")
                            .select(columns = Columns.list("id", "name")) {
                                filter { isIn("id", fridgeIds.map { it.toString() }) }
                            }
                            .decodeList<FridgeSummary>()
                    } else emptyList()
                } catch (e: Exception) {
                    Log.w("SettingsVM", "Failed loading joined fridges: ${e.message}")
                    emptyList()
                }
                Log.d("SettingsVM", "load: joinedFridgesCount=${joinedFridges.size}")

                // Load complete fridge data with members
                val fridgesWithMembers = loadFridgesWithMembers(currentFridgeId)

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    email = email,
                    username = resolvedUsername,
                    avatarUrl = avatarUrl,
                    currentFridgeId = currentFridgeId,
                    currentFridgeName = currentFridgeName,
                    canChangePassword = canChangePassword,
                    memberUsernames = members,
                    joinedFridges = joinedFridges,
                    fridgesWithMembers = fridgesWithMembers
                )
                Log.d("SettingsVM", "load: state updated")

                // Device list loading removed
            } catch (e: Exception) {
                Log.e("SettingsVM", "Load failed: ${e.message}", e)
                _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = "Failed to load settings: ${e.message}")
            }
        }
    }

    fun saveProfile(username: String?, avatarUrl: String?) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null, successMessage = null)
            try {
                Log.d("SettingsVM", "saveProfile start username=$username avatarUrl=$avatarUrl")
                val user = SupabaseClient.client.auth.currentUserOrNull()
                if (user == null) {
                    _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = "Please login first")
                    return@launch
                }

                // Update auth user metadata username
                if (!username.isNullOrBlank()) {
                    SupabaseClient.client.auth.updateUser {
                        data = buildJsonObject { put("username", username) }
                    }
                    Log.d("SettingsVM", "Updated auth user metadata: username=$username")
                }

                // Upsert profile with username and avatar
                val profilePayload = buildJsonObject {
                    put("user_id", user.id.toString())
                    if (!username.isNullOrBlank()) put("username", username)
                    if (!avatarUrl.isNullOrBlank()) put("avatar_url", avatarUrl)
                }
                Log.d("SettingsVM", "Upserting profile payload=$profilePayload")
                SupabaseClient.client.postgrest.from("profiles").upsert(profilePayload) {
                    onConflict = "user_id"
                }
                Log.d("SettingsVM", "Upsert to profiles success")

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    username = if (!username.isNullOrBlank()) username else _uiState.value.username,
                    avatarUrl = if (!avatarUrl.isNullOrBlank()) avatarUrl else _uiState.value.avatarUrl,
                    successMessage = "Profile saved"
                )
            } catch (e: Exception) {
                Log.e("SettingsVM", "Save profile failed: ${e.message}", e)
                _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = "Failed to save profile: ${e.message}")
            }
        }
    }

    fun changePassword(newPassword: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null, successMessage = null)
            try {
                SupabaseClient.client.auth.updateUser { password = newPassword }
                _uiState.value = _uiState.value.copy(isLoading = false, successMessage = "Password updated")
            } catch (e: Exception) {
                Log.e("SettingsVM", "Change password failed: ${e.message}", e)
                _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = "Failed to change password: ${e.message}")
            }
        }
    }

    fun joinFridge(serial: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null, successMessage = null)
            try {
                val user = SupabaseClient.client.auth.currentUserOrNull()
                if (user == null) {
                    _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = "Please login first")
                    return@launch
                }

                val rows = SupabaseClient.client.postgrest
                    .from("fridge")
                    .select(columns = Columns.list("id", "serial_number", "name")) {
                        filter { eq("serial_number", serial.trim()) }
                        limit(1)
                    }
                    .decodeList<FridgeRow>()

                if (rows.isEmpty()) {
                    _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = "Fridge not found")
                    return@launch
                }

                val fridgeId = rows.first().id
                // Prevent duplicate membership
                val existingMembership = SupabaseClient.client.postgrest
                    .from("fridge_members")
                    .select(columns = Columns.list("user_id", "fridge_id")) {
                        filter {
                            eq("user_id", user.id.toString())
                            eq("fridge_id", fridgeId.toString())
                        }
                        limit(1)
                    }
                    .decodeList<FridgeMemberRow>()
                if (existingMembership.isNotEmpty()) {
                    _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = "You already joined this fridge")
                    return@launch
                }
                val membership = buildJsonObject {
                    put("user_id", user.id.toString())
                    put("fridge_id", fridgeId)
                }
                SupabaseClient.client.postgrest.from("fridge_members").insert(membership)

                _uiState.value = _uiState.value.copy(isLoading = false, successMessage = "Joined fridge")
                load()
            } catch (e: Exception) {
                Log.e("SettingsVM", "Join fridge failed: ${e.message}", e)
                _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = "Failed to join fridge: ${e.message}")
            }
        }
    }

    fun createFridge(name: String = "My Fridge") {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null, successMessage = null)
            try {
                val user = SupabaseClient.client.auth.currentUserOrNull()
                if (user == null) {
                    _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = "Please login first")
                    return@launch
                }

                val serial = generateSerial()
                val payload = buildJsonObject {
                    put("name", name)
                    put("serial_number", serial)
                }
                SupabaseClient.client.postgrest.from("fridge").insert(payload)
                val rows = SupabaseClient.client.postgrest
                    .from("fridge")
                    .select(columns = Columns.list("id", "serial_number", "name")) {
                        filter { eq("serial_number", serial) }
                        limit(1)
                    }
                    .decodeList<FridgeRow>()
                val fridgeId = rows.first().id
                val membership = buildJsonObject {
                    put("user_id", user.id.toString())
                    put("fridge_id", fridgeId)
                }
                SupabaseClient.client.postgrest.from("fridge_members").insert(membership)

                // Set current fridge metadata
                SupabaseClient.client.auth.updateUser {
                    data = buildJsonObject { put("current_fridge_id", fridgeId) }
                }

                _uiState.value = _uiState.value.copy(isLoading = false, successMessage = "Fridge created! Serial: $serial")
                load()
            } catch (e: Exception) {
                Log.e("SettingsVM", "Create fridge failed: ${e.message}", e)
                _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = "Failed to create fridge: ${e.message}")
            }
        }
    }

    fun switchFridge(fridgeId: Long) {
        viewModelScope.launch {
            Log.d("SettingsVM", "switchFridge: requested fridgeId=${fridgeId}")
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null, successMessage = null)
            try {
                SupabaseClient.client.auth.updateUser {
                    data = buildJsonObject { put("current_fridge_id", fridgeId) }
                }
                val metaVal = SupabaseClient.client.auth.currentUserOrNull()?.userMetadata?.get("current_fridge_id")
                Log.d("SettingsVM", "switchFridge: auth.updateUser done, meta current_fridge_id=${metaVal}")
                // Stay on the fridge settings screen and refresh the local state
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    successMessage = "Switched fridge successfully"
                )
                // Reload to update current fridge name, members, devices, etc.
                Log.d("SettingsVM", "switchFridge: calling load() to refresh state")
                load()
            } catch (e: Exception) {
                Log.e("SettingsVM", "Switch fridge failed: ${e.message}", e)
                _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = "Failed to switch fridge: ${e.message}")
            }
        }
    }

    fun consumeNavigation() {
        _uiState.value = _uiState.value.copy(shouldNavigateToMyFridge = false)
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(errorMessage = null, successMessage = null)
    }

    fun logout() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            try {
                SupabaseClient.client.auth.signOut()
                _uiState.value = _uiState.value.copy(isLoading = false, isLoggedOut = true)
            } catch (e: Exception) {
                Log.e("SettingsVM", "Logout failed: ${e.message}", e)
                _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = "Logout failed: ${e.message}")
            }
        }
    }

    fun consumeLogout() {
        _uiState.value = _uiState.value.copy(isLoggedOut = false)
    }

    private fun generateSerial(): String {
        val base = "FRG" + System.currentTimeMillis().toString().takeLast(6)
        return base
    }

    // Device list loader removed

    private suspend fun loadFridgesWithMembers(currentFridgeId: Long?): List<FridgeWithMembers> {
        return try {
            val user = SupabaseClient.client.auth.currentUserOrNull()
            if (user == null) return emptyList()

            // Get all fridge IDs the user is a member of
            val fridgeIds = SupabaseClient.client.postgrest
                .from("fridge_members")
                .select(columns = Columns.list("fridge_id")) {
                    filter { eq("user_id", user.id.toString()) }
                }
                .decodeList<Map<String, Long>>()
                .mapNotNull { it["fridge_id"] }

            if (fridgeIds.isEmpty()) return emptyList()

            // Get fridge details with serial numbers
            val fridges = SupabaseClient.client.postgrest
                .from("fridge")
                .select(columns = Columns.list("id", "name", "serial_number")) {
                    filter { isIn("id", fridgeIds.map { it.toString() }) }
                }
                .decodeList<FridgeRow>()

            // For each fridge, get its members
            val fridgesWithMembers = fridges.map { fridge ->
                val memberUserIds = try {
                    SupabaseClient.client.postgrest
                        .from("fridge_members")
                        .select(columns = Columns.list("user_id")) {
                            filter { eq("fridge_id", fridge.id.toString()) }
                        }
                        .decodeList<Map<String, String>>()
                        .mapNotNull { it["user_id"] }
                } catch (e: Exception) {
                    Log.w("SettingsVM", "Failed loading members for fridge ${fridge.id}: ${e.message}")
                    emptyList()
                }

                val memberUsernames = if (memberUserIds.isNotEmpty()) {
                    try {
                        SupabaseClient.client.postgrest
                            .from("profiles")
                            .select(columns = Columns.list("user_id", "username")) {
                                filter { isIn("user_id", memberUserIds) }
                            }
                            .decodeList<Map<String, String>>()
                            .map { it["username"] ?: "User" }
                    } catch (e: Exception) {
                        Log.w("SettingsVM", "Failed loading usernames for fridge ${fridge.id}: ${e.message}")
                        memberUserIds.map { "User" }
                    }
                } else emptyList()

                FridgeWithMembers(
                    id = fridge.id,
                    name = fridge.name ?: "Fridge #${fridge.id}",
                    serialNumber = fridge.serial_number,
                    members = memberUsernames,
                    isCurrentFridge = fridge.id == currentFridgeId
                )
            }

            fridgesWithMembers
        } catch (e: Exception) {
            Log.e("SettingsVM", "Failed loading fridges with members: ${e.message}", e)
            emptyList()
        }
    }

    
}