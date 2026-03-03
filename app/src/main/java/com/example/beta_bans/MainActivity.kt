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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.beta_bans.ui.theme.BetaBansTheme

import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector.ObjectDetectorOptions
import java.util.concurrent.Executors
import com.google.mediapipe.tasks.core.BaseOptions

private var objectDetector: ObjectDetector? = null

class MainActivity : ComponentActivity() {
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
}

private fun setupObjectDetector(context: Context) {

    val baseOptionsBuilder = BaseOptions.builder()
        .setModelAssetPath("efficientdet_lite0.tflite")

    val optionsBuilder = ObjectDetectorOptions.builder()
        .setBaseOptions(baseOptionsBuilder.build())
        .setScoreThreshold(0.5f)
        .setMaxResults(5)
        .setRunningMode(RunningMode.LIVE_STREAM)
        .setResultListener { result, image ->
            Log.d("Detection", "Found ${result.detections().size} objects")
            for (detect in result.detections()) {
                Log.d("Detection", "    ${detect.categories()}")
                Log.d("Detection", "    ${detect}")
            }
        }
        .setErrorListener { error ->
            Log.e("Detection", "MediaPipe Error: ${error.message}")
        }

    val options = optionsBuilder.build()
    objectDetector = ObjectDetector.createFromOptions(context, options)
}

@Composable
fun CameraScreen() {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    // New state: Track the INDEX of the camera in the list
    var cameraIndex by remember { mutableIntStateOf(0) }
    // We also need to know how many cameras are available
    var cameraCount by remember { mutableIntStateOf(0) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted -> hasCameraPermission = granted }
    )

    LaunchedEffect(Unit) {
        launcher.launch(Manifest.permission.CAMERA)
        // Initialize the Model
        setupObjectDetector(context)
    }

    if (hasCameraPermission) {
        Box(modifier = Modifier.fillMaxSize()) {

            CameraPreview(cameraIndex) { count ->
                cameraCount = count // Callback to update the total count found
            }

            Button(
                onClick = {
                    // Cycle through the indices (0, 1, 2, back to 0)
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
fun CameraPreview(cameraIndex: Int, onCameraCountReady: (Int) -> Unit) {
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val context = LocalContext.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx -> PreviewView(ctx) },
        update = { previewView ->
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()

                // 1. Get all available cameras (Internal + External)
                val cameraInfos = cameraProvider.availableCameraInfos
                onCameraCountReady(cameraInfos.size)

                if (cameraInfos.isNotEmpty()) {
                    // 2. Pick the camera at our current index
                    val selectedCameraInfo = cameraInfos[cameraIndex]

                    // 3. Create a selector specifically for THIS camera
                    val cameraSelector = selectedCameraInfo.cameraSelector

                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    // OBJECT DETECTION

                    if (objectDetector == null) {
                        setupObjectDetector(context)
                    }

                    // 2. Setup Analysis (The connection to MediaPipe)
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