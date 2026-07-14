package data.parser.a2l

import data.generator.A2lToXdfGenerator
import data.parser.bin.BinParser
import data.parser.xdf.XdfParser
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CI coverage for the A2L → XDF pipeline, driven by a small REAL MED9 A2L slice
 * (`src/test/resources/a2l/med9_sample.a2l`): KFMIOP (MAP 16×11), BSGTYP (VALUE),
 * ABKKATTAB (CURVE) with their real RECORD_LAYOUT / COMPU_METHOD closure.
 *
 * Exercises the exact paths PR #95 shipped untested: CHARACTERISTIC/AXIS_DESCR
 * parsing, record-layout offset math, MED9 big-endian, and XDF round-trip.
 */
class A2lToXdfTest {

    private fun sample(): String =
        javaClass.getResource("/a2l/med9_sample.a2l")!!.readText(Charsets.ISO_8859_1)

    private fun defs() = A2lParser.parse(sample()).toTableDefinitions()

    @Test
    fun `parses the real MED9 slice`() {
        val r = A2lParser.parse(sample())
        val names = r.characteristics.map { it.name }
        assertTrue(names.containsAll(listOf("KFMIOP", "BSGTYP", "ABKKATTAB")), "expected maps present, got $names")
        assertTrue(r.compuMethods.isNotEmpty(), "compu methods parsed")
        assertTrue(r.recordLayouts.isNotEmpty(), "record layouts parsed")
    }

    @Test
    fun `KFMIOP resolves as a 16x11 MED9 map at the right offset`() {
        val kfmiop = defs().firstOrNull { it.tableName == "KFMIOP" }
        assertNotNull(kfmiop, "KFMIOP table produced")
        assertNotNull(kfmiop.xAxis, "KFMIOP has an x-axis")
        assertNotNull(kfmiop.yAxis, "KFMIOP has a y-axis")
        assertEquals(16, kfmiop.xAxis!!.indexCount, "x = 16 RPM breakpoints")
        assertEquals(11, kfmiop.yAxis!!.indexCount, "y = 11 load breakpoints")
        // z is 11 rows (load) x 16 cols (rpm)
        assertEquals(11, kfmiop.zAxis.rowCount)
        assertEquals(16, kfmiop.zAxis.columnCount)
        // MED9 is big-endian
        assertTrue(!kfmiop.zAxis.lsbFirst, "MED9 z-data is big-endian")
        // address is a real file offset inside the 2 MB flash
        assertTrue(kfmiop.zAxis.address in 0x1C0000..0x1FFFFF, "z addr in MED9 data region: 0x${kfmiop.zAxis.address.toString(16)}")
    }

    @Test
    fun `BSGTYP is a scalar and ABKKATTAB is a curve`() {
        val d = defs()
        val bsg = d.first { it.tableName == "BSGTYP" }
        assertNull(bsg.xAxis); assertNull(bsg.yAxis)

        val curve = d.first { it.tableName == "ABKKATTAB" }
        assertNotNull(curve.xAxis)
        assertNull(curve.yAxis)
        assertEquals(6, curve.xAxis!!.indexCount, "curve x = 6 breakpoints")
    }

    @Test
    fun `generated XDF round-trips through the project XdfParser`() {
        val defs = defs()
        val xdf = A2lToXdfGenerator.generate(defs)
        assertTrue(xdf.contains("<XDFFORMAT"), "valid XDF envelope")
        assertTrue(xdf.contains("""lsbfirst="0""""), "MED9 default big-endian")
        val (_, reparsed) = XdfParser.parseToList(ByteArrayInputStream(xdf.toByteArray()))
        assertEquals(defs.size, reparsed.size, "every A2L table survives the XDF round-trip")
        assertTrue(reparsed.any { it.tableName == "KFMIOP" }, "KFMIOP present after round-trip")
    }

    @Test
    fun `KFMIOP read from the stock MED9 bin yields sane load percentages`() {
        val bin = File("example/med9/MED9_STOCK.bin")
        if (!bin.exists()) return
        val kfmiop = defs().first { it.tableName == "KFMIOP" }
        val map = BinParser.parseToList(FileInputStream(bin), listOf(kfmiop)).single().second
        val flat = map.zAxis.flatMap { it.toList() }
        assertEquals(11, map.zAxis.size)
        assertEquals(16, map.zAxis.first().size)
        assertTrue(flat.isNotEmpty() && flat.all { it.isFinite() }, "finite values")
        assertTrue(flat.all { it in -10.0..200.0 }, "KFMIOP is a load/torque % map — sane range, got ${flat.min()}..${flat.max()}")
        assertTrue(flat.toSet().size > 1, "real, non-constant calibration data")
    }

    @Test
    fun `comment stripping tolerates a commented-out block terminator`() {
        val withComments = """
            /begin COMPU_METHOD CM "x" RAT_FUNC "%1" "%" COEFFS 0 1 0 0 0 1 /end COMPU_METHOD
            // /end CHARACTERISTIC   <- commented-out terminator must NOT end the block early
            /* block comment with /begin CHARACTERISTIC decoy */
            /begin CHARACTERISTIC REAL "d" VALUE 0x1D0000 KwUb 0 CM 0 100 /end CHARACTERISTIC
        """.trimIndent()
        val r = A2lParser.parse(withComments)
        assertEquals(1, r.characteristics.size, "decoys in comments ignored; the real one parses")
        assertEquals("REAL", r.characteristics.first().name)
    }
}
