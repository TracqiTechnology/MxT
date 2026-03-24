package data.parser.med17log

import data.contract.Med17LogFileContract
import data.contract.Med17LogFileContract.Header as H
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVRecord
import java.io.File
import java.io.FileReader

/**
 * Parser for Dyno Spectrum (DS1) MED17 CSV log files.
 *
 * Dyno Spectrum CSV format:
 *   Line 1: Metadata — `DS1 firmware:...,WUAZZZFFXJ...,MED17_1_62,...`
 *   Line 2: Headers — `Time(s),Description(signal_name) (unit),...`
 *   Line 3+: Numeric data rows
 *
 * Signal names are extracted from the parenthetical group in each header column.
 * For example, `Eng spd(nmot_w) (1/min)` → signal name = `nmot_w`.
 *
 * This parser produces `Map<Med17LogFileContract.Header, List<Double>>` which is
 * then adapted by [Med17LogAdapter] to feed into the platform-generic optimizer
 * and LDRPID calculators.
 */
class Med17LogParser {

    enum class LogType {
        LDRPID,
        OPTIMIZER,
        FUEL_TRIM,
        PFI_SPLIT,
        PLSOL
    }

    data class ParseDiagnostics(
        val matchedHeaders: Set<String>,
        val missingHeaders: Set<String>,
        val totalRows: Int
    )

    fun interface ProgressCallback {
        fun onProgress(value: Int, max: Int)
    }

    // Column indices resolved from header line
    private var columnIndices = mutableMapOf<Med17LogFileContract.Header, Int>()

    /** Populated after each parse attempt with header match diagnostics. */
    var lastDiagnostics: ParseDiagnostics? = null
        private set

    fun parseLogDirectory(
        logType: LogType,
        directory: File,
        callback: ProgressCallback
    ): Map<Med17LogFileContract.Header, List<Double>> {
        val map = generateMap(logType)
        val files = directory.listFiles()?.filter {
            it.isFile && it.name.endsWith(".csv", ignoreCase = true)
        } ?: return map
        val numFiles = files.size
        var count = 0

        for (file in files) {
            parse(file, logType, map)
            callback.onProgress(++count, numFiles)
        }

        return map
    }

    fun parseLogFile(
        logType: LogType,
        file: File
    ): Map<Med17LogFileContract.Header, List<Double>> {
        val map = generateMap(logType)
        parse(file, logType, map)
        return map
    }

    private fun parse(
        file: File,
        logType: LogType,
        map: Map<Med17LogFileContract.Header, MutableList<Double>>
    ) {
        columnIndices.clear()
        var rowCount = 0

        try {
            FileReader(file).use { reader ->
                val records = CSVFormat.RFC4180.parse(reader)
                val iterator = records.iterator()
                var headersFound = false
                var isFirstLine = true

                while (iterator.hasNext()) {
                    val record = iterator.next()

                    // Line 1: metadata line (DS1 firmware info) — skip
                    if (isFirstLine) {
                        isFirstLine = false
                        // Check if this is a Dyno Spectrum metadata line
                        if (record.size() > 0) {
                            val first = record.get(0)
                            if (first.contains("firmware") || first.contains("DS1") || first.contains("MED17")) {
                                continue
                            }
                        }
                    }

                    // Try to parse as header line
                    if (!headersFound) {
                        for (i in 0 until record.size()) {
                            val raw = record.get(i).trim()
                            val signalName = extractSignalName(raw)

                            // Match against all MED17 headers by their current header value
                            for (header in Med17LogFileContract.Header.entries) {
                                if (header.header == signalName || header.header == raw) {
                                    columnIndices[header] = i
                                }
                            }

                            // Special case: Time column is just "Time(s)" → signal = "s" but we want the column
                            if (raw.startsWith("Time(") || raw == "Time") {
                                columnIndices[H.TIME_STAMP_COLUMN_HEADER] = i
                            }
                        }

                        headersFound = headersFound(logType)
                        if (headersFound) continue
                        // If this line didn't produce headers, try next line
                        continue
                    }

                    // Parse data rows
                    rowCount++
                    try {
                        when (logType) {
                            LogType.LDRPID -> parseLdrpidRow(record, map)
                            LogType.OPTIMIZER -> parseOptimizerRow(record, map)
                            LogType.FUEL_TRIM -> parseFuelTrimRow(record, map)
                            LogType.PFI_SPLIT -> parsePfiSplitRow(record, map)
                            LogType.PLSOL -> parsePlsolRow(record, map)
                        }
                    } catch (_: NumberFormatException) {
                    } catch (_: ArrayIndexOutOfBoundsException) {
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Populate diagnostics after parse attempt
        val matched = columnIndices.keys.map { it.header }.toSet()
        val required = requiredHeaders(logType)
        val missing = required - matched
        lastDiagnostics = ParseDiagnostics(
            matchedHeaders = matched,
            missingHeaders = missing,
            totalRows = rowCount
        )
    }

    /**
     * Extract signal name from Dyno Spectrum (DS1) column header format.
     *
     * Format: `Description text(signal_name) (unit)`
     * We need the content of the LAST parenthetical group that is NOT the trailing unit.
     *
     * Examples:
     *   `Eng spd(nmot_w) (1/min)` → `nmot_w`
     *   `Load rel(rl_w) (%)` → `rl_w`
     *   `WGDC(tvldste_w) (%)` → `tvldste_w`
     *   `Tgt dist fac prop port fuel inj(InjSys_facPrtnPfiTar) (-)` → `InjSys_facPrtnPfiTar`
     *   `Time(s)` → `s` (special case handled elsewhere)
     */
    private fun extractSignalName(header: String): String {
        // Find all parenthetical groups
        val groups = Regex("\\(([^)]+)\\)").findAll(header).toList()

        return when {
            groups.size >= 2 -> {
                // Last group is unit, second-to-last is the signal name
                groups[groups.size - 2].groupValues[1]
            }
            groups.size == 1 -> {
                // Single group — could be the signal or the unit
                groups[0].groupValues[1]
            }
            else -> header.trim()
        }
    }

    private fun parseLdrpidRow(
        record: CSVRecord,
        map: Map<Med17LogFileContract.Header, MutableList<Double>>
    ) {
        val time = getDouble(record, H.TIME_STAMP_COLUMN_HEADER) ?: return
        val rpm = getDouble(record, H.RPM_COLUMN_HEADER) ?: return
        val throttle = getDouble(record, H.THROTTLE_PLATE_ANGLE_HEADER) ?: return
        val baro = getDouble(record, H.BAROMETRIC_PRESSURE_HEADER) ?: return
        val wgdc = getDouble(record, H.WASTEGATE_DUTY_CYCLE_HEADER)
            ?: getDouble(record, H.LDR_DUTY_CYCLE_HEADER) ?: return
        val absBoost = getDouble(record, H.ABSOLUTE_BOOST_PRESSURE_ACTUAL_HEADER) ?: return
        val gear = getDouble(record, H.SELECTED_GEAR_HEADER) ?: 0.0

        map[H.TIME_STAMP_COLUMN_HEADER]!!.add(time)
        map[H.RPM_COLUMN_HEADER]!!.add(rpm)
        map[H.THROTTLE_PLATE_ANGLE_HEADER]!!.add(throttle)
        map[H.BAROMETRIC_PRESSURE_HEADER]!!.add(baro)
        map[H.WASTEGATE_DUTY_CYCLE_HEADER]!!.add(wgdc)
        map[H.ABSOLUTE_BOOST_PRESSURE_ACTUAL_HEADER]!!.add(absBoost)
        map[H.SELECTED_GEAR_HEADER]!!.add(gear)
    }

    private fun parseOptimizerRow(
        record: CSVRecord,
        map: Map<Med17LogFileContract.Header, MutableList<Double>>
    ) {
        val time = getDouble(record, H.TIME_STAMP_COLUMN_HEADER) ?: return
        val rpm = getDouble(record, H.RPM_COLUMN_HEADER) ?: return
        val throttle = getDouble(record, H.THROTTLE_PLATE_ANGLE_HEADER) ?: return
        val wgdc = getDouble(record, H.WASTEGATE_DUTY_CYCLE_HEADER)
            ?: getDouble(record, H.LDR_DUTY_CYCLE_HEADER) ?: return
        val baro = getDouble(record, H.BAROMETRIC_PRESSURE_HEADER) ?: return
        val absBoost = getDouble(record, H.ABSOLUTE_BOOST_PRESSURE_ACTUAL_HEADER) ?: return
        val reqPressure = getDouble(record, H.REQUESTED_PRESSURE_HEADER) ?: return
        val reqLoad = getDouble(record, H.REQUESTED_LOAD_HEADER) ?: return
        val engLoad = getDouble(record, H.ENGINE_LOAD_HEADER) ?: return

        map[H.TIME_STAMP_COLUMN_HEADER]!!.add(time)
        map[H.RPM_COLUMN_HEADER]!!.add(rpm)
        map[H.THROTTLE_PLATE_ANGLE_HEADER]!!.add(throttle)
        map[H.WASTEGATE_DUTY_CYCLE_HEADER]!!.add(wgdc)
        map[H.BAROMETRIC_PRESSURE_HEADER]!!.add(baro)
        map[H.ABSOLUTE_BOOST_PRESSURE_ACTUAL_HEADER]!!.add(absBoost)
        map[H.REQUESTED_PRESSURE_HEADER]!!.add(reqPressure)
        map[H.REQUESTED_LOAD_HEADER]!!.add(reqLoad)
        map[H.ENGINE_LOAD_HEADER]!!.add(engLoad)

        // Optional: fupsrls_w (live VE factor — logged, useful for diagnostics)
        getDouble(record, H.FUPSRLS_HEADER)?.let {
            map[H.FUPSRLS_HEADER]!!.add(it)
        }
    }

    private fun parseFuelTrimRow(
        record: CSVRecord,
        map: Map<Med17LogFileContract.Header, MutableList<Double>>
    ) {
        val time = getDouble(record, H.TIME_STAMP_COLUMN_HEADER) ?: return
        val rpm = getDouble(record, H.RPM_COLUMN_HEADER) ?: return
        val load = getDouble(record, H.ENGINE_LOAD_HEADER) ?: return

        // STFT: prefer frm_w, fall back to fr_w
        val stft = getDouble(record, H.STFT_MIXED_COLUMN_HEADER)
            ?: getDouble(record, H.STFT_COLUMN_HEADER)
        // LTFT: prefer fra_w, fall back to longft1_w
        val ltft = getDouble(record, H.LTFT_COLUMN_HEADER)
            ?: getDouble(record, H.LONG_TERM_FT_HEADER)

        // Require at least one fuel trim signal
        if (stft == null && ltft == null) return

        map[H.TIME_STAMP_COLUMN_HEADER]!!.add(time)
        map[H.RPM_COLUMN_HEADER]!!.add(rpm)
        map[H.ENGINE_LOAD_HEADER]!!.add(load)

        // Store STFT under whichever header was found (prefer STFT_MIXED)
        if (stft != null) {
            val stftHeader = if (H.STFT_MIXED_COLUMN_HEADER in columnIndices)
                H.STFT_MIXED_COLUMN_HEADER else H.STFT_COLUMN_HEADER
            map[stftHeader]!!.add(stft)
        }
        if (ltft != null) {
            val ltftHeader = if (H.LTFT_COLUMN_HEADER in columnIndices)
                H.LTFT_COLUMN_HEADER else H.LONG_TERM_FT_HEADER
            map[ltftHeader]!!.add(ltft)
        }

        // Optional signals (avoid double-adding longft1_w if already stored as ltft above)
        if (ltft == null || (H.LTFT_COLUMN_HEADER in columnIndices)) {
            // Only add longft1_w separately if it wasn't used as the ltft fallback
            getDouble(record, H.LONG_TERM_FT_HEADER)?.let { map[H.LONG_TERM_FT_HEADER]?.add(it) }
        }
        getDouble(record, H.FUEL_MASS_REL_HEADER)?.let { map[H.FUEL_MASS_REL_HEADER]?.add(it) }
        getDouble(record, H.LAMBDA_CONTROL_ACTIVE_HEADER)?.let { map[H.LAMBDA_CONTROL_ACTIVE_HEADER]?.add(it) }
    }

    private fun parsePlsolRow(
        record: CSVRecord,
        map: Map<Med17LogFileContract.Header, MutableList<Double>>
    ) {
        val time = getDouble(record, H.TIME_STAMP_COLUMN_HEADER) ?: return
        val load = getDouble(record, H.ENGINE_LOAD_HEADER) ?: return
        val pressure = getDouble(record, H.ABSOLUTE_BOOST_PRESSURE_ACTUAL_HEADER) ?: return
        val baro = getDouble(record, H.BAROMETRIC_PRESSURE_HEADER) ?: return
        val throttle = getDouble(record, H.THROTTLE_PLATE_ANGLE_HEADER) ?: return

        map[H.TIME_STAMP_COLUMN_HEADER]!!.add(time)
        map[H.ENGINE_LOAD_HEADER]!!.add(load)
        map[H.ABSOLUTE_BOOST_PRESSURE_ACTUAL_HEADER]!!.add(pressure)
        map[H.BAROMETRIC_PRESSURE_HEADER]!!.add(baro)
        map[H.THROTTLE_PLATE_ANGLE_HEADER]!!.add(throttle)

        getDouble(record, H.FUPSRLS_HEADER)?.let { map[H.FUPSRLS_HEADER]?.add(it) }
    }

    private fun parsePfiSplitRow(
        record: CSVRecord,
        map: Map<Med17LogFileContract.Header, MutableList<Double>>
    ) {
        val rpm = getDouble(record, H.RPM_COLUMN_HEADER) ?: return
        val pfi = getDouble(record, H.PFI_SPLIT_FACTOR_HEADER)
            ?: getDouble(record, H.PFI_SPLIT_FACTOR_UNLIM_HEADER) ?: return

        map[H.RPM_COLUMN_HEADER]!!.add(rpm)
        map[H.PFI_SPLIT_FACTOR_HEADER]!!.add(pfi)
        getDouble(record, H.PFI_SPLIT_FACTOR_UNLIM_HEADER)?.let { map[H.PFI_SPLIT_FACTOR_UNLIM_HEADER]?.add(it) }
    }

    private fun getDouble(
        record: CSVRecord,
        header: Med17LogFileContract.Header
    ): Double? {
        val idx = columnIndices[header] ?: return null
        return try {
            if (idx < record.size()) record.get(idx).trim().toDoubleOrNull() else null
        } catch (_: Exception) {
            null
        }
    }

    private fun headersFound(logType: LogType): Boolean {
        val hasTime = H.TIME_STAMP_COLUMN_HEADER in columnIndices
        val hasRpm = H.RPM_COLUMN_HEADER in columnIndices
        val hasThrottle = H.THROTTLE_PLATE_ANGLE_HEADER in columnIndices
        val hasBaro = H.BAROMETRIC_PRESSURE_HEADER in columnIndices
        val hasBoost = H.ABSOLUTE_BOOST_PRESSURE_ACTUAL_HEADER in columnIndices
        val hasWgdc = H.WASTEGATE_DUTY_CYCLE_HEADER in columnIndices ||
                      H.LDR_DUTY_CYCLE_HEADER in columnIndices

        return when (logType) {
            LogType.LDRPID -> hasTime && hasRpm && hasThrottle && hasBaro && hasBoost && hasWgdc
            LogType.OPTIMIZER -> hasTime && hasRpm && hasThrottle && hasBaro && hasBoost &&
                    hasWgdc &&
                    H.REQUESTED_PRESSURE_HEADER in columnIndices &&
                    H.REQUESTED_LOAD_HEADER in columnIndices &&
                    H.ENGINE_LOAD_HEADER in columnIndices
            LogType.FUEL_TRIM -> {
                val hasStft = H.STFT_COLUMN_HEADER in columnIndices ||
                              H.STFT_MIXED_COLUMN_HEADER in columnIndices
                val hasLtft = H.LTFT_COLUMN_HEADER in columnIndices ||
                              H.LONG_TERM_FT_HEADER in columnIndices
                val hasLoad = H.ENGINE_LOAD_HEADER in columnIndices
                hasTime && hasRpm && hasLoad && (hasStft || hasLtft)
            }
            LogType.PFI_SPLIT -> {
                val hasPfi = H.PFI_SPLIT_FACTOR_HEADER in columnIndices ||
                             H.PFI_SPLIT_FACTOR_UNLIM_HEADER in columnIndices
                hasTime && hasRpm && hasPfi
            }
            LogType.PLSOL -> {
                val hasLoad = H.ENGINE_LOAD_HEADER in columnIndices
                hasTime && hasLoad && hasBoost && hasBaro && hasThrottle
            }
        }
    }

    /**
     * Returns the set of required header signal names for a given [LogType].
     * For headers with alternatives (e.g., WGDC or LDR duty cycle), both are listed —
     * the requirement is satisfied if at least one is matched (handled by [headersFound]).
     */
    private fun requiredHeaders(logType: LogType): Set<String> {
        val common = setOf(H.TIME_STAMP_COLUMN_HEADER.header)
        val rpm = H.RPM_COLUMN_HEADER.header
        val throttle = H.THROTTLE_PLATE_ANGLE_HEADER.header
        val baro = H.BAROMETRIC_PRESSURE_HEADER.header
        val boost = H.ABSOLUTE_BOOST_PRESSURE_ACTUAL_HEADER.header
        val wgdc = H.WASTEGATE_DUTY_CYCLE_HEADER.header
        val ldr = H.LDR_DUTY_CYCLE_HEADER.header

        return common + when (logType) {
            LogType.LDRPID -> setOf(rpm, throttle, baro, boost, wgdc, ldr)
            LogType.OPTIMIZER -> setOf(
                rpm, throttle, baro, boost, wgdc, ldr,
                H.REQUESTED_PRESSURE_HEADER.header,
                H.REQUESTED_LOAD_HEADER.header,
                H.ENGINE_LOAD_HEADER.header
            )
            LogType.FUEL_TRIM -> setOf(
                rpm,
                H.ENGINE_LOAD_HEADER.header,
                H.STFT_COLUMN_HEADER.header,
                H.STFT_MIXED_COLUMN_HEADER.header,
                H.LTFT_COLUMN_HEADER.header,
                H.LONG_TERM_FT_HEADER.header
            )
            LogType.PFI_SPLIT -> setOf(
                rpm,
                H.PFI_SPLIT_FACTOR_HEADER.header,
                H.PFI_SPLIT_FACTOR_UNLIM_HEADER.header
            )
            LogType.PLSOL -> setOf(
                H.ENGINE_LOAD_HEADER.header,
                boost, baro, throttle
            )
        }
    }

    private fun generateMap(logType: LogType): Map<Med17LogFileContract.Header, MutableList<Double>> {
        val map = mutableMapOf<Med17LogFileContract.Header, MutableList<Double>>()

        map[H.START_TIME_HEADER] = mutableListOf()

        when (logType) {
            LogType.LDRPID -> {
                map[H.TIME_STAMP_COLUMN_HEADER] = mutableListOf()
                map[H.RPM_COLUMN_HEADER] = mutableListOf()
                map[H.THROTTLE_PLATE_ANGLE_HEADER] = mutableListOf()
                map[H.BAROMETRIC_PRESSURE_HEADER] = mutableListOf()
                map[H.WASTEGATE_DUTY_CYCLE_HEADER] = mutableListOf()
                map[H.ABSOLUTE_BOOST_PRESSURE_ACTUAL_HEADER] = mutableListOf()
                map[H.SELECTED_GEAR_HEADER] = mutableListOf()
            }
            LogType.OPTIMIZER -> {
                map[H.TIME_STAMP_COLUMN_HEADER] = mutableListOf()
                map[H.RPM_COLUMN_HEADER] = mutableListOf()
                map[H.THROTTLE_PLATE_ANGLE_HEADER] = mutableListOf()
                map[H.WASTEGATE_DUTY_CYCLE_HEADER] = mutableListOf()
                map[H.BAROMETRIC_PRESSURE_HEADER] = mutableListOf()
                map[H.ABSOLUTE_BOOST_PRESSURE_ACTUAL_HEADER] = mutableListOf()
                map[H.REQUESTED_PRESSURE_HEADER] = mutableListOf()
                map[H.REQUESTED_LOAD_HEADER] = mutableListOf()
                map[H.ENGINE_LOAD_HEADER] = mutableListOf()
                map[H.FUPSRLS_HEADER] = mutableListOf()
            }
            LogType.FUEL_TRIM -> {
                map[H.TIME_STAMP_COLUMN_HEADER] = mutableListOf()
                map[H.RPM_COLUMN_HEADER] = mutableListOf()
                map[H.ENGINE_LOAD_HEADER] = mutableListOf()
                map[H.STFT_COLUMN_HEADER] = mutableListOf()
                map[H.STFT_MIXED_COLUMN_HEADER] = mutableListOf()
                map[H.LTFT_COLUMN_HEADER] = mutableListOf()
                map[H.LONG_TERM_FT_HEADER] = mutableListOf()
                map[H.FUEL_MASS_REL_HEADER] = mutableListOf()
                map[H.LAMBDA_CONTROL_ACTIVE_HEADER] = mutableListOf()
            }
            LogType.PFI_SPLIT -> {
                map[H.TIME_STAMP_COLUMN_HEADER] = mutableListOf()
                map[H.RPM_COLUMN_HEADER] = mutableListOf()
                map[H.PFI_SPLIT_FACTOR_HEADER] = mutableListOf()
                map[H.PFI_SPLIT_FACTOR_UNLIM_HEADER] = mutableListOf()
            }
            LogType.PLSOL -> {
                map[H.TIME_STAMP_COLUMN_HEADER] = mutableListOf()
                map[H.ENGINE_LOAD_HEADER] = mutableListOf()
                map[H.ABSOLUTE_BOOST_PRESSURE_ACTUAL_HEADER] = mutableListOf()
                map[H.BAROMETRIC_PRESSURE_HEADER] = mutableListOf()
                map[H.THROTTLE_PLATE_ANGLE_HEADER] = mutableListOf()
                map[H.FUPSRLS_HEADER] = mutableListOf()
            }
        }

        return map
    }
}
