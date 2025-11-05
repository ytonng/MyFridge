package com.example.myfridge

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.content.ClipData
import android.view.View.DragShadowBuilder
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class FridgeItemDisplay(
    val id: Long,
    val name: String,
    val imagePath: String?,
    val isBookmarked: Boolean = false,
    val expiredDate: String? = null
)

class FridgeItemsAdapter(
    private var items: List<FridgeItemDisplay> = emptyList(),
    private val onItemClick: ((FridgeItemDisplay) -> Unit)? = null,
    private val onBookmarkToggle: ((FridgeItemDisplay) -> Unit)? = null
) : RecyclerView.Adapter<FridgeItemsAdapter.ItemViewHolder>() {

    fun submitList(newItems: List<FridgeItemDisplay>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ItemViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_fridge_ingredient, parent, false)
        return ItemViewHolder(view)
    }

    override fun onBindViewHolder(holder: ItemViewHolder, position: Int) {
        val item = items[position]
        holder.name.text = item.name
        // Load image from imagePath if available; fallback to placeholder
        val path = item.imagePath
        if (!path.isNullOrBlank()) {
            Glide.with(holder.image.context)
                .load(path)
                .centerCrop()
                .transition(DrawableTransitionOptions.withCrossFade())
                .placeholder(R.drawable.ic_food)
                .error(R.drawable.ic_food)
                .into(holder.image)
        } else {
            holder.image.setImageResource(R.drawable.ic_food)
        }
        holder.itemView.setOnClickListener { onItemClick?.invoke(item) }

        // Bookmark icon state
        val iconRes = if (item.isBookmarked) R.drawable.ic_bookmark_filled else R.drawable.ic_bookmark_border
        holder.bookmarkButton.setImageResource(iconRes)
        holder.bookmarkButton.setOnClickListener { onBookmarkToggle?.invoke(item) }

        // Expiry color indicator
        val label = holder.expiryLabel
        val expStr = item.expiredDate
        if (expStr.isNullOrBlank()) {
            label.visibility = View.GONE
        } else {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val color: Int? = try {
                val expDate = sdf.parse(expStr)
                val today = Date()
                val days = ((expDate.time - today.time) / (1000 * 60 * 60 * 24)).toInt()
                when {
                    days < 0 -> Color.parseColor("#D32F2F") // expired: red
                    days <= 3 -> Color.parseColor("#F57C00") // 3 days: orange
                    days <= 7 -> Color.parseColor("#FBC02D") // 7 days: yellow
                    else -> Color.parseColor("#388E3C") // later: green
                }
            } catch (e: Exception) { null }

            if (color == null) {
                label.visibility = View.GONE
            } else {
                label.visibility = View.VISIBLE
                val dot = GradientDrawable()
                dot.shape = GradientDrawable.OVAL
                dot.setColor(color)
                dot.setSize(24, 24)
                label.setImageDrawable(dot)
                label.contentDescription = "Expiry indicator"
            }
        }

        // Long-press to start drag for delete
        holder.itemView.setOnLongClickListener {
            val clipData = ClipData.newPlainText("fridge_item_id", item.id.toString())
            val dragShadow = DragShadowBuilder(holder.itemView)
            // Use localState to pass the item directly
            it.startDragAndDrop(clipData, dragShadow, item, 0)
            true
        }
    }

    override fun getItemCount(): Int = items.size

    class ItemViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val image: ImageView = itemView.findViewById(R.id.imageIngredient)
        val name: TextView = itemView.findViewById(R.id.textIngredientName)
        val bookmarkButton: android.widget.ImageButton = itemView.findViewById(R.id.buttonBookmark)
        val expiryLabel: ImageView = itemView.findViewById(R.id.labelExpiryDate)
    }
}