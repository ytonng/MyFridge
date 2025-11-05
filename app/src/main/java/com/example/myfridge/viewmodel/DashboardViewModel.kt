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
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import com.example.myfridge.FridgeItemDisplay

@kotlinx.serialization.Serializable
data class TipRow(
    val title: String? = null,
    val image_key: String? = null, // storage key using bucket
    val active: Boolean? = null,
    val sort_order: Int? = null
)

data class TipSlide(
    val title: String? = null,
    // Can be a full URL or a storage object key; Fragment will resolve
    val imageRef: String? = null
)

data class DashboardUIState(
    val username: String? = null,
    val isAuthenticated: Boolean = false,
    val authCheckCompleted: Boolean = false,
    val isLoggedOut: Boolean = false,
    val currentFridgeId: Long? = null,
    val currentFridgeName: String? = null,
    val avatarUrl: String? = null,
    val tips: List<TipSlide> = emptyList(),
    val totalItemsCount: Int = 0,
    val expiringSoonCount: Int = 0,
    val expiredItemsCount: Int = 0,
    val nearestExpiring: List<FridgeItemDisplay> = emptyList(),
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
                    Log.d("Dashboard", "loadCurrentUser: userId=" + user.id + ", email=" + (user.email ?: "<none>") )
                    val rawMetaUsername = user.userMetadata?.get("username")
                    Log.d("Dashboard", "loadCurrentUser: raw meta username=" + (rawMetaUsername?.toString() ?: "<none>") )
                    // Get username from user metadata; cast to String to avoid extra quotes
                    val username = (user.userMetadata?.get("username") as? String)
                        ?: user.email?.substringBefore("@") // Fallback to email prefix
                        ?: "User"
                    Log.d("Dashboard", "loadCurrentUser: resolved username=" + username)

                    // Load avatar URL from profiles table (if present)
                    val avatarUrl: String? = try {
                        Log.d("Dashboard", "Querying profiles table for user_id: ${user.id}")
                        val profileResult = SupabaseClient.client.postgrest
                            .from("profiles")
                            .select(columns = Columns.list("avatar_url")) {
                                filter { eq("user_id", user.id.toString()) }
                                limit(1)
                            }
                            .decodeList<Map<String, String>>()
                        Log.d("Dashboard", "Profile query result: $profileResult")
                        val avatarUrl = profileResult.firstOrNull()?.get("avatar_url")
                        Log.d("Dashboard", "Extracted avatar_url: '$avatarUrl'")
                        avatarUrl
                    } catch (e: Exception) { 
                        Log.e("Dashboard", "Error loading avatar URL from profiles", e)
                        null 
                    }

                    _uiState.value = _uiState.value.copy(
                        username = username,
                        isAuthenticated = true,
                        authCheckCompleted = true,
                        avatarUrl = avatarUrl
                    )

                    Log.d("Dashboard", "User loaded: ${user.email}, username: $username")
                } else {
                    Log.d("Dashboard", "loadCurrentUser: currentUserOrNull returned null")
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

    fun loadFridgeContext() {
        viewModelScope.launch {
            try {
                val user = SupabaseClient.client.auth.currentUserOrNull()
                if (user == null) {
                    Log.w("Dashboard", "loadFridgeContext: no authenticated user")
                    _uiState.value = _uiState.value.copy(
                        currentFridgeId = null,
                        currentFridgeName = null,
                        errorMessage = "No authenticated user"
                    )
                    return@launch
                }

                val metaVal = user.userMetadata?.get("current_fridge_id")
                val metaType = metaVal?.let { it::class.simpleName } ?: "<none>"
                Log.d("Dashboard", "loadFridgeContext: meta current_fridge_id raw=" + (metaVal?.toString() ?: "<none>") + ", type=" + metaType)
                var fridgeId: Long? = when (metaVal) {
                    is Number -> metaVal.toLong()
                    is String -> metaVal.toLongOrNull()
                    else -> metaVal?.toString()?.toLongOrNull()
                }

                if (fridgeId == null) {
                    Log.d("Dashboard", "loadFridgeContext: falling back to first fridge membership")
                    try {
                        val memberships = SupabaseClient.client.postgrest.from("fridge_members")
                            .select(columns = Columns.list("fridge_id")) {
                                filter { eq("user_id", user.id) }
                                limit(1)
                            }
                            .decodeList<Map<String, Long>>()
                        fridgeId = memberships.firstOrNull()?.get("fridge_id")
                    } catch (e: Exception) {
                        Log.w("Dashboard", "loadFridgeContext: membership lookup failed: " + e.message)
                        fridgeId = null
                    }
                }

                if (fridgeId == null) {
                    Log.w("Dashboard", "loadFridgeContext: no fridgeId available")
                    _uiState.value = _uiState.value.copy(
                        currentFridgeId = null,
                        currentFridgeName = null
                    )
                    return@launch
                }

                Log.d("Dashboard", "loadFridgeContext: using fridgeId=" + fridgeId)
                val fridgeName: String = try {
                    SupabaseClient.client.postgrest.from("fridge")
                        .select(columns = Columns.list("id", "name")) {
                            filter { eq("id", fridgeId.toString()) }
                            limit(1)
                        }
                        .decodeList<FridgeInfoRow>()
                        .firstOrNull()?.name ?: "Fridge #$fridgeId"
                } catch (e: Exception) {
                    Log.w("Dashboard", "loadFridgeContext: failed to fetch fridge name: " + e.message)
                    "Fridge #$fridgeId"
                }
                Log.d("Dashboard", "loadFridgeContext: fridgeName=" + fridgeName)

                _uiState.value = _uiState.value.copy(
                    currentFridgeId = fridgeId,
                    currentFridgeName = fridgeName
                )
                // After resolving fridge context, refresh item summary
                loadFridgeItemSummary(fridgeId)
            } catch (e: Exception) {
                Log.e("Dashboard", "loadFridgeContext: unexpected error", e)
                _uiState.value = _uiState.value.copy(errorMessage = "Failed to load fridge context")
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

    fun loadTips() {
        viewModelScope.launch {
            try {
                val rows: List<TipRow> = try {
                    SupabaseClient.client.postgrest
                        .from("fridge_tips")
                        .select(columns = Columns.list("title", "image_key", "active", "sort_order"))
                        .decodeList<TipRow>()
                } catch (e: Exception) {
                    Log.w("Dashboard", "loadTips: failed to fetch tips: ${e.message}")
                    emptyList()
                }

                // Filter active tips only, then randomize order and take a subset
                val slides = rows
                    .filter { it.active != false && !it.image_key.isNullOrBlank() }
                    .shuffled()
                    .take(7)
                    .map { TipSlide(title = it.title, imageRef = it.image_key) }

                _uiState.value = _uiState.value.copy(tips = slides)
            } catch (e: Exception) {
                Log.e("Dashboard", "loadTips: unexpected error", e)
                // Don’t surface error; slider is optional
            }
        }
    }

    fun loadFridgeItemSummary(fridgeIdParam: Long? = null) {
        viewModelScope.launch {
            try {
                val fridgeId = fridgeIdParam ?: _uiState.value.currentFridgeId
                if (fridgeId == null) {
                    Log.w("Dashboard", "loadFridgeItemSummary: no fridgeId")
                    return@launch
                }

                // Fetch item fields and compute counts + nearest expiry list client-side
                val rowsFull: List<FridgeItemRow> = try {
                    SupabaseClient.client.postgrest.from("fridge_items")
                        .select(columns = Columns.list("id", "fridge_id", "ingredient_id", "image_path", "is_bookmarked", "expired_date")) {
                            filter { eq("fridge_id", fridgeId.toString()) }
                        }
                        .decodeList<FridgeItemRow>()
                } catch (e: Exception) {
                    Log.w("Dashboard", "loadFridgeItemSummary: fetch failed: ${e.message}")
                    emptyList()
                }
                Log.d("Dashboard", "Summary: fetched ${rowsFull.size} items for fridgeId=$fridgeId")
                rowsFull.take(5).forEachIndexed { idx, r ->
                    Log.d("Dashboard", "Summary sample[$idx]: id=${r.id} ingredient_id=${r.ingredient_id} expired_date=${r.expired_date}")
                }

                val total = rowsFull.size
                val now = java.util.Calendar.getInstance()
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                val soonCount = rowsFull.count { row ->
                    val s = row.expired_date
                    if (s.isNullOrBlank()) return@count false
                    try {
                        val d = sdf.parse(s.substringBefore('T')) ?: return@count false
                        // Calculate days until expiry (positive = future, negative = past)
                        val nowDate = sdf.parse(sdf.format(now.time)) ?: return@count false
                        val days = ((d.time - nowDate.time) / (1000 * 60 * 60 * 24)).toInt()
                        days in 0..7 // Expires today (0) to 7 days from now
                    } catch (_: Exception) { false }
                }

                val expiredCount = rowsFull.count { row ->
                    val s = row.expired_date
                    if (s.isNullOrBlank()) return@count false
                    try {
                        val d = sdf.parse(s.substringBefore('T')) ?: return@count false
                        // Calculate days until expiry (positive = future, negative = past)
                        val nowDate = sdf.parse(sdf.format(now.time)) ?: return@count false
                        val days = ((d.time - nowDate.time) / (1000 * 60 * 60 * 24)).toInt()
                        days < 0 // Already expired
                    } catch (_: Exception) { false }
                }
                Log.d("Dashboard", "Summary: total=$total soon=$soonCount expired=$expiredCount")

                // Build nearest-expiring list (non-expired, closest dates), include name via ingredients
                val ingredientIds = rowsFull.mapNotNull { it.ingredient_id }.distinct()
                val ingredientsById: Map<Long, String?> = if (ingredientIds.isNotEmpty()) {
                    try {
                        SupabaseClient.client.postgrest.from("ingredients")
                            .select(columns = Columns.list("id", "ingredient_name")) {
                                filter { isIn("id", ingredientIds.map { it.toString() }) }
                            }
                            .decodeList<IngredientNameRow>()
                            .associate { it.id to it.ingredient_name }
                    } catch (e: Exception) { emptyMap() }
                } else emptyMap()

                val itemsDisplays: List<FridgeItemDisplay> = rowsFull.mapNotNull { row ->
                    val id = row.id
                    val ingredientId = row.ingredient_id
                    val name = ingredientsById[ingredientId ?: -1] ?: "Unknown"
                    val imagePath = row.image_path
                    val expStr = row.expired_date
                    FridgeItemDisplay(
                        id = id,
                        name = name ?: "Unknown",
                        imagePath = com.example.myfridge.util.ImageUtils.resolveUrl(imagePath),
                        isBookmarked = row.is_bookmarked == true,
                        expiredDate = expStr?.substringBefore('T')
                    )
                }
                Log.d("Dashboard", "Nearest: built ${itemsDisplays.size} display items (names+dates)")
                itemsDisplays.take(5).forEachIndexed { idx, it ->
                    Log.d("Dashboard", "Nearest sample[$idx]: id=${it.id} name=${it.name} expiredDate=${it.expiredDate}")
                }

                val nearest = try {
                    val nowDate = sdf.parse(sdf.format(now.time)) ?: java.util.Date()
                    val parsed = itemsDisplays.mapNotNull { item ->
                        val d = try { item.expiredDate?.let { sdf.parse(it) } } catch (_: Exception) { null }
                        if (d == null) null else Pair(item, d)
                    }
                    // Only show items that haven't expired yet (today or future dates)
                    parsed.filter { !it.second.before(nowDate) }
                        .sortedBy { it.second.time }
                        .take(3)
                        .map { it.first }
                } catch (_: Exception) { emptyList() }
                Log.d("Dashboard", "Nearest: ${nearest.size} items selected for display")
                nearest.forEachIndexed { idx, it -> Log.d("Dashboard", "Nearest[$idx]: id=${it.id} name=${it.name} expiredDate=${it.expiredDate}") }

                _uiState.value = _uiState.value.copy(
                    totalItemsCount = total,
                    expiringSoonCount = soonCount,
                    expiredItemsCount = expiredCount,
                    nearestExpiring = nearest
                )
                Log.d("Dashboard", "UI state updated: total=${_uiState.value.totalItemsCount} soon=${_uiState.value.expiringSoonCount} expired=${_uiState.value.expiredItemsCount} nearest=${_uiState.value.nearestExpiring.size}")
            } catch (e: Exception) {
                Log.e("Dashboard", "loadFridgeItemSummary: unexpected error", e)
            }
        }
    }

}