package com.example.myfridge

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.ui.PlayerView
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.postgrest
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.Serializable
import com.example.myfridge.data.SupabaseClient

@Serializable
data class PairedDevice(
    val id: Long,
    val fridge_id: Long,
    val device_serial: String,
    val stream_name: String,
    val region: String? = "ap-southeast-1",
    val created_at: String? = null,
)

@Serializable
data class FridgeInfo(
    val id: Long,
    val name: String?,
    val serial_number: String
)

class ViewsFragment : Fragment() {
    private val auth: Auth get() = SupabaseClient.client.auth

    private lateinit var fridgeNameText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var addCameraButton: Button
    private lateinit var spinnerDevices: Spinner
    private lateinit var viewStreamButton: Button
    private lateinit var playerView: PlayerView

    private lateinit var exoPlayer: ExoPlayer

    // Store current fridge ID
    private var currentFridgeId: Long? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_views, container, false)

        fridgeNameText = view.findViewById(R.id.textFridgeName)
        progress = view.findViewById(R.id.profileProgress)
        addCameraButton = view.findViewById(R.id.buttonAdd)
        spinnerDevices = view.findViewById(R.id.dropdownDeviceSerial)
        viewStreamButton = view.findViewById(R.id.buttonViewStream)
        playerView = view.findViewById(R.id.playerView)

        exoPlayer = ExoPlayer.Builder(requireContext()).build()
        playerView.player = exoPlayer

        loadCurrentFridge()

        addCameraButton.setOnClickListener { generatePairingQRCode() }

        viewStreamButton.setOnClickListener {
            val selectedDevice = spinnerDevices.selectedItem?.toString()
            if (selectedDevice.isNullOrEmpty()) {
                Toast.makeText(requireContext(), "Please select a device", Toast.LENGTH_SHORT).show()
            } else {
                fetchAndPlayStream(selectedDevice)
            }
        }

        return view
    }

    private fun loadCurrentFridge() {
        progress.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val userId = auth.currentUserOrNull()?.id ?: return@launch

                // Get user's first fridge (you can modify this to select specific fridge)
                val fridgeResponse = SupabaseClient.client.postgrest["fridge_members"]
                    .select {
                        filter {
                            eq("user_id", userId)
                        }
                    }

                val fridgeMembers = fridgeResponse.decodeList<FridgeMemberResponse>()

                if (fridgeMembers.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        progress.visibility = View.GONE
                        fridgeNameText.text = "No fridge found"
                        Toast.makeText(requireContext(), "Please join a fridge first", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                // Get first fridge details
                val fridgeId = fridgeMembers[0].fridge_id
                currentFridgeId = fridgeId

                val fridgeInfoResponse = SupabaseClient.client.postgrest["fridge"]
                    .select {
                        filter {
                            eq("id", fridgeId)
                        }
                    }

                val fridges = fridgeInfoResponse.decodeList<FridgeInfo>()
                val fridgeName = fridges.firstOrNull()?.name ?: "Fridge #$fridgeId"

                withContext(Dispatchers.Main) {
                    fridgeNameText.text = fridgeName
                    progress.visibility = View.GONE
                }

                // Load cameras for this fridge
                loadFridgeCameras(fridgeId)

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progress.visibility = View.GONE
                    fridgeNameText.text = "Error loading fridge"
                    Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun loadFridgeCameras(fridgeId: Long) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                Log.d("SupabaseDebug", "Loading cameras for fridgeId = $fridgeId")

                val response = SupabaseClient.client.postgrest["paired_devices"]
                    .select {
                        filter {
                            eq("fridge_id", fridgeId)
                        }
                    }

                Log.d("SupabaseDebug", "Raw response: ${response.data}")

                val devices = response.decodeList<PairedDevice>()
                val serials = devices.map { it.device_serial }

                withContext(Dispatchers.Main) {
                    if (serials.isEmpty()) {
                        Toast.makeText(requireContext(), "No cameras paired to this fridge", Toast.LENGTH_SHORT).show()
                    }
                    val adapter = ArrayAdapter(
                        requireContext(),
                        android.R.layout.simple_spinner_item,
                        serials
                    )
                    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                    spinnerDevices.adapter = adapter
                }

            } catch (e: Exception) {
                Log.e("SupabaseDebug", "Error loading cameras", e)
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        requireContext(),
                        "Error fetching cameras: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun generatePairingQRCode() {
        val fridgeId = currentFridgeId
        if (fridgeId == null) {
            Toast.makeText(requireContext(), "No fridge selected", Toast.LENGTH_SHORT).show()
            return
        }

        progress.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val session = auth.currentSessionOrNull()
                val accessToken = session?.accessToken

                if (accessToken.isNullOrBlank()) {
                    withContext(Dispatchers.Main) {
                        progress.visibility = View.GONE
                        Toast.makeText(
                            requireContext(),
                            "Session expired. Please sign in again.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return@launch
                }

                val httpResponse = SupabaseClient.client.functions.invoke(
                    function = "createPairingToken",
                    body = buildJsonObject { put("fridge_id", fridgeId) },
                    headers = Headers.build {
                        append(HttpHeaders.ContentType, "application/json")
                        append(HttpHeaders.Authorization, "Bearer $accessToken")
                    }
                )

                val bodyText = httpResponse.bodyAsText()
                val result = JSONObject(bodyText)
                val token = result.optString("token")
                val expiresInSeconds = result.optInt("expires_in_seconds", 300)

                withContext(Dispatchers.Main) {
                    progress.visibility = View.GONE
                    if (token.isNotEmpty()) {
                        showQRCodeDialog(token, expiresInSeconds)
                    } else {
                        Toast.makeText(
                            requireContext(),
                            "Error generating QR: ${result.optString("error", bodyText)}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progress.visibility = View.GONE
                    Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showQRCodeDialog(token: String, expiresInSeconds: Int) {
        try {
            val qrBitmap = generateQRCode(token)
            if (qrBitmap == null) {
                Toast.makeText(requireContext(), "Failed to generate QR code", Toast.LENGTH_SHORT).show()
                return
            }

            val imageView = ImageView(requireContext()).apply {
                setImageBitmap(qrBitmap)
                setPadding(32, 32, 32, 32)
            }

            AlertDialog.Builder(requireContext())
                .setTitle("Scan with Camera")
                .setMessage("Point your RPi camera at this QR to pair\n(Expires in $expiresInSeconds seconds)")
                .setView(imageView)
                .setPositiveButton("Done") { dialog, _ ->
                    dialog.dismiss()
                    // Refresh camera list after pairing
                    currentFridgeId?.let { loadFridgeCameras(it) }
                }
                .show()

        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Error showing QR code: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun generateQRCode(token: String): android.graphics.Bitmap? {
        return try {
            // Only include token in QR code
            val jsonPayload = JSONObject().apply {
                put("token", token)
            }.toString()

            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(jsonPayload, BarcodeFormat.QR_CODE, 512, 512)
            val width = bitMatrix.width
            val height = bitMatrix.height
            val bitmap = android.graphics.Bitmap.createBitmap(
                width,
                height,
                android.graphics.Bitmap.Config.RGB_565
            )

            for (x in 0 until width) {
                for (y in 0 until height) {
                    bitmap.setPixel(
                        x,
                        y,
                        if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                    )
                }
            }
            bitmap
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Error generating QR: ${e.message}", Toast.LENGTH_SHORT).show()
            null
        }
    }

    private fun fetchAndPlayStream(deviceSerial: String) {
        progress.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val requestBody = JSONObject().apply {
                    put("device_serial", deviceSerial)
                    // Removed user_id - not needed anymore
                }.toString()

                val url = URL("https://w2d82zw6t0.execute-api.ap-southeast-1.amazonaws.com/prod/get-stream-url")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.outputStream.use { it.write(requestBody.toByteArray()) }

                val responseText = conn.inputStream.bufferedReader().readText()
                val jsonResponse = JSONObject(responseText)
                val playbackUrl = jsonResponse.optString("playback_url")

                withContext(Dispatchers.Main) {
                    progress.visibility = View.GONE
                    if (playbackUrl.isNotEmpty()) {
                        playVideo(playbackUrl)
                    } else {
                        Toast.makeText(
                            requireContext(),
                            "Invalid playback URL",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progress.visibility = View.GONE
                    Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun playVideo(url: String) {
        try {
            exoPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(url)))
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
            playerView.visibility = View.VISIBLE
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Error playing stream: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        exoPlayer.release()
    }
}

// Helper data class for fridge_members query
@Serializable
data class FridgeMemberResponse(
    val fridge_id: Long,
    val user_id: String
)