package domain.model.fueltrim

import kotlin.test.*

/**
 * Tests for [FuelTrimAnalyzer.isMapSwitchTable] and [FuelTrimAnalyzer.isRkwTable].
 *
 * On MED17 with DS1 (map switches), map-switch rk_w tables overwrite native ones
 * at runtime. These helpers detect whether a table is a map-switch variant so the
 * UI can warn users when they've selected a native table that won't take effect.
 */
class FuelTrimDefaultSelectionTest {

    // ── isMapSwitchTable: positive cases ────────────────────────────

    @Test
    fun `isMapSwitchTable detects _MAP marker in description`() {
        assertTrue(
            FuelTrimAnalyzer.isMapSwitchTable(
                "InjSys_RelMCorHom1_MAP Gasoline 0 rl_w(%) vs nmot_w(1/min) = --"
            )
        )
    }

    @Test
    fun `isMapSwitchTable detects MAP Gasoline pattern`() {
        assertTrue(
            FuelTrimAnalyzer.isMapSwitchTable(
                "InjSys_RelMCorHom1_MAP Gasoline 1 rl_w(%) vs nmot_w(1/min) = --"
            )
        )
    }

    @Test
    fun `isMapSwitchTable detects MAP Ethanol pattern`() {
        assertTrue(
            FuelTrimAnalyzer.isMapSwitchTable(
                "InjSys_RelMCorHom1_MAP Ethanol 0 rl_w(%) vs nmot_w(1/min) = --"
            )
        )
    }

    @Test
    fun `isMapSwitchTable detects _MAP at end of description`() {
        assertTrue(
            FuelTrimAnalyzer.isMapSwitchTable("SomeTable_MAP")
        )
    }

    @Test
    fun `isMapSwitchTable is case insensitive`() {
        assertTrue(
            FuelTrimAnalyzer.isMapSwitchTable(
                "InjSys_RelMCorHom1_map Gasoline 0 rl_w(%) vs nmot_w(1/min) = --"
            )
        )
    }

    @Test
    fun `isMapSwitchTable detects all RKW_SWITCH profile variants`() {
        // All six variants from MED17_162_RS3_TTRS_2_5T.mxtprofile.json
        val descriptions = listOf(
            "InjSys_RelMCorHom1_MAP Gasoline 0 rl_w(%) vs nmot_w(1/min) = --",
            "InjSys_RelMCorHom1_MAP Ethanol 0 rl_w(%) vs nmot_w(1/min) = --",
            "InjSys_RelMCorHom1_MAP Gasoline 1 rl_w(%) vs nmot_w(1/min) = --",
            "InjSys_RelMCorHom1_MAP Ethanol 1 rl_w(%) vs nmot_w(1/min) = --",
            "InjSys_RelMCorHom1_MAP Gasoline 2 rl_w(%) vs nmot_w(1/min) = --",
            "InjSys_RelMCorHom1_MAP Ethanol 2 rl_w(%) vs nmot_w(1/min) = --",
        )
        for (desc in descriptions) {
            assertTrue(
                FuelTrimAnalyzer.isMapSwitchTable(desc),
                "Expected isMapSwitchTable=true for: $desc"
            )
        }
    }

    // ── isMapSwitchTable: negative cases ────────────────────────────

    @Test
    fun `isMapSwitchTable returns false for native rk_w table`() {
        // Native variant (no _MAP marker)
        assertFalse(
            FuelTrimAnalyzer.isMapSwitchTable(
                "InjSys_RelMCorHom1 rl_w(%) vs nmot_w(1/min) = --"
            )
        )
    }

    @Test
    fun `isMapSwitchTable returns false for empty description`() {
        assertFalse(FuelTrimAnalyzer.isMapSwitchTable(""))
    }

    @Test
    fun `isMapSwitchTable returns false for unrelated tables`() {
        assertFalse(FuelTrimAnalyzer.isMapSwitchTable("KFMIOP rl_w(%) vs nmot_w(1/min) = %"))
        assertFalse(FuelTrimAnalyzer.isMapSwitchTable("KFLDRL lde(hPa) vs nmot(1/min) ="))
    }

    @Test
    fun `isMapSwitchTable does not false-positive on MAPPER or MAPPING`() {
        // "MAP" as a substring of another word should not match
        assertFalse(
            FuelTrimAnalyzer.isMapSwitchTable("SomeMapper description without switch")
        )
        assertFalse(
            FuelTrimAnalyzer.isMapSwitchTable("Mapping table for boost")
        )
    }

    // ── isRkwTable ──────────────────────────────────────────────────

    @Test
    fun `isRkwTable detects RelMCor identifier`() {
        assertTrue(
            FuelTrimAnalyzer.isRkwTable(
                "InjSys_RelMCorHom1_MAP Gasoline 0 rl_w(%) vs nmot_w(1/min) = --"
            )
        )
    }

    @Test
    fun `isRkwTable detects native RelMCor identifier`() {
        assertTrue(
            FuelTrimAnalyzer.isRkwTable(
                "InjSys_RelMCorHom1 rl_w(%) vs nmot_w(1/min) = --"
            )
        )
    }

    @Test
    fun `isRkwTable detects rk_w in description`() {
        assertTrue(
            FuelTrimAnalyzer.isRkwTable("Some table rk_w correction")
        )
    }

    @Test
    fun `isRkwTable returns false for unrelated tables`() {
        assertFalse(FuelTrimAnalyzer.isRkwTable("KFMIOP rl_w(%) vs nmot_w(1/min) = %"))
        assertFalse(FuelTrimAnalyzer.isRkwTable("KFLDRL lde(hPa) vs nmot(1/min) ="))
        assertFalse(FuelTrimAnalyzer.isRkwTable(""))
    }

    // ── combined detection: native vs map-switch ────────────────────

    @Test
    fun `native rk_w table is detected as rk_w but not map-switch`() {
        val desc = "InjSys_RelMCorHom1 rl_w(%) vs nmot_w(1/min) = --"
        assertTrue(FuelTrimAnalyzer.isRkwTable(desc))
        assertFalse(FuelTrimAnalyzer.isMapSwitchTable(desc))
    }

    @Test
    fun `map-switch rk_w table is detected as both rk_w and map-switch`() {
        val desc = "InjSys_RelMCorHom1_MAP Gasoline 0 rl_w(%) vs nmot_w(1/min) = --"
        assertTrue(FuelTrimAnalyzer.isRkwTable(desc))
        assertTrue(FuelTrimAnalyzer.isMapSwitchTable(desc))
    }

    @Test
    fun `warning logic - native selected when map-switch exists should trigger`() {
        // Simulate: user selected native, but MAP variants exist in BIN
        val selectedDescription = "InjSys_RelMCorHom1 rl_w(%) vs nmot_w(1/min) = --"
        val binHasMapSwitch = listOf(
            "InjSys_RelMCorHom1_MAP Gasoline 0 rl_w(%) vs nmot_w(1/min) = --",
            "InjSys_RelMCorHom1_MAP Ethanol 0 rl_w(%) vs nmot_w(1/min) = --"
        )

        val isSelectedNative = !FuelTrimAnalyzer.isMapSwitchTable(selectedDescription)
        val mapSwitchAvailable = binHasMapSwitch.any {
            FuelTrimAnalyzer.isRkwTable(it) && FuelTrimAnalyzer.isMapSwitchTable(it)
        }

        assertTrue(isSelectedNative, "Selected table should be native")
        assertTrue(mapSwitchAvailable, "Map-switch variants should be detected")
        // Warning should fire when both conditions are true
        assertTrue(isSelectedNative && mapSwitchAvailable)
    }

    @Test
    fun `warning logic - map-switch selected should not trigger`() {
        val selectedDescription = "InjSys_RelMCorHom1_MAP Gasoline 0 rl_w(%) vs nmot_w(1/min) = --"
        val isSelectedNative = !FuelTrimAnalyzer.isMapSwitchTable(selectedDescription)
        assertFalse(isSelectedNative, "Map-switch table should not trigger native warning")
    }

    @Test
    fun `warning logic - no map-switch in BIN should not trigger`() {
        // Only native tables in the BIN
        val binDescriptions = listOf(
            "InjSys_RelMCorHom1 rl_w(%) vs nmot_w(1/min) = --"
        )
        val mapSwitchAvailable = binDescriptions.any {
            FuelTrimAnalyzer.isRkwTable(it) && FuelTrimAnalyzer.isMapSwitchTable(it)
        }
        assertFalse(mapSwitchAvailable, "No map-switch variants should mean no warning")
    }
}
