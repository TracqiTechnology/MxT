package data.parser.bin

import data.parser.csv.WinOlsCsvDefinitionAdapter
import data.parser.csv.WinOlsCsvMapDefinition
import data.parser.csv.WinOlsCsvParser
import data.parser.kp.KpDefinitionAdapter
import data.parser.kp.KpHintParser
import data.parser.kp.KpMapDefinition
import data.parser.xdf.AxisDefinition
import data.parser.xdf.TableDefinition
import data.parser.xdf.XdfParser
import data.preferences.bin.BinFilePreferences
import data.writer.BinWriter
import domain.math.map.Map3d
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.script.*

object BinParser {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val engine: ScriptEngine = ScriptEngineManager().getEngineByName("graal.js")
    private val parseMutex = Mutex()

    private val _mapList = MutableStateFlow<List<Pair<TableDefinition, Map3d>>>(emptyList())
    val mapList: StateFlow<List<Pair<TableDefinition, Map3d>>> = _mapList.asStateFlow()

    private var binaryFile = File("")

    fun init() {
        scope.launch {
            combine(
                BinFilePreferences.file,
                XdfParser.tableDefinitions,
                WinOlsCsvParser.definitions,
                KpHintParser.definitions
            ) { file, xdfDefs, csvDefs, kpDefs ->
                arrayOf(file, xdfDefs, csvDefs, kpDefs)
            }.collect { args ->
                @Suppress("UNCHECKED_CAST")
                val file = args[0] as File
                val xdfDefs = args[1] as List<TableDefinition>
                val csvDefs = args[2] as List<WinOlsCsvMapDefinition>
                val kpDefs = args[3] as List<KpMapDefinition>
                binaryFile = file
                val merged = mergeDefinitions(xdfDefs, csvDefs, kpDefs)
                if (file.exists() && file.isFile) {
                    try { parseMutex.withLock { parse(FileInputStream(file), merged) } }
                    catch (e: IOException) { e.printStackTrace() }
                }
            }
        }
        scope.launch {
            BinWriter.writeEvents.collect {
                if (binaryFile.exists() && binaryFile.isFile) {
                    val merged = mergeDefinitions(
                        XdfParser.tableDefinitions.value,
                        WinOlsCsvParser.definitions.value,
                        KpHintParser.definitions.value
                    )
                    try { parseMutex.withLock { parse(FileInputStream(binaryFile), merged) } }
                    catch (e: IOException) { e.printStackTrace() }
                }
            }
        }
    }

    /**
     * Merge XDF, CSV-derived, and KP-derived table definitions.
     * Priority: XDF > CSV > KP — each source only fills gaps left by higher-priority sources.
     */
    internal fun mergeDefinitions(
        xdfDefs: List<TableDefinition>,
        csvDefs: List<WinOlsCsvMapDefinition>,
        kpDefs: List<KpMapDefinition> = emptyList()
    ): List<TableDefinition> {
        val csvTableDefs = if (csvDefs.isNotEmpty()) WinOlsCsvDefinitionAdapter.toTableDefinitions(csvDefs) else emptyList()
        val kpTableDefs = if (kpDefs.isNotEmpty()) KpDefinitionAdapter.toTableDefinitions(kpDefs) else emptyList()

        if (csvTableDefs.isEmpty() && kpTableDefs.isEmpty()) return xdfDefs
        if (xdfDefs.isEmpty() && csvTableDefs.isEmpty()) return kpTableDefs
        if (xdfDefs.isEmpty() && kpTableDefs.isEmpty()) return csvTableDefs

        val names = mutableSetOf<String>()
        val result = mutableListOf<TableDefinition>()

        // XDF first (highest priority)
        for (def in xdfDefs) {
            names.add(def.tableName.lowercase())
            result.add(def)
        }

        // CSV fills gaps
        for (def in csvTableDefs) {
            if (def.tableName.lowercase() !in names) {
                names.add(def.tableName.lowercase())
                result.add(def)
            }
        }

        // KP fills remaining gaps
        for (def in kpTableDefs) {
            if (def.tableName.lowercase() !in names) {
                names.add(def.tableName.lowercase())
                result.add(def)
            }
        }

        return result
    }

    private fun parse(inputStream: InputStream, tableDefinitions: List<TableDefinition>) {
        _mapList.value = parseToList(inputStream, tableDefinitions)
    }

    /** Sets the singleton mapList directly — for UI tests that need to populate state without file I/O. */
    internal fun setMapListForTesting(list: List<Pair<TableDefinition, Map3d>>) {
        _mapList.value = list
    }

    /**
     * Parses a BIN input stream using the given table definitions and returns
     * the list of (TableDefinition, Map3d) pairs without touching singleton state.
     * Visible to tests.
     */
    internal fun parseToList(
        inputStream: InputStream,
        tableDefinitions: List<TableDefinition>
    ): List<Pair<TableDefinition, Map3d>> {
        val result = mutableListOf<Pair<TableDefinition, Map3d>>()
        val bytes: ByteArray
        BufferedInputStream(inputStream).use { bytes = it.readAllBytes() }

        var skippedCount = 0
        for (tableDefinition in tableDefinitions) {
            val xAxis = tableDefinition.xAxis?.let { parseAxis(bytes, it) } ?: emptyArray()
            val yAxis = tableDefinition.yAxis?.let { parseAxis(bytes, it) } ?: emptyArray()
            val zAxis = parseData(bytes, tableDefinition.zAxis)
            result.add(tableDefinition to Map3d(xAxis, yAxis, zAxis))

            // Count maps skipped due to out-of-range addresses
            if (tableDefinition.zAxis.address != 0 && zAxis.isEmpty() && xAxis.isEmpty() && yAxis.isEmpty()) {
                skippedCount++
            }
        }

        if (skippedCount > 0) {
            System.err.println("WARNING: $skippedCount of ${tableDefinitions.size} XDF map definitions reference addresses beyond BIN size (${bytes.size} bytes) — skipped")
        }

        return result
    }

    // ─────────────────────────────────────────────────────────────────────
    // Raw integer/float read — honours sizeBits, endianness and sign
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Read one scalar element from [buf] at the current position.
     * Handles 8 / 16 / 32-bit integers (signed or unsigned) and
     * 32-bit IEEE-754 floats.  Returns the raw numeric value
     * BEFORE the equation is applied.
     */
    private fun readScalar(buf: ByteBuffer, axis: AxisDefinition): Double {
        return when {
            axis.isFloat && axis.sizeBits == 32 -> buf.float.toDouble()
            axis.sizeBits == 32 -> {
                val raw = buf.int
                if (axis.isSigned) raw.toDouble() else Integer.toUnsignedLong(raw).toDouble()
            }
            axis.sizeBits == 16 -> {
                val raw = buf.short
                if (axis.isSigned) raw.toDouble() else java.lang.Short.toUnsignedInt(raw).toDouble()
            }
            else -> {  // 8-bit
                val raw = buf.get()
                if (axis.isSigned) raw.toDouble() else java.lang.Byte.toUnsignedInt(raw).toDouble()
            }
        }
    }

    private fun byteOrderFor(axis: AxisDefinition): ByteOrder =
        if (axis.lsbFirst) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN

    // ─────────────────────────────────────────────────────────────────────
    // Equation compilation helper — caches nothing (GraalJS is fast enough
    // for the map sizes used here)
    // ─────────────────────────────────────────────────────────────────────

    private fun applyEquation(rawValues: DoubleArray, axis: AxisDefinition): DoubleArray {
        if (axis.equation.isBlank() || axis.equation == axis.varId) return rawValues
        return try {
            val compiled = (engine as Compilable)
                .compile("function func(${axis.varId}) { return ${axis.equation} }")
            compiled.eval(compiled.engine.getBindings(ScriptContext.ENGINE_SCOPE))
            val inv = compiled.engine as Invocable
            DoubleArray(rawValues.size) { i ->
                (inv.invokeFunction("func", rawValues[i]) as Number).toDouble()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            rawValues
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Axis (1-D array) reader
    // ─────────────────────────────────────────────────────────────────────

    private fun parseAxis(bytes: ByteArray, axis: AxisDefinition): Array<Double> {
        // Virtual axis — data lives in LABELs, not in the binary
        if (axis.isVirtual) {
            if (axis.axisValues.isNotEmpty()) {
                val raw = DoubleArray(axis.indexCount) { i ->
                    if (i < axis.axisValues.size) axis.axisValues[i].second.toDouble() else 0.0
                }
                return applyEquation(raw, axis).toTypedArray()
            }
            return emptyArray()
        }

        val count = axis.indexCount
        if (count == 0) return emptyArray()

        val strideBytes = axis.sizeBits / 8
        // minorStrideBits: extra bits between consecutive elements (often used for padding)
        val minorSkipBytes = axis.minorStrideBits / 8

        val requiredBytes = axis.address.toLong() + count.toLong() * (strideBytes + minorSkipBytes)
        if (axis.address < 0 || requiredBytes > bytes.size) {
            return emptyArray()
        }

        val buf = ByteBuffer.wrap(bytes).order(byteOrderFor(axis))
        val raw = DoubleArray(count)

        try {
            buf.position(axis.address)
            for (i in 0 until count) {
                raw[i] = readScalar(buf, axis)
                // Skip any inter-element padding
                if (minorSkipBytes > 0) repeat(minorSkipBytes) { buf.get() }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return emptyArray()
        }

        return applyEquation(raw, axis).toTypedArray()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Z-data (2-D) reader
    // ─────────────────────────────────────────────────────────────────────

    private fun parseData(bytes: ByteArray, axis: AxisDefinition): Array<Array<Double>> {
        if (axis.address == 0) return emptyArray()

        val rows    = maxOf(axis.rowCount, 1)
        val cols    = maxOf(axis.columnCount, 1)
        val stride  = axis.sizeBits / 8

        // majorStrideBits: bits between the START of consecutive rows (0 = tightly packed)
        // When set, each row occupies (majorStrideBits/8) bytes — meaning there may be
        // padding between the actual data and the next row start.
        val rowSizeBytes  = if (axis.majorStrideBits > 0) axis.majorStrideBits / 8 else stride * cols
        val rowPadBytes   = rowSizeBytes - stride * cols   // bytes of padding after each row's data
        val minorSkipBytes = axis.minorStrideBits / 8      // per-element padding

        val buf = ByteBuffer.wrap(bytes).order(byteOrderFor(axis))

        val totalDataBytes = axis.address.toLong() + rows.toLong() * rowSizeBytes
        if (axis.address < 0 || totalDataBytes > bytes.size) {
            return emptyArray()
        }

        val raw: Array<Array<Double>>
        try {
            buf.position(axis.address)
            if (axis.isColumnMajor) {
                // Read column-major: [col][row] in memory, transpose to [row][col]
                val colMajor = Array(cols) { DoubleArray(rows) }
                for (col in 0 until cols) {
                    for (row in 0 until rows) {
                        colMajor[col][row] = readScalar(buf, axis)
                        if (minorSkipBytes > 0) repeat(minorSkipBytes) { buf.get() }
                    }
                }
                raw = Array(rows) { row -> Array(cols) { col -> colMajor[col][row] } }
            } else {
                // Row-major (standard)
                raw = Array(rows) { _ ->
                    val rowData = Array(cols) { _ ->
                        val v = readScalar(buf, axis)
                        if (minorSkipBytes > 0) repeat(minorSkipBytes) { buf.get() }
                        v
                    }
                    // Skip row padding if majorStrideBits was set
                    if (rowPadBytes > 0) repeat(rowPadBytes) { buf.get() }
                    rowData
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return emptyArray()
        }

        // Apply equation to all cells
        val flatRaw = DoubleArray(rows * cols) { i -> raw[i / cols][i % cols] }
        val flatOut = applyEquation(flatRaw, axis)
        return Array(rows) { r -> Array(cols) { c -> flatOut[r * cols + c] } }
    }
}
