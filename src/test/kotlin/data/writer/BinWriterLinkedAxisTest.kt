package data.writer

import data.parser.bin.BinParser
import data.parser.xdf.TableDefinition
import data.parser.xdf.XdfParser
import domain.math.map.Map3d
import java.io.File
import java.io.FileInputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Reproduction of the rk_w write corruption reported against v2.1.2 (and the
 * "X axis resets to 0.00" seen since long before).
 *
 * MED17 rk_w ("Rel fuel mass fac inj type corr HO1") uses LINKED x/y axes
 * (embedinfo type 3). [XdfParser.resolveLinkedAxis] copies the linked table's
 * z dims into the axis's rowCount/columnCount, so BinWriter's axis buffer —
 * sized rowCount × indexCount — ballooned to 256 entries (512 bytes) for a
 * 16-value axis. The buffer's zero tail then overwrote whatever follows the
 * axis in flash. rk_w's layout is contiguous [y axis][x axis][z data], so:
 *   - the x-axis write's zero tail wiped the first 15 of 16 z rows, and
 *   - the y-axis write's zero tail wiped the X AXIS and most of z.
 * The final z write papered over the z damage — until anything (e.g. the
 * v2.1.2 dimension guard) aborted between the axis writes and the z write,
 * leaving the user's bin with a zeroed x axis and z zeroed except its last row.
 *
 * These tests write rk_w HO1 through the real 404E XDF + stock bin and assert
 * the write is surgical: every region reads back exactly what it should, and
 * no byte outside the table's own regions changes.
 */
class BinWriterLinkedAxisTest {

    companion object {
        private val XDF_FILE = File("example/med17/404E/404E_normal.xdf")
        private val BIN_FILE = File("example/med17/404E/MED17_1_62_STOCK.bin")
        private const val RKW_HO1 = "Rel fuel mass fac inj type corr HO1"
    }

    private lateinit var tableDefs: List<TableDefinition>
    private lateinit var rkwDef: TableDefinition
    private lateinit var originalRkw: Map3d
    private lateinit var tmpBin: File

    @BeforeTest
    fun setUp() {
        assertTrue(XDF_FILE.exists(), "404E XDF fixture missing")
        assertTrue(BIN_FILE.exists(), "404E stock bin fixture missing")

        val (_, defs) = XdfParser.parseToList(FileInputStream(XDF_FILE))
        tableDefs = defs
        rkwDef = defs.first { it.tableName == RKW_HO1 }
        originalRkw = BinParser.parseToList(FileInputStream(BIN_FILE), listOf(rkwDef)).single().second

        assertTrue(originalRkw.xAxis.isNotEmpty(), "rk_w x axis parsed")
        assertTrue(originalRkw.yAxis.isNotEmpty(), "rk_w y axis parsed")
        assertTrue(originalRkw.zAxis.isNotEmpty(), "rk_w z parsed")

        tmpBin = File.createTempFile("mxt-rkw", ".bin")
        BIN_FILE.copyTo(tmpBin, overwrite = true)
    }

    @AfterTest
    fun tearDown() {
        tmpBin.delete()
    }

    /** Mirrors FuelTrimScreen: copy the parsed map, apply trims, write. */
    private fun writeCorrectedCopy(): Map3d {
        val output = Map3d(originalRkw)
        for (r in output.zAxis.indices) for (c in output.zAxis[r].indices) {
            output.zAxis[r][c] = originalRkw.zAxis[r][c] * 1.02 // +2% trim everywhere
        }
        BinWriter.write(tmpBin, rkwDef, output)
        return output
    }

    @Test
    fun `x axis survives an rk_w write (user report - X axis reset to 0)`() {
        writeCorrectedCopy()
        val readBack = BinParser.parseToList(FileInputStream(tmpBin), listOf(rkwDef)).single().second
        assertEquals(
            originalRkw.xAxis.toList(), readBack.xAxis.toList(),
            "x axis (load breakpoints) must be unchanged by an rk_w write"
        )
        assertEquals(
            originalRkw.yAxis.toList(), readBack.yAxis.toList(),
            "y axis (RPM breakpoints) must be unchanged by an rk_w write"
        )
    }

    @Test
    fun `z data reads back as the corrected map`() {
        val written = writeCorrectedCopy()
        val readBack = BinParser.parseToList(FileInputStream(tmpBin), listOf(rkwDef)).single().second
        for (r in written.zAxis.indices) for (c in written.zAxis[r].indices) {
            assertEquals(written.zAxis[r][c], readBack.zAxis[r][c], 0.02, "z[$r][$c]")
        }
    }

    @Test
    fun `no byte outside rk_w's own axis and z regions changes`() {
        writeCorrectedCopy()
        val before = BIN_FILE.readBytes()
        val after = tmpBin.readBytes()
        assertEquals(before.size, after.size, "bin size unchanged")

        // Allowed regions: exactly the bytes each axis/z legitimately owns.
        fun region(axis: data.parser.xdf.AxisDefinition?): IntRange? {
            if (axis == null || axis.address == 0) return null
            val stride = axis.sizeBits / 8
            val count = maxOf(axis.indexCount, 1) *
                (if (axis === rkwDef.zAxis) 1 else 1) // axes: indexCount elements
            return axis.address until (axis.address + count * stride)
        }
        val zStride = rkwDef.zAxis.sizeBits / 8
        val zCells = maxOf(rkwDef.zAxis.rowCount, 1) * maxOf(rkwDef.zAxis.columnCount, 1)
        val allowed = listOfNotNull(
            region(rkwDef.xAxis),
            region(rkwDef.yAxis),
            rkwDef.zAxis.address until (rkwDef.zAxis.address + zCells * zStride)
        )

        val illegal = mutableListOf<Int>()
        for (i in before.indices) {
            if (before[i] != after[i] && allowed.none { i in it }) illegal.add(i)
        }
        assertTrue(
            illegal.isEmpty(),
            "write must not touch bytes outside rk_w's own regions; illegally changed: " +
                illegal.take(8).joinToString { "0x${it.toString(16)}" } +
                (if (illegal.size > 8) " (+${illegal.size - 8} more)" else "")
        )
    }

    @Test
    fun `abort between axis and z writes must not corrupt (all-or-nothing)`() {
        // A map with a wrong z row count triggers the dimension guard.
        // The guard must fire BEFORE any byte is written — a mid-sequence abort
        // is exactly what froze the user's bin with a zeroed x axis and z.
        val bad = Map3d(
            originalRkw.xAxis,
            originalRkw.yAxis.copyOfRange(0, originalRkw.yAxis.size - 1),
            originalRkw.zAxis.copyOfRange(0, originalRkw.zAxis.size - 1)
        )
        runCatching { BinWriter.write(tmpBin, rkwDef, bad) }
        assertNotNull(tmpBin)
        assertTrue(
            BIN_FILE.readBytes().contentEquals(tmpBin.readBytes()),
            "a rejected write must leave the bin byte-identical (no partial axis writes)"
        )
    }
}
