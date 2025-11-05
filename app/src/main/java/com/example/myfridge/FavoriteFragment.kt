package com.example.myfridge

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.AdapterView
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.appcompat.widget.SearchView
import com.example.myfridge.viewmodel.Recipe
import com.example.myfridge.viewmodel.RecipeViewModel

class FavoriteFragment : Fragment() {
    private lateinit var viewModel: RecipeViewModel
    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var adapter: RecipeAdapter
    private lateinit var emptyView: View
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var buttonSortFavourite: View
    private lateinit var spinnerSortFavourite: Spinner
    private lateinit var searchViewFavourite: SearchView
    private lateinit var buttonLoadMoreFavourite: com.google.android.material.button.MaterialButton
    private var sortAscendingFavourite: Boolean = false
    private var sortKeyFavouriteIsRating: Boolean = true
    private var favouriteQuery: String = ""
    private var lastFavorites: List<Recipe> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_favourite, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        recyclerView = view.findViewById(R.id.recyclerViewFavourite)
        progressBar = view.findViewById(R.id.progressBarFavourite)
        emptyView = view.findViewById(R.id.textViewEmptyFavourite)
        swipeRefresh = view.findViewById(R.id.swipeRefreshFavourite)
        buttonSortFavourite = view.findViewById(R.id.buttonSortFavourite)
        spinnerSortFavourite = view.findViewById(R.id.spinnerSortFavourite)
        searchViewFavourite = view.findViewById(R.id.searchViewFavourite)
        buttonLoadMoreFavourite = view.findViewById(R.id.buttonLoadMoreFavourite)
        
        setupRecyclerView()
        setupViewModel()
        setupSearch()

        swipeRefresh.setOnRefreshListener {
            viewModel.refreshFavorites()
        }

        // Show Load More when scrolled to bottom and a search query is active
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(rv, dx, dy)
                val atBottom = !rv.canScrollVertically(1)
                val queryActive = !searchViewFavourite.query.isNullOrEmpty()
                buttonLoadMoreFavourite.visibility = if (atBottom && queryActive) View.VISIBLE else View.GONE
            }
        })

        buttonLoadMoreFavourite.setOnClickListener {
            val queryActive = !searchViewFavourite.query.isNullOrEmpty()
            if (queryActive) {
                viewModel.loadMoreFavoriteSearch()
            } else {
                viewModel.refreshFavorites()
            }
            buttonLoadMoreFavourite.visibility = View.GONE
        }
    }
    
    private fun setupRecyclerView() {
        adapter = RecipeAdapter(
            onFavoriteClick = { recipeId ->
                viewModel.toggleFavorite(recipeId)
            },
            onRecipeClick = { recipeId ->
                viewModel.trackRecipeView(recipeId)
                // Navigate to recipe detail screen
                try {
                    RecipeDetailActivity.start(requireContext(), recipeId.toLong())
                } catch (_: Exception) {
                    // Fallback in case of bad id
                }
            }
        )
        recyclerView.layoutManager = GridLayoutManager(requireContext(), 2)
        recyclerView.adapter = adapter
    }
    
    private fun setupViewModel() {
        // Use the parent fragment (RecipeFragment) to share the ViewModel
        viewModel = ViewModelProvider(requireParentFragment())[RecipeViewModel::class.java]
        
        viewModel.favoriteRecipes.observe(viewLifecycleOwner) { recipes ->
            lastFavorites = recipes
            // Favorites list is already filtered remotely when query is active
            val base = recipes
            val sorted = if (sortKeyFavouriteIsRating) {
                if (sortAscendingFavourite) {
                    base.sortedBy { it.rating ?: Double.NEGATIVE_INFINITY }
                } else {
                    base.sortedByDescending { it.rating ?: Double.NEGATIVE_INFINITY }
                }
            } else {
                val withTotals = base.map { r ->
                    val total = r.total_count ?: (r.clean_ingredients?.size
                        ?: (r.display_ingredients?.size ?: 0))
                    r.copy(total_count = total)
                }
                if (sortAscendingFavourite) {
                    withTotals.sortedBy { it.total_count ?: 0 }
                } else {
                    withTotals.sortedByDescending { it.total_count ?: 0 }
                }
            }
            adapter.submitList(sorted)
            
            // Show empty view if no favorites
            if (sorted.isEmpty()) {
                emptyView.visibility = View.VISIBLE
                recyclerView.visibility = View.GONE
            } else {
                emptyView.visibility = View.GONE
                recyclerView.visibility = View.VISIBLE
            }
        }
        
        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
            swipeRefresh.isRefreshing = isLoading
        }

        buttonSortFavourite.setOnClickListener {
            sortAscendingFavourite = !sortAscendingFavourite
            if (buttonSortFavourite is android.widget.ImageButton) {
                (buttonSortFavourite as android.widget.ImageButton).setImageResource(
                    if (sortAscendingFavourite) android.R.drawable.arrow_up_float
                    else android.R.drawable.arrow_down_float
                )
            }
            val current = adapter.currentList
            val resorted = if (sortKeyFavouriteIsRating) {
                if (sortAscendingFavourite) {
                    current.sortedBy { it.rating ?: Double.NEGATIVE_INFINITY }
                } else {
                    current.sortedByDescending { it.rating ?: Double.NEGATIVE_INFINITY }
                }
            } else {
                val withTotals = current.map { r ->
                    val total = r.total_count ?: (r.clean_ingredients?.size
                        ?: (r.display_ingredients?.size ?: 0))
                    r.copy(total_count = total)
                }
                if (sortAscendingFavourite) {
                    withTotals.sortedBy { it.total_count ?: 0 }
                } else {
                    withTotals.sortedByDescending { it.total_count ?: 0 }
                }
            }
            adapter.submitList(resorted)
        }

        // Sort key dropdown setup
        val sortOptions = listOf("Rating", "Ingredients")
        val spinnerAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, sortOptions)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerSortFavourite.adapter = spinnerAdapter
        spinnerSortFavourite.setSelection(0)
        spinnerSortFavourite.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                sortKeyFavouriteIsRating = position == 0
                val current = adapter.currentList
                val resorted = if (sortKeyFavouriteIsRating) {
                    if (sortAscendingFavourite) {
                        current.sortedBy { it.rating ?: Double.NEGATIVE_INFINITY }
                    } else {
                        current.sortedByDescending { it.rating ?: Double.NEGATIVE_INFINITY }
                    }
                } else {
                    val withTotals = current.map { r ->
                        val total = r.total_count ?: (r.clean_ingredients?.size
                            ?: (r.display_ingredients?.size ?: 0))
                        r.copy(total_count = total)
                    }
                    if (sortAscendingFavourite) {
                        withTotals.sortedBy { it.total_count ?: 0 }
                    } else {
                        withTotals.sortedByDescending { it.total_count ?: 0 }
                    }
                }
                adapter.submitList(resorted)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) { /* no-op */ }
        }
    }

    private fun setupSearch() {
        searchViewFavourite.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                favouriteQuery = query?.trim() ?: ""
                viewModel.filterFavoriteRecipes(favouriteQuery)
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                favouriteQuery = newText?.trim() ?: ""
                viewModel.filterFavoriteRecipes(favouriteQuery)
                return true
            }
        })
    }

    // Local applyFilterAndSort no longer needed; ViewModel provides filtered favorites
}
