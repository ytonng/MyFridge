package com.example.myfridge.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myfridge.data.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class AddItemViewModel : ViewModel() {

    // Prefer 'current_fridge_id' from user metadata; fallback to first 'fridge_members' entry
    private suspend fun resolveCurrentFridgeId(): Long? {
        val user = SupabaseClient.client.auth.currentUserOrNull() ?: return null
        return try {
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
        } catch (_: Exception) { null }
    }

    fun resolveUsername(): String {
        return try {
            val user = SupabaseClient.client.auth.currentUserOrNull()
            val usernameMeta = user?.userMetadata?.get("username") as? String
            usernameMeta ?: (user?.email?.substringBefore('@') ?: "User")
        } catch (e: Exception) {
            "User"
        }
    }

    fun loadIngredients(onLoaded: (List<String>) -> Unit) {
        viewModelScope.launch {
            try {
                Log.d("AddItemViewModel", "loadIngredients: start")
                val rows = SupabaseClient.client.postgrest.from("ingredients")
                    .select(columns = Columns.list("id", "ingredient_name", "is_custom")) {
                        order("ingredient_name", Order.ASCENDING)
                    }
                    .decodeList<IngredientRow>()
                Log.d("AddItemViewModel", "loadIngredients: loaded count=" + rows.size)
                onLoaded(rows.mapNotNull { it.ingredient_name })
            } catch (e: Exception) {
                Log.e("AddItemViewModel", "Failed to load ingredients", e)
                onLoaded(emptyList())
            }
        }
    }

    fun addItem(
        itemName: String,
        quantity: Int?,
        quantityUnit: String?,
        expiredDate: String?,
        addOnDate: String?,
        imagePath: String?,
        callback: (Boolean, String?) -> Unit
    ) {
        viewModelScope.launch {
            try {
                Log.d("AddItemViewModel", "addItem: start itemName=" + itemName + ", qty=" + (quantity ?: -1) + ", unit=" + (quantityUnit ?: "<none>") + ", expired=" + (expiredDate ?: "<none>") + ", addOn=" + (addOnDate ?: "<none>") + ", imagePath=" + (imagePath ?: "<none>") )
                val user = SupabaseClient.client.auth.currentUserOrNull()
                if (user == null) {
                    Log.w("AddItemViewModel", "addItem: not authenticated")
                    callback(false, "Not authenticated")
                    return@launch
                }

                // Resolve user's current fridge (metadata first, membership fallback)
                val fridgeId = resolveCurrentFridgeId()

                if (fridgeId == null) {
                    Log.w("AddItemViewModel", "addItem: no fridge joined")
                    callback(false, "No fridge joined")
                    return@launch
                }
                Log.d("AddItemViewModel", "addItem: using fridgeId=" + fridgeId)

                // Resolve ingredient_id: match existing (case-insensitive) or insert new with is_custom = true
                val existingIngredients = try {
                    SupabaseClient.client.postgrest.from("ingredients")
                        .select(columns = Columns.list("id", "ingredient_name")) {
                            order("ingredient_name", Order.ASCENDING)
                        }
                        .decodeList<IngredientRow>()
                } catch (e: Exception) { emptyList() }
                Log.d("AddItemViewModel", "addItem: existing ingredients loaded=" + existingIngredients.size)

                val match = existingIngredients.firstOrNull { it.ingredient_name?.equals(itemName, ignoreCase = true) == true }
                val ingredientId: Long = if (match != null) {
                    Log.d("AddItemViewModel", "addItem: matched existing ingredient id=" + match.id)
                    match.id
                } else {
                    val payload = buildJsonObject {
                        put("ingredient_name", itemName)
                        put("is_custom", true)
                    }
                    val inserted = SupabaseClient.client.postgrest.from("ingredients")
                        .insert(payload) {
                            select() // return inserted row
                        }
                        .decodeList<IngredientRow>()
                    Log.d("AddItemViewModel", "addItem: inserted new ingredient rows=" + inserted.size)
                    inserted.firstOrNull()?.id ?: throw IllegalStateException("Failed to insert new ingredient")
                }

                // Insert into fridge_items
                val row = buildJsonObject {
                    put("fridge_id", fridgeId)
                    put("ingredient_id", ingredientId)
                    if (quantity != null) put("quantity", quantity)
                    if (quantityUnit != null) put("quantity_unit", quantityUnit)
                    if (expiredDate != null) put("expired_date", expiredDate)
                    if (addOnDate != null) put("add_on", addOnDate)
                    if (imagePath != null) put("image_path", imagePath)
                    put("updated_by", user.id.toString())
                    put("updated_source", "user")
                }

                Log.d("AddItemViewModel", "addItem: inserting fridge_items for ingredientId=" + ingredientId + ", fridgeId=" + fridgeId)
                SupabaseClient.client.postgrest.from("fridge_items").insert(row)

                Log.d("AddItemViewModel", "addItem: success")
                callback(true, null)
            } catch (e: Exception) {
                Log.e("AddItemViewModel", "Failed to add item", e)
                callback(false, e.message)
            }
        }
    }

    fun loadItemDetails(itemId: Long, onLoaded: (String?, Int?, String?, String?, String?, String?) -> Unit) {
        viewModelScope.launch {
            try {
                Log.d("AddItemViewModel", "loadItemDetails: start itemId=" + itemId)
                val rows = SupabaseClient.client.postgrest.from("fridge_items")
                    .select(columns = Columns.list("id", "ingredient_id", "quantity", "quantity_unit", "expired_date", "add_on", "image_path")) {
                        filter { eq("id", itemId.toString()) }
                        limit(1)
                    }
                    .decodeList<FridgeItemDetailRow>()
                Log.d("AddItemViewModel", "loadItemDetails: fetched rows=" + rows.size)

                val row = rows.firstOrNull()
                if (row == null) {
                    Log.w("AddItemViewModel", "loadItemDetails: item not found")
                    onLoaded(null, null, null, null, null, null)
                    return@launch
                }

                val name: String? = row.ingredient_id?.let { ingId ->
                    try {
                        SupabaseClient.client.postgrest.from("ingredients")
                            .select(columns = Columns.list("id", "ingredient_name")) {
                                filter { eq("id", ingId.toString()) }
                                limit(1)
                            }
                            .decodeList<IngredientRow>()
                            .firstOrNull()?.ingredient_name
                    } catch (e: Exception) { null }
                }

                onLoaded(
                    name,
                    row.quantity?.toInt(),
                    row.quantity_unit,
                    row.expired_date,
                    row.add_on,
                    row.image_path
                )
                Log.d("AddItemViewModel", "loadItemDetails: success with name=" + (name ?: "<unknown>") )
            } catch (e: Exception) {
                Log.e("AddItemViewModel", "Failed to load item details", e)
                onLoaded(null, null, null, null, null, null)
            }
        }
    }

    fun getItemBookmark(itemId: Long, onLoaded: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                Log.d("AddItemViewModel", "getItemBookmark: start itemId=" + itemId)
                val rows = SupabaseClient.client.postgrest.from("fridge_items")
                    .select(columns = Columns.list("id", "is_bookmarked")) {
                        filter { eq("id", itemId.toString()) }
                        limit(1)
                    }
                    .decodeList<FridgeItemBookmarkRow>()
                val current = rows.firstOrNull()?.is_bookmarked ?: false
                Log.d("AddItemViewModel", "getItemBookmark: current=" + current)
                onLoaded(current)
            } catch (e: Exception) {
                Log.w("AddItemViewModel", "getItemBookmark: failed, defaulting to false", e)
                onLoaded(false)
            }
        }
    }

    fun toggleBookmark(itemId: Long, onResult: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            try {
                Log.d("AddItemViewModel", "toggleBookmark: start itemId=" + itemId)
                val user = SupabaseClient.client.auth.currentUserOrNull()
                if (user == null) {
                    Log.w("AddItemViewModel", "toggleBookmark: not authenticated")
                    onResult(false, "Not authenticated")
                    return@launch
                }

                val fridgeId = resolveCurrentFridgeId()
                if (fridgeId == null) {
                    Log.w("AddItemViewModel", "toggleBookmark: no fridge joined")
                    onResult(false, "No fridge joined")
                    return@launch
                }

                val rows = SupabaseClient.client.postgrest.from("fridge_items")
                    .select(columns = Columns.list("id", "is_bookmarked")) {
                        filter {
                            eq("id", itemId.toString())
                            eq("fridge_id", fridgeId.toString())
                        }
                        limit(1)
                    }
                    .decodeList<FridgeItemBookmarkRow>()

                val current = rows.firstOrNull()?.is_bookmarked ?: false
                val newVal = !current
                Log.d("AddItemViewModel", "toggleBookmark: current=" + current + ", new=" + newVal)

                val payload = buildJsonObject {
                    put("is_bookmarked", newVal)
                    put("updated_by", user.id.toString())
                    put("updated_source", "user")
                }
                SupabaseClient.client.postgrest.from("fridge_items")
                    .update(payload) {
                        filter {
                            eq("id", itemId.toString())
                            eq("fridge_id", fridgeId.toString())
                        }
                    }

                onResult(true, null)
            } catch (e: Exception) {
                Log.e("AddItemViewModel", "toggleBookmark: failed", e)
                onResult(false, e.message)
            }
        }
    }

    fun updateItem(
        itemId: Long,
        itemName: String,
        quantity: Int?,
        quantityUnit: String?,
        expiredDate: String?,
        addOnDate: String?,
        imagePath: String?,
        callback: (Boolean, String?) -> Unit
    ) {
        viewModelScope.launch {
            try {
                Log.d("AddItemViewModel", "updateItem: start itemId=" + itemId + ", itemName=" + itemName)
                val user = SupabaseClient.client.auth.currentUserOrNull()
                if (user == null) {
                    Log.w("AddItemViewModel", "updateItem: not authenticated")
                    callback(false, "Not authenticated")
                    return@launch
                }

                val existingIngredients = try {
                    SupabaseClient.client.postgrest.from("ingredients")
                        .select(columns = Columns.list("id", "ingredient_name")) {
                            order("ingredient_name", Order.ASCENDING)
                        }
                        .decodeList<IngredientRow>()
                } catch (e: Exception) { emptyList() }
                Log.d("AddItemViewModel", "updateItem: existing ingredients loaded=" + existingIngredients.size)

                val match = existingIngredients.firstOrNull { it.ingredient_name?.equals(itemName, ignoreCase = true) == true }
                val ingredientId: Long = if (match != null) {
                    Log.d("AddItemViewModel", "updateItem: matched existing ingredient id=" + match.id)
                    match.id
                } else {
                    val payload = buildJsonObject {
                        put("ingredient_name", itemName)
                        put("is_custom", true)
                    }
                    val inserted = SupabaseClient.client.postgrest.from("ingredients")
                        .insert(payload) {
                            select()
                        }
                        .decodeList<IngredientRow>()
                    Log.d("AddItemViewModel", "updateItem: inserted new ingredient rows=" + inserted.size)
                    inserted.firstOrNull()?.id ?: throw IllegalStateException("Failed to insert new ingredient")
                }

                val updatePayload = buildJsonObject {
                    put("ingredient_id", ingredientId)
                    if (quantity != null) put("quantity", quantity) else put("quantity", null)
                    if (quantityUnit != null) put("quantity_unit", quantityUnit) else put("quantity_unit", null)
                    if (expiredDate != null) put("expired_date", expiredDate) else put("expired_date", null)
                    if (addOnDate != null) put("add_on", addOnDate) else put("add_on", null)
                    if (imagePath != null) put("image_path", imagePath)
                    put("updated_by", user.id.toString())
                    put("updated_source", "user")
                }

                Log.d("AddItemViewModel", "updateItem: updating itemId=" + itemId + " with ingredientId=" + ingredientId)
                SupabaseClient.client.postgrest.from("fridge_items")
                    .update(updatePayload) {
                        filter { eq("id", itemId.toString()) }
                    }

                Log.d("AddItemViewModel", "updateItem: success")
                callback(true, null)
            } catch (e: Exception) {
                Log.e("AddItemViewModel", "Failed to update item", e)
                callback(false, e.message)
            }
        }
    }
}

@Serializable
data class IngredientRow(
    val id: Long,
    val ingredient_name: String?,
    val is_custom: Boolean? = null
)

@Serializable
data class FridgeItemDetailRow(
    val id: Long,
    val ingredient_id: Long?,
    val quantity: Double? = null,
    val quantity_unit: String? = null,
    val expired_date: String? = null,
    val add_on: String? = null,
    val image_path: String? = null
)

@Serializable
data class FridgeItemBookmarkRow(
    val id: Long,
    val is_bookmarked: Boolean? = null
)
