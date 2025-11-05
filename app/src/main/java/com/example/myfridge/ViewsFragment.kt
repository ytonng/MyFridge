package com.example.myfridge

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ui.PlayerView
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.example.myfridge.viewmodel.ViewsViewModel
import kotlinx.coroutines.launch
import org.json.JSONObject

class ViewsFragment : Fragment() {

    private val viewModel: ViewsViewModel by viewModels()

    private lateinit var fridgeNameText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var addCameraButton: Button
    private lateinit var spinnerDevices: Spinner
    private lateinit var viewStreamButton: Button
    private lateinit var playerView: PlayerView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var emptyVideoState: View
    private lateinit var exoPlayer: ExoPlayer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Listen for fridge switch events and reload fridge context
        parentFragmentManager.setFragmentResultListener("fridge_switched", this) { _, bundle ->
            val switchedId = bundle.getLong("fridgeId", -1L)
            android.util.Log.d("ViewsFragment", "fridge_switched received: id=" + switchedId + ", reloading fridge")
            viewModel.refreshFridge()
        }
    }

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
        swipeRefresh = view.findViewById(R.id.swipeRefreshView)
        emptyVideoState = view.findViewById(R.id.emptyVideoState)

        exoPlayer = ExoPlayer.Builder(requireContext()).build()
        playerView.player = exoPlayer

        // Toggle empty state overlay based on player state
        exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        playerView.visibility = View.VISIBLE
                        emptyVideoState.visibility = View.GONE
                    }
                    Player.STATE_IDLE, Player.STATE_ENDED -> {
                        playerView.visibility = View.GONE
                        emptyVideoState.visibility = View.VISIBLE
                    }
                    Player.STATE_BUFFERING -> {
                        // Keep PlayerView visible while buffering; empty overlay hidden
                        playerView.visibility = View.VISIBLE
                        emptyVideoState.visibility = View.GONE
                    }
                }
            }

            override fun onPlayerError(error: com.google.android.exoplayer2.PlaybackException) {
                playerView.visibility = View.GONE
                emptyVideoState.visibility = View.VISIBLE
            }
        })

        setupObservers()
        setupClickListeners()

        // Setup swipe to refresh
        swipeRefresh.setOnRefreshListener {
            viewModel.refreshFridge()
        }

        // Load fridge data on creation
        viewModel.loadCurrentFridge()

        return view
    }

    private fun setupObservers() {
        lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                // Update loading state
                progress.visibility = if (state.isLoading) View.VISIBLE else View.GONE
                swipeRefresh.isRefreshing = state.isLoading

                // Update fridge name
                fridgeNameText.text = state.fridgeName

                // Update device spinner
                if (state.pairedDevices.isNotEmpty()) {
                    val adapter = ArrayAdapter(
                        requireContext(),
                        android.R.layout.simple_spinner_item,
                        state.pairedDevices
                    )
                    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                    spinnerDevices.adapter = adapter
                }

                // Handle QR code generation
                state.qrCodeData?.let { qrData ->
                    showQRCodeDialog(qrData.token, qrData.expiresInSeconds)
                    viewModel.clearQRCodeData()
                }

                // Handle playback URL
                state.playbackUrl?.let { url ->
                    playVideo(url)
                    viewModel.clearPlaybackUrl()
                }

                // Handle error messages
                state.errorMessage?.let { error ->
                    Toast.makeText(requireContext(), error, Toast.LENGTH_LONG).show()
                    viewModel.clearError()
                }

                // Handle success messages
                state.successMessage?.let { message ->
                    Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                    viewModel.clearSuccessMessage()
                }
            }
        }
    }

    private fun setupClickListeners() {
        addCameraButton.setOnClickListener {
            viewModel.generatePairingQRCode()
        }

        viewStreamButton.setOnClickListener {
            val selectedDevice = spinnerDevices.selectedItem?.toString() ?: ""
            viewModel.fetchAndPlayStream(selectedDevice)
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

    private fun playVideo(url: String) {
        try {
            exoPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(url)))
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
            playerView.visibility = View.VISIBLE
            emptyVideoState.visibility = View.GONE
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Error playing stream: ${e.message}", Toast.LENGTH_LONG).show()
            playerView.visibility = View.GONE
            emptyVideoState.visibility = View.VISIBLE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        exoPlayer.release()
    }
}