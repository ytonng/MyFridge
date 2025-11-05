package com.example.myfridge

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.example.myfridge.databinding.FragmentDashboardBinding
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.GridLayoutManager
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import android.util.Log
import com.example.myfridge.viewmodel.DashboardViewModel
import com.example.myfridge.viewmodel.RecipeViewModel
import com.example.myfridge.RecipeAdapter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.example.myfridge.util.ImageUtils
import com.denzcoskun.imageslider.constants.ScaleTypes
import com.denzcoskun.imageslider.models.SlideModel

class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!
    private var username: String = "User"
    private val viewModel: DashboardViewModel by viewModels()
    private lateinit var recipeAdapter: RecipeAdapter
    private var refreshInProgress: Boolean = false
    private var lastTotalItems: Int = 0
    private var lastSoonCount: Int = 0
    private var lastExpiredCount: Int = 0
    private var lastNearestSize: Int = 0
    private var lastTipsSize: Int = 0
    private var lastNotifiedSoonCount: Int = -1
    private var refreshStartAt: Long = 0L
    private val maxRefreshSpinMs: Long = 5000L
    private var refreshTimeoutJob: kotlinx.coroutines.Job? = null
    private val minNotificationIntervalMs = java.util.concurrent.TimeUnit.MINUTES.toMillis(1)
    private val prefs by lazy { requireContext().getSharedPreferences("expiry_notifier", android.content.Context.MODE_PRIVATE) }
    private val requestNotificationsPermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            // Optionally trigger a notification immediately if there are expiring items
            val current = viewModel.uiState.value
            val count = current.expiringSoonCount
            if (count > 0) {
                try {
                    com.example.myfridge.util.ExpiryNotifier.notifyExpiringSoon(
                        requireContext(), count, current.nearestExpiring
                    )
                } catch (_: Exception) {}
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        username = arguments?.getString(ARG_USERNAME) ?: "User"

        // Request POST_NOTIFICATIONS runtime permission on Android 13+
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                    requireContext(), android.Manifest.permission.POST_NOTIFICATIONS
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (!granted) {
                    requestNotificationsPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        } catch (_: Exception) {}

        // Observe dashboard UI state and set header, avatar, and date
        // Ensure we load the current user so avatarUrl is populated
        try { viewModel.loadCurrentUser() } catch (_: Exception) {}

        viewLifecycleOwner.lifecycleScope.launchWhenStarted {
            viewModel.uiState.collectLatest { state ->
                Log.d("DashboardFragment", "UI State updated - isAuthenticated: ${state.isAuthenticated}, username: '${state.username}', avatarUrl: '${state.avatarUrl}'")
                binding.welcomeText.text = "What's in your fridge today?"
                binding.textDate.text = formatToday()

                val avatar = state.avatarUrl
                Log.d("DashboardFragment", "Avatar URL from state: '$avatar'")
                if (!avatar.isNullOrBlank()) {
                    val resolved = ImageUtils.resolveUrl(avatar)
                    Log.d("DashboardFragment", "Resolved avatar URL: '$resolved'")
                    Glide.with(this@DashboardFragment)
                        .load(resolved)
                        .transition(DrawableTransitionOptions.withCrossFade())
                        .placeholder(R.drawable.ic_user)
                        .error(R.drawable.ic_user)
                        .listener(object : com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable> {
                            override fun onLoadFailed(
                                e: com.bumptech.glide.load.engine.GlideException?,
                                model: Any?,
                                target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>,
                                isFirstResource: Boolean
                            ): Boolean {
                                Log.e("DashboardFragment", "Glide failed to load avatar: $e")
                                return false // Let Glide handle the error (show placeholder/error drawable)
                            }

                            override fun onResourceReady(
                                resource: android.graphics.drawable.Drawable,
                                model: Any,
                                target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>,
                                dataSource: com.bumptech.glide.load.DataSource,
                                isFirstResource: Boolean
                            ): Boolean {
                                Log.d("DashboardFragment", "Glide successfully loaded avatar")
                                // Ensure no tint overlays the loaded avatar image
                                try {
                                    binding.avatarIcon.imageTintList = null
                                } catch (_: Exception) {}
                                return false // Let Glide handle the success
                            }
                        })
                        .into(binding.avatarIcon)
                    Log.d("DashboardFragment", "Glide load initiated for avatar")
                } else {
                    Log.d("DashboardFragment", "No avatar URL available, keeping default icon")
                    // Keep default icon if no avatar URL
                }

                // Bind tips to ImageSlider (without titles)
                Log.d("DashboardFragment", "UI render start: total=${state.totalItemsCount} soon=${state.expiringSoonCount} expired=${state.expiredItemsCount} nearestSize=${state.nearestExpiring.size}")
                val imageList = ArrayList<SlideModel>()
                state.tips.forEach { tip ->
                    val ref = tip.imageRef
                    val resolved = if (!ref.isNullOrBlank()) ImageUtils.resolveUrl(ref) else null
                    if (!resolved.isNullOrBlank()) {
                        imageList.add(SlideModel(resolved, ScaleTypes.CENTER_CROP))
                    }
                }
                if (imageList.isNotEmpty()) {
                    binding.imageSlider.setImageList(imageList, ScaleTypes.CENTER_CROP)
                }

                // Bind summary counts
                binding.textTotalItems.text = "Total items: ${state.totalItemsCount}"
                binding.textExpiringSoon.text = "Expiring soon: ${state.expiringSoonCount}"
                binding.textExpiredItems.text = "Expired: ${state.expiredItemsCount}"
                Log.d("DashboardFragment", "Summary bound: total=${state.totalItemsCount} soon=${state.expiringSoonCount} expired=${state.expiredItemsCount}")

                // Notify user about items expiring soon with a minimum interval throttle
                try {
                    val now = System.currentTimeMillis()
                    val lastAt = prefs.getLong("last_notified_at", 0L)
                    val lastCount = prefs.getInt("last_notified_count", -1)
                    val count = state.expiringSoonCount
                    val managerCompat = androidx.core.app.NotificationManagerCompat.from(requireContext())
                    val enabled = managerCompat.areNotificationsEnabled()
                    val canNotify = enabled && count > 0 && (count != lastCount || (now - lastAt) >= minNotificationIntervalMs)
                    if (canNotify) {
                        com.example.myfridge.util.ExpiryNotifier.notifyExpiringSoon(
                            requireContext(),
                            count,
                            state.nearestExpiring
                        )
                        prefs.edit()
                            .putLong("last_notified_at", now)
                            .putInt("last_notified_count", count)
                            .apply()
                        lastNotifiedSoonCount = count
                    }
                } catch (_: Exception) {}

                // Populate nearest-to-expiry list (up to 3)
                val container = binding.containerNearestExpiring
                container.removeAllViews()
                val inflater = LayoutInflater.from(requireContext())
                state.nearestExpiring.forEachIndexed { idx, item ->
                    Log.d("DashboardFragment", "Nearest[$idx]: id=${item.id} name=${item.name} expiredDate=${item.expiredDate}")
                    val row = inflater.inflate(R.layout.item_nearest_expiry, container, false)
                    val image = row.findViewById<android.widget.ImageView>(R.id.imageItemThumb)
                    val name = row.findViewById<android.widget.TextView>(R.id.textItemName)
                    val date = row.findViewById<android.widget.TextView>(R.id.textExpiryDate)
                    name.text = item.name
                    date.text = item.expiredDate ?: ""
                    val path = item.imagePath
                    if (!path.isNullOrBlank()) {
                        Glide.with(this@DashboardFragment)
                            .load(path)
                            .centerCrop()
                            .transition(DrawableTransitionOptions.withCrossFade())
                            .placeholder(R.drawable.ic_food)
                            .error(R.drawable.ic_food)
                            .into(image)
                    } else {
                        image.setImageResource(R.drawable.ic_food)
                    }
                    container.addView(row)
                }

                // Toggle empty placeholder when no nearest-expiry items
                val isNearestEmpty = state.nearestExpiring.isEmpty()
                binding.textNearestEmpty.visibility = if (isNearestEmpty) View.VISIBLE else View.GONE
                Log.d("DashboardFragment", "Nearest placeholder visibility: ${if (isNearestEmpty) "VISIBLE" else "GONE"}")

                // Stop swipe refresh when we detect content updated
                if (refreshInProgress) {
                    val contentChanged =
                        (state.totalItemsCount != lastTotalItems) ||
                        (state.expiringSoonCount != lastSoonCount) ||
                        (state.expiredItemsCount != lastExpiredCount) ||
                        (state.nearestExpiring.size != lastNearestSize) ||
                        (state.tips.size != lastTipsSize)
                    val timedOut = (System.currentTimeMillis() - refreshStartAt) >= maxRefreshSpinMs
                    if (contentChanged || timedOut) {
                        if (timedOut && !contentChanged) {
                            Log.d("DashboardFragment", "SwipeRefresh: timeout reached (${maxRefreshSpinMs}ms), stopping refresh spinner")
                        } else {
                            Log.d("DashboardFragment", "SwipeRefresh: content updated, stopping refresh spinner")
                        }
                        binding.swipeRefreshDashboard.isRefreshing = false
                        refreshInProgress = false
                        // Cancel any pending timeout job
                        try { refreshTimeoutJob?.cancel() } catch (_: Exception) {}
                    }
                    // Update snapshot for next comparison
                    lastTotalItems = state.totalItemsCount
                    lastSoonCount = state.expiringSoonCount
                    lastExpiredCount = state.expiredItemsCount
                    lastNearestSize = state.nearestExpiring.size
                    lastTipsSize = state.tips.size
                }
            }
        }

        // Listen for fridge switch events to refresh dashboard context
        parentFragmentManager.setFragmentResultListener("fridge_switched", viewLifecycleOwner) { _, bundle ->
            val fridgeId = bundle.getLong("fridgeId", -1L)
            Log.d("DashboardFragment", "fridge_switched received fridgeId=" + fridgeId)
            viewModel.loadFridgeContext()
        }

        // Initial loads
        viewModel.loadCurrentUser()
        viewModel.loadFridgeContext()
        viewModel.loadTips()

        // Setup swipe-to-refresh to reload tips and fridge summary
        binding.swipeRefreshDashboard.setOnRefreshListener {
            try {
                refreshInProgress = true
                refreshStartAt = System.currentTimeMillis()
                // Schedule a hard stop in case uiState doesn't emit
                try { refreshTimeoutJob?.cancel() } catch (_: Exception) {}
                refreshTimeoutJob = viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        kotlinx.coroutines.delay(maxRefreshSpinMs)
                        if (refreshInProgress) {
                            Log.d("DashboardFragment", "SwipeRefresh: timeout job stopping refresh spinner after ${maxRefreshSpinMs}ms")
                            binding.swipeRefreshDashboard.isRefreshing = false
                            refreshInProgress = false
                        }
                    } catch (_: Exception) {}
                }
                // Capture snapshot before refresh
                val current = viewModel.uiState.value
                lastTotalItems = current.totalItemsCount
                lastSoonCount = current.expiringSoonCount
                lastExpiredCount = current.expiredItemsCount
                lastNearestSize = current.nearestExpiring.size
                lastTipsSize = current.tips.size

                // Trigger data reload
                viewModel.loadTips()
                val fridgeId = current.currentFridgeId
                if (fridgeId != null) {
                    viewModel.loadFridgeItemSummary(fridgeId)
                } else {
                    // Resolve fridge context if missing, which will load summary afterward
                    viewModel.loadFridgeContext()
                }
            } catch (e: Exception) {
                Log.w("DashboardFragment", "SwipeRefresh: failed to start refresh: ${e.message}")
                binding.swipeRefreshDashboard.isRefreshing = false
                refreshInProgress = false
            }
        }

        // Setup recommended recipes grid (1 row x 2 columns)
        val recipesViewModel = ViewModelProvider(requireActivity())[RecipeViewModel::class.java]
        recipeAdapter = RecipeAdapter(
            onFavoriteClick = { recipeId ->
                try { recipesViewModel.toggleFavorite(recipeId) } catch (_: Exception) {}
            },
            onRecipeClick = { recipeId ->
                try {
                    recipesViewModel.trackRecipeView(recipeId)
                    RecipeDetailActivity.start(requireContext(), recipeId.toLong())
                } catch (_: Exception) {
                    // Ignore bad id
                }
            }
        )
        binding.recyclerRecommendedDashboard.layoutManager = GridLayoutManager(requireContext(), 2)
        binding.recyclerRecommendedDashboard.adapter = recipeAdapter

        // Observe recommended recipes and render top two based on availability, with sensible fallbacks
        recipesViewModel.recommendedRecipes.observe(viewLifecycleOwner) { list ->
            val sorted = list
            val withAvail = sorted.filter { (it.available_count ?: 0) > 0 }
            val displayList = when {
                withAvail.size >= 2 -> withAvail.take(2)
                withAvail.size == 1 -> (withAvail + sorted.filter { (it.available_count ?: 0) == 0 })
                    .distinctBy { it.id }
                    .take(2)
                else -> sorted.take(2)
            }
            recipeAdapter.submitList(displayList)
        }

        // Add Item button navigates to AddFridgeItemFragment
        binding.buttonAddItem.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, AddFridgeItemFragment())
                .addToBackStack(null)
                .commit()
        }

        // View More navigates to full fridge items list
        binding.buttonViewMore.setOnClickListener {
            (activity as? BottomNavHostActivity)?.navigateToMyFridge() ?: run {
                parentFragmentManager.beginTransaction()
                    .replace(R.id.fragmentContainer, MyFridgeFragment())
                    .addToBackStack(null)
                    .commit()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_USERNAME = "username"

        @JvmStatic
        fun newInstance(username: String) =
            DashboardFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_USERNAME, username)
                }
            }
    }

    private fun formatToday(): String {
        return try {
            val sdf = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault())
            sdf.format(Date())
        } catch (e: Exception) {
            // Fallback if formatter fails
            "Today"
        }
    }
}