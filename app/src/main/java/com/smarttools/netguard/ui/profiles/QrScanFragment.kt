package com.smarttools.netguard.ui.profiles

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.smarttools.netguard.R
import com.smarttools.netguard.databinding.FragmentQrScanBinding
import com.smarttools.netguard.util.QRScanner
import com.smarttools.netguard.viewmodel.ProfileListViewModel
import com.smarttools.netguard.viewmodel.SubscriptionViewModel
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

@androidx.camera.core.ExperimentalGetImage
class QrScanFragment : Fragment() {

    private var _binding: FragmentQrScanBinding? = null
    private val binding get() = _binding!!
    private val profileViewModel: ProfileListViewModel by activityViewModels()
    private val subscriptionViewModel: SubscriptionViewModel by activityViewModels()

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var processed = false

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            Toast.makeText(requireContext(), R.string.camera_permission_denied, Toast.LENGTH_SHORT).show()
            findNavController().popBackStack()
        }
    }

    private val galleryPicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val inputStream = requireContext().contentResolver.openInputStream(uri)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()
                if (bitmap == null) {
                    Toast.makeText(requireContext(), R.string.no_qr_found, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val value = QRScanner.scanBitmap(bitmap)
                bitmap.recycle()
                if (value != null) {
                    handleScannedValue(value)
                } else {
                    Toast.makeText(requireContext(), R.string.no_qr_found, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("QrScan", "Gallery scan failed", e)
                Toast.makeText(requireContext(), R.string.no_qr_found, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentQrScanBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnClose.setOnClickListener {
            findNavController().popBackStack()
        }

        binding.btnGallery.setOnClickListener {
            galleryPicker.launch("image/*")
        }

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            val scanner = BarcodeScanning.getClient()

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                if (processed) {
                    imageProxy.close()
                    return@setAnalyzer
                }
                val mediaImage = imageProxy.image
                if (mediaImage == null) {
                    imageProxy.close()
                    return@setAnalyzer
                }
                val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                scanner.process(inputImage)
                    .addOnSuccessListener { barcodes ->
                        for (barcode in barcodes) {
                            val value = barcode.rawValue ?: continue
                            if (value.isNotBlank() && !processed) {
                                processed = true
                                handleScannedValue(value)
                                return@addOnSuccessListener
                            }
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.w("QrScan", "Barcode scan failed", e)
                    }
                    .addOnCompleteListener {
                        imageProxy.close()
                    }
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(viewLifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                Log.e("QrScan", "Camera bind failed", e)
                Toast.makeText(requireContext(), "Camera error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun isVpnUri(value: String): Boolean {
        val schemes = listOf("vless://", "vmess://", "trojan://", "ss://", "hysteria2://", "hy2://")
        return schemes.any { value.startsWith(it, ignoreCase = true) }
    }

    private fun handleScannedValue(value: String) {
        activity?.runOnUiThread {
            when {
                isVpnUri(value) -> {
                    profileViewModel.importFromText(value)
                    Toast.makeText(requireContext(), R.string.qr_imported, Toast.LENGTH_SHORT).show()
                }
                value.startsWith("http://") || value.startsWith("https://") -> {
                    subscriptionViewModel.addSubscription("QR Subscription", value, 0)
                    Toast.makeText(requireContext(), R.string.subscription_added_qr, Toast.LENGTH_SHORT).show()
                }
                else -> {
                    // Try as multi-line VPN URIs (some QR codes have multiple lines)
                    profileViewModel.importFromText(value)
                    Toast.makeText(requireContext(), R.string.qr_imported, Toast.LENGTH_SHORT).show()
                }
            }
            findNavController().popBackStack()
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
    }
}
