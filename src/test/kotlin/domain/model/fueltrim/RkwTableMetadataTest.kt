package domain.model.fueltrim

import domain.model.fueltrim.RkwTableMetadata.FuelType
import kotlin.test.*

/**
 * Tests for [RkwTableMetadata] parsing of rk_w table metadata from XDF definitions.
 *
 * Real table name/description patterns come from MED17 XDFs (404J, etc.) and the
 * MED17_162_RS3_TTRS_2_5T profile.
 */
class RkwTableMetadataTest {

    // ── fuel type extraction ────────────────────────────────────────

    @Test
    fun `parse extracts Gasoline fuel type from table name`() {
        val meta = RkwTableMetadata.parse(
            tableName = "Rel fuel mass fac inj type corr HO1 Gasoline 0",
            tableDescription = "InjSys_RelMCorHom1_MAP Gasoline 0 rl_w(%) vs nmot_w(1/min) = --"
        )
        assertNotNull(meta)
        assertEquals(FuelType.GASOLINE, meta.fuelType)
    }

    @Test
    fun `parse extracts Ethanol fuel type`() {
        val meta = RkwTableMetadata.parse(
            tableName = "Rel fuel mass fac inj type corr HO1 Ethanol 0",
            tableDescription = "InjSys_RelMCorHom1_MAP Ethanol 0 rl_w(%) vs nmot_w(1/min) = --"
        )
        assertNotNull(meta)
        assertEquals(FuelType.ETHANOL, meta.fuelType)
    }

    @Test
    fun `parse extracts fuel type from description when name has no fuel marker`() {
        val meta = RkwTableMetadata.parse(
            tableName = "Rel fuel mass fac inj type corr HO1",
            tableDescription = "InjSys_RelMCorHom1_MAP Gasoline 2 rl_w(%) vs nmot_w(1/min) = --"
        )
        assertNotNull(meta)
        assertEquals(FuelType.GASOLINE, meta.fuelType)
    }

    // ── map switch index extraction ─────────────────────────────────

    @Test
    fun `parse extracts map switch index 0`() {
        val meta = RkwTableMetadata.parse(
            tableName = "Rel fuel mass fac inj type corr HO1 Gasoline 0",
            tableDescription = "InjSys_RelMCorHom1_MAP Gasoline 0 rl_w(%) vs nmot_w(1/min) = --"
        )
        assertNotNull(meta)
        assertEquals(0, meta.mapSwitchIndex)
    }

    @Test
    fun `parse extracts map switch index across full range`() {
        for (idx in 0..8) {
            val meta = RkwTableMetadata.parse(
                tableName = "Rel fuel mass fac inj type corr HO1 Gasoline $idx",
                tableDescription = "InjSys_RelMCorHom1_MAP Gasoline $idx rl_w(%) vs nmot_w(1/min) = --"
            )
            assertNotNull(meta, "Should parse for index $idx")
            assertEquals(idx, meta.mapSwitchIndex, "Map switch index should be $idx")
        }
    }

    @Test
    fun `parse returns -1 for missing map switch index`() {
        val meta = RkwTableMetadata.parse(
            tableName = "Rel fuel mass fac inj type corr HO1",
            tableDescription = "InjSys_RelMCorHom1_MAP rl_w(%) vs nmot_w(1/min) = --"
        )
        assertNotNull(meta)
        assertEquals(-1, meta.mapSwitchIndex)
    }

    // ── HO variant extraction ───────────────────────────────────────

    @Test
    fun `parse extracts HO variant from HO1 in table name`() {
        val meta = RkwTableMetadata.parse(
            tableName = "Rel fuel mass fac inj type corr HO1 Gasoline 0",
            tableDescription = "InjSys_RelMCorHom1_MAP Gasoline 0 rl_w(%) vs nmot_w(1/min) = --"
        )
        assertNotNull(meta)
        assertEquals(1, meta.hoVariant)
    }

    @Test
    fun `parse extracts HO variant from HO2 in table name`() {
        val meta = RkwTableMetadata.parse(
            tableName = "Rel fuel mass fac inj type corr HO2",
            tableDescription = "InjSys_RelMCorHom2_MAP rl_w(%) vs nmot_w(1/min) = --"
        )
        assertNotNull(meta)
        assertEquals(2, meta.hoVariant)
    }

    @Test
    fun `parse extracts HO variant from HO3 in table name`() {
        val meta = RkwTableMetadata.parse(
            tableName = "Rel fuel mass fac inj type corr HO3",
            tableDescription = "InjSys_RelMCorHom3_MAP rl_w(%) vs nmot_w(1/min) = --"
        )
        assertNotNull(meta)
        assertEquals(3, meta.hoVariant)
    }

    @Test
    fun `parse extracts HO variant from Hom pattern in description`() {
        val meta = RkwTableMetadata.parse(
            tableName = "Some rk_w table",
            tableDescription = "InjSys_RelMCorHom2 rl_w(%) vs nmot_w(1/min) = --"
        )
        assertNotNull(meta)
        assertEquals(2, meta.hoVariant)
    }

    @Test
    fun `parse defaults to HO1 when no HO variant present`() {
        val meta = RkwTableMetadata.parse(
            tableName = "Some table",
            tableDescription = "Some rk_w correction table"
        )
        assertNotNull(meta)
        assertEquals(1, meta.hoVariant)
    }

    // ── MAP switch detection ────────────────────────────────────────

    @Test
    fun `parse detects MAP switch from description`() {
        val meta = RkwTableMetadata.parse(
            tableName = "Rel fuel mass fac inj type corr HO1 Gasoline 0",
            tableDescription = "InjSys_RelMCorHom1_MAP Gasoline 0 rl_w(%) vs nmot_w(1/min) = --"
        )
        assertNotNull(meta)
        assertTrue(meta.isMapSwitch)
    }

    @Test
    fun `parse detects non-MAP-switch native table`() {
        val meta = RkwTableMetadata.parse(
            tableName = "Rel fuel mass fac inj type corr HO1",
            tableDescription = "InjSys_RelMCorHom1 rl_w(%) vs nmot_w(1/min) = --"
        )
        assertNotNull(meta)
        assertFalse(meta.isMapSwitch)
    }

    // ── non-rk_w table rejection ────────────────────────────────────

    @Test
    fun `parse returns null for non-rk_w tables`() {
        assertNull(RkwTableMetadata.parse("KFMIOP", "KFMIOP rl_w(%) vs nmot_w(1/min) = %"))
        assertNull(RkwTableMetadata.parse("KFLDRL", "KFLDRL lde(hPa) vs nmot(1/min) ="))
        assertNull(RkwTableMetadata.parse("", ""))
    }

    // ── parseAll ────────────────────────────────────────────────────

    @Test
    fun `parseAll processes multiple tables and filters non-rk_w`() {
        val tables = listOf(
            "Rel fuel mass fac inj type corr HO1 Gasoline 0" to
                "InjSys_RelMCorHom1_MAP Gasoline 0 rl_w(%) vs nmot_w(1/min) = --",
            "KFMIOP" to "KFMIOP rl_w(%) vs nmot_w(1/min) = %",
            "Rel fuel mass fac inj type corr HO1 Ethanol 0" to
                "InjSys_RelMCorHom1_MAP Ethanol 0 rl_w(%) vs nmot_w(1/min) = --",
            "KFLDRL" to "KFLDRL lde(hPa) vs nmot(1/min) ="
        )
        val results = RkwTableMetadata.parseAll(tables)
        assertEquals(2, results.size)
        assertEquals(FuelType.GASOLINE, results[0].fuelType)
        assertEquals(FuelType.ETHANOL, results[1].fuelType)
    }

    // ── display label formatting ────────────────────────────────────

    @Test
    fun `displayLabel formats correctly for MAP switch table`() {
        val meta = RkwTableMetadata.parse(
            tableName = "Rel fuel mass fac inj type corr HO1 Gasoline 0",
            tableDescription = "InjSys_RelMCorHom1_MAP Gasoline 0 rl_w(%) vs nmot_w(1/min) = --"
        )
        assertNotNull(meta)
        assertEquals("Gasoline 0 \u2014 HO1 (MAP switch)", meta.displayLabel)
    }

    @Test
    fun `displayLabel formats correctly for native table without switch index`() {
        val meta = RkwTableMetadata.parse(
            tableName = "Rel fuel mass fac inj type corr HO2",
            tableDescription = "InjSys_RelMCorHom2 rl_w(%) vs nmot_w(1/min) = --"
        )
        assertNotNull(meta)
        assertEquals("Unknown \u2014 HO2", meta.displayLabel)
    }

    @Test
    fun `displayLabel formats correctly for Ethanol MAP switch`() {
        val meta = RkwTableMetadata.parse(
            tableName = "Rel fuel mass fac inj type corr HO1 Ethanol 2",
            tableDescription = "InjSys_RelMCorHom1_MAP Ethanol 2 rl_w(%) vs nmot_w(1/min) = --"
        )
        assertNotNull(meta)
        assertEquals("Ethanol 2 \u2014 HO1 (MAP switch)", meta.displayLabel)
    }

    // ── edge cases ──────────────────────────────────────────────────

    @Test
    fun `parse handles unknown fuel type gracefully`() {
        val meta = RkwTableMetadata.parse(
            tableName = "Rel fuel mass fac inj type corr HO1",
            tableDescription = "InjSys_RelMCorHom1 rl_w(%) vs nmot_w(1/min) = --"
        )
        assertNotNull(meta)
        assertEquals(FuelType.UNKNOWN, meta.fuelType)
        assertEquals(-1, meta.mapSwitchIndex)
    }

    @Test
    fun `parse handles rk_w detected only via description`() {
        val meta = RkwTableMetadata.parse(
            tableName = "Custom correction table",
            tableDescription = "Custom rk_w correction"
        )
        assertNotNull(meta)
        assertEquals(FuelType.UNKNOWN, meta.fuelType)
        assertEquals(-1, meta.mapSwitchIndex)
        assertEquals(1, meta.hoVariant)
    }

    @Test
    fun `isRkwTable detects via tableName RelMCor`() {
        assertTrue(
            RkwTableMetadata.isRkwTable(
                tableName = "RelMCorHom1 table",
                tableDescription = "Some description"
            )
        )
    }

    @Test
    fun `isRkwTable detects via tableName Rel fuel mass fac inj type corr`() {
        assertTrue(
            RkwTableMetadata.isRkwTable(
                tableName = "Rel fuel mass fac inj type corr HO1",
                tableDescription = "Some description"
            )
        )
    }

    @Test
    fun `isRkwTable detects via tableDescription RelMCor`() {
        assertTrue(
            RkwTableMetadata.isRkwTable(
                tableName = "Some table",
                tableDescription = "InjSys_RelMCorHom1_MAP Gasoline 0"
            )
        )
    }

    @Test
    fun `isRkwTable detects via tableDescription rk_w`() {
        assertTrue(
            RkwTableMetadata.isRkwTable(
                tableName = "Some table",
                tableDescription = "Contains rk_w keyword"
            )
        )
    }

    @Test
    fun `isRkwTable returns false for completely unrelated table`() {
        assertFalse(
            RkwTableMetadata.isRkwTable(
                tableName = "KFMIOP",
                tableDescription = "KFMIOP rl_w(%) vs nmot_w(1/min) = %"
            )
        )
    }

    // ── all six profile variants ────────────────────────────────────

    @Test
    fun `parse handles all six RKW_SWITCH profile variants`() {
        val variants = listOf(
            "Rel fuel mass fac inj type corr HO1 Gasoline 0" to
                "InjSys_RelMCorHom1_MAP Gasoline 0 rl_w(%) vs nmot_w(1/min) = --",
            "Rel fuel mass fac inj type corr HO1 Ethanol 0" to
                "InjSys_RelMCorHom1_MAP Ethanol 0 rl_w(%) vs nmot_w(1/min) = --",
            "Rel fuel mass fac inj type corr HO1 Gasoline 1" to
                "InjSys_RelMCorHom1_MAP Gasoline 1 rl_w(%) vs nmot_w(1/min) = --",
            "Rel fuel mass fac inj type corr HO1 Ethanol 1" to
                "InjSys_RelMCorHom1_MAP Ethanol 1 rl_w(%) vs nmot_w(1/min) = --",
            "Rel fuel mass fac inj type corr HO1 Gasoline 2" to
                "InjSys_RelMCorHom1_MAP Gasoline 2 rl_w(%) vs nmot_w(1/min) = --",
            "Rel fuel mass fac inj type corr HO1 Ethanol 2" to
                "InjSys_RelMCorHom1_MAP Ethanol 2 rl_w(%) vs nmot_w(1/min) = --",
        )
        val results = RkwTableMetadata.parseAll(variants)
        assertEquals(6, results.size)

        // Verify fuel types alternate Gasoline/Ethanol
        assertEquals(FuelType.GASOLINE, results[0].fuelType)
        assertEquals(FuelType.ETHANOL, results[1].fuelType)
        assertEquals(FuelType.GASOLINE, results[2].fuelType)
        assertEquals(FuelType.ETHANOL, results[3].fuelType)

        // Verify map switch indices
        assertEquals(0, results[0].mapSwitchIndex)
        assertEquals(0, results[1].mapSwitchIndex)
        assertEquals(1, results[2].mapSwitchIndex)
        assertEquals(1, results[3].mapSwitchIndex)
        assertEquals(2, results[4].mapSwitchIndex)
        assertEquals(2, results[5].mapSwitchIndex)

        // All should be MAP switch
        assertTrue(results.all { it.isMapSwitch })

        // All should be HO1
        assertTrue(results.all { it.hoVariant == 1 })
    }
}
