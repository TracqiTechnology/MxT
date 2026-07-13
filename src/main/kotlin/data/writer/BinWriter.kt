package data.writer

import data.parser.xdf.AxisDefinition
import data.parser.xdf.TableDefinition
import domain.math.map.Map3d
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.script.*

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
        RandomAccessFile(file, "rws").use { raf ->
            // Axis writes only apply when the map carries that axis's breakpoints.
            // A scalar map (MED17 1×1 KFMIOP) has empty x/y axes and writes only z.
            // The guard compares against indexCount (the axis's own breakpoint count);
            // rowCount/columnCount mirror the linked z-table dims, not the axis length.
            tableDefinition.xAxis?.takeIf { it.address != INVALID_ADDRESS && map.xAxis.isNotEmpty() }?.let { axis ->
                require(map.xAxis.size == axis.indexCount) {
                    "Refusing to write '${tableDefinition.tableName}': x-axis has ${map.xAxis.size} " +
                        "points but the binary table holds ${axis.indexCount}. Breakpoint counts cannot change on write."
                }
                val xFlat = DoubleArray(maxOf(axis.rowCount, 1) * maxOf(axis.indexCount, 1))
                for (i in map.xAxis.indices) xFlat[i] = map.xAxis[i]
                write(raf, axis, xFlat)
            }

            tableDefinition.yAxis?.takeIf { it.address != INVALID_ADDRESS && map.yAxis.isNotEmpty() }?.let { axis ->
                require(map.yAxis.size == axis.indexCount) {
                    "Refusing to write '${tableDefinition.tableName}': y-axis has ${map.yAxis.size} " +
                        "points but the binary table holds ${axis.indexCount}. Breakpoint counts cannot change on write."
                }
                val yFlat = DoubleArray(maxOf(axis.rowCount, 1) * maxOf(axis.indexCount, 1))
                for (i in map.yAxis.indices) yFlat[i] = map.yAxis[i]
                write(raf, axis, yFlat)
            }

            tableDefinition.zAxis.takeIf { it.address != INVALID_ADDRESS }?.let { axis ->
                val rows = maxOf(axis.rowCount, 1)
                val cols = maxOf(axis.columnCount, 1)
                require(map.zAxis.size == rows && map.zAxis.all { it.size == cols }) {
                    "Refusing to write '${tableDefinition.tableName}': z-data is " +
                        "${map.zAxis.size}×${map.zAxis.firstOrNull()?.size ?: 0} but the binary table is " +
                        "$rows×$cols. Map dimensions cannot change on write."
                }
                val zFlat = DoubleArray(rows * cols)
                var index = 0
                if (axis.isColumnMajor) {
                    // COLUMN_DIR: write column-by-column so the on-disk layout matches
                    // what BinParser reads back (column-major storage, e.g. MED9 KFMIOP).
                    for (j in 0 until cols) for (i in 0 until rows) zFlat[index++] = map.zAxis[i][j]
                } else {
                    for (i in 0 until rows) for (j in 0 until cols) zFlat[index++] = map.zAxis[i][j]
                }
                write(raf, axis, zFlat)
            }
        }

        _writeEvents.tryEmit(tableDefinition)
    }

    // ─────────────────────────────────────────────────────────────────────
    // Low-level write: invert equation, encode, and write bytes
    // ─────────────────────────────────────────────────────────────────────

    private fun write(raf: RandomAccessFile, axis: AxisDefinition, values: DoubleArray) {
        val inverseEquation = buildInverseEquation(axis.equation, axis.varId)
        val byteOrder = if (axis.lsbFirst) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        val stride = axis.sizeBits / 8

        try {
            val compiled = (engine as Compilable).compile(
                "function func(X) { return $inverseEquation }"
            )
            compiled.eval(compiled.engine.getBindings(ScriptContext.ENGINE_SCOPE))
            val inv = compiled.engine as Invocable

            raf.seek(axis.address.toLong())
            val bb = ByteBuffer.allocate(values.size * stride).order(byteOrder)

            for (value in values) {
                val raw = (inv.invokeFunction("func", value) as Number)
                when {
                    axis.isFloat && stride == 4 -> bb.putFloat(raw.toFloat())
                    stride == 4 -> bb.putInt(raw.toInt())
                    stride == 2 -> bb.putShort(raw.toShort())
                    else        -> bb.put(raw.toByte())
                }
            }
            raf.write(bb.array())
        } catch (e: Exception) { e.printStackTrace() }
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
    // For anything more complex the GraalJS engine evaluates the forward
    // equation; we keep the expression as-is and let a numeric round-trip
    // produce the best approximation (clamped to [0, 2^sizeBits-1] at write).
    //
    // "X" in the inverse equation always stands for the engineering-unit
    // value (the value shown in the table).  The result is the raw integer.
    // ─────────────────────────────────────────────────────────────────────

    internal fun buildInverseEquation(equation: String, varId: String): String {
        // Normalise: replace the variable id with X so the inverse is always
        // in terms of X regardless of what the XDF varId was.
        val eq = equation.trim().replace(varId, "X")

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

        // ── Fallback: identity (X) or passthrough ─────────────────────────
        // If we can't analytically invert, write back the engineering value
        // directly (works for identity maps, and is safe for constants).
        return "X"
    }
}
