package app.blinkscroll

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.blinkscroll.audio.ProfileQuality
import app.blinkscroll.databinding.ActivityMainBinding
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: AppSettings
    private var hasProximitySensor = false

    /** A permission was requested by the Start button: continue starting once granted. */
    private var startAfterPermission = false

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            onPermissionResult(granted, Manifest.permission.RECORD_AUDIO, "le micro")
        }

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            onPermissionResult(granted, Manifest.permission.CAMERA, "la caméra")
        }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { startDetection() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        settings = AppSettings(this)
        hasProximitySensor =
            getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_PROXIMITY) != null
        bindTriggers()
        bindEyeSettings()
        bindGeneral()

        binding.btnAccess.setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        binding.btnMic.setOnClickListener { micPermission.launch(Manifest.permission.RECORD_AUDIO) }
        binding.btnCamera.setOnClickListener { cameraPermission.launch(Manifest.permission.CAMERA) }
        binding.btnAppInfo.setOnClickListener { openAppInfo() }
        binding.btnLearn.setOnClickListener { startActivity(Intent(this, CalibrationActivity::class.java)) }
        binding.btnStart.setOnClickListener {
            if (LiveState.state.value.running) DetectionService.stop(this) else onStartClicked()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                LiveState.state.collect { render(it) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        LiveState.uiVisible = true
    }

    override fun onResume() {
        super.onResume()
        refreshSetup()
        if (LiveState.restartAfterCalibration) {
            LiveState.restartAfterCalibration = false
            if (!LiveState.state.value.running) onStartClicked()
        }
    }

    override fun onStop() {
        LiveState.uiVisible = false
        super.onStop()
    }

    // --- Setup ------------------------------------------------------------------

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun refreshSetup() {
        val b = binding
        val accessibility = ScrollAccessibilityService.isEnabled(this)
        b.statusAccess.text = if (accessibility) "✓  Service d’accessibilité actif" else "Service d’accessibilité"
        b.btnAccess.isVisible = !accessibility
        b.restrictedHelp.isVisible = !accessibility

        val mic = granted(Manifest.permission.RECORD_AUDIO)
        b.rowMic.isVisible = settings.soundEnabled
        b.statusMic.text = if (mic) "✓  Micro autorisé" else "Micro"
        b.btnMic.isVisible = !mic

        val camera = granted(Manifest.permission.CAMERA)
        b.rowCamera.isVisible = settings.eyesEnabled
        b.statusCamera.text = if (camera) "✓  Caméra autorisée" else "Caméra"
        b.btnCamera.isVisible = !camera

        b.soundSettings.isVisible = settings.soundEnabled
        b.soundStatus.text = when {
            !settings.hasSoundProfile -> "Pas encore appris : commence par là."
            else -> when (settings.soundQuality) {
                ProfileQuality.GOOD.name -> "✓  Bruit appris · fiabilité bonne"
                ProfileQuality.MEDIUM.name -> "✓  Bruit appris · fiabilité moyenne"
                else -> "Bruit appris · fiabilité faible, réapprends avec un bruit plus sec"
            }
        }
        b.btnLearn.text = if (settings.hasSoundProfile) "Réapprendre mon bruit" else "Apprendre mon bruit"

        b.switchProximity.isEnabled = hasProximitySensor
        if (!hasProximitySensor) {
            b.proximityStatus.text = "Pas de capteur de proximité sur ce téléphone."
        }
        b.eyesSettings.isVisible = settings.eyesEnabled
    }

    private fun onPermissionResult(granted: Boolean, permission: String, name: String) {
        if (!granted && !shouldShowRequestPermissionRationale(permission)) {
            Toast.makeText(this, "Autorise $name dans les réglages de l’appli", Toast.LENGTH_LONG).show()
            openAppInfo()
        }
        refreshSetup()
        if (granted && startAfterPermission) onStartClicked()
        startAfterPermission = false
    }

    private fun onStartClicked() {
        if (LiveState.state.value.running) return
        val sound = settings.soundEnabled
        val proximity = settings.proximityEnabled && hasProximitySensor
        val eyes = settings.eyesEnabled
        if (!sound && !proximity && !eyes) {
            Toast.makeText(this, "Active au moins un déclencheur", Toast.LENGTH_LONG).show()
            return
        }
        if (sound && !settings.hasSoundProfile) {
            if (!proximity && !eyes) {
                startActivity(Intent(this, CalibrationActivity::class.java))
                return
            }
            Toast.makeText(this, "Bruit pas encore appris : seuls les autres déclencheurs tournent", Toast.LENGTH_LONG).show()
        }
        if (sound && settings.hasSoundProfile && !granted(Manifest.permission.RECORD_AUDIO)) {
            startAfterPermission = true
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (eyes && !granted(Manifest.permission.CAMERA)) {
            startAfterPermission = true
            cameraPermission.launch(Manifest.permission.CAMERA)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !settings.askedNotifications &&
            !granted(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            // Only used to show the "Stop" notification; detection works without it.
            settings.askedNotifications = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        startDetection()
    }

    private fun startDetection() {
        LiveState.update { it.copy(error = null) }
        try {
            DetectionService.start(this)
        } catch (e: Exception) {
            Toast.makeText(this, "Impossible de démarrer : ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        if (!ScrollAccessibilityService.isEnabled(this)) {
            Toast.makeText(this, "Active aussi le service d’accessibilité, sinon rien ne défilera", Toast.LENGTH_LONG).show()
        }
    }

    /** Trigger choices are read when detection starts, so restart it to apply them. */
    private fun restartIfRunning() {
        refreshSetup()
        if (!LiveState.state.value.running) return
        DetectionService.stop(this)
        lifecycleScope.launch {
            withTimeoutOrNull(5_000) { LiveState.state.first { !it.running } }
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) onStartClicked()
        }
    }

    // --- Settings ---------------------------------------------------------------

    private fun bindTriggers() {
        val b = binding
        b.switchSound.isChecked = settings.soundEnabled
        b.switchSound.setOnCheckedChangeListener { _, checked ->
            settings.soundEnabled = checked
            restartIfRunning()
        }
        b.switchProximity.isChecked = settings.proximityEnabled && hasProximitySensor
        b.switchProximity.setOnCheckedChangeListener { _, checked ->
            settings.proximityEnabled = checked
            restartIfRunning()
        }
        b.switchEyes.isChecked = settings.eyesEnabled
        b.switchEyes.setOnCheckedChangeListener { _, checked ->
            settings.eyesEnabled = checked
            restartIfRunning()
        }
        b.switchDouble.isChecked = settings.doubleForPrevious
        b.switchDouble.setOnCheckedChangeListener { _, checked -> settings.doubleForPrevious = checked }

        b.sliderSensitivity.value = (settings.soundSensitivity * 100f).roundToInt().coerceIn(-10, 10).toFloat()
        b.sliderSensitivity.addOnChangeListener { _, value, _ ->
            settings.soundSensitivity = value / 100f
            updateSensitivityLabel()
        }
        updateSensitivityLabel()
    }

    private fun updateSensitivityLabel() {
        val v = binding.sliderSensitivity.value.roundToInt()
        binding.sensitivityLabel.text = when {
            v == 0 -> "Sensibilité : normale"
            v < 0 -> "Sensibilité : plus stricte ($v)"
            else -> "Sensibilité : plus sensible (+$v)"
        }
    }

    private fun bindEyeSettings() {
        val modeButtons = mapOf(
            TriggerMode.WINK_ANY to binding.modeWinkAny,
            TriggerMode.WINK_LEFT_RIGHT to binding.modeWinkLeftRight,
            TriggerMode.LONG_BLINK to binding.modeLongBlink,
            TriggerMode.DOUBLE_BLINK to binding.modeDoubleBlink,
        )
        modeButtons.getValue(settings.mode).isChecked = true
        binding.modeGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = modeButtons.entries.firstOrNull { it.value.id == checkedId }?.key
                ?: return@setOnCheckedChangeListener
            settings.mode = mode
            updateHoldLabel()
        }

        binding.sliderHold.value = snap(settings.holdMs.toFloat(), 150f, 800f, 50f)
        binding.sliderHold.addOnChangeListener { _, value, _ ->
            settings.holdMs = value.toLong()
            updateHoldLabel()
        }
        updateHoldLabel()

        binding.sliderThreshold.value = snap(settings.closedThreshold * 100f, 10f, 50f, 5f)
        binding.sliderThreshold.addOnChangeListener { _, value, _ ->
            settings.closedThreshold = value / 100f
            binding.thresholdLabel.text = "Œil considéré fermé sous : ${value.roundToInt()} %"
        }
        binding.thresholdLabel.text = "Œil considéré fermé sous : ${binding.sliderThreshold.value.roundToInt()} %"

        binding.switchSwap.isChecked = settings.swapEyes
        binding.switchSwap.setOnCheckedChangeListener { _, checked -> settings.swapEyes = checked }
    }

    private fun updateHoldLabel() {
        val hold = binding.sliderHold.value.toLong()
        binding.sliderHold.isEnabled = settings.mode != TriggerMode.DOUBLE_BLINK
        binding.holdLabel.text = when (settings.mode) {
            TriggerMode.WINK_ANY, TriggerMode.WINK_LEFT_RIGHT -> "Durée du clin d’œil : $hold ms"
            TriggerMode.LONG_BLINK ->
                "Durée yeux fermés : ${maxOf(hold, DetectorConfig.LONG_BLINK_MIN_MS)} ms (min. 500 ms)"
            TriggerMode.DOUBLE_BLINK -> "Durée : non utilisée en mode double clignement"
        }
    }

    private fun bindGeneral() {
        binding.switchOnlyTargets.isChecked = settings.onlyTargetApps
        binding.switchOnlyTargets.setOnCheckedChangeListener { _, checked -> settings.onlyTargetApps = checked }
        binding.switchVibrate.isChecked = settings.vibrate
        binding.switchVibrate.setOnCheckedChangeListener { _, checked -> settings.vibrate = checked }
    }

    // --- Live display -----------------------------------------------------------

    private fun render(state: LiveSnapshot) {
        val b = binding
        b.btnStart.text = if (state.running) "Arrêter" else "Démarrer"
        b.liveStatus.text = when {
            state.error != null -> state.error
            !state.running -> "Détection arrêtée"
            !state.listening -> "En veille — se réactive dans TikTok ou Instagram"
            else -> "À l’écoute — essaie ton déclencheur"
        }
        b.count.text = state.count.toString()
        b.lastAction.text = state.lastAction?.let { "Dernier : $it" } ?: "déclenchements"

        // Sound
        b.liveSound.isVisible = settings.soundEnabled
        val listeningSound = state.running && state.listening && state.soundActive
        b.progressSound.setProgressCompat(if (listeningSound) (state.soundLevel * 100).roundToInt() else 0, false)
        val recent = state.lastSoundScore >= 0f && SystemClock.elapsedRealtime() - state.lastSoundAt < 10_000
        b.lastSound.text = when {
            !state.running || !state.soundActive -> "Micro inactif"
            !recent -> "Aucun bruit net détecté pour l’instant"
            else -> {
                val score = (state.lastSoundScore * 100).roundToInt()
                val threshold = (state.lastSoundThreshold * 100).roundToInt()
                if (state.lastSoundAccepted) {
                    "✓  Dernier bruit reconnu : $score % (seuil $threshold %)"
                } else {
                    "✗  Dernier bruit ignoré : $score % (seuil $threshold %)"
                }
            }
        }

        // Proximity
        b.liveProximity.isVisible = settings.proximityEnabled && hasProximitySensor
        b.liveProximity.text = when {
            !state.running || !state.proximityActive || !state.listening -> "Capteur de proximité : inactif"
            state.proximityNear == true -> "Capteur de proximité : PROCHE"
            state.proximityNear == false -> "Capteur de proximité : loin"
            else -> "Capteur de proximité : en attente (passe la main)"
        }

        // Eyes
        b.liveEyes.isVisible = settings.eyesEnabled
        val eyesLive = state.running && state.listening && state.eyesActive && state.faceDetected
        renderEye(b.progressLeft, b.labelLeft, "Œil gauche", state.left, eyesLive)
        renderEye(b.progressRight, b.labelRight, "Œil droit", state.right, eyesLive)
    }

    private fun renderEye(bar: LinearProgressIndicator, label: TextView, name: String, value: Float, live: Boolean) {
        if (!live || value < 0f) {
            bar.setProgressCompat(0, false)
            label.text = name
            return
        }
        val percent = (value * 100).roundToInt().coerceIn(0, 100)
        bar.setProgressCompat(percent, true)
        val closed = value < settings.closedThreshold
        label.text = "$name · $percent % ouvert" + if (closed) "  — fermé" else ""
    }

    /** Material sliders crash if the value is not on a step, so round it first. */
    private fun snap(value: Float, from: Float, to: Float, step: Float): Float {
        val clamped = value.coerceIn(from, to)
        return from + ((clamped - from) / step).roundToInt() * step
    }

    private fun openAppInfo() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
        )
    }
}
