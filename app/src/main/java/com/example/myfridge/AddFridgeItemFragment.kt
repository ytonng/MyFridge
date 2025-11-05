package com.example.myfridge

import android.app.DatePickerDialog
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.example.myfridge.viewmodel.AddItemViewModel
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import android.util.Log
import com.bumptech.glide.Glide
import com.example.myfridge.util.ImageUtils
import com.example.myfridge.data.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.storage.storage
import io.ktor.http.ContentType
// Removed duplicate MimeTypeMap import
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class AddFridgeItemFragment : Fragment() {

    private val viewModel: AddItemViewModel by viewModels()

    private lateinit var buttonClose: ImageButton
    private lateinit var imageFood: ImageView
    private lateinit var inputItemName: AutoCompleteTextView
    private lateinit var pickerQuantity: NumberPicker
    private lateinit var inputQuantityUnit: EditText
    private lateinit var inputExpiredDate: EditText
    private lateinit var inputAddOn: EditText
    private lateinit var textAddBy: TextView
    private lateinit var buttonAddItem: com.google.android.material.button.MaterialButton
    private lateinit var textDescription: TextView
    private lateinit var buttonHeaderBookmark: ImageButton
    private lateinit var buttonScanDate: ImageButton
    private var pendingScan: Boolean = false
    private var lastScanImageUri: Uri? = null

    private var selectedImageUri: Uri? = null
    private var editingItemId: Long? = null
    companion object {
        private const val TAG = "AddFridgeItem"
    }

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        // If we initiated from the Scan button, treat this as a scan-only selection
        if (pendingScan) {
            pendingScan = false
            if (uri != null) {
                lastScanImageUri = uri
                runScanForImage(uri)
            }
            return@registerForActivityResult
        }

        // Otherwise, this selection is for the food image representation
        selectedImageUri = uri
        // Keep scan button enabled regardless; click handler manages picker
        buttonScanDate.isEnabled = true
        if (uri != null) {
            imageFood.setImageURI(uri)
        } else {
            imageFood.setImageResource(android.R.drawable.ic_menu_gallery)
        }
    }

    private suspend fun uploadFridgeImage(uri: Uri): String {
        Log.d(TAG, "uploadFridgeImage start: uri=$uri")
        val user = SupabaseClient.client.auth.currentUserOrNull()
            ?: throw IllegalStateException("Not logged in")
        val inputStream = requireContext().contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Failed to read image")
        val bytes = inputStream.readBytes()
        inputStream.close()
        Log.d(TAG, "Read ${bytes.size} bytes from uri")
        val mime = requireContext().contentResolver.getType(uri) ?: "image/jpeg"
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "jpg"
        val path = "${user.id}-item-${System.currentTimeMillis()}.$ext"
        val bucketName = "fridge_images" // Ensure this bucket exists and is public
        Log.d(TAG, "Preparing upload: mime=$mime, ext=$ext, path=$path, bucket=$bucketName")
        val bucket = SupabaseClient.client.storage.from(bucketName)
        try {
            bucket.upload(path, bytes) {
                upsert = true
                contentType = ContentType.parse(mime)
            }
            Log.d(TAG, "Storage upload succeeded: path=$path")
        } catch (e: Exception) {
            if ((e.message ?: "").contains("bucket", ignoreCase = true) && (e.message ?: "").contains("not", ignoreCase = true)) {
                throw IllegalStateException("Storage bucket '" + bucketName + "' not found or not accessible. Create it in Supabase Storage and set it to public.")
            }
            Log.e(TAG, "Storage upload error: ${e.message}", e)
            throw e
        }
        val url = bucket.publicUrl(path)
        Log.d(TAG, "Generated publicUrl: $url")
        return url
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_add_fridge_item, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        buttonClose = view.findViewById(R.id.buttonClose)
        imageFood = view.findViewById(R.id.imageFood)
        inputItemName = view.findViewById(R.id.inputItemName)
        pickerQuantity = view.findViewById(R.id.pickerQuantity)
        inputQuantityUnit = view.findViewById(R.id.inputQuantityUnit)
        inputExpiredDate = view.findViewById(R.id.inputExpiredDate)
        inputAddOn = view.findViewById(R.id.inputAddOn)
        textAddBy = view.findViewById(R.id.textAddBy)
        buttonAddItem = view.findViewById(R.id.buttonAddItem)
        textDescription = view.findViewById(R.id.textDescription)
        buttonHeaderBookmark = view.findViewById(R.id.buttonHeaderBookmark)
        buttonScanDate = view.findViewById(R.id.buttonScanDate)
        // Keep scan button enabled; if no image, it will open picker
        buttonScanDate.isEnabled = true

        // Detect edit mode via argument
        editingItemId = arguments?.getLong("item_id")
        val isEdit = editingItemId != null
        if (isEdit) {
            textDescription.text = getString(R.string.edit_item_title)
            buttonAddItem.text = getString(R.string.save_changes)
            // Show and initialize header bookmark button state
            buttonHeaderBookmark.visibility = View.VISIBLE
            var currentBookmark = false
            editingItemId?.let { id ->
                viewModel.getItemBookmark(id) { isBookmarked ->
                    currentBookmark = isBookmarked
                    buttonHeaderBookmark.setImageResource(
                        if (isBookmarked) R.drawable.ic_bookmark_filled else R.drawable.ic_bookmark_border
                    )
                }
            }
            buttonHeaderBookmark.setOnClickListener {
                val itemId = editingItemId ?: return@setOnClickListener
                buttonHeaderBookmark.isEnabled = false
                viewModel.toggleBookmark(itemId) { success, message ->
                    buttonHeaderBookmark.isEnabled = true
                    if (success) {
                        currentBookmark = !currentBookmark
                        buttonHeaderBookmark.setImageResource(
                            if (currentBookmark) R.drawable.ic_bookmark_filled else R.drawable.ic_bookmark_border
                        )
                        Toast.makeText(requireContext(), if (currentBookmark) getString(R.string.bookmarked) else getString(R.string.unbookmarked), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(requireContext(), message ?: getString(R.string.toggle_bookmark_failed), Toast.LENGTH_LONG).show()
                    }
                }
            }
            // Load existing item details and prefill
            editingItemId?.let { id ->
                viewModel.loadItemDetails(id) { name, quantity, unit, expired, addOn, imagePath ->
                    name?.let { inputItemName.setText(it, false) }
                    (quantity ?: 1).let { pickerQuantity.value = it.coerceIn(pickerQuantity.minValue, pickerQuantity.maxValue) }
                    unit?.let { inputQuantityUnit.setText(it) }
                    expired?.let { inputExpiredDate.setText(it) }
                    addOn?.let {
                        // Normalize to date-only if backend returns timestamp like "2025-10-30T00:00:00"
                        inputAddOn.setText(it.substringBefore('T'))
                    }
                    if (!imagePath.isNullOrBlank()) {
                        val resolved = ImageUtils.resolveUrl(imagePath)
                        if (!resolved.isNullOrBlank()) {
                            try {
                                Glide.with(requireContext())
                                    .load(resolved)
                                    .centerCrop()
                                    .placeholder(android.R.drawable.ic_menu_gallery)
                                    .error(android.R.drawable.ic_menu_gallery)
                                    .into(imageFood)
                            } catch (_: Exception) {
                                imageFood.setImageResource(android.R.drawable.ic_menu_gallery)
                            }
                        } else {
                            imageFood.setImageResource(android.R.drawable.ic_menu_gallery)
                        }
                    }
                }
            }
        }

        // Close button
        buttonClose.setOnClickListener {
            requireActivity().supportFragmentManager.popBackStack()
        }

        // Image tap to pick
        imageFood.setOnClickListener { pickImage.launch("image/*") }

        // Scan expiry date via ML Kit: always open picker, auto-scan after selection
        buttonScanDate.setOnClickListener {
            pendingScan = true
            pickImage.launch("image/*")
        }

        // Long-press to rescan the last selected scan image (if any)
        buttonScanDate.setOnLongClickListener {
            val uri = lastScanImageUri
            if (uri != null) {
                runScanForImage(uri)
                true
            } else {
                false
            }
        }

        // Quantity picker configuration
        pickerQuantity.minValue = 0
        pickerQuantity.maxValue = 100
        pickerQuantity.value = 1

        // Expired date picker
        inputExpiredDate.setOnClickListener {
            val cal = Calendar.getInstance()
            DatePickerDialog(
                requireContext(),
                { _, year, month, dayOfMonth ->
                    val m = month + 1
                    inputExpiredDate.setText(String.format(Locale.US, "%04d-%02d-%02d", year, m, dayOfMonth))
                },
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH)
            ).show()
        }

        // Add on date prefilled to current date and selectable via date picker
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        inputAddOn.setText(sdf.format(Date()))

        inputAddOn.setOnClickListener {
            val cal = Calendar.getInstance()
            DatePickerDialog(
                requireContext(),
                { _, year, month, dayOfMonth ->
                    val m = month + 1
                    inputAddOn.setText(String.format(Locale.US, "%04d-%02d-%02d", year, m, dayOfMonth))
                },
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH)
            ).show()
        }

        // Username display (non-editable)
        textAddBy.text = viewModel.resolveUsername()

        // Ingredient suggestions
        viewModel.loadIngredients { names ->
            val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, names)
            inputItemName.setAdapter(adapter)
            inputItemName.threshold = 1
        }

        // Submit (add or update)
        buttonAddItem.setOnClickListener {
            val name = inputItemName.text?.toString()?.trim().orEmpty()
            val qty = pickerQuantity.value
            val unit = inputQuantityUnit.text?.toString()?.trim().orEmpty()
            val expiredDate = inputExpiredDate.text?.toString()?.trim().orEmpty()
            val addOnText = inputAddOn.text?.toString()?.trim().orEmpty()
            val uri = selectedImageUri

            if (name.isBlank()) {
                Toast.makeText(requireContext(), getString(R.string.item_name_required), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (unit.isBlank()) {
                Toast.makeText(requireContext(), getString(R.string.quantity_unit_required), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (expiredDate.isBlank()) {
                Toast.makeText(requireContext(), getString(R.string.expired_date_required), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Validate expired date must be after today
            try {
                val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                sdf.isLenient = false
                val exp = sdf.parse(expiredDate)
                val today = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.time
                if (exp == null || !exp.after(today)) {
                    Toast.makeText(requireContext(), getString(R.string.expired_date_after_today_required), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), getString(R.string.expired_date_required), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (addOnText.isBlank()) {
                Toast.makeText(requireContext(), getString(R.string.add_on_required), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            buttonAddItem.isEnabled = false
            val editingId = editingItemId
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    var uploadedUrl: String? = null
                    if (uri != null) {
                        Toast.makeText(requireContext(), getString(R.string.ingredient_image) + ": Uploading...", Toast.LENGTH_SHORT).show()
                        Log.d(TAG, "Uploading fridge item image start: uri=$uri")
                        uploadedUrl = uploadFridgeImage(uri)
                        Log.d(TAG, "Fridge item image upload done. url=$uploadedUrl")
                    }

                    if (editingId != null) {
                        viewModel.updateItem(
                            itemId = editingId,
                            itemName = name,
                            quantity = qty,
                            quantityUnit = unit,
                            expiredDate = expiredDate,
                            addOnDate = addOnText,
                            imagePath = uploadedUrl
                        ) { success, message ->
                            buttonAddItem.isEnabled = true
                            if (success) {
                                Toast.makeText(requireContext(), getString(R.string.update_item_success), Toast.LENGTH_SHORT).show()
                                requireActivity().supportFragmentManager.popBackStack()
                            } else {
                                Toast.makeText(requireContext(), message ?: getString(R.string.update_item_failed), Toast.LENGTH_LONG).show()
                            }
                        }
                    } else {
                        viewModel.addItem(
                            itemName = name,
                            quantity = qty,
                            quantityUnit = unit,
                            expiredDate = expiredDate,
                            addOnDate = addOnText,
                            imagePath = uploadedUrl
                        ) { success, message ->
                            buttonAddItem.isEnabled = true
                            if (success) {
                                Toast.makeText(requireContext(), getString(R.string.add_item_success), Toast.LENGTH_SHORT).show()
                                requireActivity().supportFragmentManager.popBackStack()
                            } else {
                                Toast.makeText(requireContext(), message ?: getString(R.string.add_item_failed), Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Fridge item image upload failed: ${e.message}", e)
                    buttonAddItem.isEnabled = true
                    Toast.makeText(requireContext(), "Image upload failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun runScanForImage(uri: Uri) {
        try {
            val image = InputImage.fromFilePath(requireContext(), uri)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val text = visionText.text
                    val found = extractDate(text)
                    if (found != null) {
                        inputExpiredDate.setText(found)
                        Toast.makeText(requireContext(), getString(R.string.expired_date_label) + ": " + found, Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(requireContext(), getString(R.string.expired_date_required), Toast.LENGTH_SHORT).show()
                    }
                }
                .addOnFailureListener { e ->
                    Toast.makeText(requireContext(), "Scan failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Scan error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun extractDate(text: String): String? {
        // Try patterns commonly printed on packaging: YYYY-MM-DD, DD/MM/YYYY, DD-MM-YYYY, MM/DD/YYYY
        val patterns = listOf(
            Regex("(20\\d{2})[-/](0?[1-9]|1[0-2])[-/](0?[1-9]|[12]\\d|3[01])"), // yyyy-mm-dd or yyyy/mm/dd
            Regex("(0?[1-9]|[12]\\d|3[01])[-/](0?[1-9]|1[0-2])[-/](20\\d{2})"),     // dd-mm-yyyy or dd/mm/yyyy
            Regex("(0?[1-9]|1[0-2])[-/](0?[1-9]|[12]\\d|3[01])[-/](20\\d{2})")      // mm-dd-yyyy or mm/dd/yyyy
        )
        for (pattern in patterns) {
            val match = pattern.find(text)
            if (match != null) {
                val parts = match.groupValues.drop(1)
                return when (pattern) {
                    patterns[0] -> String.format(Locale.US, "%04d-%02d-%02d", parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
                    patterns[1] -> String.format(Locale.US, "%04d-%02d-%02d", parts[2].toInt(), parts[1].toInt(), parts[0].toInt())
                    patterns[2] -> String.format(Locale.US, "%04d-%02d-%02d", parts[2].toInt(), parts[0].toInt(), parts[1].toInt())
                    else -> null
                }
            }
        }
        return null
    }
}