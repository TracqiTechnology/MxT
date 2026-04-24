package ui.screens.fueltrim

import data.parser.xdf.AxisDefinition
import data.parser.xdf.TableDefinition
import domain.math.map.Map3d
import domain.model.fueltrim.*
import domain.model.fueltrim.RkwTableMetadata.FuelType
import kotlin.math.abs
import kotlin.test.*

/**
 * Tests for [FuelTrimBulkApply] — the multi-table bulk apply logic.
 *
 * Covers table enumeration, multiplicative correction application,
 * grouping/selection helpers, and edge cases.
 */
class FuelTrimBulkApplyTest {

    // ── test fixtures ───────────────────────────────────────────────

    private fun makeAxisDef(address: Int = 0x1000, indexCount: Int = 3, rowCount: Int = 1, columnCount: Int = 3): AxisDefinition {
        return AxisDefinition(
            id = "0",
            type = 0,
            address = address,
            indexCount = indexCount,
            sizeBits = 16,
            rowCount = rowCount,
            columnCount = columnCount,
            unit = "%",
            equation = "X",
            varId = "X",
            axisValues = emptyList()
        )
    }

    private fun makeTableDef(name: String, desc: String): TableDefinition {
        return TableDefinition(
            tableName = name,
            tableDescription = desc,
            xAxis = makeAxisDef(0x2000, indexCount = 3),
            yAxis = makeAxisDef(0x3000, indexCount = 3),
            zAxis = makeAxisDef(0x4000, indexCount = 9, rowCount = 3, columnCount = 3)
        )
    }

    private fun makeMap3d(
        xAxis: Array<Double> = arrayOf(20.0, 50.0, 80.0),
        yAxis: Array<Double> = arrayOf(1000.0, 3000.0, 5000.0),
        zValues: Double = 1.0
    ): Map3d {
        val z = Array(yAxis.size) { Array(xAxis.size) { zValues } }
        return Map3d(xAxis, yAxis, z)
    }

    private fun makeRkwTablePair(
        name: String,
        desc: String,
        zValues: Double = 1.0
    ): Pair<TableDefinition, Map3d> {
        return makeTableDef(name, desc) to makeMap3d(zValues = zValues)
    }

    private val sampleRkwTables = listOf(
        makeRkwTablePair(
            "Rel fuel mass fac inj type corr HO1 Gasoline 0",
            "InjSys_RelMCorHom1_MAP Gasoline 0 rl_w(%) vs nmot_w(1/min) = --"
        ),
        makeRkwTablePair(
            "Rel fuel mass fac inj type corr HO1 Gasoline 1",
            "InjSys_RelMCorHom1_MAP Gasoline 1 rl_w(%) vs nmot_w(1/min) = --"
        ),
        makeRkwTablePair(
            "Rel fuel mass fac inj type corr HO1 Ethanol 0",
            "InjSys_RelMCorHom1_MAP Ethanol 0 rl_w(%) vs nmot_w(1/min) = --"
        ),
        makeRkwTablePair(
            "Rel fuel mass fac inj type corr HO2 Gasoline 0",
            "InjSys_RelMCorHom2_MAP Gasoline 0 rl_w(%) vs nmot_w(1/min) = --"
        ),
        makeRkwTablePair(
            "Rel fuel mass fac inj type corr HO2",
            "InjSys_RelMCorHom2 rl_w(%) vs nmot_w(1/min) = --"
        ),
        // Non-rk_w table — should be excluded
        makeTableDef("KFMIOP", "Load/fill to optimum torque") to makeMap3d()
    )

    // ── 1. enumerateRkwTables ───────────────────────────────────────

    @Test
    fun `enumerateRkwTables finds all rk_w tables and excludes non-rk_w`() {
        val result = FuelTrimBulkApply.enumerateRkwTables(sampleRkwTables)
        assertEquals(5, result.size, "Should find 5 rk_w tables (KFMIOP excluded)")
        assertTrue(result.none { it.metadata.tableName == "KFMIOP" })
    }

    @Test
    fun `enumerateRkwTables returns empty for no rk_w tables`() {
        val noRkw = listOf(
            makeTableDef("KFMIOP", "Load/fill") to makeMap3d(),
            makeTableDef("KFZWOP", "Ignition timing") to makeMap3d()
        )
        val result = FuelTrimBulkApply.enumerateRkwTables(noRkw)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `enumerateRkwTables preserves table definition and map data`() {
        val result = FuelTrimBulkApply.enumerateRkwTables(sampleRkwTables)
        val first = result.first()
        assertEquals(sampleRkwTables[0].first.tableName, first.tableDefinition.tableName)
        assertEquals(sampleRkwTables[0].second.xAxis.size, first.map.xAxis.size)
    }

    // ── 2. corrections apply multiplicatively ───────────────────────

    @Test
    fun `applyCorrections applies multiplicative correction`() {
        val targetMap = makeMap3d(zValues = 1.0)
        val rpmBins = doubleArrayOf(1000.0, 3000.0, 5000.0)
        val loadBins = doubleArrayOf(20.0, 50.0, 80.0)
        // +5% correction at all cells
        val corrections = Array(3) { DoubleArray(3) { 5.0 } }

        val result = FuelTrimBulkApply.applyCorrections(targetMap, corrections, rpmBins, loadBins)

        for (r in result.zAxis.indices) {
            for (c in result.zAxis[r].indices) {
                assertEquals(1.05, result.zAxis[r][c], 1e-10,
                    "Cell [$r][$c] should be 1.0 * (1 + 5/100) = 1.05")
            }
        }
    }

    @Test
    fun `applyCorrections with negative correction reduces values`() {
        val targetMap = makeMap3d(zValues = 1.0)
        val rpmBins = doubleArrayOf(1000.0, 3000.0, 5000.0)
        val loadBins = doubleArrayOf(20.0, 50.0, 80.0)
        val corrections = Array(3) { DoubleArray(3) { -3.0 } }

        val result = FuelTrimBulkApply.applyCorrections(targetMap, corrections, rpmBins, loadBins)

        for (r in result.zAxis.indices) {
            for (c in result.zAxis[r].indices) {
                assertEquals(0.97, result.zAxis[r][c], 1e-10,
                    "Cell [$r][$c] should be 1.0 * (1 - 3/100) = 0.97")
            }
        }
    }

    @Test
    fun `applyCorrections with zero correction leaves values unchanged`() {
        val targetMap = makeMap3d(
            zValues = 1.234
        )
        val rpmBins = doubleArrayOf(1000.0, 3000.0, 5000.0)
        val loadBins = doubleArrayOf(20.0, 50.0, 80.0)
        val corrections = Array(3) { DoubleArray(3) { 0.0 } }

        val result = FuelTrimBulkApply.applyCorrections(targetMap, corrections, rpmBins, loadBins)

        for (r in result.zAxis.indices) {
            for (c in result.zAxis[r].indices) {
                assertEquals(1.234, result.zAxis[r][c], 1e-10)
            }
        }
    }

    @Test
    fun `applyCorrections does not mutate the original map`() {
        val targetMap = makeMap3d(zValues = 1.0)
        val rpmBins = doubleArrayOf(1000.0, 3000.0, 5000.0)
        val loadBins = doubleArrayOf(20.0, 50.0, 80.0)
        val corrections = Array(3) { DoubleArray(3) { 10.0 } }

        FuelTrimBulkApply.applyCorrections(targetMap, corrections, rpmBins, loadBins)

        for (r in targetMap.zAxis.indices) {
            for (c in targetMap.zAxis[r].indices) {
                assertEquals(1.0, targetMap.zAxis[r][c], 1e-10, "Original map should not be mutated")
            }
        }
    }

    @Test
    fun `applyCorrections maps to nearest bin when axes differ`() {
        // Target has different RPM/load axes than the correction grid
        val targetMap = Map3d(
            arrayOf(25.0, 55.0, 85.0),  // load bins slightly offset
            arrayOf(1100.0, 3100.0, 5100.0),  // RPM bins slightly offset
            Array(3) { Array(3) { 1.0 } }
        )
        val rpmBins = doubleArrayOf(1000.0, 3000.0, 5000.0)
        val loadBins = doubleArrayOf(20.0, 50.0, 80.0)
        // Different correction per bin
        val corrections = Array(3) { r ->
            DoubleArray(3) { l -> (r + 1) * (l + 1).toDouble() }
        }

        val result = FuelTrimBulkApply.applyCorrections(targetMap, corrections, rpmBins, loadBins)

        // 1100 RPM → nearest is 1000 (idx 0), 25% load → nearest is 20% (idx 0)
        // correction = (0+1)*(0+1) = 1.0%
        assertEquals(1.0 * (1.0 + 1.0 / 100.0), result.zAxis[0][0], 1e-10)

        // 3100 RPM → nearest is 3000 (idx 1), 85% load → nearest is 80% (idx 2)
        // correction = (1+1)*(2+1) = 6.0%
        assertEquals(1.0 * (1.0 + 6.0 / 100.0), result.zAxis[1][2], 1e-10)
    }

    @Test
    fun `applyCorrections preserves axes of target map`() {
        val targetXAxis = arrayOf(15.0, 45.0, 75.0)
        val targetYAxis = arrayOf(900.0, 2900.0, 4900.0)
        val targetMap = Map3d(targetXAxis, targetYAxis, Array(3) { Array(3) { 1.0 } })

        val result = FuelTrimBulkApply.applyCorrections(
            targetMap,
            Array(3) { DoubleArray(3) { 5.0 } },
            doubleArrayOf(1000.0, 3000.0, 5000.0),
            doubleArrayOf(20.0, 50.0, 80.0)
        )

        assertContentEquals(targetXAxis, result.xAxis)
        assertContentEquals(targetYAxis, result.yAxis)
    }

    // ── 3. grouping by fuel type ────────────────────────────────────

    @Test
    fun `groupByFuelType separates gasoline and ethanol`() {
        val tables = FuelTrimBulkApply.enumerateRkwTables(sampleRkwTables)
        val grouped = FuelTrimBulkApply.groupByFuelType(tables)

        assertEquals(3, grouped[FuelType.GASOLINE]?.size ?: 0,
            "Should have 3 gasoline tables")
        assertEquals(1, grouped[FuelType.ETHANOL]?.size ?: 0,
            "Should have 1 ethanol table")
    }

    @Test
    fun `selectByFuelType returns correct table names`() {
        val tables = FuelTrimBulkApply.enumerateRkwTables(sampleRkwTables)

        val gasolineNames = FuelTrimBulkApply.selectByFuelType(tables, FuelType.GASOLINE)
        assertTrue(gasolineNames.all { it.contains("Gasoline", ignoreCase = true) || !it.contains("Ethanol", ignoreCase = true) })

        val ethanolNames = FuelTrimBulkApply.selectByFuelType(tables, FuelType.ETHANOL)
        assertEquals(1, ethanolNames.size)
        assertTrue(ethanolNames.first().contains("Ethanol"))
    }

    // ── 4. select-all buttons select correct subset ─────────────────

    @Test
    fun `selectAll returns all table names`() {
        val tables = FuelTrimBulkApply.enumerateRkwTables(sampleRkwTables)
        val all = FuelTrimBulkApply.selectAll(tables)
        assertEquals(tables.size, all.size)
    }

    @Test
    fun `selectByMapSwitch separates MAP switch from native`() {
        val tables = FuelTrimBulkApply.enumerateRkwTables(sampleRkwTables)

        val mapSwitchNames = FuelTrimBulkApply.selectByMapSwitch(tables, isMapSwitch = true)
        val nativeNames = FuelTrimBulkApply.selectByMapSwitch(tables, isMapSwitch = false)

        // All tables should be in one or the other
        assertEquals(tables.size, mapSwitchNames.size + nativeNames.size)
        // No overlap
        assertTrue(mapSwitchNames.intersect(nativeNames).isEmpty())
    }

    @Test
    fun `selectByHoVariant returns correct tables`() {
        val tables = FuelTrimBulkApply.enumerateRkwTables(sampleRkwTables)

        val ho1 = FuelTrimBulkApply.selectByHoVariant(tables, 1)
        val ho2 = FuelTrimBulkApply.selectByHoVariant(tables, 2)

        assertTrue(ho1.isNotEmpty(), "Should have HO1 tables")
        assertTrue(ho2.isNotEmpty(), "Should have HO2 tables")
        assertTrue(ho1.intersect(ho2).isEmpty(), "HO1 and HO2 should not overlap")
    }

    // ── 5. diagnostics panel data flow ──────────────────────────────

    @Test
    fun `FuelTrimCellDiagnostic shows rejection reason for high std_dev`() {
        val cell = FuelTrimCellDiagnostic(
            rpmBin = 3000.0,
            loadBin = 50.0,
            sampleCount = 10,
            meanTrimPercent = 4.5,
            stdDevPercent = 6.2,
            correctionApplied = 0.0,
            rejected = true,
            rejectReason = "std_dev 6.2% > 5.0%"
        )

        assertTrue(cell.rejected)
        assertNotNull(cell.rejectReason)
        assertTrue(cell.rejectReason!!.contains("std_dev"))
        assertEquals(0.0, cell.correctionApplied)
    }

    @Test
    fun `FuelTrimCellDiagnostic shows correction for accepted cell`() {
        val cell = FuelTrimCellDiagnostic(
            rpmBin = 3000.0,
            loadBin = 50.0,
            sampleCount = 15,
            meanTrimPercent = 4.2,
            stdDevPercent = 1.8,
            correctionApplied = 4.2,
            rejected = false,
            rejectReason = null
        )

        assertFalse(cell.rejected)
        assertNull(cell.rejectReason)
        assertEquals(4.2, cell.correctionApplied, 1e-10)
    }

    @Test
    fun `diagnosticResult diagnostics array has correct dimensions`() {
        val rpmBins = doubleArrayOf(1000.0, 3000.0, 5000.0)
        val loadBins = doubleArrayOf(20.0, 50.0)
        val diag = FuelTrimDiagnosticResult(
            corrections = Array(3) { DoubleArray(2) },
            diagnostics = Array(3) { r ->
                Array(2) { l ->
                    FuelTrimCellDiagnostic(rpmBins[r], loadBins[l], 0, 0.0, 0.0, 0.0, true, "no samples")
                }
            },
            rpmBins = rpmBins,
            loadBins = loadBins,
            totalSamplesProcessed = 0,
            samplesFilteredOut = 0,
            binsWithData = 0,
            binsRejected = 0,
            warnings = emptyList()
        )

        assertEquals(3, diag.diagnostics.size)
        assertEquals(2, diag.diagnostics[0].size)
        assertEquals(1000.0, diag.diagnostics[0][0].rpmBin)
        assertEquals(50.0, diag.diagnostics[0][1].loadBin)
    }
}
