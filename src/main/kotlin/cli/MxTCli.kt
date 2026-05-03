package cli

import data.contract.Me7LogFileContract
import data.parser.bin.BinParser
import data.parser.me7log.Me7LogParser
import data.parser.xdf.TableDefinition
import data.parser.xdf.XdfParser
import domain.math.map.Map3d
import domain.model.closedloopfueling.ClosedLoopFuelingCorrectionManager
import domain.model.krkte.KrkteCalculator
import domain.model.optimizer.OptimizerCalculator
import domain.model.simulator.Me7Simulator
import domain.model.simulator.MechanicalLimitDetector
import java.io.*
import java.util.regex.Pattern

/**
 * Headless CLI/REPL harness for MxT (ME7Tuner).
 * Provides command-line access to all domain-layer calculators:
 * optimizer, MLHFM correction, KRKTE, simulator, mechanical limits.
 *
 * Designed for AI-driven analysis — load ECU files and logs, run
 * analysis, and get structured text output without the Compose GUI.
 *
 * Usage:
 *   ./gradlew cli                           # REPL mode
 *   ./gradlew cli --args="help"             # one-shot
 */

// ── State ────────────────────────────────────────────────────────────

private var mapList: List<Pair<TableDefinition, Map3d>> = emptyList()
private var logData: Map<Me7LogFileContract.Header, List<Double>>? = null
private var logDir: File? = null
private var binFile: File? = null
private var xdfFile: File? = null

// ── Entry Point ──────────────────────────────────────────────────────

fun main(args: Array<String>) {
    if (args.isNotEmpty()) {
        dispatch(args)
    } else {
        repl()
    }
}

// ── REPL ─────────────────────────────────────────────────────────────

private fun repl() {
    println("MxT CLI — type 'help' for commands, 'quit' to exit")
    print("mxt> ")
    System.out.flush()

    BufferedReader(InputStreamReader(System.`in`)).use { reader ->
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            val trimmed = line!!.trim()
            if (trimmed.isEmpty()) {
                print("mxt> "); System.out.flush(); continue
            }
            if (trimmed == "quit" || trimmed == "exit") {
                println("Goodbye."); break
            }
            dispatch(tokenize(trimmed))
            print("mxt> "); System.out.flush()
        }
    }
}

private fun tokenize(line: String): Array<String> {
    val tokens = mutableListOf<String>()
    val m = Pattern.compile("\"([^\"]*)\"|'([^']*)'|(\\S+)").matcher(line)
    while (m.find()) {
        tokens.add(m.group(1) ?: m.group(2) ?: m.group(3))
    }
    return tokens.toTypedArray()
}

// ── Command Dispatch ─────────────────────────────────────────────────

private fun dispatch(args: Array<String>) {
    if (args.isEmpty()) return
    try {
        when (args[0].lowercase()) {
            "load-ecu"   -> cmdLoadEcu(args)
            "load-logs"  -> cmdLoadLogs(args)
            "maps"       -> cmdMaps(args)
            "map"        -> cmdMap(args)
            "optimize"   -> cmdOptimize()
            "mlhfm-correct" -> cmdMlhfmCorrect(args)
            "krkte"      -> cmdKrkte(args)
            "limits"     -> cmdLimits()
            "summary"    -> cmdSummary()
            "export"     -> cmdExport(args)
            "help"       -> cmdHelp()
            else         -> println("Unknown command: ${args[0]} — type 'help' for commands")
        }
    } catch (e: Exception) {
        println("Error: ${e.message}")
    }
}

// ── Commands ─────────────────────────────────────────────────────────

private fun cmdLoadEcu(args: Array<String>) {
    if (args.size < 3) {
        println("Usage: load-ecu <xdf-file> <bin-file>"); return
    }
    val xdf = File(args[1])
    val bin = File(args[2])

    if (!xdf.exists()) { println("XDF file not found: ${args[1]}"); return }
    if (!bin.exists()) { println("BIN file not found: ${args[2]}"); return }

    print("Parsing XDF... "); System.out.flush()
    val (_, tableDefs) = XdfParser.parseToList(FileInputStream(xdf))
    println("${tableDefs.size} table definitions")

    print("Parsing BIN... "); System.out.flush()
    val parsed = BinParser.parseToList(FileInputStream(bin), tableDefs)
    println("${parsed.size} maps loaded")

    mapList = parsed
    binFile = bin
    xdfFile = xdf

    // Show key maps for optimizer
    val keyMaps = listOf("KFLDRL", "KFLDIMX", "KFPBRK", "KFMIOP", "KFMIRL", "MLHFM", "KFURL")
    val found = keyMaps.filter { name -> findMap(name) != null }
    val missing = keyMaps.filter { name -> findMap(name) == null }
    println("Key maps found: ${found.joinToString(", ")}")
    if (missing.isNotEmpty()) {
        println("Key maps NOT found: ${missing.joinToString(", ")}")
    }
}

private fun cmdLoadLogs(args: Array<String>) {
    if (args.size < 2) {
        println("Usage: load-logs <directory> [--type optimizer|closed|open|ldrpid]"); return
    }
    val dir = File(args[1])
    if (!dir.exists() || !dir.isDirectory) {
        println("Directory not found: ${args[1]}"); return
    }

    // Parse --type flag (default: optimizer for broadest column set)
    var logType = Me7LogParser.LogType.OPTIMIZER
    for (i in 2 until args.size) {
        if (args[i] == "--type" && i + 1 < args.size) {
            logType = when (args[i + 1].lowercase()) {
                "optimizer" -> Me7LogParser.LogType.OPTIMIZER
                "closed"    -> Me7LogParser.LogType.CLOSED_LOOP
                "open"      -> Me7LogParser.LogType.OPEN_LOOP
                "ldrpid"    -> Me7LogParser.LogType.LDRPID
                "plsol"     -> Me7LogParser.LogType.PLSOL
                else -> { println("Unknown type: ${args[i + 1]}"); return }
            }
        }
    }

    print("Loading logs as ${logType.name} from ${dir.name}... "); System.out.flush()
    val parser = Me7LogParser()
    val data = parser.parseLogDirectory(logType, dir) { current, total ->
        if (current % 50 == 0 || current == total) {
            print("\rLoading logs as ${logType.name} from ${dir.name}... $current/$total")
            System.out.flush()
        }
    }

    // Count rows from first non-empty data column
    val rows = data.entries
        .filter { it.key != Me7LogFileContract.Header.START_TIME_HEADER }
        .firstOrNull()?.value?.size ?: 0

    println("\rLoaded ${rows} data rows from ${dir.name} (type: ${logType.name})          ")
    logData = data
    logDir = dir

    // Show column availability
    val available = data.filter { (k, v) ->
        k != Me7LogFileContract.Header.START_TIME_HEADER && v.isNotEmpty()
    }.map { it.key.title }
    println("Columns: ${available.joinToString(", ")}")
}

private fun cmdMaps(args: Array<String>) {
    if (mapList.isEmpty()) { println("No ECU loaded. Use 'load-ecu <xdf> <bin>' first."); return }

    val filter = if (args.size > 1) args[1].lowercase() else null

    val filtered = if (filter != null) {
        mapList.filter { it.first.tableName.lowercase().contains(filter) }
    } else {
        mapList
    }

    println("=== Maps${if (filter != null) " matching '$filter'" else ""} (${filtered.size}) ===")
    for ((def, map) in filtered) {
        val dims = "${map.yAxis.size}×${map.xAxis.size}"
        val desc = if (def.tableDescription.isNotBlank()) " — ${def.tableDescription}" else ""
        println("  %-25s %8s%s".format(def.tableName, dims, desc))
    }
}

private fun cmdMap(args: Array<String>) {
    if (args.size < 2) { println("Usage: map <name>"); return }
    if (mapList.isEmpty()) { println("No ECU loaded."); return }

    val match = findMap(args[1])
    if (match == null) { println("Map '${args[1]}' not found. Use 'maps' to list."); return }

    val (def, map) = match
    println("=== ${def.tableName} ===")
    if (def.tableDescription.isNotBlank()) println(def.tableDescription)
    println("Dimensions: ${map.yAxis.size} rows × ${map.xAxis.size} columns")

    if (map.xAxis.isNotEmpty()) {
        println("X-axis: ${map.xAxis.joinToString("  ") { fmtVal(it) }}")
    }

    if (map.yAxis.isEmpty() && map.xAxis.isEmpty()) {
        // Scalar or 1D
        if (map.zAxis.isNotEmpty() && map.zAxis[0].isNotEmpty()) {
            if (map.zAxis[0].size == 1) {
                println("Value: ${fmtVal(map.zAxis[0][0])}")
            } else {
                println("Values: ${map.zAxis[0].joinToString("  ") { fmtVal(it) }}")
            }
        }
    } else {
        // 2D table
        // Header row
        val colWidth = 10
        print("%${colWidth}s".format(""))
        for (x in map.xAxis) print("%${colWidth}s".format(fmtVal(x)))
        println()

        for (row in map.yAxis.indices) {
            if (row < map.zAxis.size) {
                print("%${colWidth}s".format(fmtVal(map.yAxis[row])))
                for (col in map.zAxis[row].indices) {
                    print("%${colWidth}s".format(fmtVal(map.zAxis[row][col])))
                }
                println()
            }
        }
    }
}

private fun cmdOptimize() {
    if (mapList.isEmpty()) { println("No ECU loaded. Use 'load-ecu' first."); return }
    if (logData == null) { println("No logs loaded. Use 'load-logs' first."); return }

    val kfldrl = findMap("KFLDRL")?.second
    val kfldimx = findMap("KFLDIMX")?.second
    val kfpbrk = findMap("KFPBRK")?.second
    val kfmiop = findMap("KFMIOP")?.second
    val kfmirl = findMap("KFMIRL")?.second
    val kfurlMap = findMap("KFURL")?.second

    if (kfldrl == null) println("⚠ KFLDRL not found — boost duty suggestions unavailable")
    if (kfpbrk == null) println("⚠ KFPBRK not found — VE correction unavailable")

    print("Running optimizer... "); System.out.flush()

    val result = OptimizerCalculator.analyze(
        values = logData!!,
        kfldrlMap = kfldrl,
        kfldimxMap = kfldimx,
        kfpbrkMap = kfpbrk,
        kfmiopMap = kfmiop,
        kfmirlMap = kfmirl,
        kfurlMap = kfurlMap
    )

    println("done (${result.wotEntries.size} WOT entries)")
    println()

    // Warnings
    if (result.warnings.isNotEmpty()) {
        println("=== Warnings (${result.warnings.size}) ===")
        result.warnings.forEach { println("  ⚠ $it") }
        println()
    }

    // Mechanical limits
    val limits = result.mechanicalLimits
    println("=== Mechanical Limits ===")
    println("  MAF saturated:      ${if (limits.mafMaxed) "YES (${fmtVal(limits.mafMaxValue)} g/s)" else "No"}")
    println("  MAF voltage maxed:  ${if (limits.mafVoltageMaxed) "YES (${fmtVal(limits.mafMaxVoltage)}V)" else "No"}")
    println("  Injector maxed:     ${if (limits.injectorMaxed) "YES (${fmtVal(limits.injectorMaxDutyCycle)}%)" else "No"}")
    println("  Turbo maxed:        ${if (limits.turboMaxed) "YES (${fmtVal(limits.turboMaxWgdc)}% WGDC)" else "No"}")
    println("  MAP sensor maxed:   ${if (limits.mapSensorMaxed) "YES (${fmtVal(limits.mapSensorMaxValue)} mbar)" else "No"}")
    println("  MAP sensor type:    ${limits.mapSensorType}")
    if (limits.dataReliabilityCompromised) {
        println("  ⚠ DATA RELIABILITY: ${limits.dataReliabilityDetail}")
    }
    if (limits.sensorSaturationWarnings.isNotEmpty()) {
        for (w in limits.sensorSaturationWarnings) {
            println("  ⚠ ${w.sensorName}: ${w.recommendation}")
        }
    }
    println()

    // Pressure errors summary
    if (result.pressureErrors.isNotEmpty()) {
        println("=== Pressure Errors (requested vs actual mBar) ===")
        val grouped = result.pressureErrors.groupBy { (it.first / 500).toInt() * 500 }
        println("  %-12s %8s %8s %6s".format("RPM Range", "Mean Err", "Max Err", "n"))
        println("  ${"-".repeat(40)}")
        for ((rpmBin, errors) in grouped.toSortedMap()) {
            val errs = errors.map { it.second }
            println("  %-12s %8.0f %8.0f %6d".format(
                "${rpmBin}-${rpmBin + 499}",
                errs.average(), errs.maxOrNull() ?: 0.0, errs.size
            ))
        }
        println()
    }

    // Suggested KFLDRL
    val kfldrlDelta = result.suggestedMaps.kfldrl
    if (kfldrlDelta != null) {
        println("=== Suggested KFLDRL (Boost Duty Cycle) ===")
        println("  Modified ${kfldrlDelta.cellsModified} of ${kfldrlDelta.totalCells} cells (${(kfldrlDelta.coverage * 100).toInt()}% coverage)")
        println("  Avg change: ${fmtVal(kfldrlDelta.avgAbsoluteDelta)}, avg samples/cell: ${fmtVal(kfldrlDelta.avgSamplesPerModifiedCell)}")
        printMap(kfldrlDelta.suggested)
        println()
    } else if (result.suggestedKfldrl != null) {
        println("=== Suggested KFLDRL (Boost Duty Cycle) ===")
        printMap(result.suggestedKfldrl!!)
        println()
    }

    // Suggested KFLDIMX
    val kfldimxDelta = result.suggestedMaps.kfldimx
    if (kfldimxDelta != null) {
        println("=== Suggested KFLDIMX (I-Term Limit) ===")
        println("  Modified ${kfldimxDelta.cellsModified} of ${kfldimxDelta.totalCells} cells")
        printMap(kfldimxDelta.suggested)
        println()
    } else if (result.suggestedKfldimx != null) {
        println("=== Suggested KFLDIMX (I-Term Limit) ===")
        printMap(result.suggestedKfldimx!!)
        println()
    }

    // KFPBRK corrections
    val kfpbrkDelta = result.suggestedMaps.kfpbrk
    if (kfpbrkDelta != null) {
        println("=== Suggested KFPBRK (VE Correction) ===")
        println("  Modified ${kfpbrkDelta.cellsModified} of ${kfpbrkDelta.totalCells} cells")
        printMap(kfpbrkDelta.suggested)
        println()
    } else if (result.kfpbrkMultipliers != null) {
        println("=== KFPBRK Multipliers (VE Correction) ===")
        printMap(result.kfpbrkMultipliers!!)
        println()
    }

    // Chain diagnosis
    val diag = result.chainDiagnosis
    if (diag.recommendations.isNotEmpty() || diag.onTargetPercent < 100) {
        println("=== Chain Diagnosis ===")
        println("  On target:       ${fmtVal(diag.onTargetPercent)}%")
        println("  Torque capped:   ${fmtVal(diag.torqueCappedPercent)}%")
        println("  PSSOL error:     ${fmtVal(diag.pssolErrorPercent)}%")
        println("  Boost shortfall: ${fmtVal(diag.boostShortfallPercent)}%")
        println("  VE mismatch:     ${fmtVal(diag.veMismatchPercent)}%")
        println("  Dominant error:  ${diag.dominantError}")
        if (diag.recommendations.isNotEmpty()) {
            println("  Recommendations:")
            diag.recommendations.forEach { println("    → $it") }
        }
        println()
    }

    // Safety modes
    result.safetyModes?.let { safety ->
        if (safety.excludedSamples.isNotEmpty()) {
            println("=== Safety Mode Events ===")
            println("  Overload: ${safety.overloadCount}, Fallback: ${safety.fallbackCount}, Regulation Error: ${safety.regulationErrorCount}")
            println("  Total excluded samples: ${safety.excludedSamples.size}")
            if (safety.warnings.isNotEmpty()) {
                safety.warnings.forEach { println("  ⚠ $it") }
            }
            println()
        }
    }

    // Environmental
    result.environmental?.let { env ->
        println("=== Environmental ===")
        println("  Avg barometric: ${fmtVal(env.avgBaroPressure)} mBar")
        println("  Avg IAT: ${env.avgIntakeTemp?.let { fmtVal(it) } ?: "N/A"}°C")
        println("  Estimated altitude: ${fmtVal(env.estimatedAltitudeM)} m")
        println()
    }

    println("Optimizer complete. Use 'export <map-name> <file>' to save corrected maps.")
}

private fun cmdMlhfmCorrect(args: Array<String>) {
    if (mapList.isEmpty()) { println("No ECU loaded."); return }
    if (logData == null) { println("No logs loaded."); return }

    val mlhfmPair = findMap("MLHFM")
    if (mlhfmPair == null) { println("MLHFM map not found in ECU data."); return }

    // Default: closed-loop (uses narrowband O2 + fuel trims, no wideband needed)
    var mode = "closed"
    for (i in 1 until args.size) {
        if (args[i] == "--mode" && i + 1 < args.size) {
            mode = args[i + 1].lowercase()
        }
    }

    println("=== MLHFM Correction (mode: $mode) ===")
    val originalMlhfm = mlhfmPair.second

    println("Original MLHFM: ${originalMlhfm.zAxis.firstOrNull()?.size ?: 0} voltage bins")
    if (originalMlhfm.xAxis.isNotEmpty()) {
        println("  Voltage range: ${fmtVal(originalMlhfm.xAxis.first())}V — ${fmtVal(originalMlhfm.xAxis.last())}V")
        val maxKgH = originalMlhfm.zAxis.flatMap { it.toList() }.maxOrNull() ?: 0.0
        println("  Max airflow: ${fmtVal(maxKgH)} kg/h (${fmtVal(maxKgH / 3.6)} g/s)")
    }

    when (mode) {
        "closed" -> {
            // Needs: fr_w (STFT), fra_w (LTFT), uhfm_w (MAF voltage), B_lr (lambda control), wdkba, nmot, rl_w
            val stft = logData!![Me7LogFileContract.Header.STFT_COLUMN_HEADER]
            val ltft = logData!![Me7LogFileContract.Header.LTFT_COLUMN_HEADER]
            val mafV = logData!![Me7LogFileContract.Header.MAF_VOLTAGE_HEADER]

            if (stft == null || stft.isEmpty()) {
                println("⚠ No STFT (fr_w) data in logs. Need closed-loop fuel trim data.")
                println("  Load logs with: load-logs <dir> --type closed")
                return
            }
            if (ltft == null || ltft.isEmpty()) {
                println("⚠ No LTFT (fra_w) data in logs.")
                return
            }
            if (mafV == null || mafV.isEmpty()) {
                println("⚠ No MAF voltage (uhfm_w) data in logs.")
                return
            }

            val corrector = ClosedLoopFuelingCorrectionManager(
                minThrottleAngle = 0.0,  // include part-throttle for closed-loop
                minRpm = 600.0,
                maxDerivative = 100.0
            )

            print("Computing closed-loop correction... "); System.out.flush()
            corrector.correct(logData!!, originalMlhfm)
            val result = corrector.closedLoopMlhfmCorrection

            if (result == null) {
                println("failed — insufficient closed-loop data")
                return
            }

            println("done")
            println()
            println("=== Corrected MLHFM ===")
            val corrected = result.correctedMlhfm

            // Show comparison table
            println("%-12s %12s %12s %10s".format("Voltage(V)", "Original", "Corrected", "Δ%"))
            println("-".repeat(48))
            val voltages = originalMlhfm.xAxis
            val origZ = originalMlhfm.zAxis.firstOrNull() ?: emptyArray()
            val corrZ = corrected.zAxis.firstOrNull() ?: emptyArray()
            for (i in voltages.indices) {
                val orig = if (i < origZ.size) origZ[i] else Double.NaN
                val corr = if (i < corrZ.size) corrZ[i] else Double.NaN
                val pct = if (orig != 0.0 && !orig.isNaN() && !corr.isNaN())
                    ((corr - orig) / orig) * 100 else Double.NaN
                println("%-12s %12s %12s %10s".format(
                    fmtVal(voltages[i]), fmtKgH(orig), fmtKgH(corr), fmtPct(pct)
                ))
            }

            // Store corrected map for export
            println()
            println("Corrected MLHFM ready. Use 'export MLHFM_corrected <file>' to save.")
        }
        "open" -> {
            println("Open-loop correction requires wideband AFR log data (lamsoni_w).")
            val wb = logData!![Me7LogFileContract.Header.WIDE_BAND_O2_HEADER]
            if (wb == null || wb.isEmpty()) {
                println("⚠ No wideband O2 data found in logs.")
                println("  Open-loop correction needs a separate Zeitronix/wideband CSV.")
                return
            }
            println("Wideband data available (${wb.size} samples). Open-loop correction not yet wired in CLI.")
        }
        else -> println("Unknown mode '$mode'. Use 'closed' or 'open'.")
    }
}

private fun cmdKrkte(args: Array<String>) {
    // Defaults for Audi 2.7T B5 S4
    var displacement = 0.4505  // single cylinder dm³ (2703cc / 6)
    var injectorSize = 6.15    // 615cc injector in cm³
    var airDensity = 1.184     // g/dm³ at ~20°C, sea level
    var gasDensity = 0.72      // gasoline g/cm³
    var stoichAFR = 14.7       // gasoline stoichiometric

    for (i in 1 until args.size step 2) {
        if (i + 1 >= args.size) break
        when (args[i]) {
            "--displacement" -> displacement = args[i + 1].toDouble() / 1000.0 / 6.0  // cc total → dm³ per cyl
            "--injector" -> injectorSize = args[i + 1].toDouble() / 100.0  // cc → cm³ (1cc = 1cm³, but injectors are in cc/min)
            "--air-density" -> airDensity = args[i + 1].toDouble()
            "--gas-density" -> gasDensity = args[i + 1].toDouble()
            "--afr" -> stoichAFR = args[i + 1].toDouble()
            "--cylinders" -> {
                // Recalc per-cylinder displacement
                val cyls = args[i + 1].toInt()
                displacement = displacement * 6.0 / cyls  // adjust from default 6-cyl
            }
        }
    }

    val krkte = KrkteCalculator.calculateKrkte(
        airDensityGramsPerDecimetersCubed = airDensity,
        cylinderDisplacementDecimetersCubed = displacement,
        fuelInjectorSizeCubicCentimeters = injectorSize,
        gasolineGramsPerCubicCentimeter = gasDensity,
        stoichiometricAirFuelRatio = stoichAFR
    )

    println("=== KRKTE Calculation ===")
    println("  Cylinder displacement: ${fmtVal(displacement)} dm³ (${fmtVal(displacement * 1000)} cc)")
    println("  Injector size:         ${fmtVal(injectorSize)} cm³")
    println("  Air density:           ${fmtVal(airDensity)} g/dm³")
    println("  Gasoline density:      ${fmtVal(gasDensity)} g/cm³")
    println("  Stoich AFR:            ${fmtVal(stoichAFR)}")
    println()
    println("  KRKTE = ${fmtVal(krkte)} ms/%")

    // Compare with loaded BIN if available
    val binKrkte = findMap("KRKTE")
    if (binKrkte != null) {
        val binVal = binKrkte.second.zAxis.firstOrNull()?.firstOrNull()
        if (binVal != null) {
            val diff = ((krkte - binVal) / binVal) * 100
            println("  BIN value = ${fmtVal(binVal)} ms/% (Δ${fmtPct(diff)})")
        }
    }
}

private fun cmdLimits() {
    if (logData == null) { println("No logs loaded."); return }

    // Build WOT entries from log data
    val filtered = OptimizerCalculator.filterWotEntriesWithOptionalData(logData!!)
    if (filtered.wotEntries.isEmpty()) {
        println("No WOT entries found in log data.")
        println("  Logs may need required columns: nmot, wdkba, ldtvm, pus_w, pvdks_w, pssol_w, rlsol_w, rl_w")
        return
    }

    println("=== Mechanical Limit Detection (${filtered.wotEntries.size} WOT samples) ===")
    println()

    val limits = MechanicalLimitDetector.detect(
        wotEntries = filtered.wotEntries,
        mafValues = filtered.mafValues,
        injectorOnTimes = filtered.injectorOnTimes,
        rpms = filtered.wotRpms,
        mafVoltages = filtered.mafVoltages
    )

    println("  MAF saturated:      ${if (limits.mafMaxed) "⚠ YES — max ${fmtVal(limits.mafMaxValue)} g/s" else "✓ No (max ${fmtVal(limits.mafMaxValue)} g/s)"}")
    println("  MAF voltage:        ${if (limits.mafVoltageMaxed) "⚠ YES — max ${fmtVal(limits.mafMaxVoltage)}V (sensor clipping)" else "✓ No (max ${fmtVal(limits.mafMaxVoltage)}V)"}")
    println("  Injector duty:      ${if (limits.injectorMaxed) "⚠ YES — max ${fmtVal(limits.injectorMaxDutyCycle)}% (fuel starvation risk)" else "✓ No (max ${fmtVal(limits.injectorMaxDutyCycle)}%)"}")
    println("  Turbo WGDC:         ${if (limits.turboMaxed) "⚠ YES — max ${fmtVal(limits.turboMaxWgdc)}% (boost target unreachable)" else "✓ No (max ${fmtVal(limits.turboMaxWgdc)}%)"}")
    println("  MAP sensor:         ${if (limits.mapSensorMaxed) "⚠ YES — max ${fmtVal(limits.mapSensorMaxValue)} mBar" else "✓ No (max ${fmtVal(limits.mapSensorMaxValue)} mBar)"}")
    println("  MAP sensor type:    ${limits.mapSensorType}")

    if (limits.dataReliabilityCompromised) {
        println()
        println("  ⚠ DATA RELIABILITY COMPROMISED: ${limits.dataReliabilityDetail}")
    }

    if (limits.warnings.isNotEmpty()) {
        println()
        println("  Additional warnings:")
        limits.warnings.forEach { println("    ⚠ $it") }
    }

    if (limits.sensorSaturationWarnings.isNotEmpty()) {
        println()
        println("  Sensor saturation details:")
        limits.sensorSaturationWarnings.forEach {
            println("    ⚠ ${it.sensorName}: ${it.recommendation}")
        }
    }
}

private fun cmdSummary() {
    println("=== Session Summary ===")
    println()

    // ECU
    if (mapList.isNotEmpty()) {
        println("ECU: ${xdfFile?.name ?: "unknown"} + ${binFile?.name ?: "unknown"}")
        println("  ${mapList.size} maps loaded")
    } else {
        println("ECU: not loaded")
    }
    println()

    // Logs
    if (logData != null) {
        val rows = logData!!.entries
            .filter { it.key != Me7LogFileContract.Header.START_TIME_HEADER }
            .firstOrNull()?.value?.size ?: 0
        println("Logs: ${logDir?.name ?: "unknown"} ($rows data rows)")

        // RPM range
        logData!![Me7LogFileContract.Header.RPM_COLUMN_HEADER]?.let { rpms ->
            if (rpms.isNotEmpty()) {
                println("  RPM range: ${rpms.min().toInt()} – ${rpms.max().toInt()}")
            }
        }

        // Boost range
        logData!![Me7LogFileContract.Header.ABSOLUTE_BOOST_PRESSURE_ACTUAL_HEADER]?.let { boost ->
            if (boost.isNotEmpty()) {
                println("  Boost range: ${boost.min().toInt()} – ${boost.max().toInt()} mBar (abs)")
            }
        }

        // MAF range
        logData!![Me7LogFileContract.Header.MAF_GRAMS_PER_SECOND_HEADER]?.let { maf ->
            if (maf.isNotEmpty()) {
                println("  MAF range: ${fmtVal(maf.min())} – ${fmtVal(maf.max())} g/s")
            }
        }

        // WOT entries
        val filtered = OptimizerCalculator.filterWotEntriesWithOptionalData(logData!!)
        println("  WOT entries: ${filtered.wotEntries.size}")
    } else {
        println("Logs: not loaded")
    }
}

private fun cmdExport(args: Array<String>) {
    if (args.size < 3) {
        println("Usage: export <map-name> <output-file.csv>"); return
    }
    val mapName = args[1]
    val outFile = File(args[2])

    val match = findMap(mapName)
    if (match == null) {
        println("Map '$mapName' not found. Use 'maps' to list available maps."); return
    }

    val (def, map) = match
    outFile.bufferedWriter().use { writer ->
        // Header row: empty corner cell + x-axis values
        if (map.xAxis.isNotEmpty()) {
            writer.write(",${map.xAxis.joinToString(",") { fmtVal(it) }}")
            writer.newLine()
        }

        // Data rows: y-axis value + z-values
        for (row in map.zAxis.indices) {
            val yVal = if (row < map.yAxis.size) fmtVal(map.yAxis[row]) else ""
            val zVals = map.zAxis[row].joinToString(",") { fmtVal(it) }
            writer.write("$yVal,$zVals")
            writer.newLine()
        }
    }

    println("Exported ${def.tableName} (${map.yAxis.size}×${map.xAxis.size}) to ${outFile.absolutePath}")
}

private fun cmdHelp() {
    println("MxT CLI — Headless analysis harness for ME7Tuner")
    println()
    println("Commands:")
    println("  load-ecu <xdf> <bin>                Load ECU calibration files")
    println("  load-logs <dir> [--type optimizer]   Load ME7Logger CSV logs")
    println("  maps [filter]                        List maps (optional name filter)")
    println("  map <name>                           Display a specific map's values")
    println("  optimize                             Run 6-phase boost/load optimizer")
    println("  mlhfm-correct [--mode closed|open]   Correct MAF linearization")
    println("  krkte [--displacement cc] [--injector cc]")
    println("                                       Calculate injection constant")
    println("  limits                               Detect mechanical limits (MAF/injector/turbo)")
    println("  summary                              Session overview")
    println("  export <map-name> <file.csv>         Export map to CSV")
    println("  help                                 This message")
    println("  quit / exit                          Exit REPL")
    println()
    println("Examples:")
    println("  load-ecu 8D0907551M.xdf 8D0907551M.bin")
    println("  load-logs /path/to/me7logger/logs/")
    println("  optimize")
    println("  mlhfm-correct --mode closed")
    println("  krkte --displacement 2703 --injector 615")
}

// ── Helpers ──────────────────────────────────────────────────────────

private fun findMap(name: String): Pair<TableDefinition, Map3d>? {
    // Exact match first, then case-insensitive, then contains
    return mapList.find { it.first.tableName == name }
        ?: mapList.find { it.first.tableName.equals(name, ignoreCase = true) }
        ?: mapList.find { it.first.tableName.contains(name, ignoreCase = true) }
}

private fun printMap(map: Map3d) {
    val colWidth = 8
    if (map.xAxis.isEmpty() && map.yAxis.isEmpty()) {
        // Scalar or 1D
        if (map.zAxis.isNotEmpty()) {
            println("  ${map.zAxis[0].joinToString("  ") { fmtVal(it) }}")
        }
        return
    }

    // Header
    print("%${colWidth}s".format(""))
    for (x in map.xAxis) print("%${colWidth}s".format(fmtVal(x)))
    println()

    for (row in map.yAxis.indices) {
        if (row < map.zAxis.size) {
            print("%${colWidth}s".format(fmtVal(map.yAxis[row])))
            for (col in map.zAxis[row].indices) {
                print("%${colWidth}s".format(fmtVal(map.zAxis[row][col])))
            }
            println()
        }
    }
}

private fun fmtVal(v: Double): String = when {
    v.isNaN() -> "N/A"
    v == v.toLong().toDouble() -> v.toLong().toString()
    kotlin.math.abs(v) >= 100 -> "%.1f".format(v)
    kotlin.math.abs(v) >= 1 -> "%.2f".format(v)
    else -> "%.4f".format(v)
}

private fun fmtKgH(v: Double): String = if (v.isNaN()) "N/A" else "%.2f kg/h".format(v)

private fun fmtPct(v: Double): String = if (v.isNaN()) "N/A" else "%+.1f%%".format(v)
