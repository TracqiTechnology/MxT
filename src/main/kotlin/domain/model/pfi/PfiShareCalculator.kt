package domain.model.pfi

import data.contract.Med17LogFileContract
import data.contract.Med17LogFileContract.Header
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Status of an injector at a given operating point.
 */
enum class InjectorStatus {
    /** Both injectors within safe limits. */
    OK,
    /** DI on-time is within 80–100% of the HPFP limit. */
    DI_NEAR_LIMIT,
    /** DI on-time exceeds the HPFP limit. */
    DI_OVER_LIMIT,
    /** PFI on-time exceeds 85% of the available injection window. */
    PFI_NEAR_LIMIT
}

enum class GridValueProvenance {
    MEASURED,
    INTERPOLATED,
    DEFAULT_CURVE
}

enum class PressureCompensationMode {
    REFERENCE_TO_OPERATING,
    ALREADY_COMPENSATED
}

/**
 * Pressure context for KRKATE values entered at a known reference pressure.
 *
 * PFI pressures are gauge values; injector differential pressure is rail minus
 * manifold pressure. GDI rail pressures are absolute.
 */
data class InjectionPressureContext(
    val mode: PressureCompensationMode = PressureCompensationMode.REFERENCE_TO_OPERATING,
    val referencePfiDifferentialBar: Double = 4.0,
    val operatingPfiRailGaugeBar: Double = 4.0,
    val operatingManifoldGaugeBar: Double = 0.0,
    val referenceGdiRailBarAbsolute: Double = 240.0,
    val operatingGdiRailBarAbsolute: Double = 240.0
) {
    val operatingPfiDifferentialBar: Double
        get() = operatingPfiRailGaugeBar - operatingManifoldGaugeBar

    fun validate() {
        if (mode == PressureCompensationMode.ALREADY_COMPENSATED) return
        require(referencePfiDifferentialBar.isFinite() && referencePfiDifferentialBar > 0.0) {
            "PFI reference differential pressure must be finite and positive"
        }
        require(operatingPfiDifferentialBar.isFinite() && operatingPfiDifferentialBar > 0.0) {
            "PFI operating differential pressure must be positive (rail gauge pressure must exceed manifold pressure)"
        }
        require(referenceGdiRailBarAbsolute.isFinite() && referenceGdiRailBarAbsolute > 0.0) {
            "GDI reference rail pressure must be finite and positive"
        }
        require(operatingGdiRailBarAbsolute.isFinite() && operatingGdiRailBarAbsolute > 0.0) {
            "GDI operating rail pressure must be finite and positive"
        }
    }

    fun effectivePortKrkte(referenceKrkte: Double): Double {
        validate()
        return if (mode == PressureCompensationMode.ALREADY_COMPENSATED) {
            referenceKrkte
        } else {
            referenceKrkte * sqrt(referencePfiDifferentialBar / operatingPfiDifferentialBar)
        }
    }

    fun effectiveDirectKrkte(referenceKrkte: Double): Double {
        validate()
        return if (mode == PressureCompensationMode.ALREADY_COMPENSATED) {
            referenceKrkte
        } else {
            referenceKrkte * sqrt(referenceGdiRailBarAbsolute / operatingGdiRailBarAbsolute)
        }
    }
}

data class InjectionOnTimeResult(
    val pfiSharePercent: Double,
    val portOnTimeMs: Double,
    val directOnTimeMs: Double,
    val availableWindowMs: Double,
    val effectivePortKrkte: Double,
    val effectiveDirectKrkte: Double,
    val status: InjectorStatus
)

/**
 * A single row of an RPM sweep timing table.
 */
data class RpmSweepRow(
    val rpm: Double,
    val pfiSharePercent: Double,
    val portOnTimeMs: Double,
    val directOnTimeMs: Double,
    val totalFuelMs: Double,
    val status: InjectorStatus
)

/**
 * Result of the reverse PFI share calculator: for each (RPM, load) cell,
 * the suggested PFI share that keeps DI on-time at or below a target.
 */
data class ReversePfiResult(
    val rpmAxis: DoubleArray,
    val loadAxis: DoubleArray,
    val suggestedPfiShare: Array<DoubleArray>,
    val constraintFlags: Array<Array<InjectorStatus>>
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ReversePfiResult) return false
        return rpmAxis.contentEquals(other.rpmAxis) &&
            loadAxis.contentEquals(other.loadAxis) &&
            suggestedPfiShare.contentDeepEquals(other.suggestedPfiShare) &&
            constraintFlags.contentDeepEquals(other.constraintFlags)
    }

    override fun hashCode(): Int {
        var h = rpmAxis.contentHashCode()
        h = 31 * h + loadAxis.contentHashCode()
        h = 31 * h + suggestedPfiShare.contentDeepHashCode()
        h = 31 * h + constraintFlags.contentDeepHashCode()
        return h
    }
}

/**
 * Result of an RPM-dependent PFI share calculation.
 *
 * @property rpmAxis          RPM breakpoints (sorted ascending)
 * @property pfiSharePercent  PFI share at each RPM breakpoint (0–100 %)
 * @property loggedRpmAxis    RPM breakpoints extracted from log data (null when no log was used)
 * @property loggedPfiPercent PFI share from log at each [loggedRpmAxis] point (null when no log)
 */
data class PfiShareResult(
    val rpmAxis: DoubleArray,
    val pfiSharePercent: DoubleArray,
    val loggedRpmAxis: DoubleArray? = null,
    val loggedPfiPercent: DoubleArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PfiShareResult) return false
        return rpmAxis.contentEquals(other.rpmAxis) &&
            pfiSharePercent.contentEquals(other.pfiSharePercent) &&
            loggedRpmAxis.contentNullEquals(other.loggedRpmAxis) &&
            loggedPfiPercent.contentNullEquals(other.loggedPfiPercent)
    }

    override fun hashCode(): Int {
        var h = rpmAxis.contentHashCode()
        h = 31 * h + pfiSharePercent.contentHashCode()
        h = 31 * h + (loggedRpmAxis?.contentHashCode() ?: 0)
        h = 31 * h + (loggedPfiPercent?.contentHashCode() ?: 0)
        return h
    }

    private fun DoubleArray?.contentNullEquals(other: DoubleArray?): Boolean = when {
        this == null && other == null -> true
        this != null && other != null -> contentEquals(other)
        else -> false
    }
}

/**
 * Result of a 2D RPM × Load PFI share calculation.
 *
 * @property rpmAxis             RPM breakpoints (sorted ascending)
 * @property loadAxis            Load breakpoints (sorted ascending, %)
 * @property pfiSharePercent2d   PFI share grid [rpmIdx][loadIdx] in percent (0–100)
 * @property sampleCounts        Per-cell sample count [rpmIdx][loadIdx]
 * @property totalSamples        Total log samples processed
 * @property rpmOnlyCurve        1D RPM-only curve for backward compatibility
 */
data class PfiShare2dResult(
    val rpmAxis: DoubleArray,
    val loadAxis: DoubleArray,
    val pfiSharePercent2d: Array<DoubleArray>,
    val sampleCounts: Array<IntArray>,
    val totalSamples: Int,
    val rpmOnlyCurve: PfiShareResult,
    val effectiveSampleWeights: Array<DoubleArray> = emptyArray(),
    val provenance: Array<Array<GridValueProvenance>> = emptyArray()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PfiShare2dResult) return false
        return rpmAxis.contentEquals(other.rpmAxis) &&
            loadAxis.contentEquals(other.loadAxis) &&
            pfiSharePercent2d.contentDeepEquals(other.pfiSharePercent2d) &&
            sampleCounts.contentDeepEquals(other.sampleCounts) &&
            totalSamples == other.totalSamples &&
            rpmOnlyCurve == other.rpmOnlyCurve &&
            effectiveSampleWeights.contentDeepEquals(other.effectiveSampleWeights) &&
            provenance.contentDeepEquals(other.provenance)
    }

    override fun hashCode(): Int {
        var h = rpmAxis.contentHashCode()
        h = 31 * h + loadAxis.contentHashCode()
        h = 31 * h + pfiSharePercent2d.contentDeepHashCode()
        h = 31 * h + sampleCounts.contentDeepHashCode()
        h = 31 * h + totalSamples
        h = 31 * h + rpmOnlyCurve.hashCode()
        h = 31 * h + effectiveSampleWeights.contentDeepHashCode()
        h = 31 * h + provenance.contentDeepHashCode()
        return h
    }
}

/**
 * Computes RPM-dependent PFI share curves for MED17 dual-injection systems.
 *
 * MED17 2.5T (RS3 / TTRS) uses `InjSys_facPrtnPfi` (0 = pure GDI, 1 = pure PFI)
 * to control the port-vs-direct fuel split.  Real-world behaviour ramps PFI share
 * up towards torque peak, holds steady through the mid-range, then sharply declines
 * towards redline where GDI alone can support the required fuel mass.
 *
 * ## Typical default shape (RS3 2.5T)
 * ```
 *   RPM   1000  2000  3000  4000  4500  5000  5500  6000  6500  7000
 *   PFI%    30    40    50    58    60    60    60    55    35    20
 * ```
 */
object PfiShareCalculator {

    /** Default RPM axis covering idle → redline for the 2.5T 5-cylinder. */
    val DEFAULT_RPM_AXIS = doubleArrayOf(
        1000.0, 2000.0, 3000.0, 4000.0, 4500.0,
        5000.0, 5500.0, 6000.0, 6500.0, 7000.0
    )

    /** Default PFI share (%) matching [DEFAULT_RPM_AXIS]. */
    val DEFAULT_PFI_SHARE = doubleArrayOf(
        30.0, 40.0, 50.0, 58.0, 60.0,
        60.0, 60.0, 55.0, 35.0, 20.0
    )

    /**
     * Build an RPM-dependent PFI share curve.
     *
     * If [targetPfiShare] is `null` the [DEFAULT_PFI_SHARE] is linearly interpolated
     * onto [rpmAxis].  Otherwise [rpmAxis] and [targetPfiShare] must be the same length
     * and are returned directly after validation.
     *
     * @param rpmAxis         RPM breakpoints (must be sorted ascending, at least 1 element)
     * @param targetPfiShare  Desired PFI share (0–100 %) at each RPM point, or null for defaults
     * @return [PfiShareResult] with the computed curve
     */
    fun calculateRpmDependentShare(
        rpmAxis: DoubleArray = DEFAULT_RPM_AXIS,
        targetPfiShare: DoubleArray? = null
    ): PfiShareResult {
        require(rpmAxis.isNotEmpty()) { "RPM axis must not be empty" }
        require(isStrictlyIncreasingFinite(rpmAxis)) {
            "RPM axis must contain finite, strictly increasing values"
        }

        val share = if (targetPfiShare != null) {
            require(targetPfiShare.size == rpmAxis.size) {
                "targetPfiShare (${targetPfiShare.size}) must match rpmAxis (${rpmAxis.size})"
            }
            require(targetPfiShare.all { it.isFinite() && it in 0.0..100.0 }) {
                "targetPfiShare must contain finite values between 0 and 100%"
            }
            targetPfiShare.copyOf()
        } else {
            // Interpolate default curve onto the requested RPM axis
            DoubleArray(rpmAxis.size) { i ->
                interpolateClamped(rpmAxis[i], DEFAULT_RPM_AXIS, DEFAULT_PFI_SHARE)
            }
        }

        return PfiShareResult(rpmAxis.copyOf(), share)
    }

    /**
     * Extract actual PFI share from MED17 log data and return a smoothed RPM→PFI curve.
     *
     * Reads [Header.RPM_COLUMN_HEADER] and [Header.PFI_SPLIT_FACTOR_HEADER] from the
     * log map.  RPM samples are bucketed into 500 RPM bins and averaged.  Only rows
     * where RPM > 0 are considered.
     *
     * The factor from the log is 0–1; it is converted to 0–100 % in the result.
     *
     * @param logData Parsed MED17 log (header → column of doubles)
     * @return [PfiShareResult] where [PfiShareResult.loggedRpmAxis] and
     *         [PfiShareResult.loggedPfiPercent] contain the observed data, and
     *         [PfiShareResult.rpmAxis] / [PfiShareResult.pfiSharePercent] contain the
     *         default curve for overlay comparison.
     */
    fun refineFromLog(
        logData: Map<Header, List<Double>>
    ): PfiShareResult {
        val rpmColumn = logData[Header.RPM_COLUMN_HEADER]
        val pfiColumn = logData[Header.PFI_SPLIT_FACTOR_HEADER]

        if (rpmColumn.isNullOrEmpty() || pfiColumn.isNullOrEmpty()) {
            return calculateRpmDependentShare()
        }

        val rowCount = minOf(rpmColumn.size, pfiColumn.size)

        // Bucket into 500 RPM bins
        data class Bucket(var sum: Double = 0.0, var count: Int = 0)

        val buckets = mutableMapOf<Int, Bucket>()
        for (i in 0 until rowCount) {
            val rpm = rpmColumn[i]
            val pfi = pfiColumn[i]
            if (!rpm.isFinite() || rpm <= 0.0 || !pfi.isFinite() || pfi !in 0.0..1.0) continue
            val key = (rpm / RPM_BUCKET_SIZE).toInt() * RPM_BUCKET_SIZE
            val b = buckets.getOrPut(key) { Bucket() }
            b.sum += pfi
            b.count++
        }

        if (buckets.isEmpty()) {
            return calculateRpmDependentShare()
        }

        val sortedKeys = buckets.keys.sorted()
        val logRpm = DoubleArray(sortedKeys.size) { sortedKeys[it].toDouble() }
        val logPfi = DoubleArray(sortedKeys.size) { i ->
            val b = buckets[sortedKeys[i]]!!
            (b.sum / b.count * 100.0).coerceIn(0.0, 100.0)   // factor → %
        }

        val defaultResult = calculateRpmDependentShare()

        return PfiShareResult(
            rpmAxis = defaultResult.rpmAxis,
            pfiSharePercent = defaultResult.pfiSharePercent,
            loggedRpmAxis = logRpm,
            loggedPfiPercent = logPfi
        )
    }

    // ── RPM Sweep Timing Table ──────────────────────────────────────────

    /** Threshold (fraction of DI max on-time) above which DI is flagged near-limit. */
    private const val DI_NEAR_LIMIT_FRACTION = 0.80

    /** Threshold (fraction of available injection window) above which PFI is flagged near-limit. */
    private const val PFI_NEAR_LIMIT_FRACTION = 0.85

    /**
     * Compute a table of DI/PFI injection on-times across the RPM range at a fixed load.
     *
     * For each RPM step the PFI share is looked up from [pfiShareCurve] and
     * multiplied by [loadPercent] and the respective KRKTE values to yield
     * port and direct injector on-times.
     *
     * @param rpmStart       First RPM in the sweep (default 1000)
     * @param rpmEnd         Last RPM in the sweep (default 7000)
     * @param rpmStep        RPM increment between rows (default 500)
     * @param loadPercent    Fixed engine load for the sweep (default 100 %)
     * @param pfiShareCurve  1D RPM→PFI% curve to look up PFI share
     * @param portKrkte      KRKTE for port injectors (ms/%)
     * @param directKrkte    KRKTE for GDI injectors (ms/%)
     * @param diMaxOnTimeMs  Max DI on-time before HPFP limit (default 6.0 ms)
     * @param portFuelPressureBar  PFI fuel rail pressure in bar gauge (default 4.0 for 2.5T)
     * @param boostPressureBar     Boost pressure in bar gauge (default 0.0 = atmospheric).
     *                             When > 0, PFI flow is corrected for reduced ΔP:
     *                             `correction = √((P_fuel - P_boost) / P_fuel)`.
     *                             Per MED17 FR (FRLFSDP): injector flow ∝ √ΔP.
     * @return ordered list of [RpmSweepRow], one per RPM step
     */
    fun calculateRpmSweep(
        rpmStart: Double = 1000.0,
        rpmEnd: Double = 7000.0,
        rpmStep: Double = 500.0,
        loadPercent: Double = 100.0,
        pfiShareCurve: PfiShareResult,
        portKrkte: Double,
        directKrkte: Double,
        diMaxOnTimeMs: Double = 6.0,
        portFuelPressureBar: Double = 4.0,
        boostPressureBar: Double = 0.0,
        pressureContext: InjectionPressureContext? = null
    ): List<RpmSweepRow> {
        require(rpmStart.isFinite() && rpmStart > 0.0) { "rpmStart must be finite and positive" }
        require(rpmEnd.isFinite() && rpmEnd >= rpmStart) {
            "rpmEnd must be finite and greater than or equal to rpmStart"
        }
        require(rpmStep.isFinite() && rpmStep > 0.0) { "rpmStep must be finite and positive" }
        require(loadPercent.isFinite() && loadPercent >= 0.0) {
            "loadPercent must be finite and non-negative"
        }
        require(portKrkte.isFinite() && portKrkte > 0) { "portKrkte must be finite and positive" }
        require(directKrkte.isFinite() && directKrkte > 0) {
            "directKrkte must be finite and positive"
        }
        require(diMaxOnTimeMs.isFinite() && diMaxOnTimeMs > 0.0) {
            "diMaxOnTimeMs must be finite and positive"
        }
        require(pfiShareCurve.rpmAxis.isNotEmpty() &&
            isStrictlyIncreasingFinite(pfiShareCurve.rpmAxis)) {
            "PFI share curve RPM axis must contain finite, strictly increasing values"
        }
        require(pfiShareCurve.pfiSharePercent.size == pfiShareCurve.rpmAxis.size &&
            pfiShareCurve.pfiSharePercent.all { it.isFinite() && it in 0.0..100.0 }) {
            "PFI share curve must contain one finite 0–100% value per RPM breakpoint"
        }

        // FRLFSDP correction: PFI flow ∝ √(ΔP_actual / ΔP_reference)
        // ΔP_reference = portFuelPressureBar (at atmospheric, boost = 0)
        // ΔP_actual = portFuelPressureBar - boostPressureBar
        val context = pressureContext ?: InjectionPressureContext(
            referencePfiDifferentialBar = portFuelPressureBar,
            operatingPfiRailGaugeBar = portFuelPressureBar,
            operatingManifoldGaugeBar = boostPressureBar
        )
        val effectivePortKrkte = context.effectivePortKrkte(portKrkte)
        val effectiveDirectKrkte = context.effectiveDirectKrkte(directKrkte)

        val rows = mutableListOf<RpmSweepRow>()
        var rpm = rpmStart
        while (rpm <= rpmEnd + rpmStep * 0.01) {
            val pfiPercent = interpolateClamped(
                rpm, pfiShareCurve.rpmAxis, pfiShareCurve.pfiSharePercent
            ).coerceIn(0.0, 100.0)
            val pfiShare = pfiPercent / 100.0

            // PFI on-time increases when ΔP drops (less flow per ms → need more ms)
            val portOnTime = loadPercent * pfiShare * effectivePortKrkte
            val directOnTime = loadPercent * (1.0 - pfiShare) * effectiveDirectKrkte
            val totalFuel = portOnTime + directOnTime

            val availableWindow = if (rpm > 0) 120_000.0 / rpm else Double.MAX_VALUE

            val status = when {
                directOnTime > diMaxOnTimeMs -> InjectorStatus.DI_OVER_LIMIT
                directOnTime > diMaxOnTimeMs * DI_NEAR_LIMIT_FRACTION -> InjectorStatus.DI_NEAR_LIMIT
                portOnTime > availableWindow * PFI_NEAR_LIMIT_FRACTION -> InjectorStatus.PFI_NEAR_LIMIT
                else -> InjectorStatus.OK
            }

            rows.add(
                RpmSweepRow(
                    rpm = rpm,
                    pfiSharePercent = pfiPercent,
                    portOnTimeMs = portOnTime,
                    directOnTimeMs = directOnTime,
                    totalFuelMs = totalFuel,
                    status = status
                )
            )
            rpm += rpmStep
        }
        return rows
    }

    // ── Reverse PFI Share Calculator ────────────────────────────────────

    /**
     * Reverse-calculate the PFI share needed to keep DI on-time at or below
     * [targetDiOnTimeMs] across an RPM × load grid.
     *
     * For each cell the baseline total fuel is `load × directKrkte` (100% DI).
     * The required DI portion is `min(targetDiOnTimeMs, totalFuel)`.
     * PFI share = `1.0 − requiredDI / totalFuel`, clamped to [0, 100] %.
     *
     * @param targetDiOnTimeMs Desired maximum DI on-time (ms)
     * @param rpmBins          RPM axis for the output grid
     * @param loadBins         Load axis for the output grid
     * @param portKrkte        KRKTE for port injectors (ms/%)
     * @param directKrkte      KRKTE for GDI injectors (ms/%)
     * @param diMaxOnTimeMs    HPFP hard limit for DI on-time (default 6.0 ms)
     * @param portFuelPressureBar  PFI fuel rail pressure in bar gauge (default 4.0)
     * @param boostPressureBar     Boost pressure in bar gauge (default 0.0).
     *                             Applied via FRLFSDP correction to PFI flow.
     * @return [ReversePfiResult] with suggested PFI shares and constraint flags
     */
    fun reverseCalculate(
        targetDiOnTimeMs: Double,
        rpmBins: DoubleArray,
        loadBins: DoubleArray,
        portKrkte: Double,
        directKrkte: Double,
        diMaxOnTimeMs: Double = 6.0,
        portFuelPressureBar: Double = 4.0,
        boostPressureBar: Double = 0.0,
        pressureContext: InjectionPressureContext? = null
    ): ReversePfiResult {
        require(targetDiOnTimeMs.isFinite() && targetDiOnTimeMs > 0) {
            "targetDiOnTimeMs must be finite and positive"
        }
        require(portKrkte.isFinite() && portKrkte > 0) { "portKrkte must be finite and positive" }
        require(directKrkte.isFinite() && directKrkte > 0) {
            "directKrkte must be finite and positive"
        }
        require(diMaxOnTimeMs.isFinite() && diMaxOnTimeMs > 0.0) {
            "diMaxOnTimeMs must be finite and positive"
        }
        require(rpmBins.isNotEmpty() && isStrictlyIncreasingFinite(rpmBins) &&
            rpmBins.all { it > 0.0 }) {
            "RPM bins must contain positive, finite, strictly increasing values"
        }
        require(loadBins.isNotEmpty() && isStrictlyIncreasingFinite(loadBins) &&
            loadBins.all { it >= 0.0 }) {
            "Load bins must contain non-negative, finite, strictly increasing values"
        }

        val context = pressureContext ?: InjectionPressureContext(
            referencePfiDifferentialBar = portFuelPressureBar,
            operatingPfiRailGaugeBar = portFuelPressureBar,
            operatingManifoldGaugeBar = boostPressureBar
        )
        val effectivePortKrkte = context.effectivePortKrkte(portKrkte)
        val effectiveDirectKrkte = context.effectiveDirectKrkte(directKrkte)

        val shares = Array(rpmBins.size) { DoubleArray(loadBins.size) }
        val flags = Array(rpmBins.size) { Array(loadBins.size) { InjectorStatus.OK } }

        for (r in rpmBins.indices) {
            for (l in loadBins.indices) {
                val load = loadBins[l]

                val totalFuel = load * effectiveDirectKrkte
                if (totalFuel <= 0.0) {
                    shares[r][l] = 0.0
                    flags[r][l] = InjectorStatus.OK
                    continue
                }

                val requiredDi = min(targetDiOnTimeMs, totalFuel)
                val pfiShareFraction = (1.0 - requiredDi / totalFuel).coerceIn(0.0, 1.0)
                shares[r][l] = (pfiShareFraction * 100.0)

                val actualDiOnTime = load * (1.0 - pfiShareFraction) * effectiveDirectKrkte
                val pfiOnTime = load * pfiShareFraction * effectivePortKrkte
                val availableWindow = if (rpmBins[r] > 0) 120_000.0 / rpmBins[r] else Double.MAX_VALUE

                flags[r][l] = when {
                    actualDiOnTime > diMaxOnTimeMs -> InjectorStatus.DI_OVER_LIMIT
                    actualDiOnTime > diMaxOnTimeMs * DI_NEAR_LIMIT_FRACTION -> InjectorStatus.DI_NEAR_LIMIT
                    pfiOnTime > availableWindow * PFI_NEAR_LIMIT_FRACTION -> InjectorStatus.PFI_NEAR_LIMIT
                    else -> InjectorStatus.OK
                }
            }
        }

        return ReversePfiResult(
            rpmAxis = rpmBins.copyOf(),
            loadAxis = loadBins.copyOf(),
            suggestedPfiShare = shares,
            constraintFlags = flags
        )
    }

    fun calculateInjectionOnTime(
        rpm: Double,
        loadPercent: Double,
        pfiSharePercent: Double,
        portKrkte: Double,
        directKrkte: Double,
        pressureContext: InjectionPressureContext = InjectionPressureContext(),
        diMaxOnTimeMs: Double = 6.0
    ): InjectionOnTimeResult {
        require(rpm.isFinite() && rpm > 0.0) { "RPM must be finite and positive" }
        require(loadPercent.isFinite() && loadPercent >= 0.0) {
            "Load must be finite and non-negative"
        }
        require(pfiSharePercent.isFinite() && pfiSharePercent in 0.0..100.0) {
            "PFI share must be finite and between 0 and 100%"
        }
        require(portKrkte.isFinite() && portKrkte > 0.0) {
            "portKrkte must be finite and positive"
        }
        require(directKrkte.isFinite() && directKrkte > 0.0) {
            "directKrkte must be finite and positive"
        }
        require(diMaxOnTimeMs.isFinite() && diMaxOnTimeMs > 0.0) {
            "diMaxOnTimeMs must be finite and positive"
        }

        val effectivePort = pressureContext.effectivePortKrkte(portKrkte)
        val effectiveDirect = pressureContext.effectiveDirectKrkte(directKrkte)
        val share = pfiSharePercent / 100.0
        val portOnTime = loadPercent * share * effectivePort
        val directOnTime = loadPercent * (1.0 - share) * effectiveDirect
        val availableWindow = 120_000.0 / rpm
        val status = when {
            directOnTime > diMaxOnTimeMs -> InjectorStatus.DI_OVER_LIMIT
            directOnTime > diMaxOnTimeMs * DI_NEAR_LIMIT_FRACTION -> InjectorStatus.DI_NEAR_LIMIT
            portOnTime > availableWindow * PFI_NEAR_LIMIT_FRACTION -> InjectorStatus.PFI_NEAR_LIMIT
            else -> InjectorStatus.OK
        }
        return InjectionOnTimeResult(
            pfiSharePercent = pfiSharePercent,
            portOnTimeMs = portOnTime,
            directOnTimeMs = directOnTime,
            availableWindowMs = availableWindow,
            effectivePortKrkte = effectivePort,
            effectiveDirectKrkte = effectiveDirect,
            status = status
        )
    }

    // ── Internals ────────────────────────────────────────────────────────

    private const val RPM_BUCKET_SIZE = 500

    /** Linear interpolation with clamped extrapolation (same approach as [InjectorScalingSolver]). */
    internal fun interpolateClamped(x: Double, xs: DoubleArray, ys: DoubleArray): Double {
        require(xs.size == ys.size && xs.isNotEmpty())
        if (x <= xs.first()) return ys.first()
        if (x >= xs.last()) return ys.last()

        var lo = 0
        var hi = xs.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (xs[mid] <= x) lo = mid else hi = mid
        }
        val t = (x - xs[lo]) / (xs[hi] - xs[lo])
        return ys[lo] + t * (ys[hi] - ys[lo])
    }

    private fun isStrictlyIncreasingFinite(a: DoubleArray): Boolean {
        if (a.any { !it.isFinite() }) return false
        for (i in 1 until a.size) if (a[i] <= a[i - 1]) return false
        return true
    }

    // ── 2D RPM × Load binning ───────────────────────────────────────────

    /** RPM bins for 2D grid: 1000–7000 @ 500 RPM steps. */
    val DEFAULT_2D_RPM_BINS = doubleArrayOf(
        1000.0, 1500.0, 2000.0, 2500.0, 3000.0, 3500.0,
        4000.0, 4500.0, 5000.0, 5500.0, 6000.0, 6500.0, 7000.0
    )

    /** Load bins for 2D grid: 20–200 % @ 20 % steps. */
    val DEFAULT_2D_LOAD_BINS = doubleArrayOf(
        20.0, 40.0, 60.0, 80.0, 100.0, 120.0, 140.0, 160.0, 180.0, 200.0
    )

    /**
     * Extract actual PFI share from MED17 log data, binned into a 2D RPM × Load grid.
     *
     * If the log lacks an [Header.ENGINE_LOAD_HEADER] column, falls back to a
     * [PfiShare2dResult] whose 2D grid is synthesised from the 1D RPM-only curve
     * (all load columns identical).
     *
     * @param logData Parsed MED17 log (header → column of doubles)
     * @param rpmBins RPM axis for the output grid (default [DEFAULT_2D_RPM_BINS])
     * @param loadBins Load axis for the output grid (default [DEFAULT_2D_LOAD_BINS])
     * @return [PfiShare2dResult] with the 2D grid plus a backward-compatible 1D curve
     */
    fun refineFromLog2d(
        logData: Map<Header, List<Double>>,
        rpmBins: DoubleArray = DEFAULT_2D_RPM_BINS,
        loadBins: DoubleArray = DEFAULT_2D_LOAD_BINS
    ): PfiShare2dResult {
        require(rpmBins.isNotEmpty() && isStrictlyIncreasingFinite(rpmBins) &&
            rpmBins.all { it > 0.0 }) {
            "RPM bins must contain positive, finite, strictly increasing values"
        }
        require(loadBins.isNotEmpty() && isStrictlyIncreasingFinite(loadBins) &&
            loadBins.all { it >= 0.0 }) {
            "Load bins must contain non-negative, finite, strictly increasing values"
        }
        val rpmColumn = logData[Header.RPM_COLUMN_HEADER]
        val pfiColumn = logData[Header.PFI_SPLIT_FACTOR_HEADER]
        val loadColumn = logData[Header.ENGINE_LOAD_HEADER]

        // If no RPM or PFI data, return default 2D result
        if (rpmColumn.isNullOrEmpty() || pfiColumn.isNullOrEmpty()) {
            return default2dResult(rpmBins, loadBins)
        }

        // If no load column, fall back to 1D and replicate across loads
        if (loadColumn.isNullOrEmpty()) {
            return fallbackTo1d(logData, rpmBins, loadBins)
        }

        val rowCount = minOf(rpmColumn.size, pfiColumn.size, loadColumn.size)

        val sums = Array(rpmBins.size) { DoubleArray(loadBins.size) }
        val weights = Array(rpmBins.size) { DoubleArray(loadBins.size) }
        val counts = Array(rpmBins.size) { IntArray(loadBins.size) }
        var validSamples = 0

        for (i in 0 until rowCount) {
            val rpm = rpmColumn[i]
            val load = loadColumn[i]
            val pfi = pfiColumn[i]
            if (!rpm.isFinite() || !load.isFinite() || !pfi.isFinite() ||
                rpm <= 0.0 || load <= 0.0 || pfi !in 0.0..1.0
            ) {
                continue
            }
            validSamples++

            val rpmWeights = interpolatedBinWeights(rpm, rpmBins)
            val loadWeights = interpolatedBinWeights(load, loadBins)

            for ((rpmIdx, rpmW) in rpmWeights) {
                for ((loadIdx, loadW) in loadWeights) {
                    val w = rpmW * loadW
                    sums[rpmIdx][loadIdx] += pfi * w
                    weights[rpmIdx][loadIdx] += w
                    counts[rpmIdx][loadIdx]++
                }
            }
        }

        if (validSamples == 0) {
            return fallbackTo1d(logData, rpmBins, loadBins)
        }

        val pfiPercent2d = Array(rpmBins.size) { r ->
            DoubleArray(loadBins.size) { l ->
                if (weights[r][l] > 0.0) {
                    (sums[r][l] / weights[r][l] * 100.0).coerceIn(0.0, 100.0)
                } else {
                    Double.NaN
                }
            }
        }

        val defaultCurve = calculateRpmDependentShare()
        val fallbackByRpm = DoubleArray(rpmBins.size) { row ->
            interpolateClamped(
                rpmBins[row],
                defaultCurve.rpmAxis,
                defaultCurve.pfiSharePercent
            )
        }
        interpolateEmptyCells(pfiPercent2d, counts, fallbackByRpm)
        val provenance = Array(rpmBins.size) { r ->
            Array(loadBins.size) { l ->
                when {
                    counts[r][l] > 0 -> GridValueProvenance.MEASURED
                    hasMeasuredNeighbour(counts, r, l) -> GridValueProvenance.INTERPOLATED
                    else -> GridValueProvenance.DEFAULT_CURVE
                }
            }
        }

        val rpmOnlyCurve = refineFromLog(logData)

        return PfiShare2dResult(
            rpmAxis = rpmBins.copyOf(),
            loadAxis = loadBins.copyOf(),
            pfiSharePercent2d = pfiPercent2d,
            sampleCounts = counts,
            totalSamples = validSamples,
            rpmOnlyCurve = rpmOnlyCurve,
            effectiveSampleWeights = weights,
            provenance = provenance
        )
    }

    /**
     * Find the index of the nearest bin to [value].
     */
    internal fun nearestBinIndex(value: Double, bins: DoubleArray): Int {
        var best = 0
        var bestDist = abs(value - bins[0])
        for (i in 1 until bins.size) {
            val dist = abs(value - bins[i])
            if (dist < bestDist) {
                bestDist = dist
                best = i
            }
        }
        return best
    }

    /**
     * Compute interpolated bin weights for a value.
     *
     * Instead of snapping to the single nearest bin, distributes the sample
     * between the two adjacent bins using linear basis weighting.
     *
     * Returns a list of (index, weight) pairs. Weights sum to 1.0.
     */
    internal fun interpolatedBinWeights(value: Double, bins: DoubleArray): List<Pair<Int, Double>> {
        if (bins.size <= 1) return listOf(0 to 1.0)

        if (value <= bins.first()) return listOf(0 to 1.0)
        if (value >= bins.last()) return listOf(bins.size - 1 to 1.0)

        var lo = 0
        for (i in 1 until bins.size) {
            if (bins[i] >= value) { lo = i - 1; break }
        }
        val hi = lo + 1

        val span = bins[hi] - bins[lo]
        if (span <= 0.0) return listOf(lo to 1.0)

        val t = (value - bins[lo]) / span
        return if (t < 1e-9) {
            listOf(lo to 1.0)
        } else if (t > 1.0 - 1e-9) {
            listOf(hi to 1.0)
        } else {
            listOf(lo to (1.0 - t), hi to t)
        }
    }

    /**
     * Fill NaN cells by averaging immediately measured cardinal neighbours.
     * Cells outside measured support use the RPM-dependent default curve.
     */
    internal fun interpolateEmptyCells(
        grid: Array<DoubleArray>,
        counts: Array<IntArray>,
        fallbackByRpm: DoubleArray = DoubleArray(grid.size) { 50.0 }
    ) {
        require(fallbackByRpm.size == grid.size)
        for (r in grid.indices) {
            for (l in grid[r].indices) {
                if (counts[r][l] == 0) {
                    val neighbours = mutableListOf<Double>()
                    if (r > 0 && counts[r - 1][l] > 0) neighbours.add(grid[r - 1][l])
                    if (r < grid.size - 1 && counts[r + 1][l] > 0) neighbours.add(grid[r + 1][l])
                    if (l > 0 && counts[r][l - 1] > 0) neighbours.add(grid[r][l - 1])
                    if (l < grid[r].size - 1 && counts[r][l + 1] > 0) neighbours.add(grid[r][l + 1])

                    grid[r][l] = if (neighbours.isNotEmpty()) {
                        neighbours.average()
                    } else {
                        fallbackByRpm[r]
                    }
                }
            }
        }
    }

    private fun hasMeasuredNeighbour(counts: Array<IntArray>, r: Int, l: Int): Boolean =
        (r > 0 && counts[r - 1][l] > 0) ||
            (r < counts.size - 1 && counts[r + 1][l] > 0) ||
            (l > 0 && counts[r][l - 1] > 0) ||
            (l < counts[r].size - 1 && counts[r][l + 1] > 0)

    private fun default2dResult(
        rpmBins: DoubleArray = DEFAULT_2D_RPM_BINS,
        loadBins: DoubleArray = DEFAULT_2D_LOAD_BINS
    ): PfiShare2dResult {
        val defaultCurve = calculateRpmDependentShare()
        val grid = Array(rpmBins.size) { r ->
            val pfi = interpolateClamped(rpmBins[r], defaultCurve.rpmAxis, defaultCurve.pfiSharePercent)
            DoubleArray(loadBins.size) { pfi }
        }
        return PfiShare2dResult(
            rpmAxis = rpmBins.copyOf(),
            loadAxis = loadBins.copyOf(),
            pfiSharePercent2d = grid,
            sampleCounts = Array(rpmBins.size) { IntArray(loadBins.size) },
            totalSamples = 0,
            rpmOnlyCurve = defaultCurve,
            effectiveSampleWeights = Array(rpmBins.size) { DoubleArray(loadBins.size) },
            provenance = Array(rpmBins.size) {
                Array(loadBins.size) { GridValueProvenance.DEFAULT_CURVE }
            }
        )
    }

    private fun fallbackTo1d(
        logData: Map<Header, List<Double>>,
        rpmBins: DoubleArray = DEFAULT_2D_RPM_BINS,
        loadBins: DoubleArray = DEFAULT_2D_LOAD_BINS
    ): PfiShare2dResult {
        val rpmOnlyCurve = refineFromLog(logData)
        val loggedRpm = rpmOnlyCurve.loggedRpmAxis
        val loggedPfi = rpmOnlyCurve.loggedPfiPercent
        val sourceRpm = loggedRpm ?: rpmOnlyCurve.rpmAxis
        val sourcePfi = loggedPfi ?: rpmOnlyCurve.pfiSharePercent
        val grid = Array(rpmBins.size) { r ->
            val pfi = interpolateClamped(rpmBins[r], sourceRpm, sourcePfi)
            DoubleArray(loadBins.size) { pfi }
        }
        return PfiShare2dResult(
            rpmAxis = rpmBins.copyOf(),
            loadAxis = loadBins.copyOf(),
            pfiSharePercent2d = grid,
            sampleCounts = Array(rpmBins.size) { IntArray(loadBins.size) },
            totalSamples = logData[Header.RPM_COLUMN_HEADER]?.size ?: 0,
            rpmOnlyCurve = rpmOnlyCurve,
            effectiveSampleWeights = Array(rpmBins.size) { DoubleArray(loadBins.size) },
            provenance = Array(rpmBins.size) {
                Array(loadBins.size) { GridValueProvenance.INTERPOLATED }
            }
        )
    }
}
