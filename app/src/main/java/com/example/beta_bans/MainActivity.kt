package com.example.beta_bans

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.beta_bans.ui.theme.BetaBansTheme

import com.google.mediapipe.tasks.components.containers.Detection
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector.ObjectDetectorOptions
import java.util.concurrent.Executors

data class ResultBundle(
    val results: List<Detection>,
    val inputImageHeight: Int,
    val inputImageWidth: Int
)

class MainActivity : ComponentActivity() {
    private var objectDetector: ObjectDetector? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BetaBansTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    CameraScreen()
                }
            }
        }
    }

    private fun setupObjectDetector(context: Context, onResults: (ResultBundle) -> Unit) {
        val baseOptionsBuilder = BaseOptions.builder()
            .setModelAssetPath("efficientdet_lite0.tflite")

        val optionsBuilder = ObjectDetectorOptions.builder()
            .setBaseOptions(baseOptionsBuilder.build())
            .setScoreThreshold(0.5f)
            .setMaxResults(5)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setResultListener { result, image ->
                for (res in result.detections()) {
                    Log.d("Detection", "    ${res.categories()}")
                }
                onResults(ResultBundle(result.detections(), image.height, image.width))
            }
            .setErrorListener { error ->
                Log.e("Detection", "MediaPipe Error: ${error.message}")
            }

        objectDetector = ObjectDetector.createFromOptions(context, optionsBuilder.build())
    }

    @Composable
    fun CameraScreen() {
        val context = LocalContext.current
        var hasCameraPermission by remember {
            mutableStateOf(
                ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            )
        }

        var cameraIndex by remember { mutableIntStateOf(0) }
        var cameraCount by remember { mutableIntStateOf(0) }
        var detectionResults by remember { mutableStateOf<ResultBundle?>(null) }

        val launcher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
            onResult = { granted -> hasCameraPermission = granted }
        )

        LaunchedEffect(Unit) {
            launcher.launch(Manifest.permission.CAMERA)
            setupObjectDetector(context) { results ->
                detectionResults = results
            }
        }

        if (hasCameraPermission) {
            Box(modifier = Modifier.fillMaxSize()) {
                CameraPreview(
                    cameraIndex = cameraIndex,
                    onCameraCountReady = { cameraCount = it },
                    onResults = { detectionResults = it }
                )

                // Overlay drawing layer
                detectionResults?.let { bundle ->
                    DetectionOverlay(bundle)
                }

                Button(
                    onClick = {
                        if (cameraCount > 0) {
                            cameraIndex = (cameraIndex + 1) % cameraCount
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 48.dp)
                ) {
                    Text("Switch Camera (${cameraIndex + 1}/$cameraCount)")
                }
            }
        }
    }

    @OptIn(ExperimentalGetImage::class)
    @Composable
    fun CameraPreview(cameraIndex: Int, onCameraCountReady: (Int) -> Unit, onResults: (ResultBundle) -> Unit)
    {
        val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
        val context = LocalContext.current
        val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx -> PreviewView(ctx) },
            update = { previewView ->
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val cameraInfos = cameraProvider.availableCameraInfos
                    onCameraCountReady(cameraInfos.size)

                    if (cameraInfos.isNotEmpty()) {
                        val selectedCameraInfo = cameraInfos[cameraIndex]
                        val cameraSelector = selectedCameraInfo.cameraSelector

                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }

                        if (objectDetector == null) {
                            setupObjectDetector(context, onResults)
                        }

                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                            .build()

                        analysis.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
                            imageProxy.image?.let { mediaImage ->
                                val mpImage = com.google.mediapipe.framework.image.MediaImageBuilder(mediaImage).build()
                                objectDetector?.detectAsync(mpImage, System.currentTimeMillis())
                            }
                            imageProxy.close()
                        }

                        try {
                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                cameraSelector,
                                preview,
                                analysis
                            )
                        } catch (e: Exception) {
                            Log.e("CameraApp", "Binding failed", e)
                        }
                    }
                }, ContextCompat.getMainExecutor(context))
            }
        )
    }

    @Composable
    fun DetectionOverlay(resultBundle: ResultBundle) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val scaleX = size.width / resultBundle.inputImageWidth
            val scaleY = size.height / resultBundle.inputImageHeight

            for (detection in resultBundle.results) {
                val boundingBox = detection.boundingBox()

                val left = boundingBox.left * scaleX
                val top = boundingBox.top * scaleY
                val right = boundingBox.right * scaleX
                val bottom = boundingBox.bottom * scaleY

                // Draw bounding box
                drawRect(
                    color = Color.Blue,
                    topLeft = Offset(left, top),
                    size = Size(right - left, bottom - top),
                    style = Stroke(width = 8f)
                )

                drawContext.canvas.nativeCanvas.apply {
                    val category = detection.categories().firstOrNull()
                    val text = "${category?.categoryName()} " +
                            String.format("%.2f", category?.score() ?: 0f)

                    val textPaint = android.graphics.Paint().apply {
                        color = android.graphics.Color.WHITE
                        textSize = 50f
                    }

                    val backgroundPaint = android.graphics.Paint().apply {
                        color = android.graphics.Color.BLACK
                        style = android.graphics.Paint.Style.FILL
                    }

                    val bounds = android.graphics.Rect()
                    textPaint.getTextBounds(text, 0, text.length, bounds)

                    // Draw text background
                    drawRect(
                        left,
                        top,
                        left + bounds.width() + 8f,
                        top + bounds.height() + 8f,
                        backgroundPaint
                    )

                    // Draw the label text
                    drawText(text, left, top + bounds.height(), textPaint)
                }
            }
        }
    }
}