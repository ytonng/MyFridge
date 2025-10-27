package com.example.myfridge

import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter

class RecipePagerAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {

    override fun getItemCount(): Int = 2

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> RecommendedFragment()
            1 -> FavoriteFragment()
            else -> throw IllegalArgumentException("Invalid position $position")
        }
    }
}