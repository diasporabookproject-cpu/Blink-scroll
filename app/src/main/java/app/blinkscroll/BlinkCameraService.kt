package app.blinkscroll

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
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
 * Foreground service that runs the front camera, detects winks/blinks with ML Kit
 * and asks [ScrollAccessibilityService] to swipe.
 *
 * It must be started while the app is visible: Android only lets a foreground
 * service use the camera from the background if it was started from the foreground.
 *
 * To save battery the camera is switched off when the screen is off or when neither
 * TikTok/Instagram nor the settings screen is in front, and switched back on when needed.
 */
class BlinkCameraService : LifecycleService() {

    private lateinit var settings: AppSettings
    private lateinit var powerManager: PowerManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var faceDetector: FaceDetector? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var cameraBound = false
    private var receiverRegistered = false
    private var orientationListener: OrientationEventListener? = null
    private val detector = BlinkDetector()

    @Volatile
    private var destroyed = false

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

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = refreshCameraState()
    }

    private val pauseCamera = Runnable { setCameraActive(false) }

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
                .setMinFaceSize(0.15f)
                .build(),
        )
        LiveState.update { it.copy(running = true, error = null) }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
        LiveState.contextChanged = { refreshCameraState() }

        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                // Keep the face upright for ML Kit even when the phone is turned sideways.
                imageAnalysis?.targetRotation = when (orientation) {
                    in 45..134 -> Surface.ROTATION_270
                    in 135..224 -> Surface.ROTATION_180
                    in 225..314 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
            }
        }.also { if (it.canDetectOrientation()) it.enable() }

        initCamera()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) stopSelf()
        return START_NOT_STICKY
    }

    private fun initCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (destroyed) return@addListener
            try {
                cameraProvider = future.get()
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
                cameraProvider?.unbindAll()
                refreshCameraState()
            } catch (e: Exception) {
                Log.e(TAG, "Camera init failed", e)
                LiveState.update { it.copy(error = "Impossible d’ouvrir la caméra frontale") }
                stopSelf()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Is there anything to detect right now? */
    private fun wantsDetection(): Boolean {
        if (!powerManager.isInteractive) return false
        if (LiveState.uiVisible) return true
        if (!settings.onlyTargetApps) return true
        return ScrollAccessibilityService.instance?.isTargetAppInForeground() == true
    }

    /** Turns the camera on immediately when needed, off after a short delay when not. */
    private fun refreshCameraState() {
        mainHandler.post {
            if (destroyed) return@post
            mainHandler.removeCallbacks(pauseCamera)
            when {
                wantsDetection() -> setCameraActive(true)
                // Screen off: stop right away. App switch: wait a little, it may be brief.
                !powerManager.isInteractive -> setCameraActive(false)
                cameraBound -> mainHandler.postDelayed(pauseCamera, CAMERA_OFF_DELAY_MS)
            }
        }
    }

    /** Main thread. */
    private fun setCameraActive(active: Boolean) {
        val provider = cameraProvider ?: return
        val analysis = imageAnalysis ?: return
        if (destroyed || active == cameraBound) return
        try {
            if (active) {
                val camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
                camera.cameraInfo.cameraState.removeObservers(this)
                camera.cameraInfo.cameraState.observe(this) { state ->
                    val error = state.error
                    LiveState.update {
                        it.copy(
                            error = when {
                                error == null -> null
                                error.code == CameraState.ERROR_CAMERA_IN_USE ||
                                    error.code == CameraState.ERROR_MAX_CAMERAS_IN_USE ->
                                    "Caméra utilisée par une autre appli"
                                else -> "Caméra indisponible (code ${error.code})"
                            },
                        )
                    }
                }
            } else {
                provider.unbind(analysis)
                wasPaused = true
                detector.reset()
                LiveState.update { it.copy(faceDetected = false, left = -1f, right = -1f) }
            }
            cameraBound = active
            LiveState.update { it.copy(cameraOn = active) }
        } catch (e: Exception) {
            Log.e(TAG, "Camera ${if (active) "bind" else "unbind"} failed", e)
            LiveState.update { it.copy(error = "Impossible d’ouvrir la caméra frontale") }
        }
    }

    /** Runs on the analysis thread for every camera frame. */
    @OptIn(ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy) {
        val image = proxy.image
        val detectorClient = faceDetector
        if (image == null || detectorClient == null || destroyed) {
            proxy.close()
            return
        }
        if (!wantsDetection()) {
            // The camera is about to be switched off; skip work in the meantime.
            wasPaused = true
            proxy.close()
            return
        }
        try {
            val input = InputImage.fromMediaImage(image, proxy.imageInfo.rotationDegrees)
            // Listeners run on the main thread, where all detector state lives.
            detectorClient.process(input)
                .addOnSuccessListener { faces -> onFaces(faces) }
                .addOnFailureListener { e -> Log.w(TAG, "Face detection failed", e) }
                .addOnCompleteListener { proxy.close() }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot analyze frame", e)
            proxy.close()
        }
    }

    /** Main thread. */
    private fun onFaces(faces: List<Face>) {
        if (destroyed) return
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
            description = "Affichée tant que la détection des clins d’œil est démarrée"
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
        destroyed = true
        mainHandler.removeCallbacksAndMessages(null)
        LiveState.contextChanged = null
        if (receiverRegistered) {
            unregisterReceiver(screenReceiver)
            receiverRegistered = false
        }
        orientationListener?.disable()
        imageAnalysis?.clearAnalyzer()
        cameraProvider?.unbindAll()
        faceDetector?.close()
        faceDetector = null
        analysisExecutor.shutdown()
        LiveState.update {
            it.copy(running = false, cameraOn = false, faceDetected = false, left = -1f, right = -1f)
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "BlinkScroll"
        private const val CHANNEL_ID = "detection"
        private const val NOTIFICATION_ID = 1
        private const val CAMERA_OFF_DELAY_MS = 3_000L
        const val ACTION_STOP = "app.blinkscroll.action.STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, BlinkCameraService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BlinkCameraService::class.java))
        }
    }
}
