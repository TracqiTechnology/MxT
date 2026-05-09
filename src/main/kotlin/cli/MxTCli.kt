package cli

import data.contract.Me7LogFileContract
import data.parser.bin.BinParser
import data.parser.me7log.Me7LogParser
import data.parser.xdf.TableDefinition
import data.parser.xdf.XdfParser
import domain.math.map.Map3d
import domain.model.closedloopfueling.ClosedLoopFuelingCorrectionManager
import domain.model.krkte.KrkteCalculator
import domain.model.optimizer.MapDelta
import domain.model.optimizer.OptimizerCalculator
import domain.model.simulator.Me7Simulator
import domain.model.simulator.MechanicalLimitDetector
import domain.model.simulator.PidSimulator
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
private var outputJson = false

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
    if (!outputJson) {
        println("MxT CLI — type 'help' for commands, 'quit' to exit")
        print("mxt> ")
        System.out.flush()
    }

    BufferedReader(InputStreamReader(System.`in`)).use { reader ->
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            val trimmed = line!!.trim()
            if (trimmed.isEmpty()) {
                if (!outputJson) { print("mxt> "); System.out.flush() }
                continue
            }
            if (trimmed == "quit" || trimmed == "exit") {
                if (!outputJson) println("Goodbye.")
                break
            }
            dispatch(tokenize(trimmed))
            if (!outputJson) { print("mxt> "); System.out.flush() }
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
    // Per-command --json flag
    val cmdJson = outputJson || args.any { it == "--json" || it == "--format" && args.indexOf(it) + 1 < args.size && args[args.indexOf(it) + 1] == "json" }
    val cleanArgs = args.filter { it != "--json" }.toMutableList().also { list ->
        val fmtIdx = list.indexOf("--format")
        if (fmtIdx >= 0 && fmtIdx + 1 < list.size && list[fmtIdx + 1] == "json") {
            list.removeAt(fmtIdx + 1); list.removeAt(fmtIdx)
        }
    }.toTypedArray()
    try {
        when (cleanArgs[0].lowercase()) {
            "load-ecu"   -> cmdLoadEcu(cleanArgs)
            "load-logs"  -> cmdLoadLogs(cleanArgs)
            "maps"       -> cmdMaps(cleanArgs, cmdJson)
            "map"        -> cmdMap(cleanArgs, cmdJson)
            "optimize"   -> cmdOptimize(cmdJson)
            "mlhfm-correct" -> cmdMlhfmCorrect(cleanArgs)
            "krkte"      -> cmdKrkte(cleanArgs, cmdJson)
            "limits"     -> cmdLimits(cmdJson)
            "summary"    -> cmdSummary(cmdJson)
            "export"     -> cmdExport(cleanArgs)
            "cell"       -> cmdCell(cleanArgs, cmdJson)
            "lookup"     -> cmdLookup(cleanArgs, cmdJson)
            "axis"       -> cmdAxis(cleanArgs, cmdJson)
            "set-format" -> cmdSetFormat(cleanArgs)
            "simulate"   -> cmdSimulate(cleanArgs, cmdJson)
            "pid-sim"    -> cmdPidSim(cleanArgs, cmdJson)
            "batch"      -> cmdBatch(cleanArgs)
            "what-if"    -> cmdWhatIf(cleanArgs, cmdJson)
            "help"       -> cmdHelp()
            else         -> {
                if (cmdJson) println("""{"error":${jsonStr("Unknown command: ${cleanArgs[0]}")}}""")
                else println("Unknown command: ${cleanArgs[0]} — type 'help' for commands")
            }
        }
    } catch (e: Exception) {
        if (cmdJson) println("""{"error":${jsonStr(e.message ?: "Unknown error")}}""")
        else println("Error: ${e.message}")
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

private fun cmdMaps(args: Array<String>, json: Boolean) {
    if (mapList.isEmpty()) {
        if (json) println("""{"error":"No ECU loaded. Use load-ecu first."}""")
        else println("No ECU loaded. Use 'load-ecu <xdf> <bin>' first.")
        return
    }

    val filter = if (args.size > 1) args[1].lowercase() else null

    val filtered = if (filter != null) {
        mapList.filter { it.first.tableName.lowercase().contains(filter) }
    } else {
        mapList
    }

    if (json) {
        val sb = StringBuilder("[")
        for ((i, pair) in filtered.withIndex()) {
            val (def, map) = pair
            if (i > 0) sb.append(",")
            sb.append("{")
            sb.append("\"name\":${jsonStr(def.tableName)}")
            sb.append(",\"rows\":${map.yAxis.size}")
            sb.append(",\"cols\":${map.xAxis.size}")
            sb.append(",\"description\":${jsonStr(def.tableDescription)}")
            sb.append("}")
        }
        sb.append("]")
        println(sb)
    } else {
        println("=== Maps${if (filter != null) " matching '$filter'" else ""} (${filtered.size}) ===")
        for ((def, map) in filtered) {
            val dims = "${map.yAxis.size}×${map.xAxis.size}"
            val desc = if (def.tableDescription.isNotBlank()) " — ${def.tableDescription}" else ""
            println("  %-25s %8s%s".format(def.tableName, dims, desc))
        }
    }
}

private fun cmdMap(args: Array<String>, json: Boolean) {
    if (args.size < 2) {
        if (json) println("""{"error":"Usage: map <name>"}""")
        else println("Usage: map <name>")
        return
    }
    if (mapList.isEmpty()) {
        if (json) println("""{"error":"No ECU loaded."}""")
        else println("No ECU loaded.")
        return
    }

    val match = findMap(args[1])
    if (match == null) {
        if (json) println("""{"error":${jsonStr("Map '${args[1]}' not found.")}}""")
        else println("Map '${args[1]}' not found. Use 'maps' to list.")
        return
    }

    val (def, map) = match

    if (json) {
        println(jsonMapFull(def.tableName, def.tableDescription, map))
    } else {
        println("=== ${def.tableName} ===")
        if (def.tableDescription.isNotBlank()) println(def.tableDescription)
        println("Dimensions: ${map.yAxis.size} rows × ${map.xAxis.size} columns")

        if (map.xAxis.isNotEmpty()) {
            println("X-axis: ${map.xAxis.joinToString("  ") { fmtVal(it) }}")
        }

        if (map.yAxis.isEmpty() && map.xAxis.isEmpty()) {
            if (map.zAxis.isNotEmpty() && map.zAxis[0].isNotEmpty()) {
                if (map.zAxis[0].size == 1) {
                    println("Value: ${fmtVal(map.zAxis[0][0])}")
                } else {
                    println("Values: ${map.zAxis[0].joinToString("  ") { fmtVal(it) }}")
                }
            }
        } else {
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
}

private fun cmdOptimize(json: Boolean) {
    if (mapList.isEmpty()) {
        if (json) println("""{"error":"No ECU loaded. Use load-ecu first."}""")
        else println("No ECU loaded. Use 'load-ecu' first.")
        return
    }
    if (logData == null) {
        if (json) println("""{"error":"No logs loaded. Use load-logs first."}""")
        else println("No logs loaded. Use 'load-logs' first.")
        return
    }

    val kfldrl = findMap("KFLDRL")?.second
    val kfldimx = findMap("KFLDIMX")?.second
    val kfpbrk = findMap("KFPBRK")?.second
    val kfmiop = findMap("KFMIOP")?.second
    val kfmirl = findMap("KFMIRL")?.second
    val kfurlMap = findMap("KFURL")?.second

    if (!json) {
        if (kfldrl == null) println("⚠ KFLDRL not found — boost duty suggestions unavailable")
        if (kfpbrk == null) println("⚠ KFPBRK not found — VE correction unavailable")
        print("Running optimizer... "); System.out.flush()
    }

    val result = OptimizerCalculator.analyze(
        values = logData!!,
        kfldrlMap = kfldrl,
        kfldimxMap = kfldimx,
        kfpbrkMap = kfpbrk,
        kfmiopMap = kfmiop,
        kfmirlMap = kfmirl,
        kfurlMap = kfurlMap
    )

    if (json) {
        println(jsonOptimize(result))
        return
    }

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

private fun cmdKrkte(args: Array<String>, json: Boolean) {
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

    if (json) {
        val sb = StringBuilder("{")
        sb.append("\"calculated_krkte\":${fmtJsonNum(krkte)}")
        val binKrkte = findMap("KRKTE")
        val binVal = binKrkte?.second?.zAxis?.firstOrNull()?.firstOrNull()
        if (binVal != null) {
            sb.append(",\"bin_krkte\":${fmtJsonNum(binVal)}")
            val diff = ((krkte - binVal) / binVal) * 100
            sb.append(",\"delta_pct\":${fmtJsonNum(diff)}")
        } else {
            sb.append(",\"bin_krkte\":null,\"delta_pct\":null")
        }
        sb.append(",\"parameters\":{")
        sb.append("\"displacement_dm3\":${fmtJsonNum(displacement)}")
        sb.append(",\"injector_cm3\":${fmtJsonNum(injectorSize)}")
        sb.append(",\"air_density_g_dm3\":${fmtJsonNum(airDensity)}")
        sb.append(",\"gas_density_g_cm3\":${fmtJsonNum(gasDensity)}")
        sb.append(",\"stoich_afr\":${fmtJsonNum(stoichAFR)}")
        sb.append("}}")
        println(sb)
    } else {
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
}

private fun cmdLimits(json: Boolean) {
    if (logData == null) {
        if (json) println("""{"error":"No logs loaded."}""")
        else println("No logs loaded.")
        return
    }

    // Build WOT entries from log data
    val filtered = OptimizerCalculator.filterWotEntriesWithOptionalData(logData!!)
    if (filtered.wotEntries.isEmpty()) {
        if (json) println("""{"error":"No WOT entries found in log data."}""")
        else {
            println("No WOT entries found in log data.")
            println("  Logs may need required columns: nmot, wdkba, ldtvm, pus_w, pvdks_w, pssol_w, rlsol_w, rl_w")
        }
        return
    }

    val limits = MechanicalLimitDetector.detect(
        wotEntries = filtered.wotEntries,
        mafValues = filtered.mafValues,
        injectorOnTimes = filtered.injectorOnTimes,
        rpms = filtered.wotRpms,
        mafVoltages = filtered.mafVoltages
    )

    if (json) {
        println(jsonLimits(limits))
    } else {
        println("=== Mechanical Limit Detection (${filtered.wotEntries.size} WOT samples) ===")
        println()

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
}

private fun cmdSummary(json: Boolean) {
    if (json) {
        val sb = StringBuilder("{")
        // ECU
        sb.append("\"ecu\":{")
        if (mapList.isNotEmpty()) {
            sb.append("\"xdf\":${jsonStr(xdfFile?.name ?: "unknown")}")
            sb.append(",\"bin\":${jsonStr(binFile?.name ?: "unknown")}")
            sb.append(",\"map_count\":${mapList.size}")
        } else {
            sb.append("\"xdf\":null,\"bin\":null,\"map_count\":0")
        }
        sb.append("}")
        // Logs
        sb.append(",\"logs\":{")
        if (logData != null) {
            val rows = logData!!.entries
                .filter { it.key != Me7LogFileContract.Header.START_TIME_HEADER }
                .firstOrNull()?.value?.size ?: 0
            sb.append("\"directory\":${jsonStr(logDir?.name ?: "unknown")}")
            sb.append(",\"rows\":$rows")

            logData!![Me7LogFileContract.Header.RPM_COLUMN_HEADER]?.let { rpms ->
                if (rpms.isNotEmpty()) {
                    sb.append(",\"rpm_range\":[${rpms.min().toInt()},${rpms.max().toInt()}]")
                }
            }
            logData!![Me7LogFileContract.Header.ABSOLUTE_BOOST_PRESSURE_ACTUAL_HEADER]?.let { boost ->
                if (boost.isNotEmpty()) {
                    sb.append(",\"boost_range\":[${boost.min().toInt()},${boost.max().toInt()}]")
                }
            }
            logData!![Me7LogFileContract.Header.MAF_GRAMS_PER_SECOND_HEADER]?.let { maf ->
                if (maf.isNotEmpty()) {
                    sb.append(",\"maf_range\":[${fmtJsonNum(maf.min())},${fmtJsonNum(maf.max())}]")
                }
            }
            val filtered = OptimizerCalculator.filterWotEntriesWithOptionalData(logData!!)
            sb.append(",\"wot_entries\":${filtered.wotEntries.size}")
        } else {
            sb.append("\"directory\":null,\"rows\":0,\"wot_entries\":0")
        }
        sb.append("}}")
        println(sb)
    } else {
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

private fun cmdSimulate(args: Array<String>, json: Boolean) {
    if (mapList.isEmpty()) {
        if (json) println("""{"error":"No ECU loaded. Use load-ecu first."}""")
        else println("No ECU loaded. Use 'load-ecu' first.")
        return
    }

    var rpm = Double.NaN
    var load = Double.NaN
    var baro = 1013.0
    var iat = 25.0

    for (i in 1 until args.size step 2) {
        if (i + 1 >= args.size) break
        when (args[i]) {
            "--rpm"  -> rpm = args[i + 1].toDouble()
            "--load" -> load = args[i + 1].toDouble()
            "--baro" -> baro = args[i + 1].toDouble()
            "--iat"  -> iat = args[i + 1].toDouble()
        }
    }

    if (rpm.isNaN() || load.isNaN()) {
        if (json) println("""{"error":"Usage: simulate --rpm <rpm> --load <load> [--baro 1013] [--iat 25]"}""")
        else println("Usage: simulate --rpm <rpm> --load <load> [--baro 1013] [--iat 25]")
        return
    }

    val kfpbrk = findMap("KFPBRK")?.second
    val kfldrl = findMap("KFLDRL")?.second
    val kfldimx = findMap("KFLDIMX")?.second
    val kfmiop = findMap("KFMIOP")?.second
    val kfmirl = findMap("KFMIRL")?.second
    val kfurlMap = findMap("KFURL")?.second
    val kfprgMap = findMap("KFPRG")?.second
    val ldrxnVal = findMap("LDRXN")?.second?.zAxis?.firstOrNull()?.firstOrNull() ?: 191.0

    val calibration = Me7Simulator.CalibrationSet(
        kfpbrk = kfpbrk,
        kfldrl = kfldrl,
        kfldimx = kfldimx,
        kfmiop = kfmiop,
        kfmirl = kfmirl,
        kfurlMap = kfurlMap,
        kfprgMap = kfprgMap,
        ldrxn = ldrxnVal
    )

    // Build synthetic WotLogEntry for this operating point
    // Use KFMIRL to convert load → pressure if available, otherwise estimate
    val estimatedPressure = if (kfmirl != null) {
        // KFMIRL maps torque/load → pressure; use as rough pressure estimate
        kfmirl.lookup(rpm, load) * baro / 100.0 + baro
    } else {
        // Rough estimate: load% → pressure via basic VE relationship
        load * baro / 100.0 + baro
    }

    val entry = OptimizerCalculator.WotLogEntry(
        rpm = rpm,
        requestedLoad = load,
        actualLoad = load,
        requestedMap = estimatedPressure,
        actualMap = estimatedPressure,
        barometricPressure = baro,
        wgdc = 50.0,
        throttleAngle = 100.0,
        intakeAirTemp = iat
    )

    val result = Me7Simulator.simulateEntry(entry, calibration)

    if (json) {
        val sb = StringBuilder("{")
        sb.append("\"rpm\":${fmtJsonNum(rpm)}")
        sb.append(",\"requested_load\":${fmtJsonNum(load)}")
        sb.append(",\"baro\":${fmtJsonNum(baro)}")
        sb.append(",\"iat\":${fmtJsonNum(iat)}")
        sb.append(",\"link1_torque\":{")
        sb.append("\"ldrxn_target\":${fmtJsonNum(result.ldrxnTarget)}")
        sb.append(",\"rlsol\":${fmtJsonNum(result.rlsol)}")
        sb.append(",\"torque_limited\":${result.torqueLimited}")
        sb.append(",\"headroom\":${fmtJsonNum(result.torqueHeadroom)}")
        sb.append("}")
        sb.append(",\"link2_plsol\":{")
        sb.append("\"simulated_pssol\":${fmtJsonNum(result.simulatedPssol)}")
        sb.append(",\"actual_pssol\":${fmtJsonNum(result.actualPssol)}")
        sb.append(",\"error\":${fmtJsonNum(result.pssolError)}")
        sb.append("}")
        sb.append(",\"link3_boost\":{")
        sb.append("\"simulated_plsol\":${fmtJsonNum(result.simulatedPlsol)}")
        sb.append(",\"actual_pvdks\":${fmtJsonNum(result.actualPvdks)}")
        sb.append(",\"boost_error\":${fmtJsonNum(result.boostError)}")
        sb.append(",\"predicted_wgdc\":${fmtJsonNum(result.predictedWgdc)}")
        sb.append(",\"actual_wgdc\":${fmtJsonNum(result.actualWgdc)}")
        sb.append(",\"kfldrl_correction\":${fmtJsonNum(result.kfldrlCorrection)}")
        sb.append("}")
        sb.append(",\"link4_ve\":{")
        sb.append("\"simulated_rl\":${fmtJsonNum(result.simulatedRlFromPressure)}")
        sb.append(",\"actual_rl\":${fmtJsonNum(result.actualRl)}")
        sb.append(",\"kfpbrk_correction\":${fmtJsonNum(result.kfpbrkCorrectionFactor)}")
        sb.append("}")
        sb.append(",\"dominant_error\":${jsonStr(result.dominantError.name)}")
        sb.append(",\"total_load_deficit\":${fmtJsonNum(result.totalLoadDeficit)}")
        sb.append("}")
        println(sb)
    } else {
        println("=== Chain Simulation @ ${fmtVal(rpm)} RPM, ${fmtVal(load)}% load ===")
        println("  Baro: ${fmtVal(baro)} mbar, IAT: ${fmtVal(iat)}°C")
        println()

        val link1Status = if (result.torqueLimited) "⚠ TORQUE LIMITED" else "✓ OK"
        println("  Link 1 — Torque → Load Request: $link1Status")
        println("    LDRXN target: ${fmtVal(result.ldrxnTarget)}%  rlsol: ${fmtVal(result.rlsol)}%  headroom: ${fmtVal(result.torqueHeadroom)}%")

        val pssolAbsErr = kotlin.math.abs(result.pssolError)
        val link2Status = if (pssolAbsErr > 20.0) "⚠ PSSOL ERROR (${fmtVal(result.pssolError)} mbar)" else "✓ OK"
        println("  Link 2 — Load → Pressure (PLSOL): $link2Status")
        println("    Simulated pssol: ${fmtVal(result.simulatedPssol)} mbar  actual: ${fmtVal(result.actualPssol)} mbar")

        val boostAbsErr = kotlin.math.abs(result.boostError)
        val link3Status = if (boostAbsErr > 30.0) "⚠ BOOST SHORTFALL (${fmtVal(result.boostError)} mbar)" else "✓ OK"
        println("  Link 3 — Pressure → Boost (WGDC): $link3Status")
        println("    Predicted WGDC: ${fmtVal(result.predictedWgdc)}%  actual: ${fmtVal(result.actualWgdc)}%  correction: ${fmtVal(result.kfldrlCorrection)}%")

        val veCorrDiff = kotlin.math.abs(result.kfpbrkCorrectionFactor - 1.0)
        val link4Status = if (veCorrDiff > 0.05) "⚠ VE MISMATCH (×${fmtVal(result.kfpbrkCorrectionFactor)})" else "✓ OK"
        println("  Link 4 — Pressure → Load (VE): $link4Status")
        println("    Simulated rl: ${fmtVal(result.simulatedRlFromPressure)}%  actual: ${fmtVal(result.actualRl)}%")

        println()
        println("  Dominant error: ${result.dominantError.name}")
        println("  Total load deficit: ${fmtVal(result.totalLoadDeficit)}%")
    }
}

private fun cmdPidSim(args: Array<String>, json: Boolean) {
    if (mapList.isEmpty()) {
        if (json) println("""{"error":"No ECU loaded. Use load-ecu first."}""")
        else println("No ECU loaded. Use 'load-ecu' first.")
        return
    }

    var target = Double.NaN
    var start = 1013.0
    var rpm = 4000.0
    var duration = 2.0

    for (i in 1 until args.size step 2) {
        if (i + 1 >= args.size) break
        when (args[i]) {
            "--target"   -> target = args[i + 1].toDouble()
            "--start"    -> start = args[i + 1].toDouble()
            "--rpm"      -> rpm = args[i + 1].toDouble()
            "--duration" -> duration = args[i + 1].toDouble()
        }
    }

    if (target.isNaN()) {
        if (json) println("""{"error":"Usage: pid-sim --target <mbar> [--start 1013] [--rpm 4000] [--duration 2.0]"}""")
        else println("Usage: pid-sim --target <mbar> [--start 1013] [--rpm 4000] [--duration 2.0]")
        return
    }

    val kfldrq0 = findMap("KFLDRQ0")?.second
    val kfldrq1 = findMap("KFLDRQ1")?.second
    val kfldrq2 = findMap("KFLDRQ2")?.second
    val kfldrl = findMap("KFLDRL")?.second
    val kfldimx = findMap("KFLDIMX")?.second

    // Build synthetic WOT pull: ramp from start to target over 0.5s, then hold
    val sampleIntervalMs = 20.0
    val totalSamples = (duration * 1000.0 / sampleIntervalMs).toInt()
    val rampSamples = (500.0 / sampleIntervalMs).toInt() // 0.5s ramp

    val pullEntries = (0 until totalSamples).map { i ->
        val progress = if (i < rampSamples) i.toDouble() / rampSamples else 1.0
        val currentPressure = start + (target - start) * progress
        OptimizerCalculator.WotLogEntry(
            rpm = rpm,
            requestedLoad = 100.0,
            actualLoad = 100.0,
            requestedMap = target,
            actualMap = currentPressure,
            barometricPressure = 1013.0,
            wgdc = 50.0,
            throttleAngle = 100.0,
            intakeAirTemp = 25.0
        )
    }

    val result = PidSimulator.simulate(
        pullEntries = pullEntries,
        kfldrq0 = kfldrq0,
        kfldrq1 = kfldrq1,
        kfldrq2 = kfldrq2,
        kfldrl = kfldrl,
        kfldimx = kfldimx
    )

    val diag = result.diagnosis

    if (json) {
        val sb = StringBuilder("{")
        sb.append("\"target_mbar\":${fmtJsonNum(target)}")
        sb.append(",\"start_mbar\":${fmtJsonNum(start)}")
        sb.append(",\"rpm\":${fmtJsonNum(rpm)}")
        sb.append(",\"duration_s\":${fmtJsonNum(duration)}")
        sb.append(",\"diagnosis\":{")
        sb.append("\"oscillation_detected\":${diag.oscillationDetected}")
        sb.append(",\"oscillation_count\":${diag.oscillationCount}")
        sb.append(",\"windup_detected\":${diag.windupDetected}")
        sb.append(",\"windup_duration_ms\":${fmtJsonNum(diag.windupDurationMs)}")
        sb.append(",\"slow_convergence\":${diag.slowConvergence}")
        sb.append(",\"convergence_time_ms\":${fmtJsonNum(diag.convergenceTimeMs)}")
        sb.append(",\"overshoot_detected\":${diag.overshootDetected}")
        sb.append(",\"overshoot_magnitude_mbar\":${fmtJsonNum(diag.overshootMagnitude)}")
        sb.append(",\"avg_abs_lde\":${fmtJsonNum(diag.avgAbsLde)}")
        sb.append(",\"recommendations\":${jsonStrArr(diag.recommendations)}")
        if (diag.q2Warnings.isNotEmpty()) {
            sb.append(",\"q2_warnings\":${jsonStrArr(diag.q2Warnings)}")
        }
        sb.append("}}")
        println(sb)
    } else {
        println("=== PID Simulation: ${fmtVal(start)} → ${fmtVal(target)} mbar @ ${fmtVal(rpm)} RPM ===")
        println("  Duration: ${fmtVal(duration)}s, Samples: ${result.states.size}")
        println()

        println("  Oscillation:   ${if (diag.oscillationDetected) "⚠ YES (${diag.oscillationCount} sign changes)" else "✓ No"}")
        println("  Windup:        ${if (diag.windupDetected) "⚠ YES (${fmtVal(diag.windupDurationMs)} ms at I-limit)" else "✓ No"}")
        println("  Convergence:   ${if (diag.slowConvergence) "⚠ SLOW (${fmtVal(diag.convergenceTimeMs)} ms)" else "✓ ${fmtVal(diag.convergenceTimeMs)} ms"}")
        println("  Overshoot:     ${if (diag.overshootDetected) "⚠ YES (${fmtVal(diag.overshootMagnitude)} mbar)" else "✓ No"}")
        println("  Avg |lde|:     ${fmtVal(diag.avgAbsLde)} mbar")

        if (diag.recommendations.isNotEmpty()) {
            println()
            println("  Recommendations:")
            diag.recommendations.forEach { println("    → $it") }
        }
        if (diag.q2Warnings.isNotEmpty()) {
            println()
            println("  Q2 Warnings:")
            diag.q2Warnings.forEach { println("    ⚠ $it") }
        }
    }
}

private fun cmdBatch(args: Array<String>) {
    if (args.size < 2) {
        println("Usage: batch <filename>")
        return
    }
    val file = File(args[1])
    if (!file.exists()) {
        println("File not found: ${args[1]}")
        return
    }

    var lineNum = 0
    var executed = 0
    file.bufferedReader().useLines { lines ->
        for (line in lines) {
            lineNum++
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            if (!outputJson) {
                println("[$lineNum] $trimmed")
            }
            dispatch(tokenize(trimmed))
            executed++
        }
    }
    if (!outputJson) {
        println("Batch complete: $executed commands executed from ${file.name}")
    }
}

private fun cmdWhatIf(args: Array<String>, json: Boolean) {
    if (mapList.isEmpty()) {
        if (json) println("""{"error":"No ECU loaded. Use load-ecu first."}""")
        else println("No ECU loaded. Use 'load-ecu' first.")
        return
    }
    if (args.size < 2) {
        if (json) println("""{"error":"Usage: what-if <map> --cell <x>,<y> --from <val> --to <val>"}""")
        else println("Usage: what-if <map> --cell <x>,<y> --from <val> --to <val>")
        return
    }

    val mapName = args[1]
    var cellX = Double.NaN
    var cellY = Double.NaN
    var fromVal = Double.NaN
    var toVal = Double.NaN

    for (i in 2 until args.size) {
        when (args[i]) {
            "--cell" -> {
                if (i + 1 < args.size) {
                    val parts = args[i + 1].split(",")
                    if (parts.size == 2) {
                        cellX = parts[0].toDoubleOrNull() ?: Double.NaN
                        cellY = parts[1].toDoubleOrNull() ?: Double.NaN
                    }
                }
            }
            "--from" -> if (i + 1 < args.size) fromVal = args[i + 1].toDoubleOrNull() ?: Double.NaN
            "--to"   -> if (i + 1 < args.size) toVal = args[i + 1].toDoubleOrNull() ?: Double.NaN
        }
    }

    if (cellX.isNaN() || cellY.isNaN() || fromVal.isNaN() || toVal.isNaN()) {
        if (json) println("""{"error":"Usage: what-if <map> --cell <x>,<y> --from <val> --to <val>"}""")
        else println("Usage: what-if <map> --cell <x>,<y> --from <val> --to <val>")
        return
    }

    val match = findMap(mapName)
    if (match == null) {
        if (json) println("""{"error":${jsonStr("Map '$mapName' not found.")}}""")
        else println("Map '$mapName' not found. Use 'maps' to list.")
        return
    }

    val (def, map) = match
    val nearestXIdx = findNearestIndex(map.xAxis, cellX)
    val nearestYIdx = findNearestIndex(map.yAxis, cellY)
    val nearestX = if (map.xAxis.isNotEmpty()) map.xAxis[nearestXIdx] else 0.0
    val nearestY = if (map.yAxis.isNotEmpty()) map.yAxis[nearestYIdx] else 0.0
    val currentVal = if (nearestYIdx < map.zAxis.size && nearestXIdx < map.zAxis[nearestYIdx].size)
        map.zAxis[nearestYIdx][nearestXIdx] else Double.NaN

    val delta = toVal - fromVal
    val upperName = def.tableName.uppercase()
    val isTimingMap = upperName.contains("KFZWOP") || upperName.contains("KFZW")
    val isBoostMap = upperName.contains("KFLDRL") || upperName.contains("KFLDIMX")

    if (json) {
        val sb = StringBuilder("{")
        sb.append("\"map\":${jsonStr(def.tableName)}")
        sb.append(",\"cell\":{\"x\":${fmtJsonNum(nearestX)},\"y\":${fmtJsonNum(nearestY)}}")
        sb.append(",\"current_value\":${fmtJsonNum(currentVal)}")
        sb.append(",\"from\":${fmtJsonNum(fromVal)}")
        sb.append(",\"to\":${fmtJsonNum(toVal)}")
        sb.append(",\"delta\":${fmtJsonNum(delta)}")
        if (isTimingMap) {
            sb.append(",\"timing_analysis\":{")
            sb.append("\"type\":\"ignition_timing\"")
            sb.append(",\"knock_margin_change\":${fmtJsonNum(-delta)}")
            sb.append(",\"direction\":${jsonStr(if (delta > 0) "advanced" else "retarded")}")
            sb.append(",\"warning\":${if (delta > 2.0) jsonStr("Advancing >2° increases knock risk") else "null"}")
            sb.append("}")
        }
        if (isBoostMap) {
            sb.append(",\"boost_analysis\":{")
            sb.append("\"type\":${jsonStr(if (upperName.contains("KFLDRL")) "feedforward_wgdc" else "i_term_limit")}")
            sb.append(",\"change_pct\":${fmtJsonNum(delta)}")
            sb.append(",\"direction\":${jsonStr(if (delta > 0) "more_duty" else "less_duty")}")
            sb.append("}")
        }
        sb.append("}")
        println(sb)
    } else {
        println("=== What-If: ${def.tableName} ===")
        println("  Cell [x=${fmtVal(nearestX)}, y=${fmtVal(nearestY)}]")
        println("  Current value: ${fmtVal(currentVal)}")
        println("  Change: ${fmtVal(fromVal)} → ${fmtVal(toVal)} (Δ${fmtVal(delta)})")

        if (isTimingMap) {
            println()
            println("  Timing analysis:")
            if (delta > 0) {
                println("    → Advancing ignition by ${fmtVal(delta)}° KW")
                println("    → Knock margin reduced by ${fmtVal(delta)}°")
                if (delta > 2.0) println("    ⚠ Advancing >2° increases knock risk — verify with knock logging")
            } else {
                println("    → Retarding ignition by ${fmtVal(-delta)}° KW")
                println("    → Knock margin increased by ${fmtVal(-delta)}°")
            }
        }

        if (isBoostMap) {
            println()
            val boostType = if (upperName.contains("KFLDRL")) "feedforward WGDC" else "I-term limit"
            println("  Boost analysis ($boostType):")
            if (delta > 0) {
                println("    → Increasing $boostType by ${fmtVal(delta)}%")
                println("    → Expect higher boost at this operating point")
            } else {
                println("    → Decreasing $boostType by ${fmtVal(-delta)}%")
                println("    → Expect lower boost at this operating point")
            }

            // Run PID comparison if maps are available
            val kfldrl = findMap("KFLDRL")?.second
            if (kfldrl != null) {
                val kfldrq0 = findMap("KFLDRQ0")?.second
                val kfldrq1 = findMap("KFLDRQ1")?.second
                val kfldrq2 = findMap("KFLDRQ2")?.second
                val kfldimxMap = findMap("KFLDIMX")?.second

                val targetPressure = 2500.0
                val sampleIntervalMs = 20.0
                val totalSamples = (2000.0 / sampleIntervalMs).toInt()
                val rampSamples = (500.0 / sampleIntervalMs).toInt()
                val pullEntries = (0 until totalSamples).map { i ->
                    val progress = if (i < rampSamples) i.toDouble() / rampSamples else 1.0
                    val currentPressure = 1013.0 + (targetPressure - 1013.0) * progress
                    OptimizerCalculator.WotLogEntry(
                        rpm = nearestY,
                        requestedLoad = 100.0,
                        actualLoad = 100.0,
                        requestedMap = targetPressure,
                        actualMap = currentPressure,
                        barometricPressure = 1013.0,
                        wgdc = 50.0,
                        throttleAngle = 100.0,
                        intakeAirTemp = 25.0
                    )
                }

                val beforeResult = PidSimulator.simulate(pullEntries, kfldrq0, kfldrq1, kfldrq2, kfldrl, kfldimxMap)
                println()
                println("  PID impact (simulated):")
                println("    Convergence: ${fmtVal(beforeResult.diagnosis.convergenceTimeMs)} ms")
                if (beforeResult.diagnosis.oscillationDetected)
                    println("    ⚠ Oscillation detected (${beforeResult.diagnosis.oscillationCount} sign changes)")
                if (beforeResult.diagnosis.overshootDetected)
                    println("    ⚠ Overshoot: ${fmtVal(beforeResult.diagnosis.overshootMagnitude)} mbar")
            }
        }
    }
}

private fun cmdHelp() {
    println("MxT CLI — Headless analysis harness for ME7Tuner")
    println()
    println("Commands:")
    println("  load-ecu <xdf> <bin>                Load ECU calibration files")
    println("  load-logs <dir> [--type optimizer]   Load ME7Logger CSV logs")
    println("  maps [filter]                        List maps (optional name filter)")
    println("  map <name>                           Display a specific map's values")
    println("  cell <map> <x> <y>                   Get cell value at nearest axis coordinates")
    println("  lookup <map> <x> <y>                 Get bilinear-interpolated value")
    println("  axis <map>                           Show axis breakpoints for a map")
    println("  optimize                             Run 6-phase boost/load optimizer")
    println("  mlhfm-correct [--mode closed|open]   Correct MAF linearization")
    println("  krkte [--displacement cc] [--injector cc]")
    println("                                       Calculate injection constant")
    println("  limits                               Detect mechanical limits (MAF/injector/turbo)")
    println("  simulate --rpm <n> --load <n> [--baro 1013] [--iat 25]")
    println("                                       Simulate 4-link chain at an operating point")
    println("  pid-sim --target <mbar> [--start 1013] [--rpm 4000] [--duration 2.0]")
    println("                                       Simulate PID boost transient response")
    println("  batch <filename>                     Execute commands from a file")
    println("  what-if <map> --cell <x>,<y> --from <val> --to <val>")
    println("                                       Compare map cell value change impact")
    println("  summary                              Session overview")
    println("  export <map-name> <file.csv>         Export map to CSV")
    println("  set-format <text|json>               Set output format (default: text)")
    println("  help                                 This message")
    println("  quit / exit                          Exit REPL")
    println()
    println("JSON output:")
    println("  set-format json                      Enable JSON output globally")
    println("  <command> --json                     JSON output for a single command")
    println("  <command> --format json              Same as --json")
    println()
    println("Examples:")
    println("  load-ecu 8D0907551M.xdf 8D0907551M.bin")
    println("  load-logs /path/to/me7logger/logs/")
    println("  optimize")
    println("  maps --json")
    println("  map KFZWOP --json")
    println("  cell KFZWOP 5500 282")
    println("  lookup KFZWOP 5500 280")
    println("  axis KFZWOP")
    println("  mlhfm-correct --mode closed")
    println("  krkte --displacement 2703 --injector 615")
    println("  simulate --rpm 5500 --load 280")
    println("  pid-sim --target 2500 --rpm 4000")
    println("  what-if KFZWOP --cell 5500,282 --from 30.0 --to 32.0")
    println("  batch commands.txt")
}

// ── New Commands ─────────────────────────────────────────────────────

private fun cmdCell(args: Array<String>, json: Boolean) {
    if (args.size < 4) {
        if (json) println("""{"error":"Usage: cell <map> <x> <y>"}""")
        else println("Usage: cell <map> <x> <y>")
        return
    }
    if (mapList.isEmpty()) {
        if (json) println("""{"error":"No ECU loaded."}""")
        else println("No ECU loaded.")
        return
    }

    val match = findMap(args[1])
    if (match == null) {
        if (json) println("""{"error":${jsonStr("Map '${args[1]}' not found.")}}""")
        else println("Map '${args[1]}' not found. Use 'maps' to list.")
        return
    }

    val x = args[2].toDoubleOrNull()
    val y = args[3].toDoubleOrNull()
    if (x == null || y == null) {
        if (json) println("""{"error":"x and y must be numbers"}""")
        else println("x and y must be numbers")
        return
    }

    val (def, map) = match
    val nearestXIdx = findNearestIndex(map.xAxis, x)
    val nearestYIdx = findNearestIndex(map.yAxis, y)
    val nearestX = if (map.xAxis.isNotEmpty()) map.xAxis[nearestXIdx] else 0.0
    val nearestY = if (map.yAxis.isNotEmpty()) map.yAxis[nearestYIdx] else 0.0
    val value = if (nearestYIdx < map.zAxis.size && nearestXIdx < map.zAxis[nearestYIdx].size)
        map.zAxis[nearestYIdx][nearestXIdx] else Double.NaN

    if (json) {
        val sb = StringBuilder("{")
        sb.append("\"map\":${jsonStr(def.tableName)}")
        sb.append(",\"x\":${fmtJsonNum(x)}")
        sb.append(",\"y\":${fmtJsonNum(y)}")
        sb.append(",\"value\":${fmtJsonNum(value)}")
        sb.append(",\"nearest_x\":${fmtJsonNum(nearestX)}")
        sb.append(",\"nearest_y\":${fmtJsonNum(nearestY)}")
        sb.append("}")
        println(sb)
    } else {
        println("${def.tableName}[x=${fmtVal(nearestX)}, y=${fmtVal(nearestY)}] = ${fmtVal(value)}")
        if (nearestX != x || nearestY != y) {
            println("  (snapped from x=${fmtVal(x)}, y=${fmtVal(y)})")
        }
    }
}

private fun cmdLookup(args: Array<String>, json: Boolean) {
    if (args.size < 4) {
        if (json) println("""{"error":"Usage: lookup <map> <x> <y>"}""")
        else println("Usage: lookup <map> <x> <y>")
        return
    }
    if (mapList.isEmpty()) {
        if (json) println("""{"error":"No ECU loaded."}""")
        else println("No ECU loaded.")
        return
    }

    val match = findMap(args[1])
    if (match == null) {
        if (json) println("""{"error":${jsonStr("Map '${args[1]}' not found.")}}""")
        else println("Map '${args[1]}' not found. Use 'maps' to list.")
        return
    }

    val x = args[2].toDoubleOrNull()
    val y = args[3].toDoubleOrNull()
    if (x == null || y == null) {
        if (json) println("""{"error":"x and y must be numbers"}""")
        else println("x and y must be numbers")
        return
    }

    val (def, map) = match
    val interpolated = map.lookup(x, y)

    if (json) {
        val sb = StringBuilder("{")
        sb.append("\"map\":${jsonStr(def.tableName)}")
        sb.append(",\"x\":${fmtJsonNum(x)}")
        sb.append(",\"y\":${fmtJsonNum(y)}")
        sb.append(",\"interpolated_value\":${fmtJsonNum(interpolated)}")
        sb.append("}")
        println(sb)
    } else {
        println("${def.tableName}.lookup(x=${fmtVal(x)}, y=${fmtVal(y)}) = ${fmtVal(interpolated)}")
    }
}

private fun cmdAxis(args: Array<String>, json: Boolean) {
    if (args.size < 2) {
        if (json) println("""{"error":"Usage: axis <map>"}""")
        else println("Usage: axis <map>")
        return
    }
    if (mapList.isEmpty()) {
        if (json) println("""{"error":"No ECU loaded."}""")
        else println("No ECU loaded.")
        return
    }

    val match = findMap(args[1])
    if (match == null) {
        if (json) println("""{"error":${jsonStr("Map '${args[1]}' not found.")}}""")
        else println("Map '${args[1]}' not found. Use 'maps' to list.")
        return
    }

    val (def, map) = match

    if (json) {
        val sb = StringBuilder("{")
        sb.append("\"map\":${jsonStr(def.tableName)}")
        sb.append(",\"x_axis\":{\"count\":${map.xAxis.size},\"values\":${jsonArr(map.xAxis)}}")
        sb.append(",\"y_axis\":{\"count\":${map.yAxis.size},\"values\":${jsonArr(map.yAxis)}}")
        sb.append("}")
        println(sb)
    } else {
        println("=== ${def.tableName} Axes ===")
        println("X-axis (${map.xAxis.size}): ${map.xAxis.joinToString("  ") { fmtVal(it) }}")
        println("Y-axis (${map.yAxis.size}): ${map.yAxis.joinToString("  ") { fmtVal(it) }}")
    }
}

private fun cmdSetFormat(args: Array<String>) {
    if (args.size < 2) {
        println(if (outputJson) "json" else "text")
        return
    }
    when (args[1].lowercase()) {
        "json" -> { outputJson = true; println("Output format: json") }
        "text" -> { outputJson = false; println("Output format: text") }
        else -> println("Unknown format '${args[1]}'. Use 'text' or 'json'.")
    }
}

// ── JSON Helpers ─────────────────────────────────────────────────────

private fun jsonStr(s: String): String =
    "\"${s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")}\""

private fun fmtJsonNum(v: Double): String = when {
    v.isNaN() || v.isInfinite() -> "null"
    v == v.toLong().toDouble() -> v.toLong().toString()
    else -> "%.6f".format(v).trimEnd('0').trimEnd('.')
}

private fun jsonArr(vals: Array<Double>): String =
    "[${vals.joinToString(",") { fmtJsonNum(it) }}]"

private fun jsonArr2d(vals: Array<Array<Double>>): String =
    "[${vals.joinToString(",") { row -> "[${row.joinToString(",") { fmtJsonNum(it) }}]" }}]"

private fun jsonStrArr(vals: List<String>): String =
    "[${vals.joinToString(",") { jsonStr(it) }}]"

private fun jsonMapFull(name: String, description: String, map: Map3d): String {
    val sb = StringBuilder("{")
    sb.append("\"name\":${jsonStr(name)}")
    sb.append(",\"description\":${jsonStr(description)}")
    sb.append(",\"dimensions\":[${map.yAxis.size},${map.xAxis.size}]")
    sb.append(",\"x_axis\":${jsonArr(map.xAxis)}")
    sb.append(",\"y_axis\":${jsonArr(map.yAxis)}")
    sb.append(",\"z_data\":${jsonArr2d(map.zAxis)}")
    sb.append("}")
    return sb.toString()
}

private fun jsonMapDelta(name: String, delta: MapDelta): String {
    val sb = StringBuilder("{")
    sb.append("\"cells_modified\":${delta.cellsModified}")
    sb.append(",\"total_cells\":${delta.totalCells}")
    sb.append(",\"coverage\":${fmtJsonNum(delta.coverage)}")
    sb.append(",\"x_axis\":${jsonArr(delta.suggested.xAxis)}")
    sb.append(",\"y_axis\":${jsonArr(delta.suggested.yAxis)}")
    sb.append(",\"z_data\":${jsonArr2d(delta.suggested.zAxis)}")
    sb.append("}")
    return sb.toString()
}

private fun jsonSuggestedMap(delta: MapDelta?, fallback: Map3d?): String {
    if (delta != null) return jsonMapDelta(delta.mapName, delta)
    if (fallback != null) {
        val sb = StringBuilder("{")
        sb.append("\"x_axis\":${jsonArr(fallback.xAxis)}")
        sb.append(",\"y_axis\":${jsonArr(fallback.yAxis)}")
        sb.append(",\"z_data\":${jsonArr2d(fallback.zAxis)}")
        sb.append("}")
        return sb.toString()
    }
    return "null"
}

private fun jsonOptimize(result: OptimizerCalculator.OptimizerResult): String {
    val sb = StringBuilder("{")
    sb.append("\"wot_entries\":${result.wotEntries.size}")
    sb.append(",\"warnings\":${jsonStrArr(result.warnings)}")

    // Mechanical limits
    val lim = result.mechanicalLimits
    sb.append(",\"mechanical_limits\":{")
    sb.append("\"maf_saturated\":${lim.mafMaxed}")
    sb.append(",\"injector_maxed\":${lim.injectorMaxed}")
    sb.append(",\"turbo_maxed\":${lim.turboMaxed}")
    sb.append(",\"map_sensor_maxed\":${lim.mapSensorMaxed}")
    sb.append(",\"map_sensor_type\":${jsonStr(lim.mapSensorType)}")
    sb.append("}")

    // Pressure errors
    if (result.pressureErrors.isNotEmpty()) {
        val grouped = result.pressureErrors.groupBy { (it.first / 500).toInt() * 500 }
        sb.append(",\"pressure_errors\":[")
        var first = true
        for ((rpmBin, errors) in grouped.toSortedMap()) {
            val errs = errors.map { it.second }
            if (!first) sb.append(",")
            first = false
            sb.append("{\"rpm_bin\":$rpmBin")
            sb.append(",\"mean_error\":${fmtJsonNum(errs.average())}")
            sb.append(",\"max_error\":${fmtJsonNum(errs.maxOrNull() ?: 0.0)}")
            sb.append(",\"count\":${errs.size}}")
        }
        sb.append("]")
    } else {
        sb.append(",\"pressure_errors\":[]")
    }

    // Suggested maps
    sb.append(",\"suggested_kfldrl\":${jsonSuggestedMap(result.suggestedMaps.kfldrl, result.suggestedKfldrl)}")
    sb.append(",\"suggested_kfldimx\":${jsonSuggestedMap(result.suggestedMaps.kfldimx, result.suggestedKfldimx)}")
    sb.append(",\"suggested_kfpbrk\":${jsonSuggestedMap(result.suggestedMaps.kfpbrk, result.kfpbrkMultipliers)}")

    // Chain diagnosis
    val diag = result.chainDiagnosis
    sb.append(",\"chain_diagnosis\":{")
    sb.append("\"on_target_pct\":${fmtJsonNum(diag.onTargetPercent)}")
    sb.append(",\"torque_capped_pct\":${fmtJsonNum(diag.torqueCappedPercent)}")
    sb.append(",\"pssol_error_pct\":${fmtJsonNum(diag.pssolErrorPercent)}")
    sb.append(",\"boost_shortfall_pct\":${fmtJsonNum(diag.boostShortfallPercent)}")
    sb.append(",\"dominant_error\":${jsonStr(diag.dominantError.name)}")
    sb.append(",\"recommendations\":${jsonStrArr(diag.recommendations)}")
    sb.append("}")

    // Safety modes
    val safety = result.safetyModes
    sb.append(",\"safety_modes\":{")
    if (safety != null) {
        sb.append("\"overload_count\":${safety.overloadCount}")
        sb.append(",\"fallback_count\":${safety.fallbackCount}")
        sb.append(",\"regulation_error_count\":${safety.regulationErrorCount}")
        sb.append(",\"excluded_samples\":${safety.excludedSamples.size}")
    } else {
        sb.append("\"overload_count\":0,\"fallback_count\":0,\"regulation_error_count\":0,\"excluded_samples\":0")
    }
    sb.append("}")

    // Environmental
    val env = result.environmental
    sb.append(",\"environmental\":{")
    if (env != null) {
        sb.append("\"avg_baro_mbar\":${fmtJsonNum(env.avgBaroPressure)}")
        sb.append(",\"avg_iat_c\":${if (env.avgIntakeTemp != null) fmtJsonNum(env.avgIntakeTemp) else "null"}")
        sb.append(",\"estimated_altitude_m\":${fmtJsonNum(env.estimatedAltitudeM)}")
    } else {
        sb.append("\"avg_baro_mbar\":null,\"avg_iat_c\":null,\"estimated_altitude_m\":null")
    }
    sb.append("}")

    sb.append("}")
    return sb.toString()
}

private fun jsonLimits(limits: MechanicalLimitDetector.MechanicalLimits): String {
    val sb = StringBuilder("{")
    sb.append("\"maf_saturated\":${limits.mafMaxed}")
    sb.append(",\"maf_max_gs\":${fmtJsonNum(limits.mafMaxValue)}")
    sb.append(",\"maf_voltage_maxed\":${limits.mafVoltageMaxed}")
    sb.append(",\"maf_max_voltage\":${fmtJsonNum(limits.mafMaxVoltage)}")
    sb.append(",\"injector_maxed\":${limits.injectorMaxed}")
    sb.append(",\"injector_max_duty_pct\":${fmtJsonNum(limits.injectorMaxDutyCycle)}")
    sb.append(",\"turbo_maxed\":${limits.turboMaxed}")
    sb.append(",\"turbo_max_wgdc_pct\":${fmtJsonNum(limits.turboMaxWgdc)}")
    sb.append(",\"map_sensor_maxed\":${limits.mapSensorMaxed}")
    sb.append(",\"map_sensor_max_mbar\":${fmtJsonNum(limits.mapSensorMaxValue)}")
    sb.append(",\"map_sensor_type\":${jsonStr(limits.mapSensorType)}")
    sb.append(",\"data_reliability_compromised\":${limits.dataReliabilityCompromised}")
    sb.append(",\"sensor_warnings\":[")
    for ((i, w) in limits.sensorSaturationWarnings.withIndex()) {
        if (i > 0) sb.append(",")
        sb.append("{\"sensor\":${jsonStr(w.sensorName)},\"recommendation\":${jsonStr(w.recommendation)}}")
    }
    sb.append("]}")
    return sb.toString()
}

private fun findNearestIndex(axis: Array<Double>, value: Double): Int {
    if (axis.isEmpty()) return 0
    var bestIdx = 0
    var bestDist = kotlin.math.abs(axis[0] - value)
    for (i in 1 until axis.size) {
        val dist = kotlin.math.abs(axis[i] - value)
        if (dist < bestDist) { bestDist = dist; bestIdx = i }
    }
    return bestIdx
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
