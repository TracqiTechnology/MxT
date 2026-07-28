package domain.model.pfi

import kotlin.test.*

/**
 * Tests for [PfiShareCalculator.calculateRpmSweep] (RPM sweep timing table)
 * and [PfiShareCalculator.reverseCalculate] (reverse PFI share calculator).
 */
class PfiRpmSweepTest {

    // ── RPM Sweep ───────────────────────────────────────────────────────

    @Test
    fun `calculateRpmSweep produces correct on-times`() {
        // Flat 50% PFI share at all RPMs for easy hand-calculation
        val curve = PfiShareResult(
            rpmAxis = doubleArrayOf(1000.0, 7000.0),
            pfiSharePercent = doubleArrayOf(50.0, 50.0)
        )
        val rows = PfiShareCalculator.calculateRpmSweep(
            rpmStart = 2000.0,
            rpmEnd = 4000.0,
            rpmStep = 1000.0,
            loadPercent = 100.0,
            pfiShareCurve = curve,
            portKrkte = 0.05,
            directKrkte = 0.04
        )

        assertEquals(3, rows.size, "Should have rows at 2000, 3000, 4000")
        for (row in rows) {
            assertEquals(50.0, row.pfiSharePercent, 1e-9)
            // portOnTime = 100 * 0.50 * 0.05 = 2.5
            assertEquals(2.5, row.portOnTimeMs, 1e-9)
            // directOnTime = 100 * 0.50 * 0.04 = 2.0
            assertEquals(2.0, row.directOnTimeMs, 1e-9)
            // total = 4.5
            assertEquals(4.5, row.totalFuelMs, 1e-9)
        }
    }

    @Test
    fun `explicit pressure context corrects both injector KRKATE values`() {
        val result = PfiShareCalculator.calculateInjectionOnTime(
            rpm = 5000.0,
            loadPercent = 100.0,
            pfiSharePercent = 50.0,
            portKrkte = 0.04,
            directKrkte = 0.03,
            pressureContext = InjectionPressureContext(
                referencePfiDifferentialBar = 4.0,
                operatingPfiRailGaugeBar = 4.0,
                operatingManifoldGaugeBar = 1.0,
                referenceGdiRailBarAbsolute = 240.0,
                operatingGdiRailBarAbsolute = 200.0
            )
        )

        assertEquals(0.04 * kotlin.math.sqrt(4.0 / 3.0), result.effectivePortKrkte, 1e-9)
        assertEquals(0.03 * kotlin.math.sqrt(240.0 / 200.0), result.effectiveDirectKrkte, 1e-9)
        assertEquals(50.0 * result.effectivePortKrkte, result.portOnTimeMs, 1e-9)
        assertEquals(50.0 * result.effectiveDirectKrkte, result.directOnTimeMs, 1e-9)
    }

    @Test
    fun `already compensated mode bypasses pressure scaling`() {
        val context = InjectionPressureContext(
            mode = PressureCompensationMode.ALREADY_COMPENSATED,
            referencePfiDifferentialBar = 4.0,
            operatingPfiRailGaugeBar = 1.0,
            operatingManifoldGaugeBar = 2.0,
            referenceGdiRailBarAbsolute = 240.0,
            operatingGdiRailBarAbsolute = 0.0
        )

        assertEquals(0.04, context.effectivePortKrkte(0.04), 1e-9)
        assertEquals(0.03, context.effectiveDirectKrkte(0.03), 1e-9)
    }

    @Test
    fun `timing APIs reject non-finite values and invalid axes`() {
        val curve = PfiShareCalculator.calculateRpmDependentShare()
        assertFailsWith<IllegalArgumentException> {
            PfiShareCalculator.calculateRpmSweep(
                rpmStart = 1000.0,
                rpmEnd = Double.NaN,
                pfiShareCurve = curve,
                portKrkte = 0.04,
                directKrkte = 0.03
            )
        }
        assertFailsWith<IllegalArgumentException> {
            PfiShareCalculator.calculateInjectionOnTime(
                rpm = 5000.0,
                loadPercent = 100.0,
                pfiSharePercent = Double.NaN,
                portKrkte = 0.04,
                directKrkte = 0.03
            )
        }
        assertFailsWith<IllegalArgumentException> {
            PfiShareCalculator.reverseCalculate(
                targetDiOnTimeMs = 5.0,
                rpmBins = doubleArrayOf(3000.0, 3000.0),
                loadBins = doubleArrayOf(50.0, 100.0),
                portKrkte = 0.04,
                directKrkte = 0.03
            )
        }
    }

    @Test
    fun `DI over limit flagged correctly`() {
        // Very low PFI share → DI does most of the work
        val curve = PfiShareResult(
            rpmAxis = doubleArrayOf(1000.0, 7000.0),
            pfiSharePercent = doubleArrayOf(5.0, 5.0)
        )
        val rows = PfiShareCalculator.calculateRpmSweep(
            loadPercent = 200.0,
            pfiShareCurve = curve,
            portKrkte = 0.05,
            directKrkte = 0.04,
            diMaxOnTimeMs = 6.0
        )

        // At 5% PFI, DI on-time = 200 * 0.95 * 0.04 = 7.6 ms → over 6.0 limit
        for (row in rows) {
            val expectedDi = 200.0 * 0.95 * 0.04
            assertEquals(expectedDi, row.directOnTimeMs, 1e-9)
            assertEquals(InjectorStatus.DI_OVER_LIMIT, row.status,
                "DI on-time %.2f ms should be over limit at RPM %.0f".format(row.directOnTimeMs, row.rpm))
        }
    }

    @Test
    fun `DI near limit flagged correctly`() {
        // DI on-time between 80% and 100% of limit → DI_NEAR_LIMIT
        val curve = PfiShareResult(
            rpmAxis = doubleArrayOf(1000.0, 7000.0),
            pfiSharePercent = doubleArrayOf(10.0, 10.0)
        )
        // DI on-time = 100 * 0.90 * 0.06 = 5.4 ms, which is 90% of 6.0
        val rows = PfiShareCalculator.calculateRpmSweep(
            loadPercent = 100.0,
            pfiShareCurve = curve,
            portKrkte = 0.05,
            directKrkte = 0.06,
            diMaxOnTimeMs = 6.0
        )

        for (row in rows) {
            assertEquals(InjectorStatus.DI_NEAR_LIMIT, row.status,
                "DI on-time %.2f ms should be near-limit at RPM %.0f".format(row.directOnTimeMs, row.rpm))
        }
    }

    @Test
    fun `sweep covers full RPM range`() {
        val curve = PfiShareCalculator.calculateRpmDependentShare()
        val rows = PfiShareCalculator.calculateRpmSweep(
            rpmStart = 1000.0,
            rpmEnd = 7000.0,
            rpmStep = 500.0,
            pfiShareCurve = curve,
            portKrkte = 0.05,
            directKrkte = 0.04
        )

        // 1000, 1500, 2000, ..., 7000 → (7000-1000)/500 + 1 = 13 rows
        assertEquals(13, rows.size)
        assertEquals(1000.0, rows.first().rpm, 1e-9)
        assertEquals(7000.0, rows.last().rpm, 1e-9)
    }

    @Test
    fun `sweep row RPMs are monotonically increasing`() {
        val curve = PfiShareCalculator.calculateRpmDependentShare()
        val rows = PfiShareCalculator.calculateRpmSweep(
            pfiShareCurve = curve,
            portKrkte = 0.05,
            directKrkte = 0.04
        )

        for (i in 1 until rows.size) {
            assertTrue(rows[i].rpm > rows[i - 1].rpm, "RPMs must be ascending")
        }
    }

    @Test
    fun `PFI near limit flagged at high RPM`() {
        // At high RPM the available window shrinks; use a huge PFI share and load
        val curve = PfiShareResult(
            rpmAxis = doubleArrayOf(6000.0, 7000.0),
            pfiSharePercent = doubleArrayOf(90.0, 90.0)
        )
        val rows = PfiShareCalculator.calculateRpmSweep(
            rpmStart = 7000.0,
            rpmEnd = 7000.0,
            rpmStep = 500.0,
            loadPercent = 300.0,
            pfiShareCurve = curve,
            portKrkte = 0.10,
            directKrkte = 0.01,
            diMaxOnTimeMs = 100.0 // effectively unlimited DI
        )

        // portOnTime = 300 * 0.90 * 0.10 = 27.0 ms
        // available window = 120000/7000 ≈ 17.14 ms
        assertEquals(1, rows.size)
        assertEquals(InjectorStatus.PFI_NEAR_LIMIT, rows[0].status)
    }

    // ── Reverse Calculator ──────────────────────────────────────────────

    @Test
    fun `reverseCalculate produces valid PFI shares`() {
        val result = PfiShareCalculator.reverseCalculate(
            targetDiOnTimeMs = 5.0,
            rpmBins = PfiShareCalculator.DEFAULT_2D_RPM_BINS,
            loadBins = PfiShareCalculator.DEFAULT_2D_LOAD_BINS,
            portKrkte = 0.05,
            directKrkte = 0.04
        )

        assertEquals(PfiShareCalculator.DEFAULT_2D_RPM_BINS.size, result.suggestedPfiShare.size)
        assertEquals(PfiShareCalculator.DEFAULT_2D_LOAD_BINS.size, result.suggestedPfiShare[0].size)

        for (r in result.suggestedPfiShare.indices) {
            for (l in result.suggestedPfiShare[r].indices) {
                val share = result.suggestedPfiShare[r][l]
                assertTrue(share in 0.0..100.0,
                    "PFI share $share at [${result.rpmAxis[r]}, ${result.loadAxis[l]}] must be 0–100%")
            }
        }
    }

    @Test
    fun `reverse calc increases PFI when DI over limit`() {
        // At low load, 100% DI is fine → PFI share ≈ 0
        // At high load, DI would exceed target → PFI share > 0
        val result = PfiShareCalculator.reverseCalculate(
            targetDiOnTimeMs = 4.0,
            rpmBins = doubleArrayOf(3000.0),
            loadBins = doubleArrayOf(50.0, 100.0, 200.0),
            portKrkte = 0.05,
            directKrkte = 0.04
        )

        // At load=50: totalFuel = 50 * 0.04 = 2.0 ms → requiredDi = min(4.0, 2.0) = 2.0 → pfiShare = 0%
        assertEquals(0.0, result.suggestedPfiShare[0][0], 1e-9,
            "At 50% load, DI alone is enough → PFI share should be 0")

        // At load=200: totalFuel = 200 * 0.04 = 8.0 ms → requiredDi = min(4.0, 8.0) = 4.0
        //   → pfiShare = 1 - 4.0/8.0 = 0.50 → 50%
        assertEquals(50.0, result.suggestedPfiShare[0][2], 1e-9,
            "At 200% load, PFI share should be 50% to keep DI at 4.0 ms")

        // At load=100: totalFuel = 100 * 0.04 = 4.0 ms → requiredDi = min(4.0, 4.0) = 4.0
        //   → pfiShare = 1 - 4.0/4.0 = 0.0 → 0%
        assertEquals(0.0, result.suggestedPfiShare[0][1], 1e-9,
            "At 100% load, DI just meets target → PFI share should be 0")

        // Higher load → higher PFI share
        assertTrue(result.suggestedPfiShare[0][2] > result.suggestedPfiShare[0][0],
            "PFI share should increase with load when DI is constrained")
    }

    @Test
    fun `reverse calc with zero load returns zeros`() {
        val result = PfiShareCalculator.reverseCalculate(
            targetDiOnTimeMs = 5.0,
            rpmBins = doubleArrayOf(2000.0, 4000.0),
            loadBins = doubleArrayOf(0.0),
            portKrkte = 0.05,
            directKrkte = 0.04
        )

        for (r in result.suggestedPfiShare.indices) {
            assertEquals(0.0, result.suggestedPfiShare[r][0], 1e-9,
                "Zero load should yield zero PFI share")
            assertEquals(InjectorStatus.OK, result.constraintFlags[r][0])
        }
    }

    @Test
    fun `reverse calc constraint flags are correct`() {
        // Set target so high load pushes DI past diMaxOnTimeMs
        val result = PfiShareCalculator.reverseCalculate(
            targetDiOnTimeMs = 5.5, // target is within 80-100% of diMaxOnTimeMs=6.0
            rpmBins = doubleArrayOf(3000.0),
            loadBins = doubleArrayOf(200.0),
            portKrkte = 0.05,
            directKrkte = 0.04,
            diMaxOnTimeMs = 6.0
        )

        // totalFuel = 200 * 0.04 = 8.0, requiredDi = 5.5, pfiShare = 1 - 5.5/8.0 = 0.3125 → 31.25%
        // actualDi = 200 * 0.6875 * 0.04 = 5.5, which is 91.7% of 6.0 → DI_NEAR_LIMIT
        assertEquals(InjectorStatus.DI_NEAR_LIMIT, result.constraintFlags[0][0])
    }

    @Test
    fun `reverse calc grid dimensions match input bins`() {
        val rpmBins = doubleArrayOf(1000.0, 2000.0, 3000.0)
        val loadBins = doubleArrayOf(50.0, 100.0, 150.0, 200.0)
        val result = PfiShareCalculator.reverseCalculate(
            targetDiOnTimeMs = 5.0,
            rpmBins = rpmBins,
            loadBins = loadBins,
            portKrkte = 0.05,
            directKrkte = 0.04
        )

        assertEquals(3, result.rpmAxis.size)
        assertEquals(4, result.loadAxis.size)
        assertEquals(3, result.suggestedPfiShare.size)
        assertEquals(4, result.suggestedPfiShare[0].size)
        assertEquals(3, result.constraintFlags.size)
        assertEquals(4, result.constraintFlags[0].size)
    }
}
