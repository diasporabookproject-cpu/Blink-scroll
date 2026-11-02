package app.blinkscroll

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import app.blinkscroll.audio.AudioCapture
import app.blinkscroll.audio.AudioConfig
import app.blinkscroll.audio.OnsetFeatureStream
import app.blinkscroll.audio.ProfileQuality
import app.blinkscroll.audio.SoundCalibrator
import app.blinkscroll.audio.SoundProfile
import app.blinkscroll.databinding.ActivityCalibrationBinding
import kotlin.math.roundToInt

/**
 * Teaches the app the user's sound in two steps:
 *  1. make the sound 5 times (positive examples),
 *  2. type on the keyboard for 10 seconds (sounds to ignore),
 * then lets the user test the result before saving it.
 */
class CalibrationActivity : AppCompatActivity() {

    private enum class Step { INTRO, RECORD_SOUND, INTRO_NOISE, RECORD_NOISE, RESULT }
    private enum class Listening { NONE, SOUND, NOISE, TEST }

    private lateinit var binding: ActivityCalibrationBinding
    private lateinit var settings: AppSettings
    private val main = Handler(Looper.getMainLooper())

    private var step = Step.INTRO
    private val positives = mutableListOf<FloatArray>()
    private val negatives = mutableListOf<FloatArray>()
    private var lastPositiveMs = Long.MIN_VALUE / 4
    private var noiseEndsAt = 0L
    private var profile: SoundProfile? = null

    @Volatile
    private var listening = Listening.NONE
    private var capture: AudioCapture? = null
    private var lastLevelPost = 0L

    /** Audio thread only. */
    private val stream = OnsetFeatureStream(onFeature = { onset, feature ->
        val mode = listening
        main.post { onFeature(mode, AudioConfig.samplesToMs(onset), feature) }
    })

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startSoundStep()
            } else {
                Toast.makeText(this, "Le micro est nécessaire pour apprendre ton bruit", Toast.LENGTH_LONG).show()
            }
        }

    private val noiseCountdown = object : Runnable {
        override fun run() {
            val left = noiseEndsAt - SystemClock.elapsedRealtime()
            if (left <= 0) {
                finishNoiseStep()
            } else {
                binding.bigCounter.text = "${(left / 1000.0).roundToInt().coerceAtLeast(1)} s"
                main.postDelayed(this, 200)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCalibrationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        settings = AppSettings(this)

        // The detection service would compete for the microphone.
        if (LiveState.state.value.running) {
            DetectionService.stop(this)
            LiveState.restartAfterCalibration = true
        }

        binding.primaryButton.setOnClickListener { onPrimary() }
        binding.secondaryButton.setOnClickListener { onSecondary() }
        show(Step.INTRO)
    }

    override fun onStart() {
        super.onStart()
        if (step == Step.RESULT) startListening(Listening.TEST)
    }

    override fun onStop() {
        super.onStop()
        main.removeCallbacks(noiseCountdown)
        stopListening()
        // Leaving in the middle of a recording restarts that step.
        when (step) {
            Step.RECORD_SOUND -> show(Step.INTRO)
            Step.RECORD_NOISE -> show(Step.INTRO_NOISE)
            else -> Unit
        }
    }

    private fun onPrimary() {
        when (step) {
            Step.INTRO -> {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    startSoundStep()
                } else {
                    micPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
            Step.RECORD_SOUND -> Unit
            Step.INTRO_NOISE -> startNoiseStep()
            Step.RECORD_NOISE -> Unit
            Step.RESULT -> save()
        }
    }

    private fun onSecondary() {
        main.removeCallbacks(noiseCountdown)
        stopListening()
        positives.clear()
        negatives.clear()
        profile = null
        show(Step.INTRO)
    }

    private fun startSoundStep() {
        positives.clear()
        lastPositiveMs = Long.MIN_VALUE / 4
        if (!startListening(Listening.SOUND)) return
        show(Step.RECORD_SOUND)
    }

    private fun startNoiseStep() {
        negatives.clear()
        if (!startListening(Listening.NOISE)) return
        noiseEndsAt = SystemClock.elapsedRealtime() + NOISE_DURATION_MS
        show(Step.RECORD_NOISE)
        main.post(noiseCountdown)
    }

    private fun finishNoiseStep() {
        stopListening()
        profile = SoundCalibrator.build(positives, negatives)
        show(Step.RESULT)
        startListening(Listening.TEST)
    }

    private fun save() {
        val p = profile ?: return
        try {
            p.save(settings.soundProfileFile)
        } catch (e: Exception) {
            Toast.makeText(this, "Impossible d’enregistrer : ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        settings.soundEnabled = true
        settings.soundQuality = p.quality.name
        Toast.makeText(this, "Bruit enregistré", Toast.LENGTH_SHORT).show()
        finish()
    }

    /** Main thread. */
    private fun onFeature(mode: Listening, onsetMs: Long, feature: FloatArray) {
        when (mode) {
            Listening.SOUND -> {
                if (step != Step.RECORD_SOUND) return
                // Ignore the tail or echo of the same sound.
                if (onsetMs - lastPositiveMs < MIN_GAP_BETWEEN_SAMPLES_MS) return
                lastPositiveMs = onsetMs
                positives += feature
                binding.bigCounter.text = "${positives.size} / ${SoundCalibrator.TARGET_POSITIVES}"
                if (positives.size >= SoundCalibrator.TARGET_POSITIVES) {
                    stopListening()
                    show(Step.INTRO_NOISE)
                }
            }
            Listening.NOISE -> {
                if (step != Step.RECORD_NOISE || negatives.size >= SoundCalibrator.MAX_NEGATIVES) return
                negatives += feature
                binding.detail.text = "${negatives.size} bruits captés"
            }
            Listening.TEST -> {
                val p = profile ?: return
                val match = p.match(feature, settings.soundSensitivity)
                val percent = (match.score * 100).roundToInt()
                binding.testResult.text = if (match.accepted) {
                    "✓  Reconnu ($percent %)"
                } else {
                    "✗  Ignoré ($percent %)"
                }
            }
            Listening.NONE -> Unit
        }
    }

    private fun startListening(mode: Listening): Boolean {
        listening = mode
        if (capture?.isRunning == true) return true
        stream.reset()
        val c = AudioCapture(onSamples = { samples, count ->
            stream.process(samples, count)
            val now = SystemClock.elapsedRealtime()
            if (now - lastLevelPost >= 80) {
                lastLevelPost = now
                val level = (((stream.levelDb + 70.0) / 60.0).coerceIn(0.0, 1.0) * 100).roundToInt()
                main.post { binding.levelBar.setProgressCompat(level, false) }
            }
        })
        capture = c
        if (!c.start()) {
            capture = null
            listening = Listening.NONE
            Toast.makeText(this, "Micro indisponible (utilisé par une autre appli ?)", Toast.LENGTH_LONG).show()
            return false
        }
        return true
    }

    private fun stopListening() {
        listening = Listening.NONE
        capture?.stop()
        capture = null
        binding.levelBar.setProgressCompat(0, false)
    }

    private fun show(newStep: Step) {
        step = newStep
        val b = binding
        val recording = newStep == Step.RECORD_SOUND || newStep == Step.RECORD_NOISE
        b.levelLabel.isVisible = recording || newStep == Step.RESULT
        b.levelBar.isVisible = recording || newStep == Step.RESULT
        b.bigCounter.isVisible = newStep != Step.INTRO && newStep != Step.INTRO_NOISE
        b.detail.isVisible = newStep == Step.RECORD_NOISE || newStep == Step.RESULT
        b.testResult.isVisible = newStep == Step.RESULT
        b.primaryButton.isVisible = !recording
        b.secondaryButton.isVisible = newStep != Step.INTRO

        when (newStep) {
            Step.INTRO -> {
                b.stepLabel.text = "Étape 1 sur 2 · Ton bruit"
                b.instruction.text = "Choisis un petit bruit facile à refaire et différent de ton clavier : " +
                    "un clic de langue contre le palais, un « tss », un claquement de lèvres…\n\n" +
                    "Fais-le 5 fois, en laissant environ une seconde entre chaque, sans taper au clavier. " +
                    "Laisse le téléphone à sa place habituelle."
                b.primaryButton.text = "Commencer"
            }
            Step.RECORD_SOUND -> {
                b.instruction.text = "J’écoute… fais ton bruit, une fois par seconde."
                b.bigCounter.text = "0 / ${SoundCalibrator.TARGET_POSITIVES}"
            }
            Step.INTRO_NOISE -> {
                b.stepLabel.text = "Étape 2 sur 2 · Ce qu’il faut ignorer"
                b.instruction.text = "Parfait. Maintenant, tape au clavier normalement pendant 10 secondes, " +
                    "comme quand tu travailles.\n\nSi tu regardes tes vidéos avec le son, lance-en une " +
                    "pendant ce temps : l’appli apprendra à l’ignorer aussi."
                b.primaryButton.text = "Commencer (10 s)"
            }
            Step.RECORD_NOISE -> {
                b.instruction.text = "Tape au clavier…"
                b.detail.text = "0 bruit capté"
            }
            Step.RESULT -> {
                val p = profile
                b.stepLabel.text = "Résultat"
                b.bigCounter.text = when (p?.quality) {
                    ProfileQuality.GOOD -> "Bonne"
                    ProfileQuality.MEDIUM -> "Moyenne"
                    else -> "Faible"
                }
                b.instruction.text = "Fiabilité de la reconnaissance"
                b.detail.text = when {
                    p == null -> ""
                    p.negatives.isEmpty() ->
                        "Aucun bruit de clavier capté : je n’ai pas pu vérifier la confusion avec le clavier."
                    p.quality == ProfileQuality.GOOD -> "Ton bruit se distingue bien du clavier."
                    p.quality == ProfileQuality.MEDIUM ->
                        "Ça devrait marcher, avec quelques erreurs possibles. Un bruit plus sec améliorerait le résultat."
                    else -> "Ton bruit ressemble trop au clavier. Recommence avec un bruit plus sec ou plus aigu."
                } + "\n\nEssaie maintenant : fais ton bruit, puis tape un peu au clavier."
                b.testResult.text = "En attente d’un bruit…"
                b.primaryButton.text = "Enregistrer"
            }
        }
    }

    private companion object {
        const val NOISE_DURATION_MS = 10_000L
        const val MIN_GAP_BETWEEN_SAMPLES_MS = 400L
    }
}
