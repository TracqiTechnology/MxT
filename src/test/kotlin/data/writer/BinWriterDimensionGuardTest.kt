package data.writer

import data.parser.xdf.AxisDefinition
import data.parser.xdf.TableDefinition
import domain.math.map.Map3d
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

/**
 * Tests for [BinWriter]'s dimension guard — the defense against the silent
 * zero-fill / ArrayIndexOutOfBounds corruption that PR #95 could produce when an
 * edited map's breakpoint count no longer matches the fixed-size binary table.
 * The guard must reject mismatches loudly (IllegalArgumentException) and must NOT
 * fire on the legitimate scalar (MED17 1×1) case.
 */
class BinWriterDimensionGuardTest {

    private val tmpFiles = mutableListOf<File>()

    @AfterTest
    fun cleanup() = tmpFiles.forEach { it.delete() }

    private fun bin(): File =
        File.createTempFile("mxt-guard", ".bin").also { tmpFiles.add(it); it.writeBytes(ByteArray(128)) }

    private fun axis(id: String, address: Int, count: Int, rows: Int = 1, cols: Int = count) =
        AxisDefinition(
            id = id, type = 0, address = address, indexCount = count, sizeBits = 16,
            rowCount = rows, columnCount = cols, unit = "", equation = "X", varId = "X",
            axisValues = emptyList(), lsbFirst = false
        )

    // 2D table: x=4 breakpoints @0x10, y=3 breakpoints @0x20, z=3x4 @0x30.
    private fun table2d(): TableDefinition = TableDefinition(
        "KFMIOP", "", axis("x", 0x10, 4), axis("y", 0x20, 3),
        AxisDefinition("z", 0, 0x30, 12, 16, 3, 4, "", "X", "X", emptyList(), lsbFirst = false)
    )

    private fun map(x: Int, y: Int, zr: Int, zc: Int): Map3d = Map3d(
        Array(x) { it.toDouble() }, Array(y) { it.toDouble() },
        Array(zr) { i -> Array(zc) { j -> (i * 10 + j).toDouble() } }
    )

    @Test
    fun `matching dimensions write without error`() {
        BinWriter.write(bin(), table2d(), map(x = 4, y = 3, zr = 3, zc = 4))
    }

    @Test
    fun `x-axis point count mismatch is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            BinWriter.write(bin(), table2d(), map(x = 5, y = 3, zr = 3, zc = 4))
        }
    }

    @Test
    fun `y-axis point count mismatch is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            BinWriter.write(bin(), table2d(), map(x = 4, y = 4, zr = 3, zc = 4))
        }
    }

    @Test
    fun `z row count mismatch (resized RPM axis) is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            BinWriter.write(bin(), table2d(), map(x = 4, y = 3, zr = 4, zc = 4))
        }
    }

    @Test
    fun `z column count mismatch is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            BinWriter.write(bin(), table2d(), map(x = 4, y = 3, zr = 3, zc = 5))
        }
    }

    @Test
    fun `ragged z (rows of differing length) is rejected`() {
        val ragged = Map3d(
            Array(4) { it.toDouble() }, Array(3) { it.toDouble() },
            arrayOf(arrayOf(1.0, 2.0, 3.0, 4.0), arrayOf(5.0, 6.0), arrayOf(7.0, 8.0, 9.0, 10.0))
        )
        assertFailsWith<IllegalArgumentException> { BinWriter.write(bin(), table2d(), ragged) }
    }

    @Test
    fun `scalar map to a scalar table writes z only, no guard failure`() {
        val scalarTable = TableDefinition(
            "OptEngTq", "", null, null,
            AxisDefinition("z", 0, 0x30, 1, 16, 1, 1, "", "X", "X", emptyList(), lsbFirst = false)
        )
        val scalar = Map3d(emptyArray(), emptyArray(), arrayOf(arrayOf(1234.0)))
        BinWriter.write(bin(), scalarTable, scalar) // must not throw
    }

    @Test
    fun `empty map axes skip x-y write even when the table defines them`() {
        // A scalar output map (empty x/y) written against a table that has x/y axes:
        // the x/y writes are skipped, only z is written — no guard failure.
        val scalarZ = Map3d(emptyArray(), emptyArray(), arrayOf(arrayOf(42.0, 43.0, 44.0, 45.0)))
        val oneRowTable = TableDefinition(
            "KFMIOP", "", axis("x", 0x10, 4), axis("y", 0x20, 3),
            AxisDefinition("z", 0, 0x30, 4, 16, 1, 4, "", "X", "X", emptyList(), lsbFirst = false)
        )
        BinWriter.write(bin(), oneRowTable, scalarZ) // must not throw (x/y skipped, z 1x4 matches)
    }

    @Test
    fun `encoding failure propagates and leaves BIN unchanged`() {
        val file = bin()
        val before = file.readBytes()
        val invalid = map(x = 4, y = 3, zr = 3, zc = 4).also { map ->
            map.zAxis[1][2] = -1.0 // table is unsigned
        }

        assertFailsWith<IllegalArgumentException> {
            BinWriter.write(file, table2d(), invalid)
        }
        assertContentEquals(before, file.readBytes())
    }

    @Test
    fun `dimension rejection leaves BIN byte identical`() {
        val invalidMaps = listOf(
            map(x = 5, y = 3, zr = 3, zc = 4),
            map(x = 4, y = 4, zr = 3, zc = 4),
            map(x = 4, y = 3, zr = 4, zc = 4),
            map(x = 4, y = 3, zr = 3, zc = 5)
        )

        invalidMaps.forEachIndexed { index, invalid ->
            val file = bin()
            val before = file.readBytes()
            assertFailsWith<IllegalArgumentException>("invalid dimension case $index") {
                BinWriter.write(file, table2d(), invalid)
            }
            assertContentEquals(before, file.readBytes(), "invalid dimension case $index")
        }
    }

    @Test
    fun `out of bounds staged region leaves BIN byte identical`() {
        val file = bin()
        val before = file.readBytes()
        val outOfBounds = TableDefinition(
            "OUT_OF_BOUNDS", "",
            axis("x", 0x7c, 4),
            axis("y", 0x20, 3),
            AxisDefinition(
                "z", 0, 0x30, 12, 16, 3, 4,
                "", "X", "X", emptyList(), lsbFirst = false
            )
        )

        assertFailsWith<IllegalArgumentException> {
            BinWriter.write(file, outOfBounds, map(x = 4, y = 3, zr = 3, zc = 4))
        }
        assertContentEquals(before, file.readBytes())
    }

    @Test
    fun `non finite data leaves BIN byte identical`() {
        val file = bin()
        val before = file.readBytes()
        val invalid = map(x = 4, y = 3, zr = 3, zc = 4).also {
            it.zAxis[0][0] = Double.NaN
        }

        assertFailsWith<IllegalArgumentException> {
            BinWriter.write(file, table2d(), invalid)
        }
        assertContentEquals(before, file.readBytes())
    }

    @Test
    fun `batch failure leaves every table byte identical`() {
        val file = bin()
        val before = file.readBytes()
        val valid = TableDefinition(
            "VALID", "", null, null,
            AxisDefinition(
                "z", 0, 0x10, 1, 16, 1, 1, "", "X", "X", emptyList(),
                lsbFirst = false
            )
        )
        val invalid = TableDefinition(
            "INVALID", "", null, null,
            AxisDefinition(
                "z", 0, 0x7f, 1, 16, 1, 1, "", "X", "X", emptyList(),
                lsbFirst = false
            )
        )

        assertFailsWith<IllegalArgumentException> {
            BinWriter.writeBatch(
                file,
                listOf(
                    valid to Map3d(emptyArray(), emptyArray(), arrayOf(arrayOf(123.0))),
                    invalid to Map3d(emptyArray(), emptyArray(), arrayOf(arrayOf(456.0)))
                )
            )
        }
        assertContentEquals(before, file.readBytes())
    }

    @Test
    fun `conflicting overlapping batch regions fail without modifying BIN`() {
        val file = bin()
        val before = file.readBytes()
        val first = TableDefinition(
            "FIRST", "", null, null,
            AxisDefinition(
                "z", 0, 0x20, 1, 16, 1, 1, "", "X", "X", emptyList(),
                lsbFirst = false
            )
        )
        val second = TableDefinition(
            "SECOND", "", null, null,
            AxisDefinition(
                "z", 0, 0x21, 1, 16, 1, 1, "", "X", "X", emptyList(),
                lsbFirst = false
            )
        )

        assertFailsWith<IllegalArgumentException> {
            BinWriter.writeBatch(
                file,
                listOf(
                    first to Map3d(emptyArray(), emptyArray(), arrayOf(arrayOf(0x1234.toDouble()))),
                    second to Map3d(emptyArray(), emptyArray(), arrayOf(arrayOf(0x5678.toDouble())))
                )
            )
        }
        assertContentEquals(before, file.readBytes())
    }
}
