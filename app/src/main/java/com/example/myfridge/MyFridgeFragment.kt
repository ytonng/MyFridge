package com.example.myfridge

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import android.widget.LinearLayout
import android.widget.TextView
import android.util.Log
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.floatingactionbutton.FloatingActionButton
import android.widget.ImageButton
import androidx.appcompat.widget.SearchView
import androidx.recyclerview.widget.RecyclerView
import com.example.myfridge.viewmodel.MyFridgeViewModel
import kotlinx.coroutines.flow.collectLatest
import android.view.View.GONE
import android.view.View.VISIBLE
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import android.view.DragEvent
import android.widget.ViewSwitcher

class MyFridgeFragment : Fragment() {

    private val viewModel: MyFridgeViewModel by viewModels()
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: FridgeItemsAdapter
    private lateinit var emptyState: LinearLayout
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var fridgeNameText: TextView
    private lateinit var deleteZone: View
    private lateinit var searchEmptyText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Listen for fridge switch events and refresh items when received
        parentFragmentManager.setFragmentResultListener("fridge_switched", this) { _, bundle ->
            val switchedId = bundle.getLong("fridgeId", -1L)
            android.util.Log.d("MyFridgeFragment", "fridge_switched received: id=" + switchedId + ", refreshing items")
            viewModel.refresh()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_my_fridge, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d("MyFridgeFragment", "onViewCreated: initializing UI")

        recyclerView = view.findViewById(R.id.recyclerFridgeItems)
        emptyState = view.findViewById(R.id.emptyState)
        swipeRefresh = view.findViewById(R.id.swipeRefresh)
        fridgeNameText = view.findViewById(R.id.textFridgeName)
        val sortButton: ImageButton = view.findViewById(R.id.buttonSortExpiry)
        val sortLabel: TextView = view.findViewById(R.id.textSortLabel)
        val searchInput: SearchView = view.findViewById(R.id.inputSearchIngredient)
        searchEmptyText = view.findViewById(R.id.textSearchEmpty)

        fun updateSortUI(asc: Boolean) {
            sortButton.setImageResource(if (asc) android.R.drawable.arrow_up_float else android.R.drawable.arrow_down_float)
            sortLabel.text = if (asc) "Expiry (Asc)" else "Expiry (Desc)"
            sortButton.contentDescription = if (asc) "Sort by expiry ascending" else "Sort by expiry descending"
        }

        // Initial UI state: ascending
        updateSortUI(true)

        sortButton.setOnClickListener {
            val asc = viewModel.toggleSortByExpiry()
            updateSortUI(asc)
            Toast.makeText(
                requireContext(),
                if (asc) "Sorting by expiry ascending" else "Sorting by expiry descending",
                Toast.LENGTH_SHORT
            ).show()
        }

        // Search by ingredient name (SearchView style consistent with recipe screen)
        searchInput.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                viewModel.setSearchQuery(query ?: "")
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                viewModel.setSearchQuery(newText ?: "")
                return true
            }
        })
        deleteZone = view.findViewById(R.id.deleteZone)
        adapter = FridgeItemsAdapter(onItemClick = { item ->
            Log.d("MyFridgeFragment", "onItemClick: itemId=" + item.id + ", name=" + item.name)
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, AddFridgeItemFragment().apply {
                    arguments = Bundle().apply { putLong("item_id", item.id) }
                })
                .addToBackStack(null)
                .commit()
        }, onBookmarkToggle = { item ->
            Log.d("MyFridgeFragment", "onBookmarkToggle: itemId=" + item.id + ", name=" + item.name)
            viewModel.toggleBookmark(item.id)
        })
        recyclerView.adapter = adapter
        recyclerView.layoutManager = GridLayoutManager(requireContext(), 2)

        // Show/hide delete zone when dragging starts/ends
        recyclerView.setOnDragListener { _, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> {
                    Log.d("MyFridgeFragment", "Drag: started")
                    deleteZone.visibility = VISIBLE
                    true
                }
                DragEvent.ACTION_DRAG_ENDED -> {
                    Log.d("MyFridgeFragment", "Drag: ended")
                    deleteZone.visibility = GONE
                    true
                }
                else -> false
            }
        }

        // Handle drop on delete zone
        deleteZone.setOnDragListener { v, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_ENTERED -> {
                    v.alpha = 1.0f
                    true
                }
                DragEvent.ACTION_DRAG_EXITED -> {
                    v.alpha = 0.95f
                    true
                }
                DragEvent.ACTION_DROP -> {
                    val local = event.localState
                    if (local is FridgeItemDisplay) {
                        Log.d("MyFridgeFragment", "Delete drop: itemId=" + local.id + ", name=" + local.name)
                        viewModel.deleteItem(local.id) {
                            // Show success toast only after successful deletion
                            Toast.makeText(requireContext(), "Deleted ${local.name}", Toast.LENGTH_SHORT).show()
                        }
                    }
                    deleteZone.visibility = GONE
                    v.alpha = 0.95f
                    true
                }
                DragEvent.ACTION_DRAG_ENDED -> {
                    deleteZone.visibility = GONE
                    v.alpha = 0.95f
                    true
                }
                DragEvent.ACTION_DRAG_STARTED -> true
                else -> false
            }
        }

        view.findViewById<FloatingActionButton>(R.id.fabAddItem).setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, AddFridgeItemFragment())
                .addToBackStack(null)
                .commit()
        }

        // Pull-to-refresh
        swipeRefresh.setOnRefreshListener {
            Log.d("MyFridgeFragment", "SwipeRefresh: triggered")
            viewModel.refresh()
        }

        // Observe UI state
        viewLifecycleOwner.lifecycleScope.launchWhenStarted {
            viewModel.uiState.collectLatest { state ->
                Log.d(
                    "MyFridgeFragment",
                    "UIState: isLoading=" + state.isLoading +
                        ", items=" + state.items.size +
                        ", fridgeName=" + (state.fridgeName ?: "<none>") +
                        ", hasError=" + (state.errorMessage != null)
                )
                if (state.errorMessage != null) {
                    Toast.makeText(requireContext(), state.errorMessage, Toast.LENGTH_SHORT).show()
                }
                adapter.submitList(state.items)
                state.fridgeName?.let { fridgeNameText.text = it }
                val isEmpty = !state.isLoading && state.items.isEmpty()
                val currentQuery = searchInput.query?.toString() ?: ""
                if (isEmpty) {
                    if (currentQuery.isNotBlank()) {
                        searchEmptyText.visibility = VISIBLE
                        searchEmptyText.text = "No items match \"$currentQuery\""
                        emptyState.visibility = GONE
                    } else {
                        searchEmptyText.visibility = GONE
                        emptyState.visibility = VISIBLE
                    }
                    swipeRefresh.visibility = GONE
                } else {
                    searchEmptyText.visibility = GONE
                    emptyState.visibility = GONE
                    swipeRefresh.visibility = VISIBLE
                }
                swipeRefresh.isRefreshing = state.isLoading
            }
        }

        Log.d("MyFridgeFragment", "onViewCreated: calling loadItems")
        viewModel.loadItems()
    }
}