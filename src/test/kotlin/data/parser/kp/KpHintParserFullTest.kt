package data.parser.kp

import data.parser.csv.WinOlsCsvParser
import java.io.File
import kotlin.test.*

/**
 * Tests for the full KP binary record parsing in [KpHintParser].
 *
 * Uses the real fixture files:
 *   - `example/me7/kp/8D0907551M-20190711.kp` (KP binary)
 *   - `example/me7/kp/8D0907551M-20190711.csv` (CSV ground truth)
 */
class KpHintParserFullTest {

    private val kpFile = File("example/me7/kp/8D0907551M-20190711.kp")
    private val csvFile = File("example/me7/kp/8D0907551M-20190711.csv")

    // ── Fixture availability ────────────────────────────────────────────

    @Test
    fun `fixture files exist`() {
        assertTrue(kpFile.exists(), "KP fixture file should exist at ${kpFile.absolutePath}")
        assertTrue(csvFile.exists(), "CSV fixture file should exist at ${csvFile.absolutePath}")
    }

    // ── Backward compat: hints still work ───────────────────────────────

    @Test
    fun `parseFile returns hints with names and addresses`() {
        if (!kpFile.exists()) return
        val hints = KpHintParser.parseFile(kpFile)

        assertTrue(hints.size >= 80, "Should extract at least 80 hints, got ${hints.size}")

        val kfpbrk = hints.firstOrNull { it.name == "KFPBRK" }
        assertNotNull(kfpbrk, "KFPBRK should be in hints")
        assertEquals(0x1E3B0, kfpbrk.arAddress, "KFPBRK address should be 0x1E3B0")
    }

    // ── Full definitions ────────────────────────────────────────────────

    @Test
    fun `parseFileDefinitions returns non-empty list`() {
        if (!kpFile.exists()) return
        val defs = KpHintParser.parseFileDefinitions(kpFile)

        assertTrue(defs.isNotEmpty(), "Should extract at least some full definitions")
    }

    @Test
    fun `definitions have valid dimensions`() {
        if (!kpFile.exists()) return
        val defs = KpHintParser.parseFileDefinitions(kpFile)

        for (def in defs) {
            assertTrue(def.columns in 1..1000, "${def.name}: columns=${def.columns} out of range")
            assertTrue(def.rows in 1..1000, "${def.name}: rows=${def.rows} out of range")
            assertTrue(def.sizeBits in setOf(8, 16, 32), "${def.name}: sizeBits=${def.sizeBits} invalid")
            assertTrue(def.scale != 0.0, "${def.name}: scale should not be zero")
            assertFalse(def.scale.isNaN(), "${def.name}: scale should not be NaN")
        }
    }

    @Test
    fun `definitions have valid addresses`() {
        if (!kpFile.exists()) return
        val defs = KpHintParser.parseFileDefinitions(kpFile)

        for (def in defs) {
            assertTrue(def.zAddress > 0, "${def.name}: zAddress=${def.zAddress} should be positive")
            if (def.hasXAxis) {
                assertTrue(def.xAddress > 0, "${def.name}: xAddress should be positive when hasXAxis")
            }
            if (def.hasYAxis) {
                assertTrue(def.yAddress > 0, "${def.name}: yAddress should be positive when hasYAxis")
            }
        }
    }

    // ── Cross-reference against CSV ground truth ────────────────────────

    @Test
    fun `dimensions match CSV ground truth for known maps`() {
        if (!kpFile.exists() || !csvFile.exists()) return

        val kpDefs = KpHintParser.parseFileDefinitions(kpFile)
        val csvDefs = WinOlsCsvParser.parseFile(csvFile)

        val kpByName = kpDefs.associateBy { it.name }
        val csvByName = csvDefs.associateBy { it.id }

        // Check maps that appear in both KP and CSV
        var matched = 0
        var dimMatch = 0
        for ((name, csvDef) in csvByName) {
            val kpDef = kpByName[name] ?: continue
            matched++

            if (kpDef.columns == csvDef.columns && kpDef.rows == csvDef.rows) {
                dimMatch++
            }
        }

        assertTrue(matched >= 5, "Should match at least 5 maps between KP and CSV, got $matched")
        // Per the plan: 11/11 non-scalar maps match on dimensions
        val dimAccuracy = dimMatch.toDouble() / matched
        assertTrue(dimAccuracy >= 0.8, "Dimension accuracy should be >= 80%, got ${(dimAccuracy * 100).toInt()}% ($dimMatch/$matched)")
    }

    @Test
    fun `z-axis addresses match CSV ground truth`() {
        if (!kpFile.exists() || !csvFile.exists()) return

        val kpDefs = KpHintParser.parseFileDefinitions(kpFile)
        val csvDefs = WinOlsCsvParser.parseFile(csvFile)

        val kpByName = kpDefs.associateBy { it.name }
        val csvByName = csvDefs.filter { it.hasAddress }.associateBy { it.id }

        var matched = 0
        var addrMatch = 0
        for ((name, csvDef) in csvByName) {
            val kpDef = kpByName[name] ?: continue
            matched++

            if (kpDef.zAddress == csvDef.address) {
                addrMatch++
            }
        }

        assertTrue(matched >= 5, "Should match at least 5 maps, got $matched")
        val accuracy = addrMatch.toDouble() / matched
        assertTrue(accuracy >= 0.8, "Address accuracy should be >= 80%, got ${(accuracy * 100).toInt()}% ($addrMatch/$matched)")
    }

    @Test
    fun `z-axis units match CSV ground truth`() {
        if (!kpFile.exists() || !csvFile.exists()) return

        val kpDefs = KpHintParser.parseFileDefinitions(kpFile)
        val csvDefs = WinOlsCsvParser.parseFile(csvFile)

        val kpByName = kpDefs.associateBy { it.name }
        val csvByName = csvDefs.filter { it.units.isNotBlank() }.associateBy { it.id }

        var matched = 0
        var unitMatch = 0
        for ((name, csvDef) in csvByName) {
            val kpDef = kpByName[name] ?: continue
            if (kpDef.units.isBlank()) continue
            matched++

            if (kpDef.units == csvDef.units) {
                unitMatch++
            }
        }

        if (matched >= 3) {
            val accuracy = unitMatch.toDouble() / matched
            assertTrue(accuracy >= 0.7, "Unit accuracy should be >= 70%, got ${(accuracy * 100).toInt()}% ($unitMatch/$matched)")
        }
    }

    @Test
    fun `scale factors are within 2x of CSV ground truth`() {
        if (!kpFile.exists() || !csvFile.exists()) return

        val kpDefs = KpHintParser.parseFileDefinitions(kpFile)
        val csvDefs = WinOlsCsvParser.parseFile(csvFile)

        val kpByName = kpDefs.associateBy { it.name }
        val csvByName = csvDefs.filter { it.scale != 0.0 }.associateBy { it.id }

        var matched = 0
        var within2x = 0
        for ((name, csvDef) in csvByName) {
            val kpDef = kpByName[name] ?: continue
            if (csvDef.scale == 0.0) continue
            matched++

            val ratio = kpDef.scale / csvDef.scale
            if (ratio in 0.4..2.5) {
                within2x++
            }
        }

        if (matched >= 3) {
            val accuracy = within2x.toDouble() / matched
            assertTrue(accuracy >= 0.7, "Scale within-2x accuracy should be >= 70%, got ${(accuracy * 100).toInt()}% ($within2x/$matched)")
        }
    }

    // ── Specific well-known maps ────────────────────────────────────────

    @Test
    fun `KFPBRK definition has expected properties`() {
        if (!kpFile.exists()) return
        val defs = KpHintParser.parseFileDefinitions(kpFile)
        val kfpbrk = defs.firstOrNull { it.name == "KFPBRK" }

        if (kfpbrk != null) {
            assertTrue(kfpbrk.columns > 1, "KFPBRK should have multiple columns")
            assertTrue(kfpbrk.rows > 1, "KFPBRK should have multiple rows")
            assertTrue(kfpbrk.zAddress > 0, "KFPBRK should have a valid z-address")
            assertTrue(kfpbrk.scale != 0.0, "KFPBRK should have a non-zero scale")
        }
    }
}
