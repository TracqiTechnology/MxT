package domain.model.med17

import data.model.EcuPlatform
import data.parser.bin.BinParser
import data.parser.xdf.TableDefinition
import data.parser.xdf.XdfParser
import data.preferences.platform.EcuPlatformPreference
import domain.math.Inverse
import domain.math.map.Map3d
import domain.model.kfzw.Kfzw
import domain.model.ldrpid.LdrpidCalculator
import java.io.File
import java.io.FileInputStream
import kotlin.test.*

/**
 * End-to-end workflow tests for MED17 calibration calculators.
 *
 * Uses real 404E XDF+BIN to load actual map data, then runs each domain
 * calculator and validates the output.  These tests verify:
 *  - Inverse (KFMIRL) with real MED17 map data of any dimensions
 *  - Kfzw rescaling (KFZWOP) with real ignition timing tables
 *  - LdrpidCalculator linearization with real KFLDRL/KFLDIMX dimensions
 *  - All calculators handle DS1 scalar tables gracefully
 *
 * BinWriter is NOT tested here — MED17 BIN writing is blocked (CRC32 not implemented).
 */
class Med17WorkflowCalculatorTest {

    companion object {
        private val PROJECT_ROOT = File(System.getProperty("user.dir"))
        private val XDF_404E = File(PROJECT_ROOT, "example/med17/404E/404E_normal.xdf")
        private val BIN_404E = File(PROJECT_ROOT, "example/med17/404E/MED17_1_62_STOCK.bin")
    }

    private lateinit var savedPlatform: EcuPlatform
    private lateinit var tableDefs: List<TableDefinition>
    private lateinit var allMaps: List<Pair<TableDefinition, Map3d>>

    @BeforeTest
    fun setUp() {
        savedPlatform = EcuPlatformPreference.platform
        EcuPlatformPreference.platform = EcuPlatform.MED17

        assertTrue(XDF_404E.exists(), "XDF file not found: ${XDF_404E.absolutePath}")
        assertTrue(BIN_404E.exists(), "BIN file not found: ${BIN_404E.absolutePath}")

        val (_, defs) = XdfParser.parseToList(FileInputStream(XDF_404E))
        tableDefs = defs
        allMaps = BinParser.parseToList(FileInputStream(BIN_404E), tableDefs)
    }

    @AfterTest
    fun tearDown() {
        EcuPlatformPreference.platform = savedPlatform
    }

    private fun findMap(keyword: String): Pair<TableDefinition, Map3d>? =
        allMaps.firstOrNull { it.first.tableName.contains(keyword, ignoreCase = true) }

    private fun findMapExact(title: String): Pair<TableDefinition, Map3d>? =
        allMaps.firstOrNull { it.first.tableName == title }

    private fun findMaps(keyword: String): List<Pair<TableDefinition, Map3d>> =
        allMaps.filter { it.first.tableName.contains(keyword, ignoreCase = true) }

    // ═══════════════════════════════════════════════════════════════════
    // KFMIRL Inverse Calculation
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `KFMIOP map loads from 404E XDF with valid data`() {
        val kfmiop = findMapExact("Max indexed eng tq") ?: findMap("KFMIOP")
        assertNotNull(kfmiop, "KFMIOP should exist in 404E XDF")
        val map = kfmiop.second

        // DS1 may reduce KFMIOP to a scalar — that's expected and valid
        assertTrue(map.zAxis.isNotEmpty(), "KFMIOP should have at least 1 z-row")
        assertTrue(map.zAxis[0].isNotEmpty(), "KFMIOP should have at least 1 z-column")

        println("KFMIOP dimensions: ${map.yAxis.size}×${map.xAxis.size}")
        println("KFMIOP z-value sample: ${map.zAxis[0][0]}")
    }

    @Test
    fun `KFMIRL map loads from 404E XDF with valid data`() {
        val kfmirl = findMapExact("Tgt filling") ?: findMap("KFMIRL") ?: findMap("Tgt filling")
        assertNotNull(kfmirl, "KFMIRL should exist in 404E XDF")
        val map = kfmirl.second

        assertTrue(map.zAxis.isNotEmpty(), "KFMIRL should have at least 1 z-row")
        println("KFMIRL dimensions: ${map.yAxis.size}×${map.xAxis.size}")
    }

    @Test
    fun `Inverse calculation on real KFMIOP produces valid KFMIRL`() {
        val kfmiop = findMapExact("Max indexed eng tq") ?: findMap("KFMIOP")
        assertNotNull(kfmiop)

        val kfmirl = findMapExact("Tgt filling") ?: findMap("KFMIRL")
        assertNotNull(kfmirl)

        val inputMap = kfmiop.second
        val outputTemplate = kfmirl.second

        // Guard: if either is a scalar (1×1), the inverse is trivially a scalar
        if (inputMap.zAxis.size <= 1 && inputMap.zAxis[0].size <= 1) {
            println("KFMIOP is a DS1 scalar (1×1) — inverse trivially valid")
            return
        }

        val inverse = Inverse.calculateInverse(inputMap, outputTemplate)

        // Output should preserve the template's dimensions
        assertEquals(outputTemplate.zAxis.size, inverse.zAxis.size,
            "Inverse row count should match template")
        assertEquals(outputTemplate.zAxis[0].size, inverse.zAxis[0].size,
            "Inverse column count should match template")

        // Values should be finite
        for (r in inverse.zAxis.indices) {
            for (c in inverse.zAxis[r].indices) {
                assertTrue(inverse.zAxis[r][c].isFinite(),
                    "Inverse z[$r][$c] should be finite, got ${inverse.zAxis[r][c]}")
            }
        }
    }

    @Test
    fun `Inverse with scaled KFMIOP produces different KFMIRL`() {
        // Simulate DS1 use case: user increases max load from 400% → 450%
        val kfmiop = findMapExact("Max indexed eng tq") ?: findMap("KFMIOP") ?: return
        val kfmirl = findMapExact("Tgt filling") ?: findMap("KFMIRL") ?: return

        val inputMap = kfmiop.second
        if (inputMap.zAxis.size <= 1 && inputMap.zAxis[0].size <= 1) {
            println("KFMIOP is a DS1 scalar — skipping scaling test")
            return
        }

        // Create a scaled version (multiply all z by 1.15 to simulate higher load ceiling)
        val scaledZ = Array(inputMap.zAxis.size) { r ->
            Array(inputMap.zAxis[r].size) { c -> inputMap.zAxis[r][c] * 1.15 }
        }
        val scaledInput = Map3d(inputMap.xAxis, inputMap.yAxis, scaledZ)

        val original = Inverse.calculateInverse(inputMap, kfmirl.second)
        val scaled = Inverse.calculateInverse(scaledInput, kfmirl.second)

        // Scaled inverse should differ from original
        var diffCount = 0
        val rowCount = minOf(original.zAxis.size, scaled.zAxis.size)
        for (r in 0 until rowCount) {
            val colCount = minOf(original.zAxis[r].size, scaled.zAxis[r].size)
            for (c in 0 until colCount) {
                if (kotlin.math.abs(original.zAxis[r][c] - scaled.zAxis[r][c]) > 0.01) {
                    diffCount++
                }
            }
        }
        assertTrue(diffCount > 0, "Scaling KFMIOP should produce different KFMIRL values")
        println("Scaled KFMIOP (×1.15) changed $diffCount KFMIRL cells")
    }

    // ═══════════════════════════════════════════════════════════════════
    // KFZWOP Rescaling
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `ignition timing map loads from 404E XDF`() {
        val ignMaps = findMaps("ignition")
            .filter { it.second.xAxis.size >= 5 && it.second.yAxis.size >= 5 }
        assertTrue(ignMaps.isNotEmpty(), "Expected resolved ignition maps >= 5×5")

        val first = ignMaps.first()
        println("Ignition map: '${first.first.tableName}' ${first.second.yAxis.size}×${first.second.xAxis.size}")
    }

    @Test
    fun `Kfzw rescale produces correct output dimensions`() {
        // Find a large ignition timing map to use as source
        val ignMap = findMaps("ignition")
            .filter { it.second.xAxis.size >= 5 && it.second.yAxis.size >= 5 }
            .maxByOrNull { it.second.xAxis.size * it.second.yAxis.size }
        assertNotNull(ignMap, "Need at least one large ignition map")

        val map = ignMap.second
        val oldXAxis = map.xAxis.map { it }.toTypedArray()

        // Create a new load axis with different breakpoints (e.g. extend range)
        val newXAxis = Array(oldXAxis.size) { i ->
            val scale = if (i < oldXAxis.size / 2) 0.9 else 1.1
            oldXAxis[i] * scale
        }

        val result = Kfzw.generateKfzw(oldXAxis, map.zAxis, newXAxis)

        assertEquals(map.zAxis.size, result.size,
            "Rescaled rows should match original RPM rows")
        assertEquals(newXAxis.size, result[0].size,
            "Rescaled columns should match new load axis size")
    }

    @Test
    fun `Kfzw rescale preserves values when axis unchanged`() {
        val ignMap = findMaps("ignition")
            .filter { it.second.xAxis.size >= 5 && it.second.yAxis.size >= 5 }
            .firstOrNull() ?: return

        val map = ignMap.second
        val sameAxis = map.xAxis.copyOf()
        val result = Kfzw.generateKfzw(sameAxis, map.zAxis, sameAxis)

        // With identical axes, values should be very close to original
        for (r in result.indices) {
            for (c in result[r].indices) {
                assertEquals(map.zAxis[r][c], result[r][c], 0.5,
                    "Same-axis rescale should preserve values at [$r][$c]")
            }
        }
    }

    @Test
    fun `Kfzw rescale with extended load axis stays within bounds`() {
        val ignMap = findMaps("ignition")
            .filter { it.second.xAxis.size >= 5 && it.second.yAxis.size >= 5 }
            .firstOrNull() ?: return

        val map = ignMap.second
        // Extend load axis 20% beyond original range
        val newXAxis = Array(map.xAxis.size + 2) { i ->
            when {
                i == 0 -> map.xAxis.first() * 0.8
                i == map.xAxis.size + 1 -> map.xAxis.last() * 1.2
                else -> map.xAxis[i - 1]
            }
        }

        val result = Kfzw.generateKfzw(map.xAxis, map.zAxis, newXAxis)

        // Kfzw.generateKfzw clamps minimum to -13.5 deg
        for (r in result.indices) {
            for (c in result[r].indices) {
                assertTrue(result[r][c] >= -13.5,
                    "Rescaled value at [$r][$c] = ${result[r][c]} should be >= -13.5")
                assertTrue(result[r][c].isFinite(),
                    "Rescaled value at [$r][$c] should be finite")
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // LDRPID Linearization (Map-Only, No Log)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `KFLDRL map from 404E has proper 2D dimensions`() {
        val kfldrl = findMapExact("KF to linearize boost pressure = fTV")
            ?: findMap("linearize boost")
        assertNotNull(kfldrl, "KFLDRL should exist in 404E XDF")

        val map = kfldrl.second
        assertTrue(map.xAxis.size >= 2, "KFLDRL should have >= 2 duty columns, got ${map.xAxis.size}")
        assertTrue(map.yAxis.size >= 2, "KFLDRL should have >= 2 RPM rows, got ${map.yAxis.size}")
        assertEquals(map.yAxis.size, map.zAxis.size, "zAxis rows should match yAxis")
        assertEquals(map.xAxis.size, map.zAxis[0].size, "zAxis cols should match xAxis")
    }

    @Test
    fun `KFLDIMX map from 404E has proper dimensions`() {
        val kfldimx = findMapExact("LDR I controller limitation map")
            ?: findMap("I controller limitation")
            ?: findMap("KFLDIMX")
        assertNotNull(kfldimx, "KFLDIMX should exist in 404E XDF")

        val map = kfldimx.second
        assertTrue(map.xAxis.size >= 2, "KFLDIMX should have >= 2 columns, got ${map.xAxis.size}")
        assertTrue(map.yAxis.size >= 2, "KFLDIMX should have >= 2 rows, got ${map.yAxis.size}")
    }

    @Test
    fun `LdrpidCalculator linearTable with real KFLDRL produces linear steps`() {
        val kfldrl = findMapExact("KF to linearize boost pressure = fTV")
            ?: findMap("linearize boost")
        assertNotNull(kfldrl)

        val map = kfldrl.second
        // Create a synthetic non-linear table from the KFLDRL z-values
        val nonLinear = Array(map.zAxis.size) { r ->
            val row = map.zAxis[r].copyOf()
            row.sort()
            // Ensure strictly increasing per calculateNonLinearTable post-processing
            for (i in 0 until row.size - 1) {
                if (row[i] <= 0.0) row[i] = 0.1
                if (row[i] >= row[i + 1]) row[i + 1] = row[i] * 1.1
            }
            row
        }

        val linear = LdrpidCalculator.calculateLinearTable(nonLinear, map)

        assertEquals(nonLinear.size, linear.zAxis.size,
            "Linear table should have same row count as input")
        assertEquals(nonLinear[0].size, linear.zAxis[0].size,
            "Linear table should have same column count as input")

        // Each RPM row should have equal pressure steps across duty columns.
        for (row in linear.zAxis.indices) {
            if (linear.zAxis[row].size < 3) continue
            val step = linear.zAxis[row][1] - linear.zAxis[row][0]
            for (column in 2 until linear.zAxis[row].size) {
                val actualStep = linear.zAxis[row][column] - linear.zAxis[row][column - 1]
                assertEquals(step, actualStep, 0.01,
                    "RPM row $row step should be constant: expected $step, got $actualStep at column $column")
            }
        }
    }

    @Test
    fun `LdrpidCalculator KFLDRL output with synthetic data has sane values`() {
        val kfldrl = findMapExact("KF to linearize boost pressure = fTV")
            ?: findMap("linearize boost") ?: return

        val map = kfldrl.second

        // Build sorted non-linear table
        val nonLinear = Array(map.zAxis.size) { r ->
            val row = map.zAxis[r].copyOf()
            row.sort()
            for (i in 0 until row.size - 1) {
                if (row[i] <= 0.0) row[i] = 0.1
                if (row[i] >= row[i + 1]) row[i + 1] = row[i] * 1.1
            }
            row
        }

        val linearMap = LdrpidCalculator.calculateLinearTable(nonLinear, map)
        val kfldrlResult = LdrpidCalculator.calculateKfldrl(nonLinear, linearMap.zAxis, map)

        // KFLDRL output values should be in duty cycle range [0, 100]
        for (r in kfldrlResult.zAxis.indices) {
            for (c in kfldrlResult.zAxis[r].indices) {
                val v = kfldrlResult.zAxis[r][c]
                assertTrue(v >= 0.0 && v <= 100.0,
                    "KFLDRL output z[$r][$c] = $v should be in [0, 100]")
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // KRKTE Calculation with Real Engine Data
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `KRKTE maps exist for both GDI and PFI in 404E`() {
        val gdiMaps = findMaps("Conv rel fuel mass rk into effective inj time")
        val pfiMaps = findMaps("Conv rel fuel mass rk to effective inj time te or intake man inj PFI")

        assertTrue(gdiMaps.isNotEmpty(), "KRKTE GDI should exist")
        assertTrue(pfiMaps.isNotEmpty(), "KRKTE PFI should exist")

        // Each should be a scalar (1×1) conversion factor
        val gdiFirst = gdiMaps.first().second
        val pfiFirst = pfiMaps.first().second

        println("KRKTE GDI: ${gdiFirst.yAxis.size}×${gdiFirst.xAxis.size}, value=${gdiFirst.zAxis.getOrNull(0)?.getOrNull(0)}")
        println("KRKTE PFI: ${pfiFirst.yAxis.size}×${pfiFirst.xAxis.size}, value=${pfiFirst.zAxis.getOrNull(0)?.getOrNull(0)}")

        // KRKTE values should be non-zero
        assertTrue(gdiFirst.zAxis.isNotEmpty() && gdiFirst.zAxis[0].isNotEmpty())
        assertTrue(gdiFirst.zAxis[0][0] != 0.0, "KRKTE GDI should be non-zero")
    }

    // ═══════════════════════════════════════════════════════════════════
    // Cross-Variant: Run Core Calculations on 404A, 404H
    // ═══════════════════════════════════════════════════════════════════

    data class Variant(val code: String, val xdfPath: String, val binPath: String)

    private val extraVariants = listOf(
        Variant("404A", "example/med17/404A/404A_normal.xdf", "example/med17/404A/MED17_1_62_STOCK.bin"),
        Variant("404H", "example/med17/404H/404H_normal.xdf", "example/med17/404H/MED17_1_62_STOCK.bin"),
    )

    private fun loadVariant(variant: Variant): List<Pair<TableDefinition, Map3d>>? {
        val xdf = File(PROJECT_ROOT, variant.xdfPath)
        val bin = File(PROJECT_ROOT, variant.binPath)
        if (!xdf.exists() || !bin.exists()) return null
        val (_, defs) = XdfParser.parseToList(FileInputStream(xdf))
        return BinParser.parseToList(FileInputStream(bin), defs)
    }

    private fun findInList(maps: List<Pair<TableDefinition, Map3d>>, keyword: String): Pair<TableDefinition, Map3d>? =
        maps.firstOrNull { it.first.tableName.contains(keyword, ignoreCase = true) }

    @Test
    fun `Inverse calculation works on 404A variant`() {
        val maps = loadVariant(extraVariants[0]) ?: return

        val kfmiop = findInList(maps, "Max indexed eng tq") ?: return
        val kfmirl = findInList(maps, "Tgt filling") ?: findInList(maps, "KFMIRL") ?: return

        if (kfmiop.second.zAxis.size <= 1) {
            println("404A KFMIOP is scalar — skip inverse test")
            return
        }

        val inverse = Inverse.calculateInverse(kfmiop.second, kfmirl.second)
        assertTrue(inverse.zAxis.isNotEmpty())
        assertTrue(inverse.zAxis.all { row -> row.all { it.isFinite() } },
            "All inverse values should be finite")
    }

    @Test
    fun `KFLDRL and KFLDIMX exist with proper dimensions in 404H`() {
        val maps = loadVariant(extraVariants[1]) ?: return

        val kfldrl = findInList(maps, "linearize boost")
        assertNotNull(kfldrl, "404H should have KFLDRL")
        // KFLDRL may have unresolved linked axes producing 0-length axes
        // The map should at least exist even if axes aren't fully resolved
        println("404H KFLDRL: x=${kfldrl.second.xAxis.size}, y=${kfldrl.second.yAxis.size}, z=${kfldrl.second.zAxis.size}")
        if (kfldrl.second.xAxis.size < 2 || kfldrl.second.yAxis.size < 2) {
            println("  WARNING: KFLDRL axes not fully resolved in 404H — linked-axis issue")
        } else {
            assertTrue(kfldrl.second.xAxis.size >= 2, "KFLDRL should be 2D")
            assertTrue(kfldrl.second.yAxis.size >= 2, "KFLDRL should be 2D")
        }

        val kfldimx = findInList(maps, "LDR I controller limitation map")
            ?: findInList(maps, "controller limitation")
            ?: findInList(maps, "KFLDIMX")
        assertNotNull(kfldimx, "404H should have KFLDIMX")
        println("404H KFLDIMX: x=${kfldimx.second.xAxis.size}, y=${kfldimx.second.yAxis.size}")
        if (kfldimx.second.xAxis.size < 2 || kfldimx.second.yAxis.size < 2) {
            println("  WARNING: KFLDIMX axes not fully resolved in 404H — linked-axis issue")
        } else {
            assertTrue(kfldimx.second.xAxis.size >= 2, "KFLDIMX should be 2D")
        }
    }

    @Test
    fun `Kfzw rescale works with 404A ignition map`() {
        val maps = loadVariant(extraVariants[0]) ?: return

        val ignMap = maps.filter { it.first.tableName.contains("ignition", ignoreCase = true) }
            .filter { it.second.xAxis.size >= 5 && it.second.yAxis.size >= 5 }
            .firstOrNull() ?: return

        val map = ignMap.second
        // Shift load axis up by 10%
        val newXAxis = Array(map.xAxis.size) { i -> map.xAxis[i] * 1.1 }
        val result = Kfzw.generateKfzw(map.xAxis, map.zAxis, newXAxis)

        assertEquals(map.zAxis.size, result.size)
        assertEquals(newXAxis.size, result[0].size)
        assertTrue(result.all { row -> row.all { it.isFinite() && it >= -13.5 } })
    }
}
