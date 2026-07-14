package ui.screens.fueltrim

import data.parser.bin.BinParser
import data.parser.xdf.TableDefinition
import data.parser.xdf.XdfParser
import data.writer.BinWriter
import domain.math.map.Map3d
import domain.model.fueltrim.FuelTrimBulkApply
import domain.model.fueltrim.RkwTableMetadata
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression tests for the z-only fuel-trim write path (Balazs follow-up #2).
 *
 * Fuel-trim application only ever changes z, so the FuelTrim screen writes a map
 * with EMPTY x/y axes — BinWriter skips the axis writes entirely. This guarantees:
 *  1. every selected rk_w table (including the first) is written,
 *  2. axis bytes are never touched — not even to re-write "correct" values, and
 *  3. a bin whose axes were already damaged by pre-2.1.3 writes is not damaged
 *     further (the zeroed axis bytes are left exactly as found, never re-written).
 *
 * Driven by the real 404E XDF + stock bin fixtures.
 */
class FuelTrimZOnlyWriteTest {

    companion object {
        private val XDF_FILE = File("example/med17/404E/404E_normal.xdf")
        private val BIN_FILE = File("example/med17/404E/MED17_1_62_STOCK.bin")
    }

    private lateinit var tableDefs: List<TableDefinition>
    private lateinit var rkwTables: List<domain.model.fueltrim.RkwTableWithData>
    private lateinit var tmpBin: File

    // A correction grid guaranteed non-zero everywhere: +4% across all bins.
    private val rpmBins = doubleArrayOf(1000.0, 3000.0, 5000.0)
    private val loadBins = doubleArrayOf(30.0, 90.0, 150.0)
    private val corrections = Array(3) { DoubleArray(3) { 4.0 } }

    @BeforeTest
    fun setUp() {
        assertTrue(XDF_FILE.exists(), "404E XDF fixture missing")
        assertTrue(BIN_FILE.exists(), "404E stock bin fixture missing")

        val (_, defs) = XdfParser.parseToList(FileInputStream(XDF_FILE))
        tableDefs = defs
        val mapList = BinParser.parseToList(FileInputStream(BIN_FILE), defs)
        rkwTables = FuelTrimBulkApply.enumerateRkwTables(mapList)
        assertTrue(rkwTables.size >= 3, "expected multiple rk_w tables in 404E, got ${rkwTables.size}")

        tmpBin = File.createTempFile("mxt-rkw-zonly", ".bin")
        BIN_FILE.copyTo(tmpBin, overwrite = true)
    }

    @AfterTest
    fun tearDown() {
        tmpBin.delete()
    }

    /** The exact write the FuelTrim screen performs for one table. */
    private fun zOnlyWrite(table: domain.model.fueltrim.RkwTableWithData): Map3d {
        val corrected = FuelTrimBulkApply.applyCorrections(table.map, corrections, rpmBins, loadBins)
        BinWriter.write(tmpBin, table.tableDefinition, Map3d(emptyArray(), emptyArray(), corrected.zAxis))
        return corrected
    }

    private fun axisRegions(def: TableDefinition): List<IntRange> =
        listOfNotNull(def.xAxis, def.yAxis)
            .filter { it.address != 0 }
            .map { it.address until it.address + it.indexCount * (it.sizeBits / 8) }

    @Test
    fun `bulk apply writes every rk_w table including the first`() {
        val expected = rkwTables.map { it to zOnlyWrite(it) }

        for ((table, corrected) in expected) {
            val readBack = BinParser.parseToList(FileInputStream(tmpBin), listOf(table.tableDefinition))
                .single().second
            var changedCells = 0
            for (r in corrected.zAxis.indices) for (c in corrected.zAxis[r].indices) {
                assertEquals(
                    corrected.zAxis[r][c], readBack.zAxis[r][c], 0.02,
                    "${table.metadata.tableName} z[$r][$c]"
                )
                if (corrected.zAxis[r][c] != table.map.zAxis[r][c]) changedCells++
            }
            assertTrue(changedCells > 0, "${table.metadata.tableName}: corrections must actually land")
        }
        // The FIRST enumerated table (the user's 'very first map') is covered by the loop above.
        assertTrue(expected.first().first === rkwTables.first())
    }

    @Test
    fun `axis bytes are byte-identical to stock after all writes`() {
        rkwTables.forEach { zOnlyWrite(it) }

        val before = BIN_FILE.readBytes()
        val after = tmpBin.readBytes()
        for (table in rkwTables) {
            for (region in axisRegions(table.tableDefinition)) {
                for (i in region) {
                    assertEquals(
                        before[i], after[i],
                        "${table.metadata.tableName}: axis byte 0x${i.toString(16)} must be untouched"
                    )
                }
            }
        }
    }

    @Test
    fun `already-damaged axis bytes are left exactly as found (no re-write, no repair)`() {
        // Simulate Balazs's bin: zero the first rk_w table's x-axis region on disk.
        val table = rkwTables.first()
        val xAxis = table.tableDefinition.xAxis!!
        val xRegion = xAxis.address until xAxis.address + xAxis.indexCount * (xAxis.sizeBits / 8)
        RandomAccessFile(tmpBin, "rws").use { raf ->
            raf.seek(xRegion.first.toLong())
            raf.write(ByteArray(xRegion.count()))
        }
        val damagedSnapshot = tmpBin.readBytes()

        // Re-parse from the damaged bin (zeroed x axis), apply trims, z-only write.
        val damagedMap = BinParser.parseToList(FileInputStream(tmpBin), listOf(table.tableDefinition))
            .single().second
        val corrected = FuelTrimBulkApply.applyCorrections(damagedMap, corrections, rpmBins, loadBins)
        BinWriter.write(tmpBin, table.tableDefinition, Map3d(emptyArray(), emptyArray(), corrected.zAxis))

        val after = tmpBin.readBytes()
        for (i in xRegion) {
            assertEquals(
                damagedSnapshot[i], after[i],
                "damaged x-axis byte 0x${i.toString(16)} must not be re-written by a trim write"
            )
        }
        // z still landed
        val readBack = BinParser.parseToList(FileInputStream(tmpBin), listOf(table.tableDefinition))
            .single().second
        assertEquals(corrected.zAxis[0][0], readBack.zAxis[0][0], 0.02, "z write still lands on a damaged bin")
    }

    @Test
    fun `RkwTableMetadata recognizes every real rk_w map and nothing else`() {
        // Real trim maps: rk_w-named, excluding the "… x rl_w(%)"/"… y nmot_w" axis
        // helper tables (description "x axis"/"y axis") whose z data IS axis data.
        val realRkwDefs = tableDefs.filter {
            it.tableName.contains("Rel fuel mass fac inj type corr", ignoreCase = true) &&
                !it.tableDescription.equals("x axis", ignoreCase = true) &&
                !it.tableDescription.equals("y axis", ignoreCase = true)
        }
        assertTrue(realRkwDefs.isNotEmpty(), "404E should contain rk_w tables")
        for (def in realRkwDefs) {
            assertTrue(
                RkwTableMetadata.parse(def.tableName, def.tableDescription) != null,
                "metadata parser must recognize '${def.tableName}' — an unrecognized table silently drops out of bulk apply"
            )
        }
        assertEquals(
            realRkwDefs.size, rkwTables.size,
            "bulk apply must enumerate exactly the real rk_w maps — no silent drops, " +
                "and no false positives (axis helper tables, or maps like KFLBKAPP that " +
                "merely have rk_w as an axis)"
        )
        // The two false-positive classes must be rejected explicitly.
        assertTrue(
            RkwTableMetadata.parse(
                "Characteristic map LBK setpoint for the application",
                "KFLBKAPP nmot(1/min) vs rk_w(%) = --"
            ) == null,
            "a map with rk_w as an AXIS is not a trim table"
        )
        assertTrue(
            RkwTableMetadata.parse("Rel fuel mass fac inj type corr HO1 x rl_w(%)", "x axis") == null,
            "axis helper tables are never trim targets"
        )
    }
}
