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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Golden round-trip tests for [BinWriter]'s COLUMN_DIR (column-major) write path and
 * the dimension guard — the safety-critical MED9 KFMIOP write behaviour that shipped
 * in PR #95 with no coverage.
 *
 * A MED9 KFMIOP z-table is stored column-major and big-endian (MPC562 PowerPC).
 * These tests build synthetic tables, write them with [BinWriter], and read them back
 * with [BinParser] to prove the writer's byte order matches the reader.
 */
class BinWriterColumnMajorTest {

    private val tmpFiles = mutableListOf<File>()

    @AfterTest
    fun cleanup() {
        tmpFiles.forEach { it.delete() }
    }

    // Address 0 is treated as INVALID_ADDRESS ("no data") by both BinWriter and
    // BinParser, so place synthetic data at a non-zero offset.
    private val zAddr = 32

    private fun tmpBin(dataBytes: Int): File {
        val f = File.createTempFile("mxt-colmajor", ".bin").also { tmpFiles.add(it) }
        f.writeBytes(ByteArray(zAddr + dataBytes))
        return f
    }

    /** z-only table (x/y axes null) so the round-trip isolates the z data path. */
    private fun zTable(rows: Int, cols: Int, columnMajor: Boolean, lsbFirst: Boolean): TableDefinition {
        val z = AxisDefinition(
            id = "z", type = 0, address = zAddr, indexCount = rows * cols, sizeBits = 16,
            rowCount = rows, columnCount = cols, unit = "", equation = "X", varId = "X",
            axisValues = emptyList(), lsbFirst = lsbFirst, isColumnMajor = columnMajor
        )
        return TableDefinition("SYN", "synthetic", null, null, z)
    }

    private fun distinctMap(rows: Int, cols: Int): Map3d =
        Map3d(emptyArray(), emptyArray(), Array(rows) { i -> Array(cols) { j -> (i * 100 + j).toDouble() } })

    private fun readBackZ(bin: File, def: TableDefinition): Array<Array<Double>> {
        val maps = BinParser.parseToList(FileInputStream(bin), listOf(def))
        return maps.single().second.zAxis
    }

    @Test
    fun `column-major big-endian z round-trips through write then parse`() {
        val rows = 11; val cols = 16               // MED9 KFMIOP shape: 11 load rows x 16 rpm cols
        val def = zTable(rows, cols, columnMajor = true, lsbFirst = false)
        val original = distinctMap(rows, cols)
        val bin = tmpBin(rows * cols * 2)

        BinWriter.write(bin, def, original)
        val readBack = readBackZ(bin, def)

        assertEquals(rows, readBack.size, "row count preserved")
        for (i in 0 until rows) for (j in 0 until cols) {
            assertEquals(original.zAxis[i][j], readBack[i][j], "z[$i][$j] must survive the column-major round-trip")
        }
    }

    @Test
    fun `row-major z still round-trips (no regression)`() {
        val rows = 3; val cols = 4
        val def = zTable(rows, cols, columnMajor = false, lsbFirst = true)
        val original = distinctMap(rows, cols)
        val bin = tmpBin(rows * cols * 2)

        BinWriter.write(bin, def, original)
        val readBack = readBackZ(bin, def)

        for (i in 0 until rows) for (j in 0 until cols) {
            assertEquals(original.zAxis[i][j], readBack[i][j], "z[$i][$j] must survive the row-major round-trip")
        }
    }

    @Test
    fun `column-major and row-major produce different on-disk bytes`() {
        val rows = 3; val cols = 4
        val map = distinctMap(rows, cols)
        val colBin = tmpBin(rows * cols * 2)
        val rowBin = tmpBin(rows * cols * 2)

        BinWriter.write(colBin, zTable(rows, cols, columnMajor = true, lsbFirst = false), map)
        BinWriter.write(rowBin, zTable(rows, cols, columnMajor = false, lsbFirst = false), map)

        assertTrue(
            !colBin.readBytes().contentEquals(rowBin.readBytes()),
            "column-major and row-major layouts must differ on disk (proves orientation is honoured)"
        )
    }

    @Test
    fun `write refuses a map whose z dimensions differ from the table`() {
        val def = zTable(rows = 11, cols = 16, columnMajor = true, lsbFirst = false)
        val wrongSize = distinctMap(4, 16)         // 4 rows instead of 11 — a resized RPM axis
        val bin = tmpBin(11 * 16 * 2)

        assertFailsWith<IllegalArgumentException>("resized z-data must be rejected, not silently zero-filled") {
            BinWriter.write(bin, def, wrongSize)
        }
    }
}
