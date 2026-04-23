package domain.model.ldrpid

import domain.math.map.Map3d
import domain.model.optimizer.OptimizerCalculator.WotLogEntry
import domain.model.simulator.PidSimulator
import kotlin.math.abs
import kotlin.test.*

/**
 * Tests for PID analysis integration with LDRPID screen and the
 * [LdrpidOptimizerBridge] cross-screen data transfer.
 */
class LdrpidPidAnalysisTest {

    // ── Helpers ─────────────────────────────────────────────────────

    private fun entry(
        rpm: Double = 3000.0,
        requestedMap: Double = 2200.0,
        actualMap: Double = 2200.0,
        barometricPressure: Double = 1013.0,
        wgdc: Double = 60.0
    ) = WotLogEntry(
        rpm = rpm,
        requestedLoad = 191.0,
        actualLoad = 191.0,
        requestedMap = requestedMap,
        actualMap = actualMap,
        barometricPressure = barometricPressure,
        wgdc = wgdc,
        throttleAngle = 100.0
    )

    private fun rampPull(
        rpm: Double = 3000.0,
        targetBoostMbar: Double = 2200.0,
        barometricPressure: Double = 1013.0,
        count: Int = 60
    ): List<WotLogEntry> {
        return (0 until count).map { i ->
            val rampFraction = (i.toDouble() / (count / 2)).coerceAtMost(1.0)
            val actualMap = barometricPressure + (targetBoostMbar - barometricPressure) * rampFraction
            entry(rpm = rpm, requestedMap = targetBoostMbar, actualMap = actualMap, barometricPressure = barometricPressure)
        }
    }

    private fun buildKfldrlMap(): Map3d {
        val xAxis = arrayOf(20.0, 40.0, 60.0, 80.0, 95.0)
        val yAxis = arrayOf(2000.0, 3000.0, 4000.0, 5000.0)
        // Reasonable linearization: output ≈ input (linear identity)
        val z = Array(yAxis.size) { Array(xAxis.size) { col -> xAxis[col] } }
        return Map3d(xAxis, yAxis, z)
    }

    private fun buildKfldimxMap(): Map3d {
        val xAxis = arrayOf(200.0, 400.0, 600.0, 800.0, 1000.0, 1200.0)
        val yAxis = arrayOf(2000.0, 3000.0, 4000.0, 5000.0)
        // Set I-limiter to 80% across the board
        val z = Array(yAxis.size) { Array(xAxis.size) { 80.0 } }
        return Map3d(xAxis, yAxis, z)
    }

    // ── 1. PidSimulator runs with KFLDRL input ──────────────────────

    @Test
    fun `PidSimulator runs with KFLDRL input without crashing`() {
        val pull = rampPull(rpm = 3000.0)
        val kfldrl = buildKfldrlMap()
        val kfldimx = buildKfldimxMap()

        val result = PidSimulator.simulate(
            pullEntries = pull,
            kfldrq0 = null,
            kfldrq1 = null,
            kfldrq2 = null,
            kfldrl = kfldrl,
            kfldimx = kfldimx
        )

        assertTrue(result.states.isNotEmpty(), "Should produce PID states")
        assertEquals(pull.size, result.states.size, "One state per log entry")
        assertNotNull(result.diagnosis, "Should produce diagnosis")
    }

    // ── 2. PID analysis detects stable gains ────────────────────────

    @Test
    fun `PID analysis detects stable gains with on-target boost`() {
        // Boost is already at target — small error from plsol = requestedMap/1.016
        val count = 40
        val requestedMap = 2200.0
        val plsol = requestedMap / 1.016  // PID computes plsol from requestedMap
        val pull = (0 until count).map {
            entry(rpm = 3000.0, requestedMap = requestedMap, actualMap = plsol)
        }

        val result = PidSimulator.simulate(
            pullEntries = pull,
            kfldrq0 = null,
            kfldrq1 = null,
            kfldrq2 = null,
            kfldrl = buildKfldrlMap(),
            kfldimx = buildKfldimxMap()
        )

        val d = result.diagnosis
        assertFalse(d.oscillationDetected, "No oscillation expected with zero error")
        assertFalse(d.overshootDetected, "No overshoot expected with zero error")
        assertTrue(d.avgAbsLde < 1.0, "Average error should be near zero, was ${d.avgAbsLde}")
    }

    @Test
    fun `PID analysis reports convergence for ramp pull`() {
        val pull = rampPull(rpm = 4000.0, count = 60)
        val result = PidSimulator.simulate(
            pullEntries = pull,
            kfldrq0 = null,
            kfldrq1 = null,
            kfldrq2 = null,
            kfldrl = buildKfldrlMap(),
            kfldimx = buildKfldimxMap()
        )

        // Convergence should happen — the ramp reaches target at count/2
        assertTrue(result.diagnosis.convergenceTimeMs >= 0, "Should report convergence time")
    }

    // ── 3. Bridge export and import round-trips ─────────────────────

    @Test
    fun `bridge export and import round-trips KFLDRL`() {
        LdrpidOptimizerBridge.clear()

        val kfldrl = buildKfldrlMap()
        LdrpidOptimizerBridge.exportKfldrl(kfldrl)

        val imported = LdrpidOptimizerBridge.importKfldrl()
        assertNotNull(imported, "Should return exported map")
        assertEquals(kfldrl, imported, "Imported map should equal exported map")
    }

    @Test
    fun `bridge export and import round-trips KFLDIMX`() {
        LdrpidOptimizerBridge.clear()

        val kfldimx = buildKfldimxMap()
        LdrpidOptimizerBridge.exportKfldimx(kfldimx)

        val imported = LdrpidOptimizerBridge.importKfldimx()
        assertNotNull(imported, "Should return exported map")
        assertEquals(kfldimx, imported, "Imported map should equal exported map")
    }

    // ── 4. Bridge import returns null when nothing exported ─────────

    @Test
    fun `bridge import returns null when nothing exported`() {
        LdrpidOptimizerBridge.clear()

        assertNull(LdrpidOptimizerBridge.importKfldrl(), "Should return null when no KFLDRL exported")
        assertNull(LdrpidOptimizerBridge.importKfldimx(), "Should return null when no KFLDIMX exported")
    }

    // ── 5. PID analysis available when KFLDRL present ───────────────

    @Test
    fun `PID analysis produces per-RPM results for all breakpoints`() {
        val kfldrl = buildKfldrlMap()
        val kfldimx = buildKfldimxMap()

        // Simulate at each RPM breakpoint (mirrors what PidAnalysisTab does)
        val results = kfldrl.yAxis.map { rpm ->
            val pull = rampPull(rpm = rpm, count = 60)
            val result = PidSimulator.simulate(
                pullEntries = pull,
                kfldrq0 = null,
                kfldrq1 = null,
                kfldrq2 = null,
                kfldrl = kfldrl,
                kfldimx = kfldimx
            )
            rpm to result
        }

        assertEquals(kfldrl.yAxis.size, results.size, "Should have one result per RPM breakpoint")
        for ((rpm, result) in results) {
            assertTrue(result.states.isNotEmpty(), "RPM $rpm should produce states")
            // None of the diagnostics should contain NaN
            assertFalse(result.diagnosis.avgAbsLde.isNaN(), "avgAbsLde should not be NaN at RPM $rpm")
            assertFalse(result.diagnosis.convergenceTimeMs.isNaN(), "convergenceTimeMs should not be NaN at RPM $rpm")
        }
    }

    @Test
    fun `PID analysis returns empty result for empty KFLDRL`() {
        val emptyMap = Map3d()
        val pull = rampPull(count = 10)

        // With an empty KFLDRL (no lookup data), simulator still shouldn't crash
        val result = PidSimulator.simulate(
            pullEntries = pull,
            kfldrq0 = null,
            kfldrq1 = null,
            kfldrq2 = null,
            kfldrl = emptyMap,
            kfldimx = null
        )

        assertEquals(pull.size, result.states.size)
    }

    // ── 6. Bridge overwrites previous export ────────────────────────

    @Test
    fun `bridge overwrites previous export with newer data`() {
        LdrpidOptimizerBridge.clear()

        val first = buildKfldrlMap()
        LdrpidOptimizerBridge.exportKfldrl(first)

        // Export a different map
        val xAxis = arrayOf(10.0, 30.0, 50.0, 70.0, 90.0)
        val yAxis = arrayOf(1500.0, 2500.0, 3500.0, 4500.0)
        val z = Array(yAxis.size) { Array(xAxis.size) { 42.0 } }
        val second = Map3d(xAxis, yAxis, z)
        LdrpidOptimizerBridge.exportKfldrl(second)

        val imported = LdrpidOptimizerBridge.importKfldrl()
        assertNotNull(imported)
        assertEquals(second, imported, "Should return the most recent export")
    }
}
