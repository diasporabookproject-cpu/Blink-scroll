package app.blinkscroll

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Foreground service that keeps the front camera running, detects winks/blinks
 * with ML Kit and asks [ScrollAccessibilityService] to swipe.
 *
 * It must be started while the app is visible (Android only lets a foreground
 * service use the camera if it was started from the foreground).
 */
class BlinkCameraService : LifecycleService() {

    private lateinit var settings: AppSettings
    private lateinit var powerManager: PowerManager
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var faceDetector: FaceDetector? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private val detector = BlinkDetector()

    /** Set from the analysis thread when frames were skipped; read on the main thread. */
    @Volatile
    private var wasPaused = true

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
    }

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        powerManager = getSystemService(PowerManager::class.java)
        createNotificationChannel()
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                } else {
                    0
                },
            )
        } catch (e: Exception) {
            Log.e(TAG, "Cannot start foreground service", e)
            LiveState.update { it.copy(running = false, error = "Impossible de démarrer la détection") }
            stopSelf()
            return
        }

        faceDetector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .setMinFaceSize(0.2f)
                .build(),
        )
        LiveState.update { it.copy(running = true, error = null) }
        startCamera()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) stopSelf()
        return START_NOT_STICKY
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    Size(640, 480),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                                ),
                            )
                            .build(),
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analysisExecutor) { proxy -> analyze(proxy) }
                imageAnalysis = analysis
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
            } catch (e: Exception) {
                Log.e(TAG, "Camera start failed", e)
                LiveState.update { it.copy(error = "Impossible d’ouvrir la caméra frontale") }
                stopSelf()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Runs on the analysis thread for every camera frame. */
    @OptIn(ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy) {
        val image = proxy.image
        val detectorClient = faceDetector
        if (image == null || detectorClient == null) {
            proxy.close()
            return
        }
        if (!shouldAnalyze()) {
            if (!wasPaused) {
                wasPaused = true
                LiveState.update { it.copy(faceDetected = false, left = -1f, right = -1f) }
            }
            // Nothing to do outside TikTok/Instagram: slow down to save battery.
            try {
                Thread.sleep(PAUSED_FRAME_DELAY_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            proxy.close()
            return
        }
        val input = InputImage.fromMediaImage(image, proxy.imageInfo.rotationDegrees)
        // Listeners run on the main thread, where all detector state lives.
        detectorClient.process(input)
            .addOnSuccessListener { faces -> onFaces(faces) }
            .addOnFailureListener { e -> Log.w(TAG, "Face detection failed", e) }
            .addOnCompleteListener { proxy.close() }
    }

    private fun shouldAnalyze(): Boolean {
        if (!powerManager.isInteractive) return false
        if (LiveState.uiVisible) return true
        if (!settings.onlyTargetApps) return true
        return ScrollAccessibilityService.instance?.isTargetAppInForeground() == true
    }

    /** Main thread. */
    private fun onFaces(faces: List<Face>) {
        if (wasPaused) {
            detector.reset()
            wasPaused = false
        }
        val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
        var left = face?.leftEyeOpenProbability
        var right = face?.rightEyeOpenProbability
        if (settings.swapEyes) {
            val tmp = left
            left = right
            right = tmp
        }
        detector.config = settings.detectorConfig()
        val action = detector.onFrame(left, right, SystemClock.elapsedRealtime())
        LiveState.update {
            it.copy(
                faceDetected = left != null && right != null,
                left = left ?: -1f,
                right = right ?: -1f,
            )
        }
        if (action != null) perform(action)
    }

    private fun perform(action: ScrollAction) {
        val label = if (action == ScrollAction.NEXT) "Vidéo suivante" else "Vidéo précédente"
        val service = ScrollAccessibilityService.instance
        val result = when {
            LiveState.uiVisible -> "$label (test)"
            service == null -> "$label — service d’accessibilité inactif"
            settings.onlyTargetApps && !service.isTargetAppInForeground() -> "$label — ignoré hors TikTok/Instagram"
            service.scroll(action) -> label
            else -> "$label — geste refusé par Android"
        }
        if (settings.vibrate) {
            try {
                vibrator?.vibrate(VibrationEffect.createOneShot(35L, VibrationEffect.DEFAULT_AMPLITUDE))
            } catch (e: RuntimeException) {
                Log.w(TAG, "Vibration failed", e)
            }
        }
        LiveState.update { it.copy(count = it.count + 1, lastAction = result) }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Détection des clins d’œil",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Affichée tant que la caméra surveille tes clins d’œil"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, BlinkCameraService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Blink Scroll actif")
            .setContentText("Clin d’œil = vidéo suivante dans TikTok et Instagram")
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(0, "Arrêter", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    override fun onDestroy() {
        imageAnalysis?.clearAnalyzer()
        cameraProvider?.unbindAll()
        faceDetector?.close()
        faceDetector = null
        analysisExecutor.shutdown()
        LiveState.update {
            it.copy(running = false, faceDetected = false, left = -1f, right = -1f)
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "BlinkScroll"
        private const val CHANNEL_ID = "detection"
        private const val NOTIFICATION_ID = 1
        private const val PAUSED_FRAME_DELAY_MS = 150L
        const val ACTION_STOP = "app.blinkscroll.action.STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, BlinkCameraService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BlinkCameraService::class.java))
        }
    }
}
