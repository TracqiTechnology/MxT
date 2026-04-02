package ui.components

import data.model.EcuPlatform
import data.parser.xdf.AxisDefinition
import data.parser.xdf.TableDefinition
import ui.navigation.CalibrationTab
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for the MapPickerDialog filter logic.
 *
 * Bug 2 regression: the filter must search both tableName AND tableDescription.
 * Without the tableDescription search, users cannot find maps by their
 * calibration code (e.g., searching "KFMIOP" to find "Opt eng tq").
 */
class MapPickerDialogFilterTest {

    /** Replicates the exact filter lambda from MapPickerDialog.kt:61-67. */
    private fun filterDefinitions(filterText: String, defs: List<TableDefinition>): List<TableDefinition> {
        if (filterText.isBlank()) return defs
        val filter = filterText.lowercase()
        return defs.filter {
            it.tableName.lowercase().contains(filter) ||
                it.tableDescription.lowercase().contains(filter)
        }
    }

    private val dummyAxis = AxisDefinition(
        id = "z", type = 0, address = 0, indexCount = 1,
        sizeBits = 16, rowCount = 1, columnCount = 1,
        unit = "", equation = "X", varId = "",
        axisValues = emptyList()
    )

    private val testDefinitions = listOf(
        TableDefinition(
            tableName = "Opt eng tq",
            tableDescription = "KFMIOP - Optimum engine torque",
            xAxis = dummyAxis, yAxis = dummyAxis, zAxis = dummyAxis
        ),
        TableDefinition(
            tableName = "Tgt filling",
            tableDescription = "KFMIRL - Target filling from torque",
            xAxis = dummyAxis, yAxis = dummyAxis, zAxis = dummyAxis
        ),
        TableDefinition(
            tableName = "Ignition GDI ex cam control/std valve lift (MAIN) Gasoline 0",
            tableDescription = "KFZW - Ignition timing",
            xAxis = dummyAxis, yAxis = dummyAxis, zAxis = dummyAxis
        ),
        TableDefinition(
            tableName = "KF to linearize boost pressure = fTV",
            tableDescription = "KFLDRL - Boost linearization",
            xAxis = dummyAxis, yAxis = dummyAxis, zAxis = dummyAxis
        ),
        TableDefinition(
            tableName = "Conv rel fuel mass rk to effective inj time te or intake man inj PFI",
            tableDescription = "KRKTE - PFI injection time conversion",
            xAxis = dummyAxis, yAxis = dummyAxis, zAxis = dummyAxis
        ),
    )

    @Test
    fun `filter by exact tableName matches`() {
        val results = filterDefinitions("Opt eng tq", testDefinitions)
        assertEquals(1, results.size)
        assertEquals("Opt eng tq", results[0].tableName)
    }

    @Test
    fun `filter by tableDescription matches calibration code`() {
        val results = filterDefinitions("KFMIOP", testDefinitions)
        assertEquals(1, results.size)
        assertEquals("Opt eng tq", results[0].tableName)
    }

    @Test
    fun `filter is case insensitive`() {
        val results = filterDefinitions("kfmiop", testDefinitions)
        assertEquals(1, results.size)
        assertEquals("Opt eng tq", results[0].tableName)
    }

    @Test
    fun `empty filter returns all definitions`() {
        val results = filterDefinitions("", testDefinitions)
        assertEquals(testDefinitions.size, results.size)
    }

    @Test
    fun `blank filter returns all definitions`() {
        val results = filterDefinitions("   ", testDefinitions)
        assertEquals(testDefinitions.size, results.size)
    }

    @Test
    fun `non-matching filter returns empty list`() {
        val results = filterDefinitions("NONEXISTENT_MAP_XYZ", testDefinitions)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `partial tableName match works`() {
        val results = filterDefinitions("linearize boost", testDefinitions)
        assertEquals(1, results.size)
        assertEquals("KF to linearize boost pressure = fTV", results[0].tableName)
    }

    @Test
    fun `filter matches across both tableName AND tableDescription`() {
        // "KRKTE" is in the description, "PFI" is in the tableName
        val byDescription = filterDefinitions("KRKTE", testDefinitions)
        assertEquals(1, byDescription.size, "Should find by description")

        val byName = filterDefinitions("PFI", testDefinitions)
        assertEquals(1, byName.size, "Should find by name")
        assertEquals(byDescription[0].tableName, byName[0].tableName)
    }

    /**
     * Regression: MED17 uses "KFLMIOP" as display label but XDF descriptions
     * use "KFMIOP" (without L). Callers must pass the base calibration name
     * as initialFilter, not the platform-specific display label.
     *
     * Verify that the base names DO match (validating the fix approach).
     */
    @Test
    fun `base name KFMIOP finds map even when display label is KFLMIOP`() {
        // "KFLMIOP" does NOT match — this is the bug scenario
        val withPlatformLabel = filterDefinitions("KFLMIOP", testDefinitions)
        assertTrue(withPlatformLabel.isEmpty(), "KFLMIOP should not substring-match KFMIOP")

        // The fix: callers pass the base CalibrationTab.label ("KFMIOP") instead
        val withBaseLabel = filterDefinitions("KFMIOP", testDefinitions)
        assertEquals(1, withBaseLabel.size, "Base name KFMIOP must find Opt eng tq")
        assertEquals("Opt eng tq", withBaseLabel[0].tableName)
    }

    @Test
    fun `base name KFMIRL finds map even when display label is KFLMIRL`() {
        val withPlatformLabel = filterDefinitions("KFLMIRL", testDefinitions)
        assertTrue(withPlatformLabel.isEmpty(), "KFLMIRL should not substring-match KFMIRL")

        val withBaseLabel = filterDefinitions("KFMIRL", testDefinitions)
        assertEquals(1, withBaseLabel.size, "Base name KFMIRL must find Tgt filling")
        assertEquals("Tgt filling", withBaseLabel[0].tableName)
    }

    /**
     * Verify that CalibrationTab.label gives the base (ME7) name that XDFs use,
     * NOT the platform-specific display label.
     */
    @Test
    fun `CalibrationTab base labels match XDF calibration codes`() {
        // These are the base labels that should be used as initialFilter
        assertEquals("KFMIOP", CalibrationTab.KFMIOP.label)
        assertEquals("KFMIRL", CalibrationTab.KFMIRL.label)
        assertEquals("KFZWOP", CalibrationTab.KFZWOP.label)
        assertEquals("KFVPDKSD/E", CalibrationTab.KFVPDKSD.label)
        assertEquals("WDKUGDN", CalibrationTab.WDKUGDN.label)

        // Platform overrides are different (this is what was being passed as filter before the fix)
        assertEquals("KFLMIOP", CalibrationTab.KFMIOP.labelFor(EcuPlatform.MED17))
        assertEquals("KFLMIRL", CalibrationTab.KFMIRL.labelFor(EcuPlatform.MED17))
    }
}
