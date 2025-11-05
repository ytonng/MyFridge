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
    
    // Pagination size for remote search queries
    private var searchLimit: Int = 20
    // Pagination size for favorites remote search
    private var favoriteSearchLimit: Int = 20
    
    init {
        loadRecommendedRecipes()
        loadFavoriteRecipes()
        loadIngredients()
    }
    
    // Resolve current fridge by preferring user metadata 'current_fridge_id' and
    // falling back to the first membership in 'fridge_members'.
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
                val fridgeId: Long? = if (user != null) resolveCurrentFridgeId() else null

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
            // Remote comprehensive search with AND semantics and pagination
            fetchSearchRecipesRemote(resetLimit = false)
        }
    }

    fun removeTag(tag: String) {
        val current = _selectedTags.value ?: emptyList()
        if (current.contains(tag)) {
            _selectedTags.value = current - tag
            // Remote comprehensive search with AND semantics and pagination
            fetchSearchRecipesRemote(resetLimit = false)
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
                val fridgeId = resolveCurrentFridgeId()
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
                Log.d("RecipeViewModel", "Fetched ${favoriteRecipeIds.size} favorite recipe IDs: ${favoriteRecipeIds}")
                
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
                if (favoriteRecipesRaw.isNotEmpty()) {
                    val sample = favoriteRecipesRaw.take(10).joinToString(
                        separator = ", "
                    ) { "${it.id}:${it.recipe_name}" }
                    Log.d("RecipeViewModel", "Favorite recipes fetched: ${sample}")
                } else {
                    Log.d("RecipeViewModel", "Favorite recipes fetched: none")
                }

                // Compute availability counts for favorites
                val withCounts = try {
                    // Resolve user's fridge ID via metadata with fallback
                    val fridgeId = resolveCurrentFridgeId()

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
                                .select(columns = Columns.list("id", "ingredient_name")) {
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
                val withCountsSample = withCounts.take(10).joinToString(
                    separator = ", "
                ) { "${it.id}:${it.recipe_name}(avail=${it.available_count ?: 0}/${it.total_count ?: 0})" }
                Log.d(
                    "RecipeViewModel",
                    "Loaded ${withCounts.size} favorite recipes with availability: ${withCountsSample}"
                )
                
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
                
                // Determine favorite state robustly: check favorites list first, then recommended
                val currentRecipeRecommended = _uiState.value.recommendedRecipes.find { it.id == recipeIdLong }
                val isFavInFavorites = _uiState.value.favoriteRecipes.any { it.id == recipeIdLong }
                val isCurrentlyFavorite = isFavInFavorites || (currentRecipeRecommended?.isFavorite ?: false)
                
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
                
                // Update favorite recipes list immediately for better UX in FavoriteFragment
                val updatedFavorites = if (isCurrentlyFavorite) {
                    // Removing from favorites: drop from current favorites list
                    _uiState.value.favoriteRecipes.filterNot { it.id == recipeIdLong }
                } else {
                    // Adding to favorites: include the recipe if we have it locally; otherwise keep list and refresh later
                    val toAdd = currentRecipeRecommended
                    if (toAdd != null) {
                        (_uiState.value.favoriteRecipes + toAdd.copy(isFavorite = true))
                            .distinctBy { it.id }
                    } else {
                        _uiState.value.favoriteRecipes
                    }
                }
                
                _uiState.value = _uiState.value.copy(
                    recommendedRecipes = updatedRecommended,
                    filteredRecipes = updatedFiltered,
                    favoriteRecipes = updatedFavorites
                )
                
                // Update LiveData for compatibility
                _recommendedRecipes.value = updatedRecommended
                _favoriteRecipes.value = updatedFavorites
                
                Log.d("RecipeViewModel", "Successfully toggled favorite for recipe $recipeId (wasFavorite=$isCurrentlyFavorite)")

                // Ensure server truth by refreshing favorites in background
                try { refreshFavorites() } catch (_: Exception) {}
                
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
            // Reset pagination for new query and fetch remotely
            searchLimit = 20
            fetchSearchRecipesRemote(resetLimit = true)
            Log.d("RecipeViewModel", "Updated search query: '$query' and fetched remote search results (AND semantics)")
        }
    }

    fun loadMoreSearch() {
        // Increase pagination and refetch using the current query and tags
        searchLimit += 20
        fetchSearchRecipesRemote(resetLimit = false)
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

    // --- Favorites remote search with AND token semantics and pagination ---
    fun filterFavoriteRecipes(query: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(searchQuery = query)
            if (query.isBlank()) {
                // Reset to normal favorites when query cleared
                favoriteSearchLimit = 20
                loadFavoriteRecipes()
            } else {
                favoriteSearchLimit = 20
                fetchFavoriteSearchRecipesRemote(resetLimit = true)
            }
        }
    }

    fun loadMoreFavoriteSearch() {
        favoriteSearchLimit += 20
        fetchFavoriteSearchRecipesRemote(resetLimit = false)
    }

    private fun fetchFavoriteSearchRecipesRemote(resetLimit: Boolean) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            _isLoading.value = true
            try {
                val rawQuery = _uiState.value.searchQuery
                val tokens = rawQuery
                    .split(',', ' ')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .map { it.lowercase() }

                // Resolve current user's favorite recipe IDs
                val user = SupabaseClient.client.auth.currentUserOrNull()
                val favoriteIds: Set<Long> = if (user != null) {
                    try {
                        SupabaseClient.client.postgrest.from("user_recipe_history")
                            .select(columns = Columns.list("recipe_id")) {
                                filter {
                                    eq("user_id", user.id.toString())
                                    eq("is_favorite", true)
                                }
                            }
                            .decodeList<Map<String, Long>>()
                            .map { it["recipe_id"]!! }
                            .toSet()
                    } catch (e: Exception) {
                        Log.w("RecipeViewModel", "Failed to load favorite IDs for search", e)
                        emptySet()
                    }
                } else emptySet()

                // If no favorites, short-circuit
                if (favoriteIds.isEmpty()) {
                    _uiState.value = _uiState.value.copy(isLoading = false, favoriteRecipes = emptyList())
                    _favoriteRecipes.value = emptyList()
                    _isLoading.value = false
                    return@launch
                }

                // Fetch candidate favorite recipes from recipes table, ordered by rating, paginated
                val candidate = SupabaseClient.client.postgrest.from("recipes")
                    .select() {
                        filter { isIn("id", favoriteIds.map { it.toString() }) }
                        order("rating", Order.DESCENDING)
                        limit(favoriteSearchLimit.toLong())
                    }
                    .decodeList<Recipe>()
                    .map { it.copy(isFavorite = true) }

                // Apply local AND token filtering across name/tags/ingredients
                val filtered = if (tokens.isEmpty()) candidate else candidate.filter { recipe ->
                    val name = recipe.recipe_name.lowercase()
                    val tagsLocal = (recipe.tags ?: emptyList()).map { it.lowercase() }
                    val ingredientsLocal = (recipe.clean_ingredients ?: emptyList()).map { it.lowercase() }
                    tokens.all { t ->
                        name.contains(t) ||
                        tagsLocal.any { it.contains(t) } ||
                        ingredientsLocal.any { it.contains(t) }
                    }
                }

                // Compute availability counts using fridge ingredient names (name-based)
                val withCounts = try {
                    val fridgeId = resolveCurrentFridgeId()
                    val fridgeNamesSet = if (fridgeId != null) {
                        try {
                            SupabaseClient.client.postgrest.from("fridge_items")
                                .select(columns = Columns.list("ingredient_name")) {
                                    filter { eq("fridge_id", fridgeId.toString()) }
                                }
                                .decodeList<Map<String, String>>()
                                .mapNotNull { it["ingredient_name"]?.lowercase() }
                                .toSet()
                        } catch (e: Exception) {
                            Log.w("RecipeViewModel", "Failed to load fridge ingredient names for favorites search", e)
                            emptySet()
                        }
                    } else emptySet()

                    filtered.map { recipe ->
                        val names = recipe.clean_ingredients ?: emptyList()
                        val total = names.size
                        val available = if (total > 0 && fridgeNamesSet.isNotEmpty()) {
                            names.count { n -> fridgeNamesSet.contains(n.lowercase()) }
                        } else 0
                        recipe.copy(available_count = available, total_count = total)
                    }
                } catch (e: Exception) {
                    Log.w("RecipeViewModel", "Could not compute availability for favorites search", e)
                    filtered.map { recipe ->
                        val names = recipe.clean_ingredients ?: emptyList()
                        recipe.copy(available_count = 0, total_count = names.size)
                    }
                }

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    favoriteRecipes = withCounts,
                    errorMessage = null
                )
                _favoriteRecipes.value = withCounts
                _isLoading.value = false
                Log.d("RecipeViewModel", "Fetched ${withCounts.size} favorite search results (limit=$favoriteSearchLimit)")
            } catch (e: Exception) {
                Log.e("RecipeViewModel", "Error fetching favorite search recipes", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "Failed to fetch favorite search recipes: ${e.message}"
                )
                _isLoading.value = false
            }
        }
    }

    private fun refreshCombinedRecommendations(totalCount: Int = 20) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            _isLoading.value = true
            try {
                val half = kotlin.math.max(1, totalCount / 2)

                // Get user's current fridge ingredients first
                val user = SupabaseClient.client.auth.currentUserOrNull()
                Log.d("RecipeViewModel", "User authenticated: ${user != null}")
                
                var fridgeIngredientIds: List<Long> = emptyList()
                val fridgeIngredientNames: Set<String> = if (user != null) {
                    try {
                        val fridgeId = resolveCurrentFridgeId()
                        
                Log.d("RecipeViewModel", "Found fridge ID: $fridgeId")

                        if (fridgeId != null) {
                            val ids = SupabaseClient.client.postgrest.from("fridge_items")
                                .select(columns = Columns.list("ingredient_id")) {
                                    filter { eq("fridge_id", fridgeId.toString()) }
                                }
                                .decodeList<Map<String, Long>>()
                                .mapNotNull { it["ingredient_id"] }
                            fridgeIngredientIds = ids
                            
                            Log.d("RecipeViewModel", "Found ${fridgeIngredientIds.size} fridge ingredient IDs: $fridgeIngredientIds")

                            if (fridgeIngredientIds.isNotEmpty()) {
                                val ingredientNames = SupabaseClient.client.postgrest.from("ingredients")
                                    .select(columns = Columns.list("id", "ingredient_name")) {
                                        filter { isIn("id", fridgeIngredientIds.map { it.toString() }) }
                                    }
                                    .decodeList<Ingredient>()
                                    .mapNotNull { it.ingredient_name?.lowercase() }
                                    .toSet()
                                
                                Log.d("RecipeViewModel", "Fridge ingredient names: $ingredientNames")
                                Log.d("RecipeViewModel", "Sample fridge ingredients: ${ingredientNames.take(5)}")
                                ingredientNames
                            } else {
                                Log.d("RecipeViewModel", "No fridge ingredient IDs found")
                                emptySet()
                            }
                        } else {
                            Log.d("RecipeViewModel", "No fridge found for user")
                            emptySet()
                        }
                    } catch (e: Exception) {
                        Log.w("RecipeViewModel", "Failed to load fridge ingredients", e)
                        emptySet()
                    }
                } else {
                    Log.d("RecipeViewModel", "No authenticated user")
                    emptySet()
                }

                // Fetch recipes and score them based on fridge ingredient IDs via recipe_ingredient table
                val pool = if (fridgeIngredientIds.isNotEmpty()) {
                    Log.d("RecipeViewModel", "Matching recipes using ${fridgeIngredientIds.size} fridge ingredient IDs via recipe_ingredients")

                    // Find all recipe-ingredient rows that intersect with the user's fridge ingredient IDs
                    val matchedRows = try {
                        SupabaseClient.client.postgrest.from("recipe_ingredients")
                            .select(columns = Columns.list("recipe_id", "ingredient_id")) {
                                filter { isIn("ingredient_id", fridgeIngredientIds.map { it.toString() }) }
                            }
                            .decodeList<Map<String, Long>>()
                    } catch (e: Exception) {
                        Log.w("RecipeViewModel", "Failed to query recipe_ingredients for matches", e)
                        emptyList()
                    }

                    Log.d("RecipeViewModel", "Matched ${matchedRows.size} recipe_ingredients rows against fridge IDs")

                    // Compute match counts per recipe (distinct ingredient IDs)
                    val matchesPerRecipe: Map<Long, Int> = matchedRows
                        .groupBy { it["recipe_id"]!! }
                        .mapValues { entry -> entry.value.mapNotNull { it["ingredient_id"] }.toSet().size }

                    val recipeIds = matchesPerRecipe.keys.toList()
                    val candidateRecipes = if (recipeIds.isNotEmpty()) {
                        try {
                            SupabaseClient.client.postgrest.from("recipes")
                                .select() {
                                    filter { isIn("id", recipeIds.map { it.toString() }) }
                                }
                                .decodeList<Recipe>()
                        } catch (e: Exception) {
                            Log.w("RecipeViewModel", "Failed to fetch candidate recipes by IDs", e)
                            emptyList()
                        }
                    } else emptyList()

                    val sortedCandidates = candidateRecipes
                        .sortedByDescending { matchesPerRecipe[it.id] ?: 0 }

                    Log.d("RecipeViewModel", "Top matches by ID: ${sortedCandidates.take(10).map { it.recipe_name + " (" + (matchesPerRecipe[it.id] ?: 0) + ")" }}")

                    // If ID-based matching yielded no candidates, fall back to a random pool
                    if (sortedCandidates.isEmpty()) {
                        Log.d("RecipeViewModel", "No ID-based matches found; using random fallback pool")
                        try {
                            SupabaseClient.client.postgrest.from("recipes")
                                .select() {
                                    limit(500)
                                }
                                .decodeList<Recipe>()
                                .shuffled()
                                .take(200)
                        } catch (e: Exception) {
                            Log.w("RecipeViewModel", "Failed to fetch recipes for random fallback", e)
                            emptyList()
                        }
                    } else {
                        sortedCandidates.take(200)
                    }
                } else {
                    // If no fridge ingredients, get truly random recipes (not just top-rated)
                    // Simply get a larger pool and shuffle it for randomness
                    SupabaseClient.client.postgrest.from("recipes")
                        .select() {
                            limit(500) // Get a larger pool for better randomness
                        }
                        .decodeList<Recipe>()
                        .shuffled() // Shuffle for randomness
                        .take(200) // Take 200 random recipes
                }

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

                // Choose an anchor recipe: always prefer a favorite if available; otherwise fallback to random from pool
                var anchor: Recipe? = null
                var hasFavorites = false
                var favoriteRecipeIds: Set<Long> = emptySet()
                if (user != null) {
                    try {
                        favoriteRecipeIds = SupabaseClient.client.postgrest.from("user_recipe_history")
                            .select(columns = Columns.list("recipe_id")) {
                                filter {
                                    eq("user_id", user.id.toString())
                                    eq("is_favorite", true)
                                }
                            }
                            .decodeList<Map<String, Long>>()
                            .map { it["recipe_id"]!! }
                            .toSet()
                        hasFavorites = favoriteRecipeIds.isNotEmpty()

                        if (hasFavorites) {
                            // Prefer a favorite recipe as anchor. If not in current pool, fetch it directly.
                            anchor = pool.firstOrNull { it.id in favoriteRecipeIds }
                            if (anchor == null) {
                                val anchorId = favoriteRecipeIds.first()
                                try {
                                    val fetched = SupabaseClient.client.postgrest.from("recipes")
                                        .select() {
                                            filter { eq("id", anchorId.toString()) }
                                        }
                                        .decodeList<Recipe>()
                                    anchor = fetched.firstOrNull()
                                } catch (e: Exception) {
                                    Log.w("RecipeViewModel", "Failed to fetch favorite anchor recipe by ID", e)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("RecipeViewModel", "Could not load favorites for anchor selection", e)
                    }
                }
                if (anchor == null) {
                    // Fallback to random from pool (pool is already shuffled or scored)
                    anchor = pool.first()
                }
                // Log anchor selection for verification
                val isAnchorFavorite = anchor?.id?.let { favoriteRecipeIds.contains(it) } == true
                if (isAnchorFavorite && anchor != null) {
                    Log.d(
                        "RecipeViewModel",
                        "Anchor recipe chosen (favorite): id=${anchor.id} name=${anchor.recipe_name}"
                    )
                } else {
                    Log.d(
                        "RecipeViewModel",
                        "Anchor recipe chosen (non-favorite): id=${anchor?.id} name=${anchor?.recipe_name}"
                    )
                }

                // Server-side similarity via RPC only if user has favorites; otherwise skip
                val similarRows = if (hasFavorites) {
                    try {
                        val params = buildJsonObject {
                            put("anchor_recipe_id", anchor!!.id)
                            put("limit_count", half)
                        }
                        Log.d(
                            "RecipeViewModel",
                            "Calling similar_recipes RPC with anchor=${anchor.id} limit=${half}"
                        )
                        SupabaseClient.client.postgrest.rpc(
                            function = "similar_recipes",
                            parameters = params
                        ).decodeList<SimilarRecipeRow>()
                    } catch (e: Exception) {
                        Log.w("RecipeViewModel", "RPC similar_recipes failed, falling back to empty similar list", e)
                        emptyList()
                    }
                } else {
                    Log.d("RecipeViewModel", "No favorites present; skipping content-based similarity and using pool only")
                    emptyList()
                }

                val similarIds = similarRows.map { it.recipe_id }.toSet()
                if (similarRows.isNotEmpty()) {
                    val preview = similarRows.take(10).joinToString(
                        separator = ", "
                    ) { "${it.recipe_id}:${"%.2f".format(it.score)}" }
                    Log.d(
                        "RecipeViewModel",
                        "similar_recipes returned ${similarRows.size} rows: ${preview}"
                    )
                } else {
                    Log.d("RecipeViewModel", "similar_recipes returned 0 rows")
                }
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
                if (scoredSimilar.isNotEmpty()) {
                    Log.d(
                        "RecipeViewModel",
                        "Fetched ${scoredSimilar.size} similar recipes: ids=${scoredSimilar.take(10).map { it.id }}"
                    )
                }

                // Random half, excluding already selected similar ones
                // For ingredient-based pool, shuffle to add variety
                // For truly random pool, it's already shuffled
                val randomHalf = pool
                    .shuffled()
                    .filter { it.id != anchor!!.id && it.id !in similarIds }
                    .take(half)

                // Combine and limit: prioritize similar recipes first so content-based results appear
                val baseCombined = if (scoredSimilar.isNotEmpty()) {
                    Log.d("RecipeViewModel", "Prioritizing similar recipes in combined selection")
                    (scoredSimilar + pool).distinctBy { it.id }
                } else {
                    (pool).distinctBy { it.id }
                }
                val combined = if (baseCombined.size < totalCount) {
                    val excludeIds = baseCombined.map { it.id }.toSet()
                    val filler = try {
                        SupabaseClient.client.postgrest.from("recipes")
                            .select() {
                                limit(500)
                            }
                            .decodeList<Recipe>()
                            .filter { it.id !in excludeIds }
                            .shuffled()
                            .take(totalCount - baseCombined.size)
                    } catch (e: Exception) {
                        Log.w("RecipeViewModel", "Failed to fetch random filler recipes", e)
                        emptyList()
                    }
                    (baseCombined + filler).take(totalCount)
                } else baseCombined.take(totalCount)
                val usedFiller = (combined.size - kotlin.math.min(baseCombined.size, totalCount)).coerceAtLeast(0)
                Log.d(
                    "RecipeViewModel",
                    "Combined from pool+similar+filler: combined=${combined.size} pool=${pool.size} similar=${scoredSimilar.size} filler=${usedFiller}"
                )

                // Origin breakdown and samples to verify content-based inclusion
                val combinedIds = combined.map { it.id }.toSet()
                val poolIds = pool.map { it.id }.toSet()
                val similarIncludedIds = combinedIds.intersect(similarIds)
                val poolIncludedIds = combinedIds.intersect(poolIds)
                val fillerIncludedIds = combinedIds - poolIncludedIds - similarIncludedIds
                Log.d(
                    "RecipeViewModel",
                    "Origin breakdown in combined: fromSimilar=${similarIncludedIds.size} fromPool=${poolIncludedIds.size} fromFiller=${fillerIncludedIds.size}"
                )
                val similarSamples = combined.filter { it.id in similarIncludedIds }.take(10)
                    .joinToString(
                        separator = ", "
                    ) { "${it.id}:${it.recipe_name}" }
                if (similarSamples.isNotEmpty()) {
                    Log.d(
                        "RecipeViewModel",
                        "Combined includes similar recipes: ${similarSamples}"
                    )
                }

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

                        // Compute availability using ingredient IDs when possible
                        val combinedIds = combined.map { it.id }
                        val allIngredientRows = try {
                            SupabaseClient.client.postgrest.from("recipe_ingredients")
                                .select(columns = Columns.list("recipe_id", "ingredient_id")) {
                                    filter { isIn("recipe_id", combinedIds.map { it.toString() }) }
                                }
                                .decodeList<Map<String, Long>>()
                        } catch (e: Exception) {
                            Log.w("RecipeViewModel", "Failed to load recipe_ingredients for combined availability; falling back to name-based counts", e)
                            emptyList()
                        }

                        val byRecipe = allIngredientRows.groupBy { it["recipe_id"]!! }
                        val fridgeIdSet = fridgeIngredientIds.toSet()

                        combined.map { recipe ->
                            val ingIds = byRecipe[recipe.id]?.mapNotNull { it["ingredient_id"] }?.toSet() ?: emptySet()
                            val total = ingIds.size
                            val available = if (total > 0 && fridgeIdSet.isNotEmpty()) ingIds.count { fridgeIdSet.contains(it) } else 0
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

                // Sort by availability count for fridge-driven recommendations.
                // If the fridge has ingredient IDs, use rating as a secondary tie-breaker
                // (and name as a tertiary) to ensure deterministic ordering when counts tie.
                val sortedWithCounts = if (fridgeIngredientIds.isNotEmpty()) {
                    withCounts.sortedWith(
                        compareByDescending<Recipe> { it.available_count ?: 0 }
                            .thenByDescending { it.rating ?: Double.NEGATIVE_INFINITY }
                            .thenBy { it.recipe_name }
                    )
                } else {
                    withCounts.sortedWith(
                        compareByDescending<Recipe> { it.available_count ?: 0 }
                            .thenByDescending { it.rating ?: Double.NEGATIVE_INFINITY }
                            .thenBy { it.recipe_name }
                    )
                }

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    recommendedRecipes = sortedWithCounts,
                    filteredRecipes = sortedWithCounts,
                    errorMessage = null
                )
                _recommendedRecipes.value = sortedWithCounts
                _isLoading.value = false
                Log.d(
                    "RecipeViewModel",
                    if (fridgeIngredientIds.isNotEmpty())
                        "Refreshed combined recommendations sorted by availability only (fridge IDs present): top=${sortedWithCounts.firstOrNull()?.recipe_name} count=${sortedWithCounts.firstOrNull()?.available_count}"
                    else
                        "Refreshed combined recommendations sorted by availability, rating, then name: top=${sortedWithCounts.firstOrNull()?.recipe_name} count=${sortedWithCounts.firstOrNull()?.available_count}"
                )
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

    private fun fetchSearchRecipesRemote(resetLimit: Boolean) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            _isLoading.value = true
            try {
                val rawQuery = _uiState.value.searchQuery
                val tokens = rawQuery
                    .split(',', ' ')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .map { it.lowercase() }

                val selectedNames = _selectedTags.value ?: emptyList()

                // Map selected ingredient names to IDs using cached ingredients
                val nameToId = _ingredients.value
                    ?.filter { it.ingredient_name != null }
                    ?.associate { it.ingredient_name!!.lowercase() to it.id }
                    ?: emptyMap()
                val selectedIds = selectedNames.mapNotNull { nameToId[it.lowercase()] }

                // Compute recipe IDs that include ALL selected ingredient IDs (AND semantics)
                var recipeIdsAND: List<Long>? = null
                if (selectedIds.isNotEmpty()) {
                    val rows = SupabaseClient.client.postgrest.from("recipe_ingredients")
                        .select(columns = Columns.list("recipe_id", "ingredient_id")) {
                            filter { isIn("ingredient_id", selectedIds.map { it.toString() }) }
                        }
                        .decodeList<Map<String, Long>>()
                    val grouped = rows.groupBy { it["recipe_id"]!! }
                    val required = selectedIds.toSet()
                    val ids = grouped.mapNotNull { (rid, list) ->
                        val present = list.mapNotNull { it["ingredient_id"] }.toSet()
                        if (required.all { present.contains(it) }) rid else null
                    }
                    recipeIdsAND = ids.distinct()
                }

                // Build recipes query with optional ingredient AND filter and pagination
                val candidateRecipes = SupabaseClient.client.postgrest.from("recipes")
                    .select() {
                        if (recipeIdsAND != null) {
                            filter {
                                if (recipeIdsAND!!.isNotEmpty()) {
                                    isIn("id", recipeIdsAND!!.map { it.toString() })
                                } else {
                                    eq("id", "-1")
                                }
                            }
                        }
                        order("rating", Order.DESCENDING)
                        limit(searchLimit.toLong())
                    }
                    .decodeList<Recipe>()

                // Apply local AND-style filtering across name, tags, and ingredients
                val filteredByTokens = if (tokens.isEmpty()) {
                    candidateRecipes
                } else {
                    candidateRecipes.filter { recipe ->
                        val name = recipe.recipe_name.lowercase()
                        val tagsLocal = (recipe.tags ?: emptyList()).map { it.lowercase() }
                        val ingredientsLocal = (recipe.clean_ingredients ?: emptyList()).map { it.lowercase() }
                        tokens.all { t ->
                            name.contains(t) ||
                            tagsLocal.any { it.contains(t) } ||
                            ingredientsLocal.any { it.contains(t) }
                        }
                    }
                }

                // Mark favorites and compute availability like other paths
                val user = SupabaseClient.client.auth.currentUserOrNull()
                val withFavoritesAndCounts = if (user != null) {
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

                        val fridgeId = resolveCurrentFridgeId()
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
                                Log.w("RecipeViewModel", "Failed to load fridge ingredient IDs for search", e)
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
                                Log.w("RecipeViewModel", "Failed to load fridge ingredient names for search", e)
                                emptySet()
                            }
                        } else emptySet()

                        filteredByTokens.map { recipe ->
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
                        Log.w("RecipeViewModel", "Could not load favorites for search", e)
                        filteredByTokens
                    }
                } else {
                    filteredByTokens.map { recipe ->
                        val names = recipe.clean_ingredients ?: emptyList()
                        recipe.copy(available_count = 0, total_count = names.size)
                    }
                }

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    filteredRecipes = withFavoritesAndCounts,
                    errorMessage = null
                )
                _isLoading.value = false
                Log.d("RecipeViewModel", "Fetched ${withFavoritesAndCounts.size} search recipes from DB (limit=$searchLimit)")
            } catch (e: Exception) {
                Log.e("RecipeViewModel", "Error fetching search recipes", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "Failed to fetch search recipes: ${e.message}"
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