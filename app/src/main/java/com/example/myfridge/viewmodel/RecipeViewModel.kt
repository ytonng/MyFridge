package com.example.myfridge.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.auth.auth
import com.example.myfridge.data.SupabaseClient
import android.util.Log

@Serializable
data class Recipe(
    val id: Long,
    val recipe_name: String,
    val total_time: String?,
    val rating: Double?,
    val url: String?,
    val img_src: String?,
    val tags: List<String>?,
    val steps: List<String>?,
    val display_ingredients: List<String>?,
    val clean_ingredients: List<String>?,
    val isFavorite: Boolean = false,
    // Derived fields: availability counts for list display
    val available_count: Int? = null,
    val total_count: Int? = null
)

@Serializable
data class UserRecipeHistory(
    val id: Long? = null,
    val user_id: String,
    val recipe_id: Long,
    val is_favorite: Boolean? = null
)

@Serializable
data class Ingredient(
    val id: Long,
    val ingredient_name: String?,
    val is_custom: Boolean? = null
)

@Serializable
data class SimilarRecipeRow(
    val recipe_id: Long,
    val score: Double
)

data class IngredientAvailability(
    val name: String,
    val available: Boolean
)

data class RecipeUIState(
    val isLoading: Boolean = false,
    val recommendedRecipes: List<Recipe> = emptyList(),
    val favoriteRecipes: List<Recipe> = emptyList(),
    val filteredRecipes: List<Recipe> = emptyList(),
    val searchQuery: String = "",
    val errorMessage: String? = null
)

class RecipeViewModel : ViewModel() {
    
    private val _uiState = MutableStateFlow(RecipeUIState())
    val uiState: StateFlow<RecipeUIState> = _uiState.asStateFlow()
    
    // Selected recipe details to display
    private val _selectedRecipe = MutableStateFlow<Recipe?>(null)
    val selectedRecipe: StateFlow<Recipe?> = _selectedRecipe.asStateFlow()

    // Ingredient availability for the selected recipe
    private val _ingredientAvailability = MutableStateFlow<List<IngredientAvailability>>(emptyList())
    val ingredientAvailability: StateFlow<List<IngredientAvailability>> = _ingredientAvailability.asStateFlow()
    
    // Legacy LiveData for compatibility with existing fragments
    private val _recommendedRecipes = MutableLiveData<List<Recipe>>()
    val recommendedRecipes: LiveData<List<Recipe>> = _recommendedRecipes
    
    private val _favoriteRecipes = MutableLiveData<List<Recipe>>()
    val favoriteRecipes: LiveData<List<Recipe>> = _favoriteRecipes
    
    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading
    
    private val _ingredients = MutableLiveData<List<Ingredient>>()
    val ingredients: LiveData<List<Ingredient>> = _ingredients

    private val _selectedTags = MutableLiveData<List<String>>(emptyList())
    val selectedTags: LiveData<List<String>> = _selectedTags

    private val _suggestedTags = MutableLiveData<List<String>>(emptyList())
    val suggestedTags: LiveData<List<String>> = _suggestedTags
    
    init {
        loadRecommendedRecipes()
        loadFavoriteRecipes()
        loadIngredients()
    }
    
    private fun loadRecommendedRecipes() {
        // Unify initial load with pull-to-refresh behavior
        refreshCombinedRecommendations(totalCount = 20)
    }
    
    // Load a single recipe by ID and populate selectedRecipe
    fun selectRecipe(recipeId: Long) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isLoading = true)
                val rows = SupabaseClient.client.postgrest.from("recipes")
                    .select(columns = Columns.list(
                        "id",
                        "recipe_name",
                        "total_time",
                        "rating",
                        "url",
                        "img_src",
                        "tags",
                        "steps",
                        "display_ingredients",
                        "clean_ingredients"
                    )) {
                        filter { eq("id", recipeId.toString()) }
                        limit(1)
                    }
                    .decodeList<Recipe>()
                var recipe = rows.firstOrNull()

                // Mark favorite state for selected recipe based on user history
                try {
                    val user = SupabaseClient.client.auth.currentUserOrNull()
                    if (user != null && recipe != null) {
                        val favRows = SupabaseClient.client.postgrest.from("user_recipe_history")
                            .select(columns = Columns.list("recipe_id")) {
                                filter {
                                    eq("user_id", user.id.toString())
                                    eq("recipe_id", recipeId.toString())
                                    eq("is_favorite", true)
                                }
                                limit(1)
                            }
                            .decodeList<Map<String, Long>>()

                        val isFav = favRows.isNotEmpty()
                        if (isFav) {
                            recipe = recipe!!.copy(isFavorite = true)
                        }
                    }
                } catch (e: Exception) {
                    Log.w("RecipeViewModel", "Could not mark selected recipe favorite state", e)
                }

                _selectedRecipe.value = recipe
                // Compute ingredient availability for the selected recipe
                if (recipe != null) {
                    loadIngredientAvailabilityForSelectedRecipe(recipe)
                } else {
                    _ingredientAvailability.value = emptyList()
                }
                _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = null)
                if (recipe != null) {
                    // Optional: track recipe view for history
                    try { trackRecipeView(recipe.id.toString()) } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                Log.e("RecipeViewModel", "Failed to load recipe $recipeId", e)
                _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = "Failed to load recipe: ${e.message}")
            }
        }
    }

    // Update selected recipe's favorite flag in state (used by detail screen)
    fun updateSelectedFavoriteFlag(isFavorite: Boolean) {
        val current = _selectedRecipe.value
        if (current != null) {
            _selectedRecipe.value = current.copy(isFavorite = isFavorite)
        }
    }

    private fun loadIngredientAvailabilityForSelectedRecipe(recipe: Recipe) {
        viewModelScope.launch {
            try {
                val user = SupabaseClient.client.auth.currentUserOrNull()
                var fridgeId: Long? = null
                if (user != null) {
                    try {
                        val fridgeRows = SupabaseClient.client.postgrest.from("fridge_members")
                            .select(columns = Columns.list("fridge_id")) {
                                filter { eq("user_id", user.id.toString()) }
                                limit(1)
                            }
                            .decodeList<Map<String, Long>>()
                        fridgeId = fridgeRows.firstOrNull()?.get("fridge_id")
                    } catch (e: Exception) {
                        Log.w("RecipeViewModel", "Failed to load user's fridge id", e)
                    }
                }

                val recipeIngredientIds: List<Long> = try {
                    SupabaseClient.client.postgrest.from("recipe_ingredients")
                        .select(columns = Columns.list("ingredient_id")) {
                            filter { eq("recipe_id", recipe.id.toString()) }
                        }
                        .decodeList<Map<String, Long>>()
                        .mapNotNull { it["ingredient_id"] }
                } catch (e: Exception) {
                    Log.w("RecipeViewModel", "Failed to load recipe ingredient IDs", e)
                    emptyList()
                }

                val fridgeIngredientIds: Set<Long> = if (fridgeId != null) {
                    try {
                        SupabaseClient.client.postgrest.from("fridge_items")
                            .select(columns = Columns.list("ingredient_id")) {
                                filter { eq("fridge_id", fridgeId.toString()) }
                            }
                            .decodeList<Map<String, Long>>()
                            .mapNotNull { it["ingredient_id"] }
                            .toSet()
                    } catch (e: Exception) {
                        Log.w("RecipeViewModel", "Failed to load fridge ingredient IDs", e)
                        emptySet()
                    }
                } else emptySet()

                val recipeIngredientsById: List<Ingredient> = if (recipeIngredientIds.isNotEmpty()) {
                    try {
                        SupabaseClient.client.postgrest.from("ingredients")
                            .select(columns = Columns.list("id", "ingredient_name")) {
                                filter { isIn("id", recipeIngredientIds.map { it.toString() }) }
                            }
                            .decodeList<Ingredient>()
                    } catch (e: Exception) {
                        Log.w("RecipeViewModel", "Failed to load ingredient names for recipe", e)
                        emptyList()
                    }
                } else emptyList()

                val availability: List<IngredientAvailability> = if (recipeIngredientsById.isNotEmpty()) {
                    recipeIngredientsById.map { ing ->
                        IngredientAvailability(
                            name = ing.ingredient_name ?: "Unknown",
                            available = fridgeIngredientIds.contains(ing.id)
                        )
                    }
                } else {
                    val names = recipe.clean_ingredients ?: emptyList()
                    val fridgeNamesSet: Set<String> = if (fridgeIngredientIds.isNotEmpty()) {
                        try {
                            SupabaseClient.client.postgrest.from("ingredients")
                                .select(columns = Columns.list("id", "ingredient_name")) {
                                    filter { isIn("id", fridgeIngredientIds.map { it.toString() }) }
                                }
                                .decodeList<Ingredient>()
                                .mapNotNull { it.ingredient_name?.lowercase() }
                                .toSet()
                        } catch (e: Exception) {
                            Log.w("RecipeViewModel", "Failed to load fridge ingredient names for fallback", e)
                            emptySet()
                        }
                    } else emptySet()
                    names.map { n ->
                        IngredientAvailability(
                            name = n,
                            available = fridgeNamesSet.contains(n.lowercase())
                        )
                    }
                }

                _ingredientAvailability.value = availability
            } catch (e: Exception) {
                Log.e("RecipeViewModel", "Failed computing ingredient availability", e)
                _ingredientAvailability.value = emptyList()
            }
        }
    }
    
    private fun loadIngredients() {
        viewModelScope.launch {
            try {
                val rows = SupabaseClient.client.postgrest.from("ingredients")
                    .select(columns = Columns.list("id", "ingredient_name", "is_custom")) {
                        order("ingredient_name", Order.ASCENDING)
                    }
                    .decodeList<Ingredient>()
                _ingredients.value = rows
                Log.d("RecipeViewModel", "Loaded ${rows.size} ingredients for tags")
                refreshSuggestedTagsInternal()
            } catch (e: Exception) {
                Log.e("RecipeViewModel", "Failed to load ingredients", e)
            }
        }
    }
    
    fun addTag(tag: String) {
        val current = _selectedTags.value ?: emptyList()
        if (!current.contains(tag)) {
            _selectedTags.value = current + tag
            fetchFilteredRecipesRemote()
        }
    }
    
    fun removeTag(tag: String) {
        val current = _selectedTags.value ?: emptyList()
        if (current.contains(tag)) {
            _selectedTags.value = current - tag
            fetchFilteredRecipesRemote()
        }
    }

    fun refreshSuggestedTags() {
        refreshSuggestedTagsInternal()
    }

    private fun refreshSuggestedTagsInternal() {
        val names = _ingredients.value?.mapNotNull { it.ingredient_name } ?: emptyList()
        val sample = if (names.size <= 3) names else names.shuffled().take(3)
        _suggestedTags.value = sample
    }
    
    private fun applyFilter() {
        val query = _uiState.value.searchQuery
        val tags = _selectedTags.value ?: emptyList()
        viewModelScope.launch {
            val filtered = if (query.isBlank() && tags.isEmpty()) {
                _uiState.value.recommendedRecipes
            } else {
                val terms = (query
                    .split(',', ' ')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() } + tags)
                    .map { it.lowercase() }

                _uiState.value.recommendedRecipes.filter { recipe ->
                    val name = recipe.recipe_name.lowercase()
                    val tagsLocal = (recipe.tags ?: emptyList()).map { it.lowercase() }
                    val ingredientsLocal = (recipe.clean_ingredients ?: emptyList()).map { it.lowercase() }
                    terms.all { t ->
                        name.contains(t) ||
                        tagsLocal.any { it.contains(t) } ||
                        ingredientsLocal.any { it.contains(t) }
                    }
                }
            }
            _uiState.value = _uiState.value.copy(filteredRecipes = filtered)
            _recommendedRecipes.value = filtered
        }
    }

    private fun fetchFilteredRecipesRemote() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            _isLoading.value = true
            try {
                val q = _uiState.value.searchQuery
                val selectedNames = _selectedTags.value ?: emptyList()

                // Map selected ingredient names to IDs using cached ingredients
                val nameToId = _ingredients.value
                    ?.filter { it.ingredient_name != null }
                    ?.associate { it.ingredient_name!!.lowercase() to it.id }
                    ?: emptyMap()
                val selectedIds = selectedNames.mapNotNull { nameToId[it.lowercase()] }

                // If tags selected, fetch recipe IDs from recipe_ingredients (OR semantics)
                var recipeIdsFilter: List<Long>? = null
                if (selectedIds.isNotEmpty()) {
                    val recipeIdRows = SupabaseClient.client.postgrest.from("recipe_ingredients")
                        .select(columns = Columns.list("recipe_id")) {
                            filter {
                                isIn("ingredient_id", selectedIds.map { it.toString() })
                            }
                        }
                        .decodeList<Map<String, Long>>()
                    val ids = recipeIdRows.mapNotNull { it["recipe_id"] }
                    recipeIdsFilter = ids.distinct()
                }

                // Build recipes query with optional filters
                val recipes = SupabaseClient.client.postgrest.from("recipes")
                    .select() {
                        if (q.isNotBlank()) {
                            filter {
                                ilike("recipe_name", "%${q.replace("%", "\\%").replace("_", "\\_")}%")
                            }
                        }
                        if (recipeIdsFilter != null) {
                            filter {
                                if (recipeIdsFilter!!.isNotEmpty()) {
                                    isIn("id", recipeIdsFilter!!.map { it.toString() })
                                } else {
                                    // No matches for selected tags: force empty by filtering impossible ID
                                    eq("id", "-1")
                                }
                            }
                        }
                        order("rating", Order.DESCENDING)
                        limit(20)
                    }
                    .decodeList<Recipe>()

                // Mark favorites for current user
                val user = SupabaseClient.client.auth.currentUserOrNull()
                val recipesWithFavorites = if (user != null) {
                    try {
                        val favoriteRecipeIds = SupabaseClient.client.postgrest.from("user_recipe_history")
                            .select(columns = Columns.list("recipe_id")) {
                                filter {
                                    eq("user_id", user.id.toString())
                                    eq("is_favorite", true)
                                }
                            }
                            .decodeList<Map<String, Long>>()
                            .map { it["recipe_id"]!! }
                            .toSet()
                        // Load user's fridge ingredient names once
                        val fridgeId = try {
                            SupabaseClient.client.postgrest.from("fridge_members")
                                .select(columns = Columns.list("fridge_id")) {
                                    filter { eq("user_id", user.id.toString()) }
                                    limit(1)
                                }
                                .decodeList<Map<String, Long>>()
                                .firstOrNull()?.get("fridge_id")
                        } catch (e: Exception) {
                            Log.w("RecipeViewModel", "Failed to load user's fridge id for filtered list", e)
                            null
                        }
                        val fridgeIngredientIds: Set<Long> = if (fridgeId != null) {
                            try {
                                SupabaseClient.client.postgrest.from("fridge_items")
                                    .select(columns = Columns.list("ingredient_id")) {
                                        filter { eq("fridge_id", fridgeId.toString()) }
                                    }
                                    .decodeList<Map<String, Long>>()
                                    .mapNotNull { it["ingredient_id"] }
                                    .toSet()
                            } catch (e: Exception) {
                                Log.w("RecipeViewModel", "Failed to load fridge ingredient IDs for filtered list", e)
                                emptySet()
                            }
                        } else emptySet()
                        val fridgeNamesSet: Set<String> = if (fridgeIngredientIds.isNotEmpty()) {
                            try {
                                SupabaseClient.client.postgrest.from("ingredients")
                                    .select(columns = Columns.list("id", "ingredient_name")) {
                                        filter { isIn("id", fridgeIngredientIds.map { it.toString() }) }
                                    }
                                    .decodeList<Ingredient>()
                                    .mapNotNull { it.ingredient_name?.lowercase() }
                                    .toSet()
                            } catch (e: Exception) {
                                Log.w("RecipeViewModel", "Failed to load fridge ingredient names for filtered list", e)
                                emptySet()
                            }
                        } else emptySet()

                        recipes.map { recipe ->
                            val names = recipe.clean_ingredients ?: emptyList()
                            val total = names.size
                            val available = if (total > 0 && fridgeNamesSet.isNotEmpty()) {
                                names.count { n -> fridgeNamesSet.contains(n.lowercase()) }
                            } else 0
                            recipe.copy(
                                isFavorite = recipe.id in favoriteRecipeIds,
                                available_count = available,
                                total_count = total
                            )
                        }
                    } catch (e: Exception) {
                        Log.w("RecipeViewModel", "Could not load favorites for filtered list", e)
                        recipes
                    }
                } else {
                    // No user - still compute totals for display
                    recipes.map { recipe ->
                        val names = recipe.clean_ingredients ?: emptyList()
                        recipe.copy(available_count = 0, total_count = names.size)
                    }
                }

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    recommendedRecipes = recipesWithFavorites,
                    filteredRecipes = recipesWithFavorites,
                    errorMessage = null
                )
                _recommendedRecipes.value = recipesWithFavorites
                _isLoading.value = false
                Log.d("RecipeViewModel", "Fetched ${recipesWithFavorites.size} filtered recipes from DB")

            } catch (e: Exception) {
                Log.e("RecipeViewModel", "Error fetching filtered recipes", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "Failed to fetch filtered recipes: ${e.message}"
                )
                _isLoading.value = false
            }
        }
    }
    
    private fun loadFavoriteRecipes() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // Get current user
                val user = SupabaseClient.client.auth.currentUserOrNull()
                if (user == null) {
                    Log.d("RecipeViewModel", "No authenticated user, skipping favorites load")
                    _uiState.value = _uiState.value.copy(favoriteRecipes = emptyList())
                    _favoriteRecipes.value = emptyList()
                    _isLoading.value = false
                    return@launch
                }
                
                // Query user_recipe_history table to get favorite recipe IDs
                val favoriteRecipeIds = SupabaseClient.client.postgrest.from("user_recipe_history")
                            .select(columns = Columns.list("recipe_id")) {
                                filter {
                                    eq("user_id", user.id.toString())
                                    eq("is_favorite", true)
                                }
                            }
                            .decodeList<Map<String, Long>>()
                    .map { it["recipe_id"]!! }
                
                if (favoriteRecipeIds.isEmpty()) {
                    _uiState.value = _uiState.value.copy(favoriteRecipes = emptyList())
                    _favoriteRecipes.value = emptyList()
                    Log.d("RecipeViewModel", "No favorite recipes found")
                    _isLoading.value = false
                    return@launch
                }
                
                // Get favorite recipes from recipes table
                val favoriteRecipesRaw = SupabaseClient.client.postgrest.from("recipes")
                    .select() {
                        filter {
                            isIn("id", favoriteRecipeIds.map { it.toString() })
                        }
                    }
                    .decodeList<Recipe>()
                    .map { it.copy(isFavorite = true) }

                // Compute availability counts for favorites
                val withCounts = try {
                    // Fetch user's fridge ID
                    val fridgeRows = SupabaseClient.client.postgrest.from("fridge_members")
                        .select(columns = Columns.list("fridge_id")) {
                            filter { eq("user_id", user.id.toString()) }
                            limit(1)
                        }
                        .decodeList<Map<String, Long>>()
                    val fridgeId = fridgeRows.firstOrNull()?.get("fridge_id")

                    if (fridgeId != null) {
                        // Fetch ingredient IDs in the fridge
                        val fridgeIngredientRows = SupabaseClient.client.postgrest.from("fridge_items")
                            .select(columns = Columns.list("ingredient_id")) {
                                filter { eq("fridge_id", fridgeId.toString()) }
                            }
                            .decodeList<Map<String, Long>>()
                        val fridgeIngredientIds = fridgeIngredientRows.mapNotNull { it["ingredient_id"] }

                        // Resolve ingredient names for matching
                        val ingredientRows = if (fridgeIngredientIds.isNotEmpty()) {
                            SupabaseClient.client.postgrest.from("ingredients")
                                .select(columns = Columns.list("ingredient_name")) {
                                    filter { isIn("id", fridgeIngredientIds.map { it.toString() }) }
                                }
                                .decodeList<Ingredient>()
                        } else emptyList()

                        val fridgeNamesSet = ingredientRows.mapNotNull { it.ingredient_name?.lowercase() }
                            .toSet()

                        favoriteRecipesRaw.map { recipe ->
                            val names = recipe.clean_ingredients ?: recipe.display_ingredients ?: emptyList()
                            val total = names.size
                            val available = if (fridgeNamesSet.isNotEmpty()) {
                                names.count { n -> fridgeNamesSet.contains(n.lowercase()) }
                            } else 0
                            recipe.copy(available_count = available, total_count = total)
                        }
                    } else {
                        // No fridge - compute totals only
                        favoriteRecipesRaw.map { recipe ->
                            val names = recipe.clean_ingredients ?: recipe.display_ingredients ?: emptyList()
                            recipe.copy(available_count = 0, total_count = names.size)
                        }
                    }
                } catch (e: Exception) {
                    Log.w("RecipeViewModel", "Could not compute availability for favorites", e)
                    // Fallback: totals only
                    favoriteRecipesRaw.map { recipe ->
                        val names = recipe.clean_ingredients ?: recipe.display_ingredients ?: emptyList()
                        recipe.copy(available_count = 0, total_count = names.size)
                    }
                }

                _uiState.value = _uiState.value.copy(favoriteRecipes = withCounts)
                _favoriteRecipes.value = withCounts

                Log.d("RecipeViewModel", "Loaded ${withCounts.size} favorite recipes")
                
            } catch (e: Exception) {
                Log.e("RecipeViewModel", "Error loading favorites", e)
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Failed to load favorites: ${e.message}"
                )
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    fun toggleFavorite(recipeId: String) {
        viewModelScope.launch {
            try {
                val recipeIdLong = recipeId.toLong()
                val user = SupabaseClient.client.auth.currentUserOrNull()
                
                if (user == null) {
                    Log.w("RecipeViewModel", "No authenticated user, cannot toggle favorite")
                    _uiState.value = _uiState.value.copy(
                        errorMessage = "Please log in to save favorites"
                    )
                    return@launch
                }
                
                val currentRecipe = _uiState.value.recommendedRecipes.find { it.id == recipeIdLong }
                val isCurrentlyFavorite = currentRecipe?.isFavorite ?: false
                
                if (isCurrentlyFavorite) {
                    // Remove from favorites by setting is_favorite to false
                    SupabaseClient.client.postgrest.from("user_recipe_history")
                        .update(UserRecipeHistory(
                            user_id = user.id.toString(),
                            recipe_id = recipeIdLong,
                            is_favorite = false
                        )) {
                            filter {
                                eq("user_id", user.id.toString())
                                eq("recipe_id", recipeIdLong.toString())
                            }
                        }
                    
                    Log.d("RecipeViewModel", "Removed recipe $recipeId from favorites")
                } else {
                    // Add to favorites - use upsert to handle both new records and updates
                    SupabaseClient.client.postgrest.from("user_recipe_history")
                        .upsert(UserRecipeHistory(
                            user_id = user.id.toString(),
                            recipe_id = recipeIdLong,
                            is_favorite = true
                        )) {
                            onConflict = "user_id,recipe_id"
                        }
                    
                    Log.d("RecipeViewModel", "Added recipe $recipeId to favorites")
                }
                
                // Update local state
                val updatedRecommended = _uiState.value.recommendedRecipes.map { recipe ->
                    if (recipe.id == recipeIdLong) {
                        recipe.copy(isFavorite = !isCurrentlyFavorite)
                    } else {
                        recipe
                    }
                }
                
                // Update filtered recipes if search is active
                val updatedFiltered = _uiState.value.filteredRecipes.map { recipe ->
                    if (recipe.id == recipeIdLong) {
                        recipe.copy(isFavorite = !isCurrentlyFavorite)
                    } else {
                        recipe
                    }
                }
                
                // Update favorite recipes
                val updatedFavorites = updatedRecommended.filter { it.isFavorite }
                
                _uiState.value = _uiState.value.copy(
                    recommendedRecipes = updatedRecommended,
                    filteredRecipes = updatedFiltered,
                    favoriteRecipes = updatedFavorites
                )
                
                // Update LiveData for compatibility
                _recommendedRecipes.value = updatedRecommended
                _favoriteRecipes.value = updatedFavorites
                
                Log.d("RecipeViewModel", "Successfully toggled favorite for recipe $recipeId")
                
            } catch (e: Exception) {
                Log.e("RecipeViewModel", "Error toggling favorite", e)
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Failed to update favorite: ${e.message}"
                )
            }
        }
    }
    
    fun filterRecommendedRecipes(query: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(searchQuery = query)
            fetchFilteredRecipesRemote()
            Log.d("RecipeViewModel", "Updated search query: '$query' and fetched filtered recipes from DB")
        }
    }
    
    fun refreshRecipes() {
        loadRecommendedRecipes()
        loadFavoriteRecipes()
    }

    fun refreshFavorites() {
        loadFavoriteRecipes()
    }
    
    fun onRecommendedTabSelected(totalCount: Int = 20) {
        refreshCombinedRecommendations(totalCount)
    }

    private fun refreshCombinedRecommendations(totalCount: Int = 20) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            _isLoading.value = true
            try {
                val half = kotlin.math.max(1, totalCount / 2)

                // Fetch a pool of recipes to pick from
                val pool = SupabaseClient.client.postgrest.from("recipes")
                    .select() {
                        order("rating", Order.DESCENDING)
                        limit(100)
                    }
                    .decodeList<Recipe>()

                if (pool.isEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        recommendedRecipes = emptyList(),
                        filteredRecipes = emptyList()
                    )
                    _recommendedRecipes.value = emptyList()
                    _isLoading.value = false
                    return@launch
                }

                // Choose an anchor recipe for content-based similarity
                val user = SupabaseClient.client.auth.currentUserOrNull()
                var anchor: Recipe? = null
                if (user != null) {
                    try {
                        val favoriteRecipeIds = SupabaseClient.client.postgrest.from("user_recipe_history")
                            .select(columns = Columns.list("recipe_id")) {
                                filter {
                                    eq("user_id", user.id.toString())
                                    eq("is_favorite", true)
                                }
                            }
                            .decodeList<Map<String, Long>>()
                            .map { it["recipe_id"]!! }
                            .toSet()
                        anchor = pool.firstOrNull { it.id in favoriteRecipeIds }
                    } catch (e: Exception) {
                        Log.w("RecipeViewModel", "Could not load favorites for anchor selection", e)
                    }
                }
                if (anchor == null) {
                    anchor = _uiState.value.recommendedRecipes.firstOrNull()
                }
                if (anchor == null) {
                    anchor = pool.shuffled().first()
                }

                // Server-side similarity via RPC similar_recipes(anchor_recipe_id, limit_count)
                val similarRows = try {
                    val params = buildJsonObject {
                        put("anchor_recipe_id", anchor!!.id)
                        put("limit_count", half)
                    }
                    SupabaseClient.client.postgrest.rpc(
                        function = "similar_recipes",
                        parameters = params
                    ).decodeList<SimilarRecipeRow>()
                } catch (e: Exception) {
                    Log.w("RecipeViewModel", "RPC similar_recipes failed, falling back to empty similar list", e)
                    emptyList()
                }

                val similarIds = similarRows.map { it.recipe_id }.toSet()
                val scoredSimilar = if (similarIds.isNotEmpty()) {
                    try {
                        SupabaseClient.client.postgrest.from("recipes")
                            .select() {
                                filter { isIn("id", similarIds.map { it.toString() }) }
                            }
                            .decodeList<Recipe>()
                    } catch (e: Exception) {
                        Log.w("RecipeViewModel", "Failed to fetch similar recipes by IDs", e)
                        emptyList()
                    }
                } else emptyList()

                // Random half, excluding already selected similar ones
                val randomHalf = pool
                    .shuffled()
                    .filter { it.id != anchor!!.id && it.id !in similarIds }
                    .take(half)

                // Combine and limit
                val combined = (randomHalf + scoredSimilar)
                    .distinctBy { it.id }
                    .let { list -> if (list.size >= totalCount) list.take(totalCount) else list }

                // Mark favorites and compute ingredient availability counts for current user
                val withCounts = if (user != null) {
                    try {
                        // Favorite IDs
                        val favoriteRecipeIds = SupabaseClient.client.postgrest.from("user_recipe_history")
                            .select(columns = Columns.list("recipe_id")) {
                                filter {
                                    eq("user_id", user.id.toString())
                                    eq("is_favorite", true)
                                }
                            }
                            .decodeList<Map<String, Long>>()
                            .map { it["recipe_id"]!! }
                            .toSet()

                        // User fridge -> ingredient IDs -> names
                        val fridgeId = try {
                            SupabaseClient.client.postgrest.from("fridge_members")
                                .select(columns = Columns.list("fridge_id")) {
                                    filter { eq("user_id", user.id.toString()) }
                                    limit(1)
                                }
                                .decodeList<Map<String, Long>>()
                                .firstOrNull()?.get("fridge_id")
                        } catch (e: Exception) {
                            Log.w("RecipeViewModel", "Failed to load user's fridge id for combined list", e)
                            null
                        }
                        val fridgeIngredientIds: Set<Long> = if (fridgeId != null) {
                            try {
                                SupabaseClient.client.postgrest.from("fridge_items")
                                    .select(columns = Columns.list("ingredient_id")) {
                                        filter { eq("fridge_id", fridgeId.toString()) }
                                    }
                                    .decodeList<Map<String, Long>>()
                                    .mapNotNull { it["ingredient_id"] }
                                    .toSet()
                            } catch (e: Exception) {
                                Log.w("RecipeViewModel", "Failed to load fridge ingredient IDs for combined list", e)
                                emptySet()
                            }
                        } else emptySet()
                        val fridgeNamesSet: Set<String> = if (fridgeIngredientIds.isNotEmpty()) {
                            try {
                                SupabaseClient.client.postgrest.from("ingredients")
                                    .select(columns = Columns.list("id", "ingredient_name")) {
                                        filter { isIn("id", fridgeIngredientIds.map { it.toString() }) }
                                    }
                                    .decodeList<Ingredient>()
                                    .mapNotNull { it.ingredient_name?.lowercase() }
                                    .toSet()
                            } catch (e: Exception) {
                                Log.w("RecipeViewModel", "Failed to load fridge ingredient names for combined list", e)
                                emptySet()
                            }
                        } else emptySet()

                        combined.map { recipe ->
                            val names = recipe.clean_ingredients ?: emptyList()
                            val total = names.size
                            val available = if (total > 0 && fridgeNamesSet.isNotEmpty()) {
                                names.count { n -> fridgeNamesSet.contains(n.lowercase()) }
                            } else 0
                            recipe.copy(
                                isFavorite = recipe.id in favoriteRecipeIds,
                                available_count = available,
                                total_count = total
                            )
                        }
                    } catch (e: Exception) {
                        Log.w("RecipeViewModel", "Could not mark favorites/availability for combined list", e)
                        // Still compute totals for display even if favorites/availability failed
                        combined.map { recipe ->
                            val names = recipe.clean_ingredients ?: emptyList()
                            recipe.copy(available_count = 0, total_count = names.size)
                        }
                    }
                } else {
                    // No user - compute totals only
                    combined.map { recipe ->
                        val names = recipe.clean_ingredients ?: emptyList()
                        recipe.copy(available_count = 0, total_count = names.size)
                    }
                }

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    recommendedRecipes = withCounts,
                    filteredRecipes = withCounts,
                    errorMessage = null
                )
                _recommendedRecipes.value = withCounts
                _isLoading.value = false
                Log.d("RecipeViewModel", "Refreshed combined recommendations: random=${randomHalf.size}, similar=${scoredSimilar.size}")
            } catch (e: Exception) {
                Log.e("RecipeViewModel", "Error refreshing combined recommendations", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "Failed to refresh recommendations: ${e.message}"
                )
                _isLoading.value = false
            }
        }
    }
    
    fun trackRecipeView(recipeId: String) {
        viewModelScope.launch {
            try {
                val user = SupabaseClient.client.auth.currentUserOrNull()
                if (user == null) {
                    Log.d("RecipeViewModel", "No authenticated user, skipping recipe view tracking")
                    return@launch
                }
                
                val recipeIdLong = recipeId.toLong()
                
                // Track recipe view - use upsert to update user_recipe_history
                SupabaseClient.client.postgrest.from("user_recipe_history")
                    .upsert(UserRecipeHistory(
                        user_id = user.id.toString(),
                        recipe_id = recipeIdLong
                        // Note: is_favorite will remain whatever it was, or default to false
                    )) {
                        onConflict = "user_id,recipe_id"
                    }
                
                Log.d("RecipeViewModel", "Tracked view for recipe $recipeId")
                
            } catch (e: Exception) {
                Log.e("RecipeViewModel", "Error tracking recipe view", e)
            }
        }
    }
}