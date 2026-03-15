package ui.screens.med9

import data.model.EcuPlatform
import data.preferences.platform.EcuPlatformPreference
import ui.navigation.CalibrationTab
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Workflow-level tests for MED9 platform: tab visibility, label overrides,
 * and map dimension contracts.
 */
class Med9WorkflowContractTest : Med9ScreenTestBase() {

    // ── Tab visibility ──────────────────────────────────────────────────

    @Test
    fun `MED9 platform shows expected calibration tabs`() {
        val visibleTabs = CalibrationTab.entries.filter { EcuPlatform.MED9 in it.platforms }
        val expectedNames = setOf(
            "FUELING", "CLOSED_LOOP", "OPEN_LOOP", "PLSOL",
            "KFMIOP", "KFMIRL", "KFZWOP", "KFZW",
            "KFVPDKSD", "WDKUGDN", "LDRPID"
        )
        for (name in expectedNames) {
            assertTrue(
                visibleTabs.any { it.name == name },
                "Tab $name should be visible on MED9"
            )
        }
    }

    @Test
    fun `MED9 platform hides MED17-only tabs`() {
        val visibleTabs = CalibrationTab.entries.filter { EcuPlatform.MED9 in it.platforms }
        assertFalse(
            visibleTabs.any { it.name == "DUAL_INJECTION" },
            "DUAL_INJECTION should not be visible on MED9"
        )
        assertFalse(
            visibleTabs.any { it.name == "FUEL_TRIM" },
            "FUEL_TRIM should not be visible on MED9"
        )
    }

    // ── Label overrides ─────────────────────────────────────────────────

    @Test
    fun `KFVPDKSD tab shows KFVPDKLD label for MED9`() {
        val label = CalibrationTab.KFVPDKSD.labelFor(EcuPlatform.MED9)
        assertEquals("KFVPDKLD", label, "KFVPDKSD should display as KFVPDKLD on MED9")
    }

    @Test
    fun `KFMIOP tab shows KFMIOP label for MED9 (same as ME7)`() {
        val label = CalibrationTab.KFMIOP.labelFor(EcuPlatform.MED9)
        assertEquals("KFMIOP", label, "KFMIOP should keep its default label on MED9")
    }

    @Test
    fun `KFMIRL tab shows KFMIRL label for MED9 (same as ME7)`() {
        val label = CalibrationTab.KFMIRL.labelFor(EcuPlatform.MED9)
        assertEquals("KFMIRL", label, "KFMIRL should keep its default label on MED9")
    }

    @Test
    fun `KFMIOP tab shows KFLMIOP label for MED17`() {
        val label = CalibrationTab.KFMIOP.labelFor(EcuPlatform.MED17)
        assertEquals("KFLMIOP", label, "KFMIOP should display as KFLMIOP on MED17")
    }

    // ── Profile engine parameters ───────────────────────────────────────

    @Test
    fun `MED9 profile has correct engine parameters`() {
        assertEquals("MED9", profile.ecuPlatform, "Profile platform should be MED9")
        val fueling = profile.primaryFueling
        if (fueling != null) {
            assertEquals(4, fueling.numCylinders, "MED9 EA113 has 4 cylinders")
            assertTrue(
                fueling.displacement in 0.49..0.50,
                "Per-cylinder displacement should be ~0.496L, got ${fueling.displacement}"
            )
        }
    }

    // ── Map dimension contracts ─────────────────────────────────────────

    @Test
    fun `KFMIOP values are in valid torque range`() {
        val kfmiop = findMap(KFMIOP_TITLE) ?: findMapContaining("KFMIOP") ?: return
        val (_, map) = kfmiop
        for (row in map.zAxis) {
            for (z in row) {
                assertTrue(
                    z in -50.0..500.0,
                    "KFMIOP value $z outside valid range [-50, 500]%"
                )
            }
        }
    }

    @Test
    fun `KFMIRL values are in valid load range`() {
        val kfmirl = findMap(KFMIRL_TITLE) ?: findMapContaining("KFMIRL") ?: return
        val (_, map) = kfmirl
        for (row in map.zAxis) {
            for (z in row) {
                assertTrue(
                    z in 0.0..500.0,
                    "KFMIRL value $z outside valid range [0, 500]%"
                )
            }
        }
    }

    @Test
    fun `ignition timing maps have physically reasonable values`() {
        val ignitionMaps = allMaps.filter {
            it.first.tableName.contains("Ignition Timing", ignoreCase = true)
        }
        assertTrue(ignitionMaps.isNotEmpty(), "Should have ignition timing maps in XDF")
        for ((def, map) in ignitionMaps) {
            for (row in map.zAxis) {
                for (z in row) {
                    assertTrue(
                        z in -30.0..70.0,
                        "Ignition value $z in '${def.tableName}' outside range [-30, 70] grad KW"
                    )
                }
            }
        }
    }

    @Test
    fun `MED9 has 6 ignition timing maps`() {
        val ignitionMaps = allMaps.filter {
            it.first.tableName.contains("Ignition Timing", ignoreCase = true)
        }
        assertEquals(6, ignitionMaps.size,
            "MED9 should have 6 ignition timing maps (3 KFZW + 3 variants), got ${ignitionMaps.size}")
    }

    @Test
    fun `KFLDHBN values are valid pressure ratios`() {
        val kfldhbn = findMap(KFLDHBN_TITLE) ?: findMapContaining("KFLDHBN") ?: return
        val (_, map) = kfldhbn
        for (row in map.zAxis) {
            for (z in row) {
                assertTrue(
                    z in 0.5..5.0,
                    "KFLDHBN pressure ratio $z outside valid range [0.5, 5.0]"
                )
            }
        }
    }

    @Test
    fun `MED9 XDF has lambda maps`() {
        val lambdaMaps = allMaps.filter {
            it.first.tableName.contains("Lambda", ignoreCase = true)
        }
        assertTrue(
            lambdaMaps.size >= 3,
            "Expected at least 3 lambda maps (LAMFA variants), got ${lambdaMaps.size}"
        )
    }

    @Test
    fun `MED9 XDF has HPFP-related tables`() {
        val hpfpMaps = allMaps.filter {
            it.first.tableName.contains("HPFP", ignoreCase = true) ||
                it.first.tableName.contains("Rail Pressure", ignoreCase = true)
        }
        assertTrue(
            hpfpMaps.isNotEmpty(),
            "MED9 XDF should contain HPFP-related tables"
        )
    }

    @Test
    fun `no map has NaN or Infinite values`() {
        for ((def, map) in allMaps) {
            for (row in map.zAxis) {
                for (z in row) {
                    assertFalse(
                        z.isNaN() || z.isInfinite(),
                        "Map '${def.tableName}' contains NaN or Infinite value"
                    )
                }
            }
        }
    }
}
