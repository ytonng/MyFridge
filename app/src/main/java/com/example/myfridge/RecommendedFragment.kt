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
import android.widget.TextView
import android.widget.ImageButton
import android.graphics.Color
import android.content.res.ColorStateList
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.example.myfridge.viewmodel.RecipeViewModel
import kotlinx.coroutines.launch

class RecommendedFragment : Fragment() {
    private lateinit var viewModel: RecipeViewModel
    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var emptyView: TextView
    private lateinit var searchView: SearchView
    private lateinit var adapter: RecipeAdapter
    private lateinit var chipGroupTags: ChipGroup
    private lateinit var chipGroupSelected: ChipGroup
    private lateinit var buttonRefreshTags: ImageButton
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var buttonSortAvailability: ImageButton
    private lateinit var spinnerSortKey: Spinner
    private var sortAscendingRecommended: Boolean = false
    private var sortKeyRecommendedIsRating: Boolean = true
    private lateinit var buttonLoadMore: com.google.android.material.button.MaterialButton
    private var currentTotalCountRecommended: Int = 20

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_recommended, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        recyclerView = view.findViewById(R.id.recyclerViewRecommended)
        progressBar = view.findViewById(R.id.progressBarRecommended)
        emptyView = view.findViewById(R.id.textViewEmptyRecommended)
        searchView = view.findViewById(R.id.searchView)
        chipGroupTags = view.findViewById(R.id.chipGroupTags)
        chipGroupSelected = view.findViewById(R.id.chipGroupSelected)
        buttonRefreshTags = view.findViewById(R.id.buttonRefreshTags)
        swipeRefresh = view.findViewById(R.id.swipeRefreshRecommended)
        buttonSortAvailability = view.findViewById(R.id.buttonSortAvailability)
        spinnerSortKey = view.findViewById(R.id.spinnerSortKey)
        buttonLoadMore = view.findViewById(R.id.buttonLoadMore)
        
        setupRecyclerView()
        setupViewModel()
        setupSearchView()

        // Populate suggested tags initially so chips are available without manual refresh
        viewModel.refreshSuggestedTags()

        swipeRefresh.setOnRefreshListener {
            viewModel.onRecommendedTabSelected(currentTotalCountRecommended)
        }

        // Show Load More when scrolled to bottom and no search query
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(rv, dx, dy)
                val atBottom = !rv.canScrollVertically(1)
                // Show for both recommended and search results
                buttonLoadMore.visibility = if (atBottom) View.VISIBLE else View.GONE
            }
        })

        buttonLoadMore.setOnClickListener {
            val queryEmpty = searchView.query.isNullOrEmpty()
            val hasActiveTags = (viewModel.selectedTags.value?.isNotEmpty() == true)
            if (queryEmpty && !hasActiveTags) {
                // No query and no tags: load more recommended
                currentTotalCountRecommended += 20
                viewModel.onRecommendedTabSelected(currentTotalCountRecommended)
            } else {
                // Query or tags present: load more search
                viewModel.loadMoreSearch()
            }
            buttonLoadMore.visibility = View.GONE
        }
    }

    // Removed automatic refresh on resume; now refresh only on pull-to-refresh
    
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
        // Use the activity to share the ViewModel
        viewModel = ViewModelProvider(requireActivity())[RecipeViewModel::class.java]

        lifecycleScope.launch {
            viewModel.uiState.collect { uiState ->
                // Drive refreshing indicator from loading state
                swipeRefresh.isRefreshing = uiState.isLoading
                progressBar.visibility = if (uiState.isLoading) View.VISIBLE else View.GONE
                if (uiState.isLoading) {
                    buttonLoadMore.visibility = View.GONE
                }
                
                // Use filtered results when either a text query or selected tags are active
                val hasActiveTags = (viewModel.selectedTags.value?.isNotEmpty() == true)
                val recipes = if (uiState.searchQuery.isEmpty() && !hasActiveTags) {
                    uiState.recommendedRecipes
                } else {
                    uiState.filteredRecipes
                }
                
                if (recipes.isEmpty()) {
                    recyclerView.visibility = View.GONE
                    emptyView.visibility = View.VISIBLE
                    val query = searchView.query.toString()
                    emptyView.text = if (query.isEmpty()) 
                        "No recipes available" 
                    else 
                        "No recipes found for '$query'"
                } else {
                    recyclerView.visibility = View.VISIBLE
                    emptyView.visibility = View.GONE
                    val sorted = if (sortKeyRecommendedIsRating) {
                        if (sortAscendingRecommended) {
                            recipes.sortedBy { it.rating ?: Double.NEGATIVE_INFINITY }
                        } else {
                            recipes.sortedByDescending { it.rating ?: Double.NEGATIVE_INFINITY }
                        }
                    } else {
                        // Sort by availability count instead of raw ingredient total
                        if (sortAscendingRecommended) {
                            recipes.sortedBy { it.available_count ?: 0 }
                        } else {
                            recipes.sortedByDescending { it.available_count ?: 0 }
                        }
                    }
                    adapter.submitList(sorted)
                    // Ensure refreshing indicator is hidden after list updates
                    swipeRefresh.isRefreshing = false
                }
            }
        }

        // Observe suggested tags to render selectable chips (limited to 5)
        viewModel.suggestedTags.observe(viewLifecycleOwner) { tags ->
            renderAvailableTags(tags)
        }

        // Observe selected tags to render chips with close icons
        viewModel.selectedTags.observe(viewLifecycleOwner) { selected ->
            renderSelectedTags(selected)
        }

        // Refresh suggestions when user taps refresh
        buttonRefreshTags.setOnClickListener {
            viewModel.refreshSuggestedTags()
        }

        // Toggle sort order and re-apply to current list
        buttonSortAvailability.setOnClickListener {
            sortAscendingRecommended = !sortAscendingRecommended
            buttonSortAvailability.setImageResource(
                if (sortAscendingRecommended) android.R.drawable.arrow_up_float
                else android.R.drawable.arrow_down_float
            )
            val current = adapter.currentList
            val resorted = if (sortKeyRecommendedIsRating) {
                if (sortAscendingRecommended) {
                    current.sortedBy { it.rating ?: Double.NEGATIVE_INFINITY }
                } else {
                    current.sortedByDescending { it.rating ?: Double.NEGATIVE_INFINITY }
                }
            } else {
                // Sort by availability count instead of raw ingredient total
                if (sortAscendingRecommended) {
                    current.sortedBy { it.available_count ?: 0 }
                } else {
                    current.sortedByDescending { it.available_count ?: 0 }
                }
            }
            adapter.submitList(resorted)
        }

        // Sort key dropdown: Rating or Total Ingredients
        val sortOptions = listOf("Rating", "Ingredients")
        val spinnerAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, sortOptions)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerSortKey.adapter = spinnerAdapter
        spinnerSortKey.setSelection(0)
        spinnerSortKey.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                sortKeyRecommendedIsRating = position == 0
                // Re-sort current list with the new key
                val current = adapter.currentList
                val resorted = if (sortKeyRecommendedIsRating) {
                    if (sortAscendingRecommended) {
                        current.sortedBy { it.rating ?: Double.NEGATIVE_INFINITY }
                    } else {
                        current.sortedByDescending { it.rating ?: Double.NEGATIVE_INFINITY }
                    }
                } else {
                    // Sort by availability count instead of raw ingredient total
                    if (sortAscendingRecommended) {
                        current.sortedBy { it.available_count ?: 0 }
                    } else {
                        current.sortedByDescending { it.available_count ?: 0 }
                    }
                }
                adapter.submitList(resorted)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) { /* no-op */ }
        }
    }
    
    private fun setupSearchView() {
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                query?.let {
                    filterRecipes(it)
                }
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                newText?.let {
                    filterRecipes(it)
                }
                return true
            }
        })
    }
    
    private fun filterRecipes(query: String) {
        viewModel.filterRecommendedRecipes(query)
    }

    private fun renderAvailableTags(tags: List<String>) {
        chipGroupTags.removeAllViews()
        tags.forEach { tagName ->
            val chip = Chip(requireContext()).apply {
                text = tagName
                isCheckable = false
                isClickable = true
                isCloseIconVisible = false
                chipBackgroundColor = ColorStateList.valueOf(Color.parseColor("#f0f4fd"))
                setTextColor(Color.parseColor("#000000"))
                setOnClickListener {
                    viewModel.addTag(tagName)
                }
            }
            chipGroupTags.addView(chip)
        }
    }

    private fun renderSelectedTags(selected: List<String>) {
        chipGroupSelected.removeAllViews()
        selected.forEach { tagName ->
            val chip = Chip(requireContext()).apply {
                text = tagName
                isCheckable = false
                isClickable = true
                isCloseIconVisible = true
                chipBackgroundColor = ColorStateList.valueOf(Color.parseColor("#f0f4fd"))
                setTextColor(Color.parseColor("#000000"))
                setOnCloseIconClickListener {
                    viewModel.removeTag(tagName)
                }
                setOnClickListener {
                    // Optional: tapping also removes
                    viewModel.removeTag(tagName)
                }
            }
            chipGroupSelected.addView(chip)
        }
    }
}
