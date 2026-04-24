package data.parser.damos

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.File

class FktParserTest {

    private val SYNTHETIC_FKT = """
        ;Tabelle der Umrechnungsformeln
        ;
        nmot_q1               1   1.0            0.0          1/min   2
        kw_q0p75              1   0.75           0.0          GradKW  3
        temp_ub_q0p75_o48     1   0.75          64.0          GradC   2
        fak_qn0p01            1  -0.01           0.0          -       6
        fak_q1p0Em8           1   0.00000001     0.0          -       1
        B_TRUE                0   1.0            0.0          -       3
        zk100msxs_ub_b25p5    5  25.6            0.0          s       3
        zk40ms_ub_b10200      7 10240.0         40.0          ms      1
        default               1   1.0            0.0          -       2
    """.trimIndent()

    @Test
    fun `parse synthetic FKT entries`() {
        val entries = FktParser.parse(SYNTHETIC_FKT)
        assertEquals(9, entries.size)
    }

    @Test
    fun `skip comment and blank lines`() {
        val entries = FktParser.parse(SYNTHETIC_FKT)
        assertFalse(entries.any { it.name.startsWith(";") })
    }

    @Test
    fun `nmot_q1 has correct conversion`() {
        val entries = FktParser.parse(SYNTHETIC_FKT)
        val nmot = entries.first { it.name == "nmot_q1" }
        assertEquals(1, nmot.typeCode)
        assertEquals(1.0, nmot.factor, 1e-10)
        assertEquals(0.0, nmot.offset, 1e-10)
        assertEquals("1/min", nmot.unit)
        assertEquals(2, nmot.precision)
    }

    @Test
    fun `kw_q0p75 has correct factor`() {
        val entries = FktParser.parse(SYNTHETIC_FKT)
        val kw = entries.first { it.name == "kw_q0p75" }
        assertEquals(0.75, kw.factor, 1e-10)
        assertEquals("GradKW", kw.unit)
    }

    @Test
    fun `temp with offset has correct values`() {
        val entries = FktParser.parse(SYNTHETIC_FKT)
        val temp = entries.first { it.name == "temp_ub_q0p75_o48" }
        assertEquals(0.75, temp.factor, 1e-10)
        assertEquals(64.0, temp.offset, 1e-10)
        assertEquals("GradC", temp.unit)
    }

    @Test
    fun `negative factor is parsed`() {
        val entries = FktParser.parse(SYNTHETIC_FKT)
        val neg = entries.first { it.name == "fak_qn0p01" }
        assertEquals(-0.01, neg.factor, 1e-10)
    }

    @Test
    fun `very small factor is parsed`() {
        val entries = FktParser.parse(SYNTHETIC_FKT)
        val small = entries.first { it.name == "fak_q1p0Em8" }
        assertEquals(1e-8, small.factor, 1e-18)
    }

    @Test
    fun `verbal type code 0 is parsed`() {
        val entries = FktParser.parse(SYNTHETIC_FKT)
        val verbal = entries.first { it.name == "B_TRUE" }
        assertEquals(0, verbal.typeCode)
    }

    @Test
    fun `counter type code 5 is parsed`() {
        val entries = FktParser.parse(SYNTHETIC_FKT)
        val counter = entries.first { it.name == "zk100msxs_ub_b25p5" }
        assertEquals(5, counter.typeCode)
        assertEquals(25.6, counter.factor, 1e-10)
    }

    @Test
    fun `counter-with-init type code 7 is parsed`() {
        val entries = FktParser.parse(SYNTHETIC_FKT)
        val init = entries.first { it.name == "zk40ms_ub_b10200" }
        assertEquals(7, init.typeCode)
        assertEquals(10240.0, init.factor, 1e-10)
        assertEquals(40.0, init.offset, 1e-10)
    }

    // --- Real file tests ---

    companion object {
        private val FKT_FILE = File("technical/vag/fkt/UMRD915A_41W200.FKT")
    }

    @Test
    fun `parse real MED9 FKT file`() {
        if (!FKT_FILE.exists()) return

        val entries = FktParser.parse(FKT_FILE)

        // Should have hundreds of conversion formulas
        assertTrue(entries.size > 400, "Expected 400+ entries, got ${entries.size}")
    }

    @Test
    fun `real FKT contains engine speed conversion`() {
        if (!FKT_FILE.exists()) return

        val entries = FktParser.parse(FKT_FILE)
        val nmot = entries.first { it.name == "nmot_q1" }
        assertEquals(1.0, nmot.factor, 1e-10)
        assertEquals(0.0, nmot.offset, 1e-10)
        assertEquals("1/min", nmot.unit)
    }

    @Test
    fun `real FKT contains ignition angle conversions`() {
        if (!FKT_FILE.exists()) return

        val entries = FktParser.parse(FKT_FILE)
        val names = entries.map { it.name }.toSet()

        assertTrue("kw_q0p75" in names, "Missing kw_q0p75 (ignition angle 0.75 GradKW)")
        assertTrue("kw_q0p0078" in names, "Missing kw_q0p0078")
    }

    @Test
    fun `real FKT contains pressure conversions`() {
        if (!FKT_FILE.exists()) return

        val entries = FktParser.parse(FKT_FILE)
        val pressureEntries = entries.filter { it.unit.contains("hPa") || it.unit.contains("kPa") }
        assertTrue(pressureEntries.size > 5, "Expected pressure conversions, got ${pressureEntries.size}")
    }

    @Test
    fun `real FKT contains mass flow conversions`() {
        if (!FKT_FILE.exists()) return

        val entries = FktParser.parse(FKT_FILE)
        val massEntries = entries.filter { it.unit == "kg/h" }
        assertTrue(massEntries.size > 5, "Expected kg/h mass flow conversions, got ${massEntries.size}")
    }

    @Test
    fun `real FKT all entries have valid structure`() {
        if (!FKT_FILE.exists()) return

        val entries = FktParser.parse(FKT_FILE)
        for (entry in entries) {
            assertTrue(entry.name.isNotEmpty(), "Entry name should not be empty")
            assertTrue(entry.typeCode in 0..7, "TypeCode ${entry.typeCode} out of range for ${entry.name}")
            assertTrue(entry.precision in 0..6, "Precision ${entry.precision} out of range for ${entry.name}")
            assertTrue(entry.unit.isNotEmpty(), "Unit should not be empty for ${entry.name}")
        }
    }
}
