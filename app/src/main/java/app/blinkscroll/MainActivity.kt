package app.blinkscroll

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import app.blinkscroll.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: AppSettings

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                Toast.makeText(this, "Autorise la caméra dans les réglages de l’appli", Toast.LENGTH_LONG).show()
                openAppInfo()
            }
            refreshSetup()
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
        bindSettings()

        binding.btnCamera.setOnClickListener { cameraPermission.launch(Manifest.permission.CAMERA) }
        binding.btnAccess.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.btnAppInfo.setOnClickListener { openAppInfo() }
        binding.btnStart.setOnClickListener {
            if (LiveState.state.value.running) BlinkCameraService.stop(this) else onStartClicked()
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
    }

    override fun onStop() {
        LiveState.uiVisible = false
        super.onStop()
    }

    private fun hasCamera() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.CAMERA,
    ) == PackageManager.PERMISSION_GRANTED

    private fun refreshSetup() {
        val camera = hasCamera()
        binding.statusCamera.text = if (camera) "✓  Caméra autorisée" else "Caméra"
        binding.btnCamera.isVisible = !camera

        val accessibility = ScrollAccessibilityService.isEnabled(this)
        binding.statusAccess.text =
            if (accessibility) "✓  Service d’accessibilité actif" else "Service d’accessibilité"
        binding.btnAccess.isVisible = !accessibility
        binding.restrictedHelp.isVisible = !accessibility
    }

    private fun onStartClicked() {
        if (!hasCamera()) {
            cameraPermission.launch(Manifest.permission.CAMERA)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !settings.askedNotifications &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            // Only used to show the "Stop" notification; detection works without it.
            settings.askedNotifications = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        startDetection()
    }

    private fun startDetection() {
        if (!hasCamera()) return
        try {
            BlinkCameraService.start(this)
        } catch (e: Exception) {
            Toast.makeText(this, "Impossible de démarrer : ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        if (!ScrollAccessibilityService.isEnabled(this)) {
            Toast.makeText(
                this,
                "Active aussi le service d’accessibilité, sinon rien ne défilera",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun render(state: LiveSnapshot) {
        binding.btnStart.text = if (state.running) "Arrêter" else "Démarrer"
        binding.liveStatus.text = when {
            state.error != null -> state.error
            !state.running -> "Détection arrêtée"
            !state.cameraOn -> "En veille — se réactive dans TikTok ou Instagram"
            !state.faceDetected -> "Aucun visage détecté"
            else -> "Visage détecté — fais un clin d’œil"
        }
        renderEye(binding.progressLeft, binding.labelLeft, "Œil gauche", state.left, state)
        renderEye(binding.progressRight, binding.labelRight, "Œil droit", state.right, state)
        binding.count.text = state.count.toString()
        binding.lastAction.text = state.lastAction?.let { "Dernier : $it" }
            ?: "clins d’œil détectés"
    }

    private fun renderEye(
        bar: com.google.android.material.progressindicator.LinearProgressIndicator,
        label: android.widget.TextView,
        name: String,
        value: Float,
        state: LiveSnapshot,
    ) {
        if (!state.running || !state.cameraOn || !state.faceDetected || value < 0f) {
            bar.setProgressCompat(0, false)
            label.text = name
            return
        }
        val percent = (value * 100).roundToInt().coerceIn(0, 100)
        bar.setProgressCompat(percent, true)
        val closed = value < settings.closedThreshold
        label.text = "$name · $percent % ouvert" + if (closed) "  — fermé" else ""
    }

    private fun bindSettings() {
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
            updateThresholdLabel()
        }
        updateThresholdLabel()

        binding.switchOnlyTargets.isChecked = settings.onlyTargetApps
        binding.switchOnlyTargets.setOnCheckedChangeListener { _, checked -> settings.onlyTargetApps = checked }
        binding.switchVibrate.isChecked = settings.vibrate
        binding.switchVibrate.setOnCheckedChangeListener { _, checked -> settings.vibrate = checked }
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

    private fun updateThresholdLabel() {
        binding.thresholdLabel.text =
            "Œil considéré fermé sous : ${binding.sliderThreshold.value.roundToInt()} %"
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
