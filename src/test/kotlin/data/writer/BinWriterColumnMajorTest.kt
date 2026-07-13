package data.writer

import data.parser.bin.BinParser
import data.parser.xdf.AxisDefinition
import data.parser.xdf.TableDefinition
import domain.math.map.Map3d
import java.io.File
import java.io.FileInputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Golden round-trip tests for [BinWriter]'s COLUMN_DIR (column-major) write path —
 * the safety-critical MED9 KFMIOP write behaviour that shipped in PR #95 with no
 * coverage. A MED9 KFMIOP z-table is stored column-major and big-endian (MPC562
 * PowerPC). These tests write synthetic tables and read them back with [BinParser]
 * to prove the writer's on-disk layout, byte order, width, and equation handling
 * exactly match the reader across a range of shapes and element types.
 */
class BinWriterColumnMajorTest {

    private val tmpFiles = mutableListOf<File>()

    @AfterTest
    fun cleanup() = tmpFiles.forEach { it.delete() }

    // Address 0 is INVALID_ADDRESS ("no data") to both writer and parser.
    private val zAddr = 32

    private fun tmpBin(dataBytes: Int): File =
        File.createTempFile("mxt-colmajor", ".bin")
            .also { tmpFiles.add(it); it.writeBytes(ByteArray(zAddr + dataBytes + 8)) }

    private fun zTable(
        rows: Int, cols: Int, columnMajor: Boolean,
        lsbFirst: Boolean = false, sizeBits: Int = 16,
        equation: String = "X", isFloat: Boolean = false, signed: Boolean = false,
    ): TableDefinition {
        val z = AxisDefinition(
            id = "z", type = if (signed) 1 else 0, address = zAddr, indexCount = rows * cols,
            sizeBits = sizeBits, rowCount = rows, columnCount = cols, unit = "", equation = equation,
            varId = "X", axisValues = emptyList(), lsbFirst = lsbFirst, isFloat = isFloat,
            isColumnMajor = columnMajor
        )
        return TableDefinition("SYN", "synthetic", null, null, z)
    }

    private fun mapOf(rows: Int, cols: Int, f: (Int, Int) -> Double): Map3d =
        Map3d(emptyArray(), emptyArray(), Array(rows) { i -> Array(cols) { j -> f(i, j) } })

    private fun readBackZ(bin: File, def: TableDefinition): Array<Array<Double>> =
        BinParser.parseToList(FileInputStream(bin), listOf(def)).single().second.zAxis

    private fun assertZEquals(expected: Map3d, actual: Array<Array<Double>>, tol: Double = 0.0) {
        assertEquals(expected.zAxis.size, actual.size, "row count")
        for (i in expected.zAxis.indices) {
            assertEquals(expected.zAxis[i].size, actual[i].size, "col count of row $i")
            for (j in expected.zAxis[i].indices)
                assertEquals(expected.zAxis[i][j], actual[i][j], tol, "z[$i][$j]")
        }
    }

    // ── Orientation ─────────────────────────────────────────────────────────

    @Test
    fun `column-major big-endian z round-trips (MED9 KFMIOP shape 11x16)`() {
        val def = zTable(11, 16, columnMajor = true, lsbFirst = false)
        val original = mapOf(11, 16) { i, j -> (i * 100 + j).toDouble() }
        val bin = tmpBin(11 * 16 * 2)
        BinWriter.write(bin, def, original)
        assertZEquals(original, readBackZ(bin, def))
    }

    @Test
    fun `row-major z still round-trips (no regression)`() {
        val def = zTable(3, 4, columnMajor = false, lsbFirst = true)
        val original = mapOf(3, 4) { i, j -> (i * 100 + j).toDouble() }
        val bin = tmpBin(3 * 4 * 2)
        BinWriter.write(bin, def, original)
        assertZEquals(original, readBackZ(bin, def))
    }

    @Test
    fun `non-square column-major preserves exact cell positions`() {
        // Distinct per-cell values catch any transpose/index error.
        val def = zTable(16, 11, columnMajor = true, lsbFirst = false)
        val original = mapOf(16, 11) { i, j -> (i * 37 + j * 3 + 1).toDouble() }
        val bin = tmpBin(16 * 11 * 2)
        BinWriter.write(bin, def, original)
        assertZEquals(original, readBackZ(bin, def))
    }

    @Test
    fun `single row and single column column-major round-trip`() {
        val rowVec = zTable(1, 8, columnMajor = true, lsbFirst = false)
        val rowMap = mapOf(1, 8) { _, j -> (j + 1).toDouble() }
        val bin1 = tmpBin(8 * 2)
        BinWriter.write(bin1, rowVec, rowMap)
        assertZEquals(rowMap, readBackZ(bin1, rowVec))

        val colVec = zTable(8, 1, columnMajor = true, lsbFirst = false)
        val colMap = mapOf(8, 1) { i, _ -> (i + 1).toDouble() }
        val bin2 = tmpBin(8 * 2)
        BinWriter.write(bin2, colVec, colMap)
        assertZEquals(colMap, readBackZ(bin2, colVec))
    }

    @Test
    fun `column-major and row-major produce different on-disk bytes`() {
        val map = mapOf(3, 4) { i, j -> (i * 100 + j).toDouble() }
        val colBin = tmpBin(3 * 4 * 2)
        val rowBin = tmpBin(3 * 4 * 2)
        BinWriter.write(colBin, zTable(3, 4, columnMajor = true, lsbFirst = false), map)
        BinWriter.write(rowBin, zTable(3, 4, columnMajor = false, lsbFirst = false), map)
        assertTrue(
            !colBin.readBytes().contentEquals(rowBin.readBytes()),
            "column-major and row-major layouts must differ on disk"
        )
    }

    // ── Element widths / types ──────────────────────────────────────────────

    @Test
    fun `8-bit column-major round-trips`() {
        val def = zTable(4, 5, columnMajor = true, lsbFirst = false, sizeBits = 8)
        val original = mapOf(4, 5) { i, j -> ((i * 5 + j) % 250).toDouble() }
        val bin = tmpBin(4 * 5 * 1)
        BinWriter.write(bin, def, original)
        assertZEquals(original, readBackZ(bin, def))
    }

    @Test
    fun `32-bit column-major round-trips`() {
        val def = zTable(3, 3, columnMajor = true, lsbFirst = false, sizeBits = 32)
        val original = mapOf(3, 3) { i, j -> (i * 1_000_000 + j * 7).toDouble() }
        val bin = tmpBin(3 * 3 * 4)
        BinWriter.write(bin, def, original)
        assertZEquals(original, readBackZ(bin, def))
    }

    @Test
    fun `32-bit float column-major round-trips`() {
        val def = zTable(3, 4, columnMajor = true, lsbFirst = false, sizeBits = 32, isFloat = true)
        val original = mapOf(3, 4) { i, j -> (i + j * 0.25f).toDouble() }
        val bin = tmpBin(3 * 4 * 4)
        BinWriter.write(bin, def, original)
        assertZEquals(original, readBackZ(bin, def), tol = 1e-6)
    }

    @Test
    fun `linear equation is inverted on write and re-applied on read (column-major)`() {
        // equation X*0.5: engineering values halve the raw; writer inverts to raw=2*value.
        val def = zTable(3, 4, columnMajor = true, lsbFirst = false, equation = "X * 0.5")
        val original = mapOf(3, 4) { i, j -> ((i * 4 + j) * 2).toDouble() } // even -> integer raw
        val bin = tmpBin(3 * 4 * 2)
        BinWriter.write(bin, def, original)
        assertZEquals(original, readBackZ(bin, def), tol = 1e-9)
    }
}
