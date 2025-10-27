package com.example.myfridge

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.example.myfridge.viewmodel.Recipe

class RecipeAdapter(
    private val onFavoriteClick: (String) -> Unit,
    private val onRecipeClick: (String) -> Unit = {}
) : ListAdapter<Recipe, RecipeAdapter.RecipeViewHolder>(RecipeDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecipeViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_recipe, parent, false)
        return RecipeViewHolder(view, onFavoriteClick, onRecipeClick)
    }

    override fun onBindViewHolder(holder: RecipeViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class RecipeViewHolder(
        itemView: View,
        private val onFavoriteClick: (String) -> Unit,
        private val onRecipeClick: (String) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        
        private val recipeName: TextView = itemView.findViewById(R.id.textViewRecipeName)
        private val recipeImage: ImageView = itemView.findViewById(R.id.imageViewRecipe)
        private val favoriteButton: ImageButton = itemView.findViewById(R.id.buttonFavorite)
        private val textRating: TextView = itemView.findViewById(R.id.textRating)
        private val textViewTime: TextView = itemView.findViewById(R.id.textViewTime)
        private val textViewIngredients: TextView = itemView.findViewById(R.id.textViewIngredients)
        
        fun bind(recipe: Recipe) {
            recipeName.text = recipe.recipe_name
            
            // Load image using Glide
            if (!recipe.img_src.isNullOrEmpty()) {
                Glide.with(itemView.context)
                    .load(recipe.img_src)
                    .placeholder(R.drawable.ic_placeholder_recipe) // Add a placeholder drawable
                    .error(R.drawable.ic_error_recipe) // Add an error drawable
                    .transition(DrawableTransitionOptions.withCrossFade())
                    .into(recipeImage)
            } else {
                // Set a default image if no img_src is available
                recipeImage.setImageResource(R.drawable.ic_placeholder_recipe)
            }
            
            // Set rating
            recipe.rating?.let { rating ->
                textRating.text = "⭐ ${String.format("%.1f", rating)}"
            } ?: run {
                textRating.text = "⭐ --"
            }
            
            // Set cooking time
            textViewTime.text = recipe.total_time ?: "Time not specified"
            
            // Show availability: x/y ingredients existed
            val available = recipe.available_count ?: 0
            val total = recipe.total_count
                ?: (recipe.clean_ingredients?.size
                    ?: (recipe.display_ingredients?.size ?: 0))
            textViewIngredients.text = "$available/$total ingredients existed"
            
            // Set favorite icon based on status
            val favoriteIcon = if (recipe.isFavorite) {
                R.drawable.ic_favorite_filled
            } else {
                R.drawable.ic_favorite_border
            }
            favoriteButton.setImageResource(favoriteIcon)
            
            favoriteButton.setOnClickListener {
                onFavoriteClick(recipe.id.toString())
            }
            
            itemView.setOnClickListener {
                onRecipeClick(recipe.id.toString())
            }
        }
    }
}

class RecipeDiffCallback : DiffUtil.ItemCallback<Recipe>() {
    override fun areItemsTheSame(oldItem: Recipe, newItem: Recipe): Boolean {
        return oldItem.id == newItem.id
    }

    override fun areContentsTheSame(oldItem: Recipe, newItem: Recipe): Boolean {
        return oldItem == newItem
    }
}