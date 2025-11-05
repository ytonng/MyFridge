package com.example.myfridge

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import com.example.myfridge.databinding.ActivityBottomNavHostBinding
import me.ibrahimsn.lib.OnItemSelectedListener

class BottomNavHostActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBottomNavHostBinding
    private var username: String = "User"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBottomNavHostBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Get username from intent
        username = intent.getStringExtra("USERNAME") ?: "User"

        // Apply window insets
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setupBottomNavigation()

        // Load initial fragment (Dashboard)
        loadFragment(DashboardFragment.newInstance(username))
    }

    private fun setupBottomNavigation() {
        binding.bottomBar.onItemSelectedListener = object : OnItemSelectedListener {
            override fun onItemSelect(pos: Int): Boolean {
                when (pos) {
                    0 -> loadFragment(DashboardFragment.newInstance(username))
                    1 -> loadFragment(RecipeFragment())
                    2 -> loadFragment(ViewsFragment())
                    3 -> loadFragment(MyFridgeFragment())
                    4 -> loadFragment(SettingsFragment())
                }
                return true
            }
        }
    }

    private fun loadFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }

    /**
     * Programmatically navigate to the My Fridge tab and highlight it in the bottom bar.
     */
    fun navigateToMyFridge() {
        // Highlight the My Fridge item (index 3) and load its fragment
        binding.bottomBar.itemActiveIndex = 3
        loadFragment(MyFridgeFragment())
    }
}