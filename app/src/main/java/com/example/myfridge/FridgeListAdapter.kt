package com.example.myfridge

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.myfridge.viewmodel.FridgeWithMembers

class FridgeListAdapter(
    private var fridges: List<FridgeWithMembers> = emptyList(),
    private val onSwitchToFridge: (FridgeWithMembers) -> Unit
) : RecyclerView.Adapter<FridgeListAdapter.FridgeViewHolder>() {

    fun submitList(newFridges: List<FridgeWithMembers>) {
        fridges = newFridges
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FridgeViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_fridge, parent, false)
        return FridgeViewHolder(view)
    }

    override fun onBindViewHolder(holder: FridgeViewHolder, position: Int) {
        holder.bind(fridges[position])
    }

    override fun getItemCount(): Int = fridges.size

    inner class FridgeViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textFridgeName: TextView = itemView.findViewById(R.id.textFridgeName)
        private val textFridgeSerial: TextView = itemView.findViewById(R.id.textFridgeSerial)
        private val textCurrentBadge: TextView = itemView.findViewById(R.id.textCurrentBadge)
        private val iconExpand: ImageView = itemView.findViewById(R.id.iconExpand)
        private val layoutFridgeHeader: LinearLayout = itemView.findViewById(R.id.layoutFridgeHeader)
        private val layoutMembers: LinearLayout = itemView.findViewById(R.id.layoutMembers)
        private val containerMembers: LinearLayout = itemView.findViewById(R.id.containerMembers)
        private val buttonSwitchToFridge: Button = itemView.findViewById(R.id.buttonSwitchToFridge)

        private var isExpanded = false

        fun bind(fridge: FridgeWithMembers) {
            textFridgeName.text = fridge.name
            textFridgeSerial.text = "Serial: ${fridge.serialNumber}"
            
            // Show/hide current badge
            textCurrentBadge.visibility = if (fridge.isCurrentFridge) View.VISIBLE else View.GONE
            
            // Set up click listener for header to expand/collapse
            layoutFridgeHeader.setOnClickListener {
                isExpanded = !isExpanded
                updateExpandedState()
            }
            
            // Populate members
            containerMembers.removeAllViews()
            fridge.members.forEach { memberName ->
                val memberView = LayoutInflater.from(itemView.context)
                    .inflate(android.R.layout.simple_list_item_1, containerMembers, false) as TextView
                memberView.text = "• $memberName"
                memberView.textSize = 14f
                memberView.setPadding(16, 8, 16, 8)
                containerMembers.addView(memberView)
            }
            
            // If no members, show placeholder
            if (fridge.members.isEmpty()) {
                val noMembersView = TextView(itemView.context)
                noMembersView.text = "• No members"
                noMembersView.textSize = 14f
                noMembersView.setPadding(16, 8, 16, 8)
                noMembersView.setTextColor(itemView.context.getColor(android.R.color.darker_gray))
                containerMembers.addView(noMembersView)
            }
            
            // Set up switch button
            buttonSwitchToFridge.setOnClickListener {
                onSwitchToFridge(fridge)
            }
            
            // Hide switch button if this is the current fridge
            buttonSwitchToFridge.visibility = if (fridge.isCurrentFridge) View.GONE else View.VISIBLE
            
            // Initialize expanded state
            updateExpandedState()
        }
        
        private fun updateExpandedState() {
            layoutMembers.visibility = if (isExpanded) View.VISIBLE else View.GONE
            iconExpand.rotation = if (isExpanded) 180f else 0f
        }
    }
}