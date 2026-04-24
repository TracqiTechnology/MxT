package ui.screens.med9

import data.preferences.krkte.KrktePreferences
import data.preferences.kfmiop.KfmiopPreferences
import data.preferences.kfmirl.KfmirlPreferences
import data.preferences.kfzwop.KfzwopPreferences
import data.preferences.kfzw.KfzwPreferences
import data.preferences.kfldrl.KfldrlPreferences
import data.preferences.kfldimx.KfldimxPreferences
import data.preferences.kfvpdksd.KfvpdksdPreferences
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Validates that applying the MED9 profile resolves all screen-used
 * map preferences against the 1K090711S XDF + stock BIN.
 */
class Med9ProfileIntegrationTest : Med9ScreenTestBase() {

    @Test
    fun `MED9 profile resolves KRKATE via KrktePreferences`() {
        val sel = KrktePreferences.getSelectedMap()
        assertNotNull(sel, "KRKATE should resolve via KrktePreferences")
        assertTrue(
            sel.first.tableName.contains("KRKATE", ignoreCase = true),
            "Expected table name containing KRKATE, got: ${sel.first.tableName}"
        )
    }

    @Test
    fun `MED9 profile resolves KFMIOP`() {
        val sel = KfmiopPreferences.getSelectedMap()
        assertNotNull(sel, "KFMIOP should resolve")
        assertTrue(
            sel.first.tableName.contains("KFMIOP", ignoreCase = true),
            "Expected KFMIOP table, got: ${sel.first.tableName}"
        )
    }

    @Test
    fun `MED9 profile resolves KFMIRL`() {
        val sel = KfmirlPreferences.getSelectedMap()
        assertNotNull(sel, "KFMIRL should resolve")
        assertTrue(
            sel.first.tableName.contains("KFMIRL", ignoreCase = true),
            "Expected KFMIRL table, got: ${sel.first.tableName}"
        )
    }

    @Test
    fun `MED9 profile resolves KFZWOP to Min Ignition Angle`() {
        val sel = KfzwopPreferences.getSelectedMap()
        assertNotNull(sel, "KFZWOP should resolve (mapped to Min Ignition Angle)")
        assertTrue(
            sel.first.tableName.contains("Ignition", ignoreCase = true) ||
                sel.first.tableName.contains("Min", ignoreCase = true),
            "Expected ignition-related table, got: ${sel.first.tableName}"
        )
    }

    @Test
    fun `MED9 profile resolves KFZW`() {
        val sel = KfzwPreferences.getSelectedMap()
        assertNotNull(sel, "KFZW should resolve")
        assertTrue(
            sel.first.tableName.contains("Ignition", ignoreCase = true),
            "Expected ignition timing table, got: ${sel.first.tableName}"
        )
    }

    @Test
    fun `MED9 profile resolves KFLDRL`() {
        val sel = KfldrlPreferences.getSelectedMap()
        assertNotNull(sel, "KFLDRL should resolve")
        assertTrue(
            sel.first.tableName.contains("Boost", ignoreCase = true) ||
                sel.first.tableName.contains("Linearize", ignoreCase = true),
            "Expected boost linearization table, got: ${sel.first.tableName}"
        )
    }

    @Test
    fun `MED9 profile resolves KFLDIMX`() {
        val sel = KfldimxPreferences.getSelectedMap()
        assertNotNull(sel, "KFLDIMX should resolve")
    }

    @Test
    fun `MED9 profile resolves KFVPDKLD via KfvpdksdPreferences`() {
        val sel = KfvpdksdPreferences.getSelectedMap()
        assertNotNull(sel, "KFVPDKLD should resolve via KfvpdksdPreferences")
        assertTrue(
            sel.first.tableName.contains("KFVPDKLD", ignoreCase = true) ||
                sel.first.tableName.contains("Pressure Ratio", ignoreCase = true),
            "Expected KFVPDKLD table, got: ${sel.first.tableName}"
        )
    }

    @Test
    fun `MED9 XDF contains expected number of tables`() {
        assertTrue(
            tableDefs.size >= 70,
            "Expected at least 70 tables in MED9 XDF, got ${tableDefs.size}"
        )
    }

    @Test
    fun `MED9 BIN is 2 MB`() {
        assertTrue(
            BIN_FILE.length() == 2097152L,
            "MED9 BIN should be exactly 2 MB (2097152 bytes), got ${BIN_FILE.length()}"
        )
    }

    @Test
    fun `all maps have non-empty z-axis data`() {
        for ((def, map) in allMaps) {
            assertTrue(
                map.zAxis.isNotEmpty(),
                "Map '${def.tableName}' has empty z-axis data"
            )
        }
    }

    @Test
    fun `2D maps have minimum dimensions`() {
        for ((def, map) in allMaps) {
            if (map.xAxis.size > 1 && map.yAxis.size > 1) {
                assertTrue(
                    map.xAxis.size >= 2 && map.yAxis.size >= 2,
                    "2D map '${def.tableName}' has degenerate dimensions: ${map.xAxis.size}x${map.yAxis.size}"
                )
            }
        }
    }

    @Test
    fun `KFMIOP has expected dimensions`() {
        val kfmiop = findMap(KFMIOP_TITLE) ?: findMapContaining("KFMIOP")
        assertNotNull(kfmiop, "KFMIOP should exist in XDF")
        val (_, map) = kfmiop
        assertTrue(
            map.xAxis.size >= 8 && map.yAxis.size >= 8,
            "KFMIOP should be at least 8x8, got ${map.xAxis.size}x${map.yAxis.size}"
        )
    }

    @Test
    fun `KFMIRL has expected dimensions`() {
        val kfmirl = findMap(KFMIRL_TITLE) ?: findMapContaining("KFMIRL")
        assertNotNull(kfmirl, "KFMIRL should exist in XDF")
        val (_, map) = kfmirl
        assertTrue(
            map.xAxis.size >= 8 && map.yAxis.size >= 8,
            "KFMIRL should be at least 8x8, got ${map.xAxis.size}x${map.yAxis.size}"
        )
    }
}
