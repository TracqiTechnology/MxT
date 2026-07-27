package data.writer

import data.parser.xdf.AxisDefinition
import data.parser.xdf.TableDefinition
import data.parser.bin.BinParser
import domain.math.map.Map3d
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import javax.script.*
import kotlin.math.abs
import kotlin.math.max

object BinWriter {
    private const val INVALID_ADDRESS = 0
    private val engine: ScriptEngine = ScriptEngineManager().getEngineByName("graal.js")

    private val _writeEvents = MutableSharedFlow<TableDefinition>(extraBufferCapacity = 1)
    val writeEvents: SharedFlow<TableDefinition> = _writeEvents.asSharedFlow()

    /**
     * Returns true if the active ECU platform supports BIN writing.
     * Both ME7 and MED17 platforms support direct byte writes to BIN files.
     */
    fun isBinWriteSupported(): Boolean = true

    fun write(file: File, tableDefinition: TableDefinition, map: Map3d) {
        writeBatch(file, listOf(tableDefinition to map))
    }

    /**
     * Atomically writes a related set of calibration tables.
     *
     * Every map is dimension-checked, encoded, collision-checked and decoded from
     * one staged BIN before the original file is replaced once. If any table is
     * invalid, no table in the batch is committed.
     */
    fun writeBatch(file: File, writes: List<Pair<TableDefinition, Map3d>>) {
        require(writes.isNotEmpty()) { "At least one table is required for a BIN write" }
        require(file.exists() && file.isFile) { "BIN file does not exist: ${file.path}" }

        val regions = mutableListOf<WriteRegion>()
        for ((tableDefinition, map) in writes) {
            regions += prepareWriteRegions(tableDefinition, map).map { prepared ->
                WriteRegion(tableDefinition, prepared.axis, prepared.bytes)
            }
        }

        val original = file.readBytes()
        val staged = original.copyOf()
        val appliedRegions = mutableListOf<WriteRegion>()
        for (region in regions) {
            val start = region.axis.address
            val end = start.toLong() + region.bytes.size
            require(start >= 0 && end <= staged.size.toLong()) {
                "Refusing to write '${region.tableDefinition.tableName}': region " +
                    "0x${start.toString(16)}..0x${end.toString(16)} is outside the " +
                    "${staged.size}-byte BIN"
            }
            for (previous in appliedRegions) {
                validateCompatibleOverlap(previous, region)
            }
            region.bytes.copyInto(staged, destinationOffset = start)
            appliedRegions += region
        }

        // Decode every table from the combined staged image and compare engineering
        // values, not merely dimensions. This validates equations, addresses,
        // endianness, strides and multi-table interactions together.
        val parsedMaps = BinParser.parseToList(
            ByteArrayInputStream(staged),
            writes.map { it.first }
        )
        require(parsedMaps.size == writes.size) {
            "Round-trip validation returned ${parsedMaps.size} tables for ${writes.size} writes"
        }
        writes.forEachIndexed { index, (tableDefinition, requestedMap) ->
            val parsed = parsedMaps[index].second
            validateDecodedMap(tableDefinition, requestedMap, parsed)
        }

        replaceAtomically(file, staged)
        writes.forEach { (tableDefinition, _) -> _writeEvents.tryEmit(tableDefinition) }
    }

    private data class PreparedRegion(
        val axis: AxisDefinition,
        val bytes: ByteArray
    )

    private interface RegionView {
        val tableDefinition: TableDefinition
        val axis: AxisDefinition
        val bytes: ByteArray
    }

    private data class WriteRegion(
        override val tableDefinition: TableDefinition,
        override val axis: AxisDefinition,
        override val bytes: ByteArray
    ) : RegionView

    private fun prepareWriteRegions(
        tableDefinition: TableDefinition,
        map: Map3d
    ): List<PreparedRegion> {
        val regions = mutableListOf<PreparedRegion>()
        // Axis writes only apply when the map carries that axis's breakpoints.
        // A scalar map has empty x/y axes and writes only z. Guards compare
        // against indexCount, which is the axis's actual binary breakpoint count.
        val xAxis = tableDefinition.xAxis?.takeIf { it.address != INVALID_ADDRESS && map.xAxis.isNotEmpty() }
        val yAxis = tableDefinition.yAxis?.takeIf { it.address != INVALID_ADDRESS && map.yAxis.isNotEmpty() }
        val zAxis = tableDefinition.zAxis.takeIf { it.address != INVALID_ADDRESS }

        xAxis?.let { axis ->
            require(map.xAxis.size == axis.indexCount) {
                "Refusing to write '${tableDefinition.tableName}': x-axis has ${map.xAxis.size} " +
                    "points but the binary table holds ${axis.indexCount}. Breakpoint counts cannot change on write."
            }
        }
        yAxis?.let { axis ->
            require(map.yAxis.size == axis.indexCount) {
                "Refusing to write '${tableDefinition.tableName}': y-axis has ${map.yAxis.size} " +
                    "points but the binary table holds ${axis.indexCount}. Breakpoint counts cannot change on write."
            }
        }
        require(map.xAxis.all { it.isFinite() } && map.yAxis.all { it.isFinite() }) {
            "Refusing to write '${tableDefinition.tableName}': axes must contain only finite values"
        }
        require(isStrictlyIncreasingOrScalar(map.xAxis)) {
            "Refusing to write '${tableDefinition.tableName}': x-axis must be strictly increasing; " +
                "received ${map.xAxis.contentToString()}"
        }
        require(isStrictlyIncreasingOrScalar(map.yAxis)) {
            "Refusing to write '${tableDefinition.tableName}': y-axis must be strictly increasing; " +
                "received ${map.yAxis.contentToString()}"
        }
        val zRows = maxOf(tableDefinition.zAxis.rowCount, 1)
        val zCols = maxOf(tableDefinition.zAxis.columnCount, 1)
        zAxis?.let {
            require(map.zAxis.size == zRows && map.zAxis.all { row -> row.size == zCols }) {
                "Refusing to write '${tableDefinition.tableName}': z-data is " +
                    "${map.zAxis.size}×${map.zAxis.firstOrNull()?.size ?: 0} but the binary table is " +
                    "$zRows×$zCols. Map dimensions cannot change on write."
            }
        }

        // Encode every region before touching the BIN. Equation or range failures
        // therefore leave the original file byte-identical.
        xAxis?.let { axis ->
            val values = map.xAxis.toDoubleArray()
            regions += PreparedRegion(axis, encode(axis, values))
        }
        yAxis?.let { axis ->
            val values = map.yAxis.toDoubleArray()
            regions += PreparedRegion(axis, encode(axis, values))
        }
        zAxis?.let { axis ->
            val zFlat = DoubleArray(zRows * zCols)
            var index = 0
            if (axis.isColumnMajor) {
                for (j in 0 until zCols) for (i in 0 until zRows) zFlat[index++] = map.zAxis[i][j]
            } else {
                for (i in 0 until zRows) for (j in 0 until zCols) zFlat[index++] = map.zAxis[i][j]
            }
            regions += PreparedRegion(axis, encode(axis, zFlat))
        }
        return regions
    }

    private fun validateCompatibleOverlap(
        previous: RegionView,
        current: RegionView
    ) {
        val overlapStart = max(previous.axis.address, current.axis.address)
        val overlapEnd = minOf(
            previous.axis.address + previous.bytes.size,
            current.axis.address + current.bytes.size
        )
        if (overlapStart >= overlapEnd) return

        for (address in overlapStart until overlapEnd) {
            val previousByte = previous.bytes[address - previous.axis.address]
            val currentByte = current.bytes[address - current.axis.address]
            require(previousByte == currentByte) {
                "Refusing batch write: '${previous.tableDefinition.tableName}' and " +
                    "'${current.tableDefinition.tableName}' encode conflicting bytes at " +
                    "0x${address.toString(16)}"
            }
        }
    }

    private fun validateDecodedMap(
        tableDefinition: TableDefinition,
        requested: Map3d,
        decoded: Map3d
    ) {
        fun compareAxis(
            label: String,
            expected: Array<Double>,
            actual: Array<Double>,
            definition: AxisDefinition?
        ) {
            if (expected.isEmpty() || definition == null || definition.address == INVALID_ADDRESS) return
            require(actual.size == expected.size) {
                "Round-trip validation failed for '${tableDefinition.tableName}': " +
                    "$label-axis size ${actual.size} != ${expected.size}"
            }
            expected.indices.forEach { index ->
                val tolerance = quantizationTolerance(definition, expected[index])
                require(abs(actual[index] - expected[index]) <= tolerance) {
                    "Round-trip validation failed for '${tableDefinition.tableName}' " +
                        "$label[$index]: requested ${expected[index]}, decoded ${actual[index]}, " +
                        "tolerance $tolerance"
                }
            }
        }

        compareAxis("x", requested.xAxis, decoded.xAxis, tableDefinition.xAxis)
        compareAxis("y", requested.yAxis, decoded.yAxis, tableDefinition.yAxis)

        val rows = maxOf(tableDefinition.zAxis.rowCount, 1)
        val cols = maxOf(tableDefinition.zAxis.columnCount, 1)
        require(decoded.zAxis.size == rows && decoded.zAxis.all { it.size == cols }) {
            "Round-trip validation failed for '${tableDefinition.tableName}': decoded z-data is " +
                "${decoded.zAxis.size}×${decoded.zAxis.firstOrNull()?.size ?: 0}, expected $rows×$cols"
        }
        for (row in 0 until rows) {
            for (column in 0 until cols) {
                val expected = requested.zAxis[row][column]
                val actual = decoded.zAxis[row][column]
                val tolerance = quantizationTolerance(tableDefinition.zAxis, expected)
                require(abs(actual - expected) <= tolerance) {
                    "Round-trip validation failed for '${tableDefinition.tableName}' " +
                        "z[$row][$column]: requested $expected, decoded $actual, tolerance $tolerance"
                }
            }
        }
    }

    private fun replaceAtomically(file: File, staged: ByteArray) {
        val parent = file.absoluteFile.parentFile.toPath()
        val stagedPath = Files.createTempFile(parent, ".mxt-write-", ".bin")
        try {
            Files.copy(
                file.toPath(),
                stagedPath,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.COPY_ATTRIBUTES
            )
            Files.write(stagedPath, staged)
            FileChannel.open(stagedPath, StandardOpenOption.WRITE).use { it.force(true) }
            Files.move(
                stagedPath,
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
            // Persist the directory entry where supported. Some platforms do not
            // permit opening directories, so the atomic replacement remains the
            // required safety boundary and directory fsync is best-effort.
            runCatching {
                FileChannel.open(parent, StandardOpenOption.READ).use { it.force(true) }
            }
        } finally {
            Files.deleteIfExists(stagedPath)
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Low-level write: invert equation, encode, and write bytes
    // ─────────────────────────────────────────────────────────────────────

    private fun encode(axis: AxisDefinition, values: DoubleArray): ByteArray {
        val inverseEquation = buildInverseEquation(axis.equation, axis.varId)
        val byteOrder = if (axis.lsbFirst) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        val stride = axis.sizeBits / 8

        val rawValues = synchronized(engine) {
            val compiled = (engine as Compilable).compile(
                "function func(X) { return $inverseEquation }"
            )
            compiled.eval(compiled.engine.getBindings(ScriptContext.ENGINE_SCOPE))
            val inv = compiled.engine as Invocable
            DoubleArray(values.size) { index ->
                val value = values[index]
                require(value.isFinite()) {
                    "Cannot encode non-finite value $value for '${axis.id}'"
                }
                (inv.invokeFunction("func", value) as Number).toDouble().also { raw ->
                    require(raw.isFinite()) {
                        "Inverse equation produced non-finite value for '${axis.id}'"
                    }
                }
            }
        }
        val bb = ByteBuffer.allocate(values.size * stride).order(byteOrder)

        for (index in values.indices) {
            val value = values[index]
            val raw = rawValues[index]

            if (!axis.isFloat) {
                val min = if (axis.isSigned) -(1L shl (axis.sizeBits - 1)).toDouble() else 0.0
                val max = if (axis.isSigned) {
                    ((1L shl (axis.sizeBits - 1)) - 1).toDouble()
                } else {
                    if (axis.sizeBits == 32) 4_294_967_295.0 else ((1L shl axis.sizeBits) - 1).toDouble()
                }
                require(raw in min..max) {
                    "Value $value encodes to $raw, outside ${axis.sizeBits}-bit " +
                        "${if (axis.isSigned) "signed" else "unsigned"} range for '${axis.id}'"
                }
            }

            when {
                axis.isFloat && stride == 4 -> bb.putFloat(raw.toFloat())
                stride == 4 -> bb.putInt(raw.toLong().toInt())
                stride == 2 -> bb.putShort(raw.toLong().toShort())
                else -> bb.put(raw.toLong().toByte())
            }
        }
        return bb.array()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Inverse equation builder
    //
    // Handles the common ME7 forms produced by Bosch / TunerPro translators:
    //   A * X
    //   A * X + B  /  A * X - B
    //   (X + B) / A  (already inverted form)
    //   X + B  /  X - B
    //   X / A  /  X * A
    //
    // Composite affine expressions are detected numerically. Anything
    // non-linear or non-invertible fails closed before any bytes are staged.
    //
    // "X" in the inverse equation always stands for the engineering-unit
    // value (the value shown in the table).  The result is the raw integer.
    // ─────────────────────────────────────────────────────────────────────

    internal fun buildInverseEquation(equation: String, varId: String): String {
        // Normalise: replace the variable id with X so the inverse is always
        // in terms of X regardless of what the XDF varId was.
        val eq = equation.trim().replace(varId, "X")
        if (eq.isBlank() || eq == "X") return "X"

        // ── Pattern: A * X  (+ or - B optional) ─────────────────────────
        // Matches:  0.023438 * X   |   0.75 * X + 18   |   0.75 * X - 18
        val mulAdd = Regex(
            """^([+-]?\s*\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)\s*\*\s*X\s*([+-]\s*\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)?$"""
        ).matchEntire(eq)
        if (mulAdd != null) {
            val a = mulAdd.groupValues[1].replace(" ", "").toDoubleOrNull()
            val bStr = mulAdd.groupValues[2].replace(" ", "")
            val b = bStr.toDoubleOrNull()
            if (a != null && a != 0.0) {
                return if (b != null) "(X - $b) / $a" else "X / $a"
            }
        }

        // ── Pattern: X * A  (+ or - B optional) ─────────────────────────
        val xMulAdd = Regex(
            """^X\s*\*\s*([+-]?\s*\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)\s*([+-]\s*\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)?$"""
        ).matchEntire(eq)
        if (xMulAdd != null) {
            val a = xMulAdd.groupValues[1].replace(" ", "").toDoubleOrNull()
            val bStr = xMulAdd.groupValues[2].replace(" ", "")
            val b = bStr.toDoubleOrNull()
            if (a != null && a != 0.0) {
                return if (b != null) "(X - $b) / $a" else "X / $a"
            }
        }

        // ── Pattern: X + B  /  X - B ─────────────────────────────────────
        val xAddSub = Regex(
            """^X\s*([+-])\s*(\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)$"""
        ).matchEntire(eq)
        if (xAddSub != null) {
            val sign = xAddSub.groupValues[1]
            val b    = xAddSub.groupValues[2]
            return if (sign == "+") "X - $b" else "X + $b"
        }

        // ── Pattern: X / A ───────────────────────────────────────────────
        val xDiv = Regex(
            """^X\s*/\s*(\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)$"""
        ).matchEntire(eq)
        if (xDiv != null) {
            val a = xDiv.groupValues[1].toDoubleOrNull()
            if (a != null && a != 0.0) return "X * $a"
        }

        // ── General affine fallback ────────────────────────────────────────
        // XDFs commonly express a linear conversion in composite form, e.g.
        // X/16384*100 or (X+40)/0.75. Detect the affine slope/intercept by
        // evaluating the forward equation. Non-linear or constant equations
        // fail closed rather than silently writing engineering units as raw data.
        val f0 = evaluateForwardEquation(eq, 0.0)
        val f1 = evaluateForwardEquation(eq, 1.0)
        val f2 = evaluateForwardEquation(eq, 2.0)
        val slope = f1 - f0
        val secondStep = f2 - f1
        val tolerance = max(1e-10, max(abs(slope), abs(secondStep)) * 1e-9)
        require(slope.isFinite() && abs(slope) > tolerance && abs(secondStep - slope) <= tolerance) {
            "Unsupported non-linear or non-invertible equation for '$varId': $equation"
        }
        return "(X - $f0) / $slope"
    }

    private fun evaluateForwardEquation(equation: String, raw: Double): Double =
        synchronized(engine) {
            val compiled = (engine as Compilable).compile(
                "function forward(X) { return $equation }"
            )
            compiled.eval(compiled.engine.getBindings(ScriptContext.ENGINE_SCOPE))
            val value = (compiled.engine as Invocable).invokeFunction("forward", raw) as? Number
                ?: throw IllegalArgumentException("Equation did not return a number: $equation")
            value.toDouble().also {
                require(it.isFinite()) { "Equation produced a non-finite value: $equation" }
            }
        }

    private fun quantizationTolerance(axis: AxisDefinition, expected: Double): Double {
        if (axis.isFloat) return max(1e-6, abs(expected) * 1e-6)
        val eq = axis.equation.trim().replace(axis.varId, "X")
        if (eq.isBlank() || eq == "X") return 1.000000001
        val atZero = evaluateForwardEquation(eq, 0.0)
        val atOne = evaluateForwardEquation(eq, 1.0)
        return max(1e-9, abs(atOne - atZero) * 1.000000001)
    }

    private fun isStrictlyIncreasingOrScalar(axis: Array<Double>): Boolean =
        axis.size <= 1 || (1 until axis.size).all { axis[it].isFinite() && axis[it] > axis[it - 1] }
}
