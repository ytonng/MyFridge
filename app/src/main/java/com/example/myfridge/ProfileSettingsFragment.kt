package com.example.myfridge

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import com.google.android.material.imageview.ShapeableImageView
import android.widget.Toast
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import android.webkit.MimeTypeMap
import io.ktor.http.ContentType
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.example.myfridge.data.SupabaseClient
import com.example.myfridge.viewmodel.SettingsViewModel
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ProfileSettingsFragment : Fragment() {

    private val viewModel: SettingsViewModel by viewModels()
    private lateinit var imageAvatar: ShapeableImageView
    private var pickedImageUri: Uri? = null
    companion object {
        private const val TAG = "ProfileSettings"
    }
    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            pickedImageUri = uri
            // Preview selected image immediately
            imageAvatar.setImageURI(uri)
            Log.d(TAG, "Picked image uri: $uri")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_profile_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val swipeRefresh = view.findViewById<SwipeRefreshLayout>(R.id.swipeRefreshProfile)
        imageAvatar = view.findViewById(R.id.imageAvatarPreview)
        val emailInput = view.findViewById<EditText>(R.id.settingsEmailInput)
        val usernameInput = view.findViewById<EditText>(R.id.settingsUsernameInput)
        val buttonSave = view.findViewById<Button>(R.id.buttonSaveProfile)
        val buttonChangePassword = view.findViewById<Button>(R.id.buttonChangePassword)

        swipeRefresh.setOnRefreshListener { viewModel.load() }
        viewModel.load()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                swipeRefresh.isRefreshing = state.isLoading

                // Populate fields
                emailInput.setText(state.email ?: "")
                usernameInput.setText(state.username ?: "")

                // Load avatar preview
                val url = state.avatarUrl
                if (!url.isNullOrBlank()) {
                    Glide.with(this@ProfileSettingsFragment)
                        .load(url)
                        .transition(DrawableTransitionOptions.withCrossFade())
                        .into(imageAvatar)
                } else {
                    imageAvatar.setImageResource(android.R.drawable.sym_def_app_icon)
                }

                state.errorMessage?.let {
                    Toast.makeText(requireContext(), it, Toast.LENGTH_LONG).show()
                    viewModel.clearMessage()
                }
                state.successMessage?.let {
                    Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show()
                    viewModel.clearMessage()
                }

                // Toggle Change Password visibility based on auth provider
                buttonChangePassword.visibility = if (state.canChangePassword) View.VISIBLE else View.GONE
            }
        }

        imageAvatar.setOnClickListener {
            // Pick image from gallery
            pickImageLauncher.launch("image/*")
        }

        buttonSave.setOnClickListener {
            val username = usernameInput.text?.toString()
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    Log.d(TAG, "Save profile tapped. username=$username, pickedUri=$pickedImageUri")
                    var uploadedUrl: String? = null
                    val uri = pickedImageUri
                    if (uri != null) {
                        Toast.makeText(requireContext(), "Uploading avatar...", Toast.LENGTH_SHORT).show()
                        Log.d(TAG, "Uploading avatar start: uri=$uri")
                        uploadedUrl = uploadAvatar(uri)
                        Log.d(TAG, "Avatar upload done. url=$uploadedUrl")
                    }
                    Log.d(TAG, "Calling viewModel.saveProfile with username=$username, avatarUrl=$uploadedUrl")
                    viewModel.saveProfile(username, uploadedUrl)
                } catch (e: Exception) {
                    Log.e(TAG, "Avatar upload failed: ${e.message}", e)
                    Toast.makeText(requireContext(), "Avatar upload failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        buttonChangePassword.setOnClickListener {
            val input = EditText(requireContext())
            input.hint = "New password"
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Change Password")
                .setView(input)
                .setPositiveButton("Update") { _, _ ->
                    val newPass = input.text?.toString()?.trim()
                    if (!newPass.isNullOrBlank()) {
                        viewModel.changePassword(newPass)
                    } else {
                        Toast.makeText(requireContext(), "Password cannot be empty", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private suspend fun uploadAvatar(uri: Uri): String {
        Log.d(TAG, "uploadAvatar start: uri=$uri")
        val user = SupabaseClient.client.auth.currentUserOrNull()
            ?: throw IllegalStateException("Not logged in")
        val inputStream = requireContext().contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Failed to read image")
        val bytes = inputStream.readBytes()
        inputStream.close()
        Log.d(TAG, "Read ${bytes.size} bytes from uri")
        val mime = requireContext().contentResolver.getType(uri) ?: "image/jpeg"
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "jpg"
        val path = "${user.id}-${System.currentTimeMillis()}.$ext"
        val bucketName = "avatars" // Ensure this bucket exists and is public
        Log.d(TAG, "Preparing upload: mime=$mime, ext=$ext, path=$path, bucket=$bucketName")
        val bucket = SupabaseClient.client.storage.from(bucketName)
        try {
            bucket.upload(path, bytes) {
                upsert = true
                contentType = ContentType.parse(mime)
            }
            Log.d(TAG, "Storage upload succeeded: path=$path")
        } catch (e: Exception) {
            // Common cause: bucket doesn't exist or is not public
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
}