package data.parser.med17log

import data.contract.Me7LogFileContract
import data.contract.Med17LogFileContract

/**
 * Adapter that converts MED17 log data (keyed by [Med17LogFileContract.Header])
 * into ME7 log data (keyed by [Me7LogFileContract.Header]) so that the existing
 * OptimizerCalculator and LdrpidCalculator can process MED17 logs without
 * any changes to their core math.
 *
 * Signal mapping:
 * ```
 * MED17 signal       → ME7 equivalent     Notes
 * ─────────────────────────────────────────────────────────────────
 * nmot_w             → nmot               Same concept, different default label
 * rl_w               → rl_w               Identical
 * rlsol_w            → rlsol_w            Identical
 * wdkba              → wdkba              Identical
 * tvldste_w/ldtvm_w  → ldtvm              WGDC (final output vs PID output)
 * psrg_w             → pvdks_w            Actual manifold absolute pressure
 * pvds_w             → pssol_w            Requested pressure target
 * pu_w               → pus_w              Barometric pressure
 * gangi              → gangi              Identical
 * lamsoni_w          → lamsoni_w          Identical
 * ```
 */
object Med17LogAdapter {

    /**
     * Convert MED17 optimizer log data to ME7 format for OptimizerCalculator.analyze.
     */
    fun toMe7OptimizerFormat(
        med17Values: Map<Med17LogFileContract.Header, List<Double>>
    ): Map<Me7LogFileContract.Header, List<Double>> {
        val result = mutableMapOf<Me7LogFileContract.Header, MutableList<Double>>()

        // Initialize all required ME7 headers with empty lists
        for (header in Me7LogFileContract.Header.entries) {
            result[header] = mutableListOf()
        }

        // Map MED17 signals to their ME7 equivalents
        mapSignal(med17Values, Med17LogFileContract.Header.RPM_COLUMN_HEADER, result, Me7LogFileContract.Header.RPM_COLUMN_HEADER)
        mapSignal(med17Values, Med17LogFileContract.Header.ENGINE_LOAD_HEADER, result, Me7LogFileContract.Header.ENGINE_LOAD_HEADER)
        mapSignal(med17Values, Med17LogFileContract.Header.REQUESTED_LOAD_HEADER, result, Me7LogFileContract.Header.REQUESTED_LOAD_HEADER)
        mapSignal(med17Values, Med17LogFileContract.Header.THROTTLE_PLATE_ANGLE_HEADER, result, Me7LogFileContract.Header.THROTTLE_PLATE_ANGLE_HEADER)
        mapSignal(med17Values, Med17LogFileContract.Header.WASTEGATE_DUTY_CYCLE_HEADER, result, Me7LogFileContract.Header.WASTEGATE_DUTY_CYCLE_HEADER)
        mapSignal(med17Values, Med17LogFileContract.Header.ABSOLUTE_BOOST_PRESSURE_ACTUAL_HEADER, result, Me7LogFileContract.Header.ABSOLUTE_BOOST_PRESSURE_ACTUAL_HEADER)
        mapSignal(med17Values, Med17LogFileContract.Header.REQUESTED_PRESSURE_HEADER, result, Me7LogFileContract.Header.REQUESTED_PRESSURE_HEADER)
        mapSignal(med17Values, Med17LogFileContract.Header.BAROMETRIC_PRESSURE_HEADER, result, Me7LogFileContract.Header.BAROMETRIC_PRESSURE_HEADER)
        mapSignal(med17Values, Med17LogFileContract.Header.SELECTED_GEAR_HEADER, result, Me7LogFileContract.Header.SELECTED_GEAR_HEADER)
        mapSignal(med17Values, Med17LogFileContract.Header.TIME_STAMP_COLUMN_HEADER, result, Me7LogFileContract.Header.TIME_STAMP_COLUMN_HEADER)
        mapSignal(med17Values, Med17LogFileContract.Header.WIDE_BAND_O2_HEADER, result, Me7LogFileContract.Header.WIDE_BAND_O2_HEADER)

        // MED17 doesn't have a separate "rl" vs "rl_w" — use rl_w for both
        mapSignal(med17Values, Med17LogFileContract.Header.ENGINE_LOAD_HEADER, result, Me7LogFileContract.Header.ACTUAL_LOAD_HEADER)

        // Enhanced signals for optimizer diagnostics (H1: KFLDHBN, H2: IAT)
        mapSignal(med17Values, Med17LogFileContract.Header.INTAKE_TEMPERATURE_HEADER, result, Me7LogFileContract.Header.INTAKE_TEMPERATURE_HEADER)
        mapSignal(med17Values, Med17LogFileContract.Header.REQUESTED_PRESSURE_MAX_HEADER, result, Me7LogFileContract.Header.REQUESTED_PRESSURE_MAX_HEADER)

        return result
    }

    /**
     * Convert MED17 LDRPID log data to ME7 format for LdrpidCalculator.
     */
    fun toMe7LdrpidFormat(
        med17Values: Map<Med17LogFileContract.Header, List<Double>>
    ): Map<Me7LogFileContract.Header, List<Double>> {
        val result = mutableMapOf<Me7LogFileContract.Header, MutableList<Double>>()

        for (header in Me7LogFileContract.Header.entries) {
            result[header] = mutableListOf()
        }

        mapSignal(med17Values, Med17LogFileContract.Header.RPM_COLUMN_HEADER, result, Me7LogFileContract.Header.RPM_COLUMN_HEADER)
        mapSignal(med17Values, Med17LogFileContract.Header.THROTTLE_PLATE_ANGLE_HEADER, result, Me7LogFileContract.Header.THROTTLE_PLATE_ANGLE_HEADER)
        mapSignal(med17Values, Med17LogFileContract.Header.WASTEGATE_DUTY_CYCLE_HEADER, result, Me7LogFileContract.Header.WASTEGATE_DUTY_CYCLE_HEADER)
        mapSignal(med17Values, Med17LogFileContract.Header.ABSOLUTE_BOOST_PRESSURE_ACTUAL_HEADER, result, Me7LogFileContract.Header.ABSOLUTE_BOOST_PRESSURE_ACTUAL_HEADER)
        mapSignal(med17Values, Med17LogFileContract.Header.BAROMETRIC_PRESSURE_HEADER, result, Me7LogFileContract.Header.BAROMETRIC_PRESSURE_HEADER)
        mapSignal(med17Values, Med17LogFileContract.Header.SELECTED_GEAR_HEADER, result, Me7LogFileContract.Header.SELECTED_GEAR_HEADER)
        mapSignal(med17Values, Med17LogFileContract.Header.TIME_STAMP_COLUMN_HEADER, result, Me7LogFileContract.Header.TIME_STAMP_COLUMN_HEADER)

        return result
    }

    private fun mapSignal(
        source: Map<Med17LogFileContract.Header, List<Double>>,
        sourceKey: Med17LogFileContract.Header,
        dest: MutableMap<Me7LogFileContract.Header, MutableList<Double>>,
        destKey: Me7LogFileContract.Header
    ) {
        source[sourceKey]?.let { dest[destKey] = it.toMutableList() }
    }
}
