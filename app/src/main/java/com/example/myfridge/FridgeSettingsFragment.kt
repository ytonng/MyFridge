package com.example.myfridge

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.myfridge.viewmodel.SettingsViewModel
import com.example.myfridge.viewmodel.FridgeWithMembers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class FridgeSettingsFragment : Fragment() {

    private val viewModel: SettingsViewModel by viewModels()
    private lateinit var fridgeAdapter: FridgeListAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_fridge_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        android.util.Log.d("FridgeSettingsFragment", "onViewCreated: initializing UI")

        val swipeRefresh = view.findViewById<SwipeRefreshLayout>(R.id.swipeRefreshFridge)
        val recyclerViewFridges = view.findViewById<RecyclerView>(R.id.recyclerViewFridges)
        val textNoFridges = view.findViewById<TextView>(R.id.textNoFridges)
        val buttonJoin = view.findViewById<Button>(R.id.buttonJoinFridge)
        val buttonCreate = view.findViewById<Button>(R.id.buttonCreateFridge)

        // Set up RecyclerView
        fridgeAdapter = FridgeListAdapter { fridge ->
            // Handle switch to fridge
            android.util.Log.d("FridgeSettingsFragment", "onSwitchFridgeClicked: fridgeId=" + fridge.id + ", name=" + (fridge.name ?: "<none>") )
            viewModel.switchFridge(fridge.id)
        }
        recyclerViewFridges.layoutManager = LinearLayoutManager(requireContext())
        recyclerViewFridges.adapter = fridgeAdapter

        swipeRefresh.setOnRefreshListener {
            android.util.Log.d("FridgeSettingsFragment", "SwipeRefreshFridge: triggered")
            viewModel.load()
        }
        android.util.Log.d("FridgeSettingsFragment", "Initial load: calling viewModel.load()")
        viewModel.load()

        // Observe state
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                swipeRefresh.isRefreshing = state.isLoading
                android.util.Log.d(
                    "FridgeSettingsFragment",
                    "State: isLoading=" + state.isLoading +
                        ", currentFridgeId=" + (state.currentFridgeId ?: -1L) +
                        ", currentFridgeName=" + (state.currentFridgeName ?: "<none>") +
                        ", fridgesWithMembersCount=" + state.fridgesWithMembers.size +
                        ", shouldNavigateToMyFridge=" + state.shouldNavigateToMyFridge
                )

                // Update fridge list
                val fridges = state.fridgesWithMembers
                if (fridges.isEmpty()) {
                    recyclerViewFridges.visibility = View.GONE
                    textNoFridges.visibility = View.VISIBLE
                } else {
                    recyclerViewFridges.visibility = View.VISIBLE
                    textNoFridges.visibility = View.GONE
                    fridgeAdapter.submitList(fridges)
                }

                state.errorMessage?.let {
                    Toast.makeText(requireContext(), it, Toast.LENGTH_LONG).show()
                    viewModel.clearMessage()
                }
                state.successMessage?.let {
                    Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show()
                    // Emit a fragment result so other screens can refresh their fridge context
                    val switchedId = state.currentFridgeId ?: -1L
                    parentFragmentManager.setFragmentResult(
                        "fridge_switched",
                        android.os.Bundle().apply { putLong("fridgeId", switchedId) }
                    )
                    viewModel.clearMessage()
                }

                if (state.shouldNavigateToMyFridge) {
                    android.util.Log.d("FridgeSettingsFragment", "shouldNavigateToMyFridge=true: navigating to MyFridgeFragment")
                    viewModel.consumeNavigation()
                    requireActivity().supportFragmentManager.beginTransaction()
                        .replace(R.id.fragmentContainer, MyFridgeFragment())
                        .addToBackStack(null)
                        .commit()
                }
            }
        }

        buttonJoin.setOnClickListener {
            val input = EditText(requireContext())
            input.hint = "Enter fridge serial number"
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Join Fridge")
                .setView(input)
                .setPositiveButton("Join") { _, _ ->
                    val code = input.text?.toString()?.trim()
                    android.util.Log.d("FridgeSettingsFragment", "JoinFridge: entered serial=" + (code ?: "<empty>") )
                    if (!code.isNullOrBlank()) {
                        viewModel.joinFridge(code)
                    } else {
                        Toast.makeText(requireContext(), "Serial cannot be empty", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        buttonCreate.setOnClickListener {
            val input = EditText(requireContext())
            input.hint = "Enter new fridge name"
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Create Fridge")
                .setView(input)
                .setPositiveButton("Create") { _, _ ->
                    val name = input.text?.toString()?.trim()
                    android.util.Log.d("FridgeSettingsFragment", "CreateFridge: entered name=" + (name ?: "<empty>") )
                    if (!name.isNullOrBlank()) {
                        viewModel.createFridge(name)
                    } else {
                        Toast.makeText(requireContext(), "Name cannot be empty", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }


    }
}