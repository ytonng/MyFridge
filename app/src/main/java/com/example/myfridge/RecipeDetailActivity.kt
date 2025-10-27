package com.example.myfridge

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.graphics.Color
import android.view.View
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.example.myfridge.databinding.ActivityRecipeDetailBinding
import com.example.myfridge.viewmodel.RecipeViewModel
import kotlinx.coroutines.launch

class RecipeDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRecipeDetailBinding
    private val viewModel: RecipeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecipeDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Close button: finish the activity
        binding.buttonClose.setOnClickListener { finish() }

        val recipeId = intent.getLongExtra(EXTRA_RECIPE_ID, -1L)
        if (recipeId != -1L) {
            viewModel.selectRecipe(recipeId)
        }

        setupObservers()
    }

    private fun setupObservers() {
        lifecycleScope.launch {
            viewModel.selectedRecipe.collect { recipe ->
                if (recipe != null) {
                    binding.recipeName.text = recipe.recipe_name
                    binding.totalTime.text = recipe.total_time ?: "-"
                    binding.rating.text = recipe.rating?.toString() ?: "-"
                    binding.url.text = recipe.url ?: "-"
                    // Render tags as chips
                    val tagNames = recipe.tags ?: emptyList()
                    binding.chipGroupTagsDetail.removeAllViews()
                    tagNames.forEach { tagName ->
                        val chip = Chip(this@RecipeDetailActivity).apply {
                            text = tagName
                            isCheckable = false
                            isClickable = false
                            isCloseIconVisible = false
                        }
                        binding.chipGroupTagsDetail.addView(chip)
                    }
                    // Make URL clickable: open in browser, add scheme if missing
                    val rawUrl = recipe.url
                    val safeUrl = rawUrl?.let {
                        if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it"
                    }
                    binding.url.setOnClickListener {
                        safeUrl?.let { url ->
                            try {
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            } catch (e: Exception) {
                                android.util.Log.e("RecipeDetailActivity", "Failed to open URL: $url", e)
                            }
                        }
                    }
                    // Tags are now rendered as Material chips in chipGroupTagsDetail
                    binding.steps.text = (recipe.steps ?: emptyList())
                        .mapIndexed { index, step -> "${index + 1}. ${step}" }
                        .joinToString("\n")
                    binding.displayIngredients.text = (recipe.display_ingredients ?: emptyList())
                        .map { item -> "\u2022 ${item}" }
                        .joinToString("\n")

                    // Favorite icon state
                    val favoriteIcon = if (recipe.isFavorite) R.drawable.ic_favorite_filled else R.drawable.ic_favorite_border
                    binding.buttonFavorite.setImageResource(favoriteIcon)

                    // Favorite toggle behavior (optimistic update)
                    binding.buttonFavorite.setOnClickListener {
                        val newFavorite = !recipe.isFavorite
                        viewModel.toggleFavorite(recipe.id.toString())
                        viewModel.updateSelectedFavoriteFlag(newFavorite)
                        val newIcon = if (newFavorite) R.drawable.ic_favorite_filled else R.drawable.ic_favorite_border
                        binding.buttonFavorite.setImageResource(newIcon)
                    }

                    // Load image using Glide
                    if (!recipe.img_src.isNullOrEmpty()) {
                        Glide.with(this@RecipeDetailActivity)
                            .load(recipe.img_src)
                            .placeholder(R.drawable.ic_placeholder_recipe)
                            .error(R.drawable.ic_error_recipe)
                            .transition(DrawableTransitionOptions.withCrossFade())
                            .into(binding.recipeImage)
                    } else {
                        binding.recipeImage.setImageResource(R.drawable.ic_placeholder_recipe)
                    }
                }
            }
        }

        // Observe ingredient availability and render dynamic list
        lifecycleScope.launch {
            viewModel.ingredientAvailability.collect { availability ->
                renderIngredientAvailability(availability)
            }
        }
    }

    private fun renderIngredientAvailability(availability: List<com.example.myfridge.viewmodel.IngredientAvailability>) {
        val container = binding.cleanIngredientsContainer
        container.removeAllViews()

        fun dpToPx(dp: Float): Int = (dp * resources.displayMetrics.density).toInt()

        availability.forEach { item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dpToPx(8f), 0, dpToPx(8f))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val indicator = View(this).apply {
                // Vertical rounded rectangle indicator: taller than wide
                layoutParams = LinearLayout.LayoutParams(dpToPx(6f), dpToPx(24f)).apply {
                    marginEnd = dpToPx(12f)
                }
                setBackgroundResource(if (item.available) R.drawable.bg_indicator_available else R.drawable.bg_indicator_missing)
            }

            val nameView = TextView(this).apply {
                text = item.name
                textSize = 14f
                setTextColor(Color.parseColor("#000000"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            row.addView(indicator)
            row.addView(nameView)
            container.addView(row)
        }
    }

    companion object {
        private const val EXTRA_RECIPE_ID = "extra_recipe_id"

        fun start(context: Context, recipeId: Long) {
            val intent = Intent(context, RecipeDetailActivity::class.java)
            intent.putExtra(EXTRA_RECIPE_ID, recipeId)
            context.startActivity(intent)
        }
    }
}