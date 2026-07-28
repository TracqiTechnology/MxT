package data.parser.med17log

import data.contract.Med17LogFileContract
import data.contract.Med17LogFileContract.Header as H
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVRecord
import java.io.File
import java.io.FileReader
import java.io.IOException

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
        val attemptedRows: Int,
        val acceptedRows: Int,
        val rejectedRows: Int,
        val errors: List<String> = emptyList()
    ) {
        /** Backward-compatible name retained for existing diagnostics consumers. */
        val totalRows: Int
            get() = attemptedRows
    }

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
        var attemptedRows = 0
        var acceptedRows = 0
        var rejectedRows = 0
        val errors = mutableListOf<String>()
        var headersFound = false

        try {
            FileReader(file).use { reader ->
                val records = CSVFormat.RFC4180.parse(reader)
                val iterator = records.iterator()
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
                    attemptedRows++
                    try {
                        val before = primaryRowCount(logType, map)
                        when (logType) {
                            LogType.LDRPID -> parseLdrpidRow(record, map)
                            LogType.OPTIMIZER -> parseOptimizerRow(record, map)
                            LogType.FUEL_TRIM -> parseFuelTrimRow(record, map)
                            LogType.PFI_SPLIT -> parsePfiSplitRow(record, map)
                            LogType.PLSOL -> parsePlsolRow(record, map)
                        }
                        if (primaryRowCount(logType, map) > before) {
                            acceptedRows++
                        } else {
                            rejectedRows++
                        }
                    } catch (e: RuntimeException) {
                        rejectedRows++
                        errors += "row ${attemptedRows + 2}: ${e.message ?: e::class.simpleName}"
                    }
                }
            }
        } catch (e: IOException) {
            val matched = columnIndices.keys.map { it.header }.toSet()
            lastDiagnostics = ParseDiagnostics(
                matchedHeaders = matched,
                missingHeaders = missingHeaders(logType),
                attemptedRows = attemptedRows,
                acceptedRows = acceptedRows,
                rejectedRows = rejectedRows,
                errors = listOf(e.message ?: "I/O error")
            )
            throw IllegalArgumentException("Could not read log file '${file.name}'", e)
        }

        val matched = columnIndices.keys.map { it.header }.toSet()
        val missing = missingHeaders(logType)
        lastDiagnostics = ParseDiagnostics(
            matchedHeaders = matched,
            missingHeaders = missing,
            attemptedRows = attemptedRows,
            acceptedRows = acceptedRows,
            rejectedRows = rejectedRows,
            errors = errors
        )

        require(headersFound) {
            "Required ${logType.name.lowercase().replace('_', ' ')} headers were not found: " +
                missing.joinToString()
        }
        require(errors.isEmpty()) {
            "Malformed rows in '${file.name}': ${errors.take(3).joinToString()}"
        }
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
        val requestedPressure = getDouble(record, H.REQUESTED_PRESSURE_HEADER)

        map[H.TIME_STAMP_COLUMN_HEADER]!!.add(time)
        map[H.RPM_COLUMN_HEADER]!!.add(rpm)
        map[H.THROTTLE_PLATE_ANGLE_HEADER]!!.add(throttle)
        map[H.BAROMETRIC_PRESSURE_HEADER]!!.add(baro)
        map[H.WASTEGATE_DUTY_CYCLE_HEADER]!!.add(wgdc)
        map[H.ABSOLUTE_BOOST_PRESSURE_ACTUAL_HEADER]!!.add(absBoost)
        map[H.SELECTED_GEAR_HEADER]!!.add(gear)
        // Keep optional diagnostics row-aligned with the required LDRPID signals.
        map[H.REQUESTED_PRESSURE_HEADER]!!.add(requestedPressure ?: Double.NaN)
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
        val reqLoad = getDouble(record, H.REQUESTED_LOAD_HEADER)
            ?: getDouble(record, H.REQUESTED_LOAD_ALT_HEADER) ?: return
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

        // Preserve row alignment for optional trim channels. Missing values use
        // NaN and are ignored by the analyzer rather than compressing the list.
        val stftHeader = when {
            H.STFT_MIXED_COLUMN_HEADER in columnIndices -> H.STFT_MIXED_COLUMN_HEADER
            H.STFT_COLUMN_HEADER in columnIndices -> H.STFT_COLUMN_HEADER
            else -> null
        }
        stftHeader?.let { map[it]!!.add(stft ?: Double.NaN) }

        val ltftHeader = when {
            H.LTFT_COLUMN_HEADER in columnIndices -> H.LTFT_COLUMN_HEADER
            H.LONG_TERM_FT_HEADER in columnIndices -> H.LONG_TERM_FT_HEADER
            else -> null
        }
        ltftHeader?.let { map[it]!!.add(ltft ?: Double.NaN) }

        // Optional signals (avoid double-adding longft1_w if already stored as ltft above)
        if (ltft == null || (H.LTFT_COLUMN_HEADER in columnIndices)) {
            // Only add longft1_w separately if it wasn't used as the ltft fallback
            if (H.LONG_TERM_FT_HEADER in columnIndices && ltftHeader != H.LONG_TERM_FT_HEADER) {
                map[H.LONG_TERM_FT_HEADER]?.add(
                    getDouble(record, H.LONG_TERM_FT_HEADER) ?: Double.NaN
                )
            }
        }
        if (H.FUEL_MASS_REL_HEADER in columnIndices) {
            map[H.FUEL_MASS_REL_HEADER]?.add(getDouble(record, H.FUEL_MASS_REL_HEADER) ?: Double.NaN)
        }
        if (H.LAMBDA_CONTROL_ACTIVE_HEADER in columnIndices) {
            map[H.LAMBDA_CONTROL_ACTIVE_HEADER]?.add(
                getDouble(record, H.LAMBDA_CONTROL_ACTIVE_HEADER) ?: Double.NaN
            )
        }
        if (H.REQUESTED_LAMBDA_HEADER in columnIndices) {
            map[H.REQUESTED_LAMBDA_HEADER]?.add(
                getDouble(record, H.REQUESTED_LAMBDA_HEADER) ?: Double.NaN
            )
        }
        if (H.STFT_BANK1_HEADER in columnIndices) {
            map[H.STFT_BANK1_HEADER]?.add(
                getDouble(record, H.STFT_BANK1_HEADER) ?: Double.NaN
            )
        }
        if (H.STFT_BANK2_HEADER in columnIndices) {
            map[H.STFT_BANK2_HEADER]?.add(
                getDouble(record, H.STFT_BANK2_HEADER) ?: Double.NaN
            )
        }
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

        if (H.FUPSRLS_HEADER in columnIndices) {
            map[H.FUPSRLS_HEADER]?.add(getDouble(record, H.FUPSRLS_HEADER) ?: Double.NaN)
        }
        if (H.INTAKE_TEMPERATURE_HEADER in columnIndices) {
            map[H.INTAKE_TEMPERATURE_HEADER]?.add(
                getDouble(record, H.INTAKE_TEMPERATURE_HEADER) ?: Double.NaN
            )
        }
    }

    private fun parsePfiSplitRow(
        record: CSVRecord,
        map: Map<Med17LogFileContract.Header, MutableList<Double>>
    ) {
        val time = getDouble(record, H.TIME_STAMP_COLUMN_HEADER) ?: return
        val rpm = getDouble(record, H.RPM_COLUMN_HEADER) ?: return
        val pfi = getDouble(record, H.PFI_SPLIT_FACTOR_HEADER)
            ?: getDouble(record, H.PFI_SPLIT_FACTOR_UNLIM_HEADER) ?: return

        map[H.TIME_STAMP_COLUMN_HEADER]!!.add(time)
        map[H.RPM_COLUMN_HEADER]!!.add(rpm)
        map[H.PFI_SPLIT_FACTOR_HEADER]!!.add(pfi)
        if (H.PFI_SPLIT_FACTOR_UNLIM_HEADER in columnIndices) {
            map[H.PFI_SPLIT_FACTOR_UNLIM_HEADER]?.add(
                getDouble(record, H.PFI_SPLIT_FACTOR_UNLIM_HEADER) ?: Double.NaN
            )
        }
        if (H.ENGINE_LOAD_HEADER in columnIndices) {
            map[H.ENGINE_LOAD_HEADER]?.add(
                getDouble(record, H.ENGINE_LOAD_HEADER) ?: Double.NaN
            )
        }
    }

    private fun getDouble(
        record: CSVRecord,
        header: Med17LogFileContract.Header
    ): Double? {
        val idx = columnIndices[header] ?: return null
        if (idx >= record.size()) return null
        val raw = record.get(idx).trim()
        if (raw.isEmpty()) return null
        return raw.toDoubleOrNull()
            ?: throw IllegalArgumentException(
                "column '${header.header}' contains non-numeric value '$raw'"
            )
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
                    (H.REQUESTED_LOAD_HEADER in columnIndices ||
                     H.REQUESTED_LOAD_ALT_HEADER in columnIndices) &&
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

    private fun missingHeaders(logType: LogType): Set<String> {
        val missing = linkedSetOf<String>()

        fun require(header: H) {
            if (header !in columnIndices) missing += header.header
        }

        fun requireAny(label: String, vararg headers: H) {
            if (headers.none { it in columnIndices }) missing += label
        }

        require(H.TIME_STAMP_COLUMN_HEADER)
        when (logType) {
            LogType.LDRPID -> {
                require(H.RPM_COLUMN_HEADER)
                require(H.THROTTLE_PLATE_ANGLE_HEADER)
                require(H.BAROMETRIC_PRESSURE_HEADER)
                require(H.ABSOLUTE_BOOST_PRESSURE_ACTUAL_HEADER)
                requireAny(
                    "${H.WASTEGATE_DUTY_CYCLE_HEADER.header} or ${H.LDR_DUTY_CYCLE_HEADER.header}",
                    H.WASTEGATE_DUTY_CYCLE_HEADER,
                    H.LDR_DUTY_CYCLE_HEADER
                )
            }
            LogType.OPTIMIZER -> {
                require(H.RPM_COLUMN_HEADER)
                require(H.THROTTLE_PLATE_ANGLE_HEADER)
                require(H.BAROMETRIC_PRESSURE_HEADER)
                require(H.ABSOLUTE_BOOST_PRESSURE_ACTUAL_HEADER)
                require(H.REQUESTED_PRESSURE_HEADER)
                require(H.ENGINE_LOAD_HEADER)
                requireAny(
                    "${H.WASTEGATE_DUTY_CYCLE_HEADER.header} or ${H.LDR_DUTY_CYCLE_HEADER.header}",
                    H.WASTEGATE_DUTY_CYCLE_HEADER,
                    H.LDR_DUTY_CYCLE_HEADER
                )
                requireAny(
                    "${H.REQUESTED_LOAD_HEADER.header} or ${H.REQUESTED_LOAD_ALT_HEADER.header}",
                    H.REQUESTED_LOAD_HEADER,
                    H.REQUESTED_LOAD_ALT_HEADER
                )
            }
            LogType.FUEL_TRIM -> {
                require(H.RPM_COLUMN_HEADER)
                require(H.ENGINE_LOAD_HEADER)
                requireAny(
                    "${H.STFT_COLUMN_HEADER.header} or ${H.STFT_MIXED_COLUMN_HEADER.header} or " +
                        "${H.LTFT_COLUMN_HEADER.header} or ${H.LONG_TERM_FT_HEADER.header}",
                    H.STFT_COLUMN_HEADER,
                    H.STFT_MIXED_COLUMN_HEADER,
                    H.LTFT_COLUMN_HEADER,
                    H.LONG_TERM_FT_HEADER
                )
            }
            LogType.PFI_SPLIT -> {
                require(H.RPM_COLUMN_HEADER)
                requireAny(
                    "${H.PFI_SPLIT_FACTOR_HEADER.header} or ${H.PFI_SPLIT_FACTOR_UNLIM_HEADER.header}",
                    H.PFI_SPLIT_FACTOR_HEADER,
                    H.PFI_SPLIT_FACTOR_UNLIM_HEADER
                )
            }
            LogType.PLSOL -> {
                require(H.ENGINE_LOAD_HEADER)
                require(H.ABSOLUTE_BOOST_PRESSURE_ACTUAL_HEADER)
                require(H.BAROMETRIC_PRESSURE_HEADER)
                require(H.THROTTLE_PLATE_ANGLE_HEADER)
            }
        }
        return missing
    }

    private fun primaryRowCount(
        logType: LogType,
        map: Map<Med17LogFileContract.Header, MutableList<Double>>
    ): Int = when (logType) {
        LogType.LDRPID,
        LogType.OPTIMIZER,
        LogType.FUEL_TRIM,
        LogType.PFI_SPLIT -> map[H.RPM_COLUMN_HEADER]?.size ?: 0
        LogType.PLSOL -> map[H.ENGINE_LOAD_HEADER]?.size ?: 0
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
                map[H.REQUESTED_PRESSURE_HEADER] = mutableListOf()
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
                map[H.REQUESTED_LAMBDA_HEADER] = mutableListOf()
                map[H.STFT_BANK1_HEADER] = mutableListOf()
                map[H.STFT_BANK2_HEADER] = mutableListOf()
            }
            LogType.PFI_SPLIT -> {
                map[H.TIME_STAMP_COLUMN_HEADER] = mutableListOf()
                map[H.RPM_COLUMN_HEADER] = mutableListOf()
                map[H.PFI_SPLIT_FACTOR_HEADER] = mutableListOf()
                map[H.PFI_SPLIT_FACTOR_UNLIM_HEADER] = mutableListOf()
                map[H.ENGINE_LOAD_HEADER] = mutableListOf()
            }
            LogType.PLSOL -> {
                map[H.TIME_STAMP_COLUMN_HEADER] = mutableListOf()
                map[H.ENGINE_LOAD_HEADER] = mutableListOf()
                map[H.ABSOLUTE_BOOST_PRESSURE_ACTUAL_HEADER] = mutableListOf()
                map[H.BAROMETRIC_PRESSURE_HEADER] = mutableListOf()
                map[H.THROTTLE_PLATE_ANGLE_HEADER] = mutableListOf()
                map[H.FUPSRLS_HEADER] = mutableListOf()
                map[H.INTAKE_TEMPERATURE_HEADER] = mutableListOf()
            }
        }

        return map
    }
}
