package com.clipport.app.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 扫码配对 Activity（纯 Java 极轻量 ZXing 方案，零 SO 库，体积仅 500KB）。
 * 通过 CameraX 获取 YUV 灰度图直接喂给 ZXing，针对 QR_CODE 格式高度优化。
 */
class ScanPairActivity : ComponentActivity() {
    companion object {
        const val TAG = "ScanPairActivity"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_CODE = "code"
        const val EXTRA_FP = "fp"
        const val EXTRA_NAME = "name"
    }

    private lateinit var previewView: PreviewView
    private lateinit var cameraExecutor: ExecutorService
    private val isScanned = AtomicBoolean(false)

    // 针对 QR 码优化的 reader，仅识别 QR_CODE 格式，单帧极速解码
    private val qrReader = MultiFormatReader().apply {
        val hints = mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.CHARACTER_SET to "UTF-8"
        )
        setHints(hints)
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startCamera()
        } else {
            Toast.makeText(this, "需要相机权限以进行扫码配对", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        previewView = PreviewView(this)
        setContentView(previewView)

        cameraExecutor = Executors.newSingleThreadExecutor()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    processImageProxy(imageProxy)
                }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
            } catch (e: Exception) {
                Log.e(TAG, "Camera binding failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processImageProxy(imageProxy: ImageProxy) {
        if (isScanned.get()) {
            imageProxy.close()
            return
        }

        try {
            val yBuffer = imageProxy.planes[0].buffer
            val yBytes = ByteArray(yBuffer.remaining())
            yBuffer.get(yBytes)

            val width = imageProxy.width
            val height = imageProxy.height

            // 优先直接解码当前帧
            var rawText = decodeYuv(yBytes, width, height)

            // 如果相机有旋转角度且未识别出，旋转 90 度重试
            if (rawText == null && imageProxy.imageInfo.rotationDegrees == 90) {
                val rotated = rotateYuv90(yBytes, width, height)
                rawText = decodeYuv(rotated, height, width)
            }

            if (rawText != null && rawText.startsWith("clipport://pair", ignoreCase = true)) {
                if (isScanned.compareAndSet(false, true)) {
                    runOnUiThread { handlePairUri(rawText) }
                }
            }
        } catch (_: Exception) {
        } finally {
            imageProxy.close()
        }
    }

    private fun decodeYuv(data: ByteArray, width: Int, height: Int): String? {
        return try {
            val source = PlanarYUVLuminanceSource(
                data, width, height, 0, 0, width, height, false
            )
            val bitmap = BinaryBitmap(HybridBinarizer(source))
            val result = qrReader.decodeWithState(bitmap)
            result.text
        } catch (_: Exception) {
            null
        } finally {
            qrReader.reset()
        }
    }

    private fun rotateYuv90(data: ByteArray, width: Int, height: Int): ByteArray {
        val rotated = ByteArray(data.size)
        var k = 0
        for (x in 0 until width) {
            for (y in height - 1 downTo 0) {
                rotated[k++] = data[y * width + x]
            }
        }
        return rotated
    }

    private fun handlePairUri(uriString: String) {
        try {
            val uri = Uri.parse(uriString)
            val host = uri.getQueryParameter("host") ?: ""
            val port = uri.getQueryParameter("port") ?: "47190"
            val code = uri.getQueryParameter("code") ?: ""
            val fp = uri.getQueryParameter("fp") ?: ""
            val name = uri.getQueryParameter("name") ?: ""

            val intent = Intent().apply {
                putExtra(EXTRA_HOST, host)
                putExtra(EXTRA_PORT, port)
                putExtra(EXTRA_CODE, code)
                putExtra(EXTRA_FP, fp)
                putExtra(EXTRA_NAME, name)
            }
            setResult(Activity.RESULT_OK, intent)
            finish()
        } catch (e: Exception) {
            Log.w(TAG, "parse pair uri failed: $uriString", e)
            isScanned.set(false)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
