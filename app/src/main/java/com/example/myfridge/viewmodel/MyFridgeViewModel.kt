package com.example.myfridge.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myfridge.FridgeItemDisplay
import com.example.myfridge.data.SupabaseClient
import android.util.Log
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.example.myfridge.util.ImageUtils

data class MyFridgeUIState(
    val isLoading: Boolean = false,
    val items: List<FridgeItemDisplay> = emptyList(),
    val fridgeName: String? = null,
    val errorMessage: String? = null
)

@kotlinx.serialization.Serializable
data class FridgeItemRow(
    val id: Long,
    val fridge_id: Long?,
    val ingredient_id: Long?,
    val image_path: String?,
    val is_bookmarked: Boolean?,
    val expired_date: String?
)

// Legacy bookmark variants removed; project standardizes on 'is_bookmarked'

@kotlinx.serialization.Serializable
data class IngredientNameRow(
    val id: Long,
    val ingredient_name: String?
)

@kotlinx.serialization.Serializable
data class FridgeInfoRow(
    val id: Long,
    val name: String?
)

class MyFridgeViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(MyFridgeUIState())
    val uiState: StateFlow<MyFridgeUIState> = _uiState.asStateFlow()

    // Sorting state: null means no sorting; true asc, false desc
    private var sortExpiryAsc: Boolean? = null
    // Search state and backing items
    private var allItems: List<FridgeItemDisplay> = emptyList()
    private var searchQuery: String = ""

    private fun resolveUsername(): String {
        return try {
            val user = SupabaseClient.client.auth.currentUserOrNull()
            val usernameMeta = user?.userMetadata?.get("username") as? String
            usernameMeta ?: (user?.email?.substringBefore('@') ?: "User")
        } catch (_: Exception) {
            "User"
        }
    }

    private fun applyFilterAndSort(base: List<FridgeItemDisplay>): List<FridgeItemDisplay> {
        val filtered = if (searchQuery.isBlank()) base else base.filter {
            it.name.contains(searchQuery, ignoreCase = true)
        }
        return sortItemsByExpiry(filtered)
    }

    private fun sortItemsByExpiry(items: List<FridgeItemDisplay>): List<FridgeItemDisplay> {
        val asc = sortExpiryAsc ?: return items
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val comparator = Comparator<FridgeItemDisplay> { a, b ->
            val da = try { a.expiredDate?.let { sdf.parse(it) } } catch (_: Exception) { null }
            val db = try { b.expiredDate?.let { sdf.parse(it) } } catch (_: Exception) { null }
            val va = da ?: Date(Long.MAX_VALUE)
            val vb = db ?: Date(Long.MAX_VALUE)
            va.compareTo(vb)
        }
        return if (asc) items.sortedWith(comparator) else items.sortedWith(comparator.reversed())
    }

    fun setSortByExpiryAscending() {
        sortExpiryAsc = true
        _uiState.value = _uiState.value.copy(items = sortItemsByExpiry(_uiState.value.items))
    }

    fun setSortByExpiryDescending() {
        sortExpiryAsc = false
        _uiState.value = _uiState.value.copy(items = sortItemsByExpiry(_uiState.value.items))
    }

    // Returns new state: true for ascending, false for descending
    fun toggleSortByExpiry(): Boolean {
        sortExpiryAsc = if (sortExpiryAsc != true) true else false
        _uiState.value = _uiState.value.copy(items = applyFilterAndSort(allItems))
        return sortExpiryAsc == true
    }

    fun loadItems() {
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            try {
                Log.d("MyFridgeViewModel", "loadItems: start")
                val user = SupabaseClient.client.auth.currentUserOrNull()
                if (user == null) {
                    Log.w("MyFridgeViewModel", "loadItems: no authenticated user")
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = "Please sign in to view fridge items."
                    )
                    return@launch
                }

                // Resolve user's current fridge: prefer metadata current_fridge_id, fallback to first membership
                val fridgeId: Long? = try {
                    val metaVal = user.userMetadata?.get("current_fridge_id")
                    val metaId = when (metaVal) {
                        is Number -> metaVal.toLong()
                        is String -> metaVal.toLongOrNull()
                        else -> metaVal?.toString()?.toLongOrNull()
                    }
                    metaId ?: SupabaseClient.client.postgrest.from("fridge_members")
                        .select(columns = Columns.list("fridge_id")) {
                            filter { eq("user_id", user.id.toString()) }
                            limit(1)
                        }
                        .decodeList<Map<String, Long>>()
                        .firstOrNull()?.get("fridge_id")
                } catch (e: Exception) { null }

                if (fridgeId == null) {
                    Log.w("MyFridgeViewModel", "loadItems: no fridge membership for user")
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = "No fridge joined yet. Create or join a fridge first."
                    )
                    return@launch
                }
                Log.d("MyFridgeViewModel", "loadItems: using fridgeId=" + fridgeId)

                // Fetch fridge name
                val fridgeName: String = try {
                    SupabaseClient.client.postgrest.from("fridge")
                        .select(columns = Columns.list("id", "name")) {
                            filter { eq("id", fridgeId.toString()) }
                            limit(1)
                        }
                        .decodeList<FridgeInfoRow>()
                        .firstOrNull()?.name ?: "Fridge #$fridgeId"
                } catch (e: Exception) {
                    Log.w("MyFridgeViewModel", "loadItems: failed to fetch fridge name: " + e.message)
                    "Fridge #$fridgeId"
                }
                Log.d("MyFridgeViewModel", "loadItems: fridgeName=" + fridgeName)

                // Fetch items in the fridge using the standardized schema
                val itemRows: List<FridgeItemRow> = queryFridgeItems(fridgeId)
                Log.d("MyFridgeViewModel", "loadItems: fetched raw rows count=" + itemRows.size)

                val ingredientIds = itemRows.mapNotNull { it.ingredient_id }.distinct()
                Log.d("MyFridgeViewModel", "loadItems: distinct ingredientIds count=" + ingredientIds.size)
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

                val displays: List<FridgeItemDisplay> = itemRows.map { row ->
                    val name = ingredientsById[row.ingredient_id ?: -1] ?: "Unknown"
                    FridgeItemDisplay(
                        id = row.id,
                        name = name ?: "Unknown",
                        imagePath = ImageUtils.resolveUrl(row.image_path),
                        isBookmarked = row.is_bookmarked == true,
                        expiredDate = row.expired_date
                    )
                }

                allItems = displays
                val finalDisplays = applyFilterAndSort(displays)
                _uiState.value = _uiState.value.copy(isLoading = false, items = finalDisplays, fridgeName = fridgeName)
                Log.d("MyFridgeViewModel", "loadItems: success with displays count=" + displays.size)

            } catch (e: Exception) {
                Log.e("MyFridgeViewModel", "loadItems: failed: " + e.message, e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "Failed to load items: ${e.message}"
                )
            }
        }
    }

    private suspend fun queryFridgeItems(fridgeId: Long): List<FridgeItemRow> {
        Log.d("MyFridgeViewModel", "queryFridgeItems: fridgeId=" + fridgeId)
        val rows = SupabaseClient.client.postgrest.from("fridge_items")
            .select(columns = Columns.list("id", "fridge_id", "ingredient_id", "image_path", "is_bookmarked", "expired_date")) {
                filter { eq("fridge_id", fridgeId.toString()) }
            }
            .decodeList<FridgeItemRow>()
        Log.d("MyFridgeViewModel", "queryFridgeItems: got rows count=" + rows.size + " with is_bookmarked")
        return rows
    }

    fun toggleBookmark(itemId: Long) {
        viewModelScope.launch {
            try {
                Log.d("MyFridgeViewModel", "toggleBookmark: start for itemId=" + itemId)
                val user = SupabaseClient.client.auth.currentUserOrNull()
                if (user == null) {
                    Log.w("MyFridgeViewModel", "toggleBookmark: no authenticated user")
                    _uiState.value = _uiState.value.copy(errorMessage = "Please sign in to use bookmarks.")
                    return@launch
                }

                // Resolve fridge id: prefer metadata current_fridge_id, fallback to first membership
                val fridgeId: Long? = try {
                    val metaVal = user.userMetadata?.get("current_fridge_id")
                    val metaId = when (metaVal) {
                        is Number -> metaVal.toLong()
                        is String -> metaVal.toLongOrNull()
                        else -> metaVal?.toString()?.toLongOrNull()
                    }
                    metaId ?: SupabaseClient.client.postgrest.from("fridge_members")
                        .select(columns = Columns.list("fridge_id")) {
                            filter { eq("user_id", user.id.toString()) }
                            limit(1)
                        }
                        .decodeList<Map<String, Long>>()
                        .firstOrNull()?.get("fridge_id")
                } catch (e: Exception) { null }

                if (fridgeId == null) {
                    Log.w("MyFridgeViewModel", "toggleBookmark: no fridge membership for user")
                    _uiState.value = _uiState.value.copy(errorMessage = "No fridge joined yet.")
                    return@launch
                }

                // Find current item state
                val currentItem = _uiState.value.items.find { it.id == itemId }
                if (currentItem == null) {
                    Log.w("MyFridgeViewModel", "toggleBookmark: item not found in UI state")
                    _uiState.value = _uiState.value.copy(errorMessage = "Item not found.")
                    return@launch
                }
                val newVal = !currentItem.isBookmarked
                Log.d("MyFridgeViewModel", "toggleBookmark: updating server to bookmark=" + newVal + " for itemId=" + itemId)

                // Update server including audit fields (updated_by expects UUID per schema)
                SupabaseClient.client.postgrest.from("fridge_items")
                    .update(buildJsonObject {
                        put("is_bookmarked", newVal)
                        put("updated_by", user.id.toString())
                        put("updated_source", "user")
                    }) {
                        filter {
                            eq("id", itemId.toString())
                            eq("fridge_id", fridgeId.toString())
                        }
                    }

                // Optimistically update UI state
                _uiState.value = _uiState.value.copy(
                    items = _uiState.value.items.map { if (it.id == itemId) it.copy(isBookmarked = newVal) else it }
                )
                // Keep backing list in sync for search/filtering
                allItems = allItems.map { if (it.id == itemId) it.copy(isBookmarked = newVal) else it }
                Log.d("MyFridgeViewModel", "toggleBookmark: success")

            } catch (e: Exception) {
                Log.e("MyFridgeViewModel", "toggleBookmark: failed: " + e.message, e)
                val msg = "Failed to toggle bookmark: ${e.message}"
                _uiState.value = _uiState.value.copy(errorMessage = msg)
            }
        }
    }

    fun refresh() { loadItems() }

    fun setSearchQuery(query: String) {
        searchQuery = query
        _uiState.value = _uiState.value.copy(items = applyFilterAndSort(allItems))
    }

    fun deleteItem(itemId: Long, onSuccess: (() -> Unit)? = null) {
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            try {
                Log.d("MyFridgeViewModel", "deleteItem: attempting delete for itemId=" + itemId)

                // Ensure user and fridge context
                val user = SupabaseClient.client.auth.currentUserOrNull()
                if (user == null) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = "Please sign in to delete items."
                    )
                    return@launch
                }

                // Resolve user's fridge ID: prefer metadata current_fridge_id, fallback to first membership
                val fridgeId: Long? = try {
                    val metaVal = user.userMetadata?.get("current_fridge_id")
                    val metaId = when (metaVal) {
                        is Number -> metaVal.toLong()
                        is String -> metaVal.toLongOrNull()
                        else -> metaVal?.toString()?.toLongOrNull()
                    }
                    metaId ?: SupabaseClient.client.postgrest.from("fridge_members")
                        .select(columns = Columns.list("fridge_id")) {
                            filter { eq("user_id", user.id.toString()) }
                            limit(1)
                        }
                        .decodeList<Map<String, Long>>()
                        .firstOrNull()?.get("fridge_id")
                } catch (e: Exception) { null }

                if (fridgeId == null) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = "No fridge joined yet. Create or join a fridge first."
                    )
                    return@launch
                }

                // First, check if the item exists in this fridge
                val existingItem = SupabaseClient.client.postgrest.from("fridge_items")
                    .select(columns = Columns.list("id")) {
                        filter {
                            eq("id", itemId.toString())
                            eq("fridge_id", fridgeId.toString())
                        }
                        limit(1)
                    }
                    .decodeList<Map<String, Long>>()

                if (existingItem.isEmpty()) {
                    Log.w("MyFridgeViewModel", "deleteItem: item not found itemId=" + itemId + " fridgeId=" + fridgeId)
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = "Item not found in this fridge"
                    )
                    return@launch
                }

                Log.d("MyFridgeViewModel", "deleteItem: item exists, proceeding itemId=" + itemId + " fridgeId=" + fridgeId)

                // Perform the delete operation scoped to this fridge
                SupabaseClient.client.postgrest.from("fridge_items")
                    .delete {
                        filter {
                            eq("id", itemId.toString())
                            eq("fridge_id", fridgeId.toString())
                        }
                    }

                Log.d("MyFridgeViewModel", "deleteItem: completed for itemId=" + itemId + " fridgeId=" + fridgeId)

                // Verify the item was actually deleted
                val verifyDeleted = SupabaseClient.client.postgrest.from("fridge_items")
                    .select(columns = Columns.list("id")) {
                        filter {
                            eq("id", itemId.toString())
                            eq("fridge_id", fridgeId.toString())
                        }
                        limit(1)
                    }
                    .decodeList<Map<String, Long>>()

                if (verifyDeleted.isNotEmpty()) {
                    println("Warning: Item with ID $itemId still exists in fridge $fridgeId after delete operation")
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = "Delete operation may have failed - item still exists"
                    )
                    return@launch
                }

                println("Delete verified successful for item ID: $itemId (fridge $fridgeId)")
                // Refresh the list after successful deletion
                loadItems()
                onSuccess?.invoke()
            } catch (e: Exception) {
                println("Delete operation failed for item ID: $itemId, error: ${e.message}")
                e.printStackTrace()
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "Failed to delete item: ${e.message}"
                )
            }
        }
    }
}