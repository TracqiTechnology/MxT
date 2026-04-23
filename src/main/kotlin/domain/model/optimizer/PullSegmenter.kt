package domain.model.optimizer

import kotlin.math.abs

/**
 * Phase 21: Segment WOT log data into individual acceleration pulls.
 *
 * A "pull" is a contiguous sequence of WOT samples where RPM is generally
 * increasing, representing a single WOT acceleration event. Multiple pulls
 * in a log are separated by lift-off, gear changes, or RPM drops.
 *
 * Per-pull analysis enables:
 * - Tracking calibration convergence across tuning sessions
 * - Detecting inconsistent pulls (cold engine, boost leaks, etc.)
 * - Weighting high-quality pulls more in corrections
 */
object PullSegmenter {

    /** Quality classification for a WOT pull. */
    enum class PullQuality {
        /** Clean, consistent pull with sufficient RPM range */
        GOOD,
        /** Pull has noisy data (RPM reversals, pressure spikes) */
        NOISY,
        /** Pull is too short for reliable analysis */
        SHORT,
        /** Pull was cut off before reaching full RPM range */
        INCOMPLETE
    }

    /**
     * A single WOT acceleration event extracted from log data.
     */
    data class WotPull(
        val pullIndex: Int,
        val startIdx: Int,
        val endIdx: Int,
        val rpmStart: Double,
        val rpmEnd: Double,
        val sampleCount: Int,
        val avgPressureError: Double,
        val avgLoadDeficit: Double,
        val quality: PullQuality,
        /** Per-link chain diagnosis scoped to this pull only. */
        val dominantErrors: Map<String, Double>,  // ErrorSource name → percentage
        /** Gear detected for this pull (null if gear data unavailable). */
        val gear: Int? = null,
        /** Whether boost reached target at any point during this pull. */
        val reachedTarget: Boolean = false,
        /** RPM at which boost first reached target (null if never reached). */
        val spoolUpRpm: Double? = null
    )

    // ── Segmentation parameters ────────────────────────────────────

    /** Minimum samples in a pull to be considered valid. */
    private const val MIN_PULL_SAMPLES = 10

    /** Minimum RPM span for a pull to be GOOD quality. */
    private const val MIN_RPM_SPAN_GOOD = 1500.0

    /** Minimum RPM span for a pull to not be SHORT. */
    private const val MIN_RPM_SPAN_SHORT = 800.0

    /** Maximum RPM drop allowed within a pull before splitting. */
    private const val MAX_RPM_DROP = 400.0

    /** Maximum fraction of samples with RPM reversals before NOISY. */
    private const val NOISE_THRESHOLD = 0.20

    // ── Public API ─────────────────────────────────────────────────

    /**
     * Segment WOT entries into individual pulls.
     *
     * @param wotEntries All WOT-filtered log entries (may span multiple pulls)
     * @param timestamps Optional time values for duration calculation
     * @return List of segmented pulls with quality and diagnostics
     */
    fun segmentPulls(
        wotEntries: List<OptimizerCalculator.WotLogEntry>,
        timestamps: List<Double>? = null,
        ldrxnTarget: Double = 191.0,
        trackingToleranceMbar: Double = 50.0
    ): List<WotPull> {
        if (wotEntries.size < MIN_PULL_SAMPLES) return emptyList()

        val pullBoundaries = findPullBoundaries(wotEntries)
        val pulls = mutableListOf<WotPull>()

        for ((pullIdx, range) in pullBoundaries.withIndex()) {
            val (startIdx, endIdx) = range
            val pullEntries = wotEntries.subList(startIdx, endIdx + 1)
            if (pullEntries.size < MIN_PULL_SAMPLES) continue

            val rpmStart = pullEntries.first().rpm
            val rpmEnd = pullEntries.last().rpm
            val rpmSpan = pullEntries.maxOf { it.rpm } - pullEntries.minOf { it.rpm }

            // Compute quality
            val rpmReversals = countRpmReversals(pullEntries)
            val noiseFraction = rpmReversals.toDouble() / pullEntries.size
            val quality = when {
                noiseFraction > NOISE_THRESHOLD -> PullQuality.NOISY
                rpmSpan < MIN_RPM_SPAN_SHORT -> PullQuality.SHORT
                rpmSpan < MIN_RPM_SPAN_GOOD -> PullQuality.INCOMPLETE
                else -> PullQuality.GOOD
            }

            // Compute per-pull statistics
            val avgPressureError = pullEntries.map { it.requestedMap - it.actualMap }.average()
            val avgLoadDeficit = pullEntries.map { ldrxnTarget - it.actualLoad }.average()

            // Simple dominant error classification for this pull
            val torqueLimited = pullEntries.count { it.requestedLoad < ldrxnTarget * 0.95 }
            val boostShort = pullEntries.count { (it.requestedMap - it.actualMap) > 50 }
            val onTarget = pullEntries.count { abs(it.requestedMap - it.actualMap) <= 50 && it.requestedLoad >= ldrxnTarget * 0.95 }
            val total = pullEntries.size.toDouble().coerceAtLeast(1.0)

            val dominantErrors = mapOf(
                "TORQUE_CAPPED" to (torqueLimited / total * 100),
                "BOOST_SHORTFALL" to (boostShort / total * 100),
                "ON_TARGET" to (onTarget / total * 100)
            )

            // M1: Gear detection — use modal gear for this pull
            val gears = pullEntries.mapNotNull { it.gear }
            val modalGear = if (gears.isNotEmpty()) {
                gears.groupBy { it }.maxByOrNull { it.value.size }?.key
            } else null

            // M2: Spool-up RPM — first entry where boost is on-target
            val firstOnTarget = pullEntries.firstOrNull { it.isTracking(trackingToleranceMbar) }
            val spoolUpRpm = firstOnTarget?.rpm
            val reachedTarget = firstOnTarget != null

            pulls.add(
                WotPull(
                    pullIndex = pullIdx,
                    startIdx = startIdx,
                    endIdx = endIdx,
                    rpmStart = rpmStart,
                    rpmEnd = rpmEnd,
                    sampleCount = pullEntries.size,
                    avgPressureError = avgPressureError,
                    avgLoadDeficit = avgLoadDeficit,
                    quality = quality,
                    dominantErrors = dominantErrors,
                    gear = modalGear,
                    reachedTarget = reachedTarget,
                    spoolUpRpm = spoolUpRpm
                )
            )
        }

        return pulls
    }

    /**
     * Check consistency across pulls — flag anomalous ones.
     *
     * @return Map of pull index → consistency note
     */
    fun checkConsistency(pulls: List<WotPull>): Map<Int, String> {
        if (pulls.size < 2) return emptyMap()

        val results = mutableMapOf<Int, String>()
        val goodPulls = pulls.filter { it.quality == PullQuality.GOOD }
        if (goodPulls.size < 2) return results

        val avgError = goodPulls.map { it.avgPressureError }.average()
        val stdDev = kotlin.math.sqrt(
            goodPulls.map { (it.avgPressureError - avgError).let { d -> d * d } }.average()
        )

        for (pull in goodPulls) {
            if (stdDev > 0 && abs(pull.avgPressureError - avgError) > 2 * stdDev) {
                results[pull.pullIndex] = "Anomalous: pressure error deviates by " +
                    "${String.format("%.0f", abs(pull.avgPressureError - avgError))} mbar from average"
            }
        }

        return results
    }

    // ── Private helpers ────────────────────────────────────────────

    /**
     * Find pull boundaries by detecting RPM drops, resets, or gear changes.
     *
     * M1: Gear changes within a pull create a new pull boundary. A gear shift
     * produces a transient region where boost data is unreliable (RPM drops
     * abruptly then rises again in the next gear).
     */
    private fun findPullBoundaries(entries: List<OptimizerCalculator.WotLogEntry>): List<Pair<Int, Int>> {
        val boundaries = mutableListOf<Pair<Int, Int>>()
        var pullStart = 0

        for (i in 1 until entries.size) {
            val rpmDrop = entries[i - 1].rpm - entries[i].rpm
            val gearChanged = entries[i].gear != null && entries[i - 1].gear != null
                    && entries[i].gear != entries[i - 1].gear

            if (rpmDrop > MAX_RPM_DROP || gearChanged) {
                if (i - pullStart >= MIN_PULL_SAMPLES) {
                    boundaries.add(pullStart to (i - 1))
                }
                pullStart = i
            }
        }

        // Last pull
        if (entries.size - pullStart >= MIN_PULL_SAMPLES) {
            boundaries.add(pullStart to (entries.size - 1))
        }

        return boundaries
    }

    /**
     * Count the number of RPM reversals (direction changes) in a pull.
     */
    private fun countRpmReversals(entries: List<OptimizerCalculator.WotLogEntry>): Int {
        if (entries.size < 3) return 0
        var reversals = 0
        var lastDirection = 0 // +1 = increasing, -1 = decreasing

        for (i in 1 until entries.size) {
            val diff = entries[i].rpm - entries[i - 1].rpm
            val direction = if (diff > 50) 1 else if (diff < -50) -1 else lastDirection
            if (direction != 0 && lastDirection != 0 && direction != lastDirection) {
                reversals++
            }
            if (direction != 0) lastDirection = direction
        }

        return reversals
    }
}

