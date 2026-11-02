package app.blinkscroll

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
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
import app.blinkscroll.audio.AudioCapture
import app.blinkscroll.audio.SoundMatch
import app.blinkscroll.audio.SoundTriggerEngine
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Foreground service that runs the enabled triggers and asks
 * [ScrollAccessibilityService] to swipe:
 *  - learned sound (microphone),
 *  - hand passing over the phone (proximity sensor),
 *  - winks (front camera + ML Kit).
 *
 * It must be started while the app is visible: Android only lets a foreground service
 * use the microphone/camera from the background if it was started from the foreground.
 * Everything is paused when the screen is off or when neither TikTok/Instagram nor
 * the settings screen is in front.
 */
class DetectionService : LifecycleService() {

    private lateinit var settings: AppSettings
    private lateinit var powerManager: PowerManager
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var destroyed = false
    private var receiverRegistered = false
    private var listenersRegistered = false

    /** Triggers used in this session (decided at start). */
    private var useSound = false
    private var useProximity = false
    private var useEyes = false

    /** Whether triggers are currently running (false = paused to save battery). */
    private var active = false

    // Sound
    private var soundEngine: SoundTriggerEngine? = null
    private var audioCapture: AudioCapture? = null
    private var lastLevelUpdate = 0L

    // Hand wave
    private var proximity: ProximityTrigger? = null

    // Eyes
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var faceDetector: FaceDetector? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var cameraBound = false
    private var orientationListener: OrientationEventListener? = null
    private val blinkDetector = BlinkDetector()

    @Volatile
    private var eyesWasPaused = true

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = refreshState()
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        mainHandler.post { applyLiveSettings() }
    }

    private val pauseRunnable = Runnable { setActive(false) }

    private val soundListener = object : SoundTriggerEngine.Listener {
        override fun onSound(match: SoundMatch) {
            LiveState.update {
                it.copy(
                    lastSoundScore = match.score,
                    lastSoundThreshold = match.threshold,
                    lastSoundAccepted = match.accepted,
                    lastSoundAt = SystemClock.elapsedRealtime(),
                )
            }
        }

        override fun onAction(action: ScrollAction) {
            mainHandler.post { perform(action, "bruit") }
        }
    }

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        powerManager = getSystemService(PowerManager::class.java)

        val profile = if (settings.soundEnabled && granted(Manifest.permission.RECORD_AUDIO)) {
            settings.loadSoundProfile()
        } else {
            null
        }
        useSound = profile != null
        val proximityTrigger = if (settings.proximityEnabled) {
            ProximityTrigger(
                this,
                onAction = { perform(it, "main") },
                onNearChanged = { near -> LiveState.update { s -> s.copy(proximityNear = near) } },
            ).takeIf { it.isAvailable }
        } else {
            null
        }
        useProximity = proximityTrigger != null
        useEyes = settings.eyesEnabled && granted(Manifest.permission.CAMERA)

        createNotificationChannel()
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), foregroundType())
        } catch (e: Exception) {
            Log.e(TAG, "Cannot start foreground service", e)
            LiveState.update { it.copy(running = false, error = "Impossible de démarrer la détection") }
            stopSelf()
            return
        }
        if (!useSound && !useProximity && !useEyes) {
            LiveState.update { it.copy(running = false, error = "Aucun déclencheur prêt") }
            stopSelf()
            return
        }

        if (profile != null) {
            soundEngine = SoundTriggerEngine(profile, settings.soundSensitivity, settings.doubleForPrevious)
            audioCapture = AudioCapture(
                onSamples = ::onAudio,
                onStopped = {
                    mainHandler.post {
                        LiveState.update { it.copy(error = "Le micro a été coupé par Android") }
                    }
                },
            )
        }
        proximity = proximityTrigger?.also { it.doubleForPrevious = settings.doubleForPrevious }
        if (useEyes) setUpEyes()

        LiveState.update {
            it.copy(
                running = true,
                listening = false,
                error = null,
                soundActive = useSound,
                proximityActive = useProximity,
                eyesActive = useEyes,
            )
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
        settings.registerListener(prefsListener)
        listenersRegistered = true
        LiveState.contextChanged = { refreshState() }
        refreshState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) stopSelf()
        return START_NOT_STICKY
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun foregroundType(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return 0
        var type = 0
        if (useEyes) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        if (useSound) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (type == 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        }
        return type
    }

    // --- Pausing ----------------------------------------------------------------

    private fun wantsDetection(): Boolean {
        if (!powerManager.isInteractive) return false
        if (LiveState.uiVisible) return true
        if (!settings.onlyTargetApps) return true
        return ScrollAccessibilityService.instance?.isTargetAppInForeground() == true
    }

    /** Starts triggers immediately when needed, stops them after a short delay when not. */
    private fun refreshState() {
        mainHandler.post {
            if (destroyed) return@post
            mainHandler.removeCallbacks(pauseRunnable)
            when {
                wantsDetection() -> setActive(true)
                // Screen off: stop right away. App switch: wait a little, it may be brief.
                !powerManager.isInteractive -> setActive(false)
                active -> mainHandler.postDelayed(pauseRunnable, PAUSE_DELAY_MS)
            }
        }
    }

    /** Main thread. */
    private fun setActive(on: Boolean) {
        if (destroyed || on == active) return
        active = on
        if (on) {
            soundEngine?.reset()
            if (audioCapture?.start() == false) {
                LiveState.update { it.copy(error = "Micro indisponible") }
            }
            proximity?.start()
            if (useEyes) setCameraActive(true)
            LiveState.update { it.copy(listening = true) }
        } else {
            audioCapture?.stop()
            proximity?.stop()
            if (useEyes) setCameraActive(false)
            LiveState.update {
                it.copy(
                    listening = false,
                    soundLevel = 0f,
                    proximityNear = null,
                    faceDetected = false,
                    left = -1f,
                    right = -1f,
                )
            }
        }
    }

    private fun applyLiveSettings() {
        if (destroyed) return
        soundEngine?.sensitivity = settings.soundSensitivity
        soundEngine?.doubleForPrevious = settings.doubleForPrevious
        proximity?.doubleForPrevious = settings.doubleForPrevious
        refreshState()
    }

    // --- Sound ------------------------------------------------------------------

    /** Audio thread. */
    private fun onAudio(samples: FloatArray, count: Int) {
        val engine = soundEngine ?: return
        if (destroyed) return
        engine.process(samples, count, soundListener)
        val now = SystemClock.elapsedRealtime()
        if (now - lastLevelUpdate >= LEVEL_UPDATE_MS) {
            lastLevelUpdate = now
            val level = ((engine.levelDb + 70.0) / 60.0).coerceIn(0.0, 1.0).toFloat()
            LiveState.update { it.copy(soundLevel = level) }
        }
    }

    // --- Eyes (camera) ----------------------------------------------------------

    private fun setUpEyes() {
        faceDetector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .setMinFaceSize(0.15f)
                .build(),
        )
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
                if (active) setCameraActive(true)
            } catch (e: Exception) {
                Log.e(TAG, "Camera init failed", e)
                LiveState.update { it.copy(error = "Impossible d’ouvrir la caméra frontale") }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Main thread. */
    private fun setCameraActive(on: Boolean) {
        val provider = cameraProvider ?: return
        val analysis = imageAnalysis ?: return
        if (destroyed || on == cameraBound) return
        try {
            if (on) {
                val camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
                camera.cameraInfo.cameraState.removeObservers(this)
                camera.cameraInfo.cameraState.observe(this) { state ->
                    val error = state.error ?: return@observe
                    LiveState.update {
                        it.copy(
                            error = if (error.code == CameraState.ERROR_CAMERA_IN_USE ||
                                error.code == CameraState.ERROR_MAX_CAMERAS_IN_USE
                            ) {
                                "Caméra utilisée par une autre appli"
                            } else {
                                "Caméra indisponible (code ${error.code})"
                            },
                        )
                    }
                }
            } else {
                provider.unbind(analysis)
                eyesWasPaused = true
                blinkDetector.reset()
            }
            cameraBound = on
        } catch (e: Exception) {
            Log.e(TAG, "Camera ${if (on) "bind" else "unbind"} failed", e)
            LiveState.update { it.copy(error = "Impossible d’ouvrir la caméra frontale") }
        }
    }

    /** Analysis thread, once per camera frame. */
    @OptIn(ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy) {
        val image = proxy.image
        val detectorClient = faceDetector
        if (image == null || detectorClient == null || destroyed) {
            proxy.close()
            return
        }
        if (!wantsDetection()) {
            eyesWasPaused = true
            proxy.close()
            return
        }
        try {
            val input = InputImage.fromMediaImage(image, proxy.imageInfo.rotationDegrees)
            // Listeners run on the main thread, where the blink detector lives.
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
        if (eyesWasPaused) {
            blinkDetector.reset()
            eyesWasPaused = false
        }
        val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
        var left = face?.leftEyeOpenProbability
        var right = face?.rightEyeOpenProbability
        if (settings.swapEyes) {
            val tmp = left
            left = right
            right = tmp
        }
        blinkDetector.config = settings.detectorConfig()
        val action = blinkDetector.onFrame(left, right, SystemClock.elapsedRealtime())
        LiveState.update {
            it.copy(faceDetected = left != null && right != null, left = left ?: -1f, right = right ?: -1f)
        }
        if (action != null) perform(action, "œil")
    }

    // --- Scrolling --------------------------------------------------------------

    /** Main thread. */
    private fun perform(action: ScrollAction, source: String) {
        if (destroyed) return
        val label = (if (action == ScrollAction.NEXT) "Vidéo suivante" else "Vidéo précédente") + " · $source"
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

    // --- Notification -----------------------------------------------------------

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Détection active",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Affichée tant que Blink Scroll est démarré"
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
            Intent(this, DetectionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val sources = buildList {
            if (useSound) add("bruit")
            if (useProximity) add("main")
            if (useEyes) add("clin d’œil")
        }.joinToString(" · ").ifEmpty { "démarrage" }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Blink Scroll actif")
            .setContentText("Déclencheurs : $sources")
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
        if (listenersRegistered) settings.unregisterListener(prefsListener)
        if (receiverRegistered) unregisterReceiver(screenReceiver)
        audioCapture?.stop()
        proximity?.stop()
        orientationListener?.disable()
        imageAnalysis?.clearAnalyzer()
        cameraProvider?.unbindAll()
        faceDetector?.close()
        faceDetector = null
        analysisExecutor.shutdown()
        LiveState.update {
            it.copy(
                running = false,
                listening = false,
                soundLevel = 0f,
                proximityNear = null,
                faceDetected = false,
                left = -1f,
                right = -1f,
            )
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "BlinkScroll"
        private const val CHANNEL_ID = "detection"
        private const val NOTIFICATION_ID = 1
        private const val PAUSE_DELAY_MS = 3_000L
        private const val LEVEL_UPDATE_MS = 100L
        const val ACTION_STOP = "app.blinkscroll.action.STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, DetectionService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DetectionService::class.java))
        }
    }
}
