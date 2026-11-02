package app.blinkscroll.audio

import java.io.File
import java.util.Locale

/** Result of comparing one detected sound with the learned sound. */
data class SoundMatch(
    /** Resemblance with the learned sound, 0..1. */
    val score: Float,
    /** Resemblance with the closest "not my sound" example (keyboard, background). */
    val negativeScore: Float,
    val threshold: Float,
    val accepted: Boolean,
)

enum class ProfileQuality { GOOD, MEDIUM, POOR }

/**
 * The learned sound: fingerprints of the user's sound ([positives]) and of
 * ordinary noises to ignore ([negatives]), plus a calibrated threshold.
 */
class SoundProfile(
    val positives: List<FloatArray>,
    val negatives: List<FloatArray>,
    val threshold: Float,
    /** Weakest genuine repetition minus strongest look-alike noise (higher = more reliable). */
    val separation: Float,
) {
    val quality: ProfileQuality
        get() = when {
            negatives.isEmpty() -> ProfileQuality.MEDIUM
            separation >= GOOD_SEPARATION -> ProfileQuality.GOOD
            separation >= 0f -> ProfileQuality.MEDIUM
            else -> ProfileQuality.POOR
        }

    /**
     * @param sensitivity -0.10 (stricter) .. +0.10 (more sensitive), added to the threshold.
     */
    fun match(feature: FloatArray, sensitivity: Float = 0f): SoundMatch {
        var best = -1f
        for (p in positives) best = maxOf(best, dot(p, feature))
        var bestNegative = -1f
        for (n in negatives) bestNegative = maxOf(bestNegative, dot(n, feature))
        val threshold = (threshold - sensitivity).coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)
        return SoundMatch(
            score = best,
            negativeScore = bestNegative,
            threshold = threshold,
            accepted = best >= threshold && best > bestNegative,
        )
    }

    fun save(file: File) {
        val sb = StringBuilder()
        sb.append(FORMAT).append('\n')
        sb.append(threshold).append(' ').append(separation).append('\n')
        for (p in positives) sb.append("P ").append(p.joinToString(",") { fmt(it) }).append('\n')
        for (n in negatives) sb.append("N ").append(n.joinToString(",") { fmt(it) }).append('\n')
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(sb.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(sb.toString())
            tmp.delete()
        }
    }

    companion object {
        const val GOOD_SEPARATION = 0.08f
        const val MIN_THRESHOLD = 0.40f
        const val MAX_THRESHOLD = 0.98f
        private const val FORMAT = "blinkscroll-sound-v1"

        private fun fmt(v: Float) = String.format(Locale.US, "%.5f", v)

        fun load(file: File): SoundProfile? = try {
            val lines = file.readLines()
            if (lines.isEmpty() || lines[0] != FORMAT) {
                null
            } else {
                val (threshold, separation) = lines[1].trim().split(' ').map { it.toFloat() }
                val positives = mutableListOf<FloatArray>()
                val negatives = mutableListOf<FloatArray>()
                for (line in lines.drop(2)) {
                    if (line.length < 3) continue
                    val values = line.substring(2).split(',').map { it.toFloat() }.toFloatArray()
                    if (values.size != AudioConfig.FEATURE_SIZE) continue
                    if (line[0] == 'P') positives += values else if (line[0] == 'N') negatives += values
                }
                if (positives.size < SoundCalibrator.MIN_POSITIVES) null
                else SoundProfile(positives, negatives, threshold, separation)
            }
        } catch (e: Exception) {
            null
        }
    }
}

object SoundCalibrator {
    const val MIN_POSITIVES = 3
    const val TARGET_POSITIVES = 5
    const val MAX_NEGATIVES = 200

    /**
     * Builds a profile from fingerprints of the user's sound and of noises to ignore.
     * The threshold sits between the weakest genuine repetition and the strongest look-alike.
     */
    fun build(positives: List<FloatArray>, negatives: List<FloatArray>): SoundProfile {
        require(positives.size >= MIN_POSITIVES) { "Need at least $MIN_POSITIVES samples" }
        val usedNegatives = negatives.take(MAX_NEGATIVES)

        // Leave-one-out: how well each sample is recognised by the others.
        val leaveOneOut = positives.indices.map { i ->
            positives.indices.filter { it != i }.maxOf { j -> dot(positives[i], positives[j]) }
        }
        val weakestGenuine = leaveOneOut.min()

        // How much each noise resembles the user's sound; ignore rare outliers when there are many.
        val negativeScores = usedNegatives.map { n -> positives.maxOf { dot(it, n) } }.sorted()
        val strongestLookAlike = when {
            negativeScores.isEmpty() -> null
            negativeScores.size >= 30 -> negativeScores[(negativeScores.size * 0.97).toInt().coerceAtMost(negativeScores.size - 1)]
            else -> negativeScores.last()
        }

        val threshold: Float
        val separation: Float
        if (strongestLookAlike == null) {
            threshold = weakestGenuine - 0.05f
            separation = 0f
        } else {
            separation = weakestGenuine - strongestLookAlike
            // Samples recorded back to back resemble each other more than later repetitions,
            // so when the margin allows it, sit well below the weakest sample (up to 0.12).
            threshold = when {
                separation >= 0.10f -> maxOf(weakestGenuine - 0.12f, strongestLookAlike + separation * 0.5f)
                else -> strongestLookAlike + separation * 0.5f
            }
        }
        // Drop the rare noises that look like the user's sound (e.g. made while typing):
        // matching requires a sound to be closer to the samples than to every stored noise.
        val keptNegatives = if (strongestLookAlike != null && usedNegatives.size >= 30) {
            usedNegatives.filter { n -> positives.maxOf { dot(it, n) } <= strongestLookAlike }
        } else {
            usedNegatives
        }
        return SoundProfile(
            positives = positives.map { it.copyOf() },
            negatives = keptNegatives.map { it.copyOf() },
            threshold = threshold.coerceIn(SoundProfile.MIN_THRESHOLD, SoundProfile.MAX_THRESHOLD),
            separation = separation,
        )
    }
}
