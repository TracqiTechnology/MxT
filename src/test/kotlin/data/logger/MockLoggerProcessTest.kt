package data.logger

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Tests for MockLoggerProcess — replaying real CSV log fixtures
 * as if they were live ME7Logger data.
 */
class MockLoggerProcessTest {

    companion object {
        private val OPEN_LOOP_LOG = File("example/med9/logs/open_loop_log.csv")
        private val CLOSED_LOOP_LOG = File("example/med9/logs/closed_loop_log.csv")
        private val LDRPID_LOG = File("example/med9/logs/ldrpid_log.csv")
    }

    // ── Lifecycle tests ────────────────────────────────────────────────

    @Test
    fun `initial status is DISCONNECTED`() {
        val mock = MockLoggerProcess()
        assertEquals(LoggerStatus.DISCONNECTED, mock.status.value)
    }

    @Test
    fun `connect transitions to CONNECTED`() = runBlocking {
        val mock = MockLoggerProcess()
        mock.connectWithFile(OPEN_LOOP_LOG)
        assertEquals(LoggerStatus.CONNECTED, mock.status.value)
        assertTrue(mock.statusMessage.value.contains("open_loop_log.csv"))
    }

    @Test
    fun `connect with missing file transitions to ERROR`() = runBlocking {
        val mock = MockLoggerProcess()
        mock.connect(LoggerConfig(cfgFile = "/nonexistent/log.csv"))
        assertEquals(LoggerStatus.ERROR, mock.status.value)
        assertTrue(mock.statusMessage.value.contains("not found"))
    }

    @Test
    fun `startLogging replays and transitions through LOGGING to CONNECTED`() = runBlocking {
        val mock = MockLoggerProcess()
        mock.connectWithFile(OPEN_LOOP_LOG)
        mock.startLogging()

        // Wait for replay to complete (instant mode, no delay)


        // After replay completes, status should be CONNECTED (replay done)
        assertEquals(LoggerStatus.CONNECTED, mock.status.value)
        assertTrue(mock.statusMessage.value.contains("complete"))
    }

    @Test
    fun `disconnect resets all state`() = runBlocking {
        val mock = MockLoggerProcess()
        mock.connectWithFile(OPEN_LOOP_LOG)
        mock.startLogging()


        mock.disconnect()

        assertEquals(LoggerStatus.DISCONNECTED, mock.status.value)
        assertEquals("", mock.statusMessage.value)
        assertTrue(mock.variables.value.isEmpty())
        assertNull(mock.session.value)
    }

    // ── Open Loop log replay ───────────────────────────────────────────

    @Test
    fun `replay open loop log extracts correct variables`() = runBlocking {
        val mock = MockLoggerProcess()
        mock.connectWithFile(OPEN_LOOP_LOG)
        mock.startLogging()


        val vars = mock.variables.value
        assertTrue(vars.isNotEmpty(), "Should have variables")

        // Verify key signal names
        val names = vars.map { it.name }
        assertTrue("nmot_w" in names, "Missing nmot_w (EngineSpeed)")
        assertTrue("rl_w" in names, "Missing rl_w (EngineLoad)")
        assertTrue("pvdks_w" in names, "Missing pvdks_w (BoostPressure)")
        assertTrue("fr_w" in names, "Missing fr_w (LambdaControl)")
        assertTrue("zwist" in names, "Missing zwist (IgnitionTiming)")
    }

    @Test
    fun `replay open loop log extracts correct aliases`() = runBlocking {
        val mock = MockLoggerProcess()
        mock.connectWithFile(OPEN_LOOP_LOG)
        mock.startLogging()


        val vars = mock.variables.value
        val nmot = vars.first { it.name == "nmot_w" }
        assertEquals("EngineSpeed", nmot.alias)

        val rl = vars.first { it.name == "rl_w" }
        assertEquals("EngineLoad", rl.alias)
    }

    @Test
    fun `replay open loop log extracts correct units`() = runBlocking {
        val mock = MockLoggerProcess()
        mock.connectWithFile(OPEN_LOOP_LOG)
        mock.startLogging()


        val vars = mock.variables.value
        val nmot = vars.first { it.name == "nmot_w" }
        assertEquals("1/min", nmot.unit.trim())

        val pvdks = vars.first { it.name == "pvdks_w" }
        assertEquals("hPa", pvdks.unit.trim())
    }

    @Test
    fun `replay open loop log produces expected sample count`() = runBlocking {
        val mock = MockLoggerProcess()
        mock.connectWithFile(OPEN_LOOP_LOG)
        mock.startLogging()


        val session = mock.session.value
        assertNotNull(session)
        assertTrue(session!!.sampleCount > 50, "Expected 50+ samples, got ${session.sampleCount}")
    }

    @Test
    fun `open loop timestamps are monotonically increasing`() = runBlocking {
        val mock = MockLoggerProcess()
        mock.connectWithFile(OPEN_LOOP_LOG)
        mock.startLogging()


        val session = mock.session.value!!
        for (i in 1 until session.samples.size) {
            assertTrue(
                session.samples[i].timestamp >= session.samples[i - 1].timestamp,
                "Timestamp not monotonic at index $i: ${session.samples[i-1].timestamp} > ${session.samples[i].timestamp}"
            )
        }
    }

    @Test
    fun `open loop physical sanity - RPM in range`() = runBlocking {
        val mock = MockLoggerProcess()
        mock.connectWithFile(OPEN_LOOP_LOG)
        mock.startLogging()


        val session = mock.session.value!!
        val nmotIdx = mock.variables.value.indexOfFirst { it.name == "nmot_w" }
        assertTrue(nmotIdx >= 0, "nmot_w not found")

        for (sample in session.samples) {
            val rpm = sample.values[nmotIdx]
            assertTrue(rpm in 0.0..9000.0,
                "RPM out of range: $rpm at t=${sample.timestamp}")
        }
    }

    @Test
    fun `open loop physical sanity - load in range`() = runBlocking {
        val mock = MockLoggerProcess()
        mock.connectWithFile(OPEN_LOOP_LOG)
        mock.startLogging()


        val session = mock.session.value!!
        val rlIdx = mock.variables.value.indexOfFirst { it.name == "rl_w" }
        assertTrue(rlIdx >= 0, "rl_w not found")

        for (sample in session.samples) {
            val load = sample.values[rlIdx]
            assertTrue(load in 0.0..350.0,
                "Load out of range: $load at t=${sample.timestamp}")
        }
    }

    @Test
    fun `open loop physical sanity - lambda in range`() = runBlocking {
        val mock = MockLoggerProcess()
        mock.connectWithFile(OPEN_LOOP_LOG)
        mock.startLogging()


        val session = mock.session.value!!
        val frIdx = mock.variables.value.indexOfFirst { it.name == "fr_w" }
        assertTrue(frIdx >= 0, "fr_w not found")

        for (sample in session.samples) {
            val lambda = sample.values[frIdx]
            assertTrue(lambda in 0.5..1.5,
                "Lambda out of range: $lambda at t=${sample.timestamp}")
        }
    }

    // ── Closed Loop log replay ─────────────────────────────────────────

    @Test
    fun `replay closed loop log produces samples with fuel trims`() = runBlocking {
        val mock = MockLoggerProcess()
        mock.connectWithFile(CLOSED_LOOP_LOG)
        mock.startLogging()


        val session = mock.session.value
        assertNotNull(session)
        assertTrue(session!!.sampleCount > 50, "Expected 50+ samples")

        // Should have fra_w (long-term fuel trim)
        val names = mock.variables.value.map { it.name }
        assertTrue("fra_w" in names, "Missing fra_w (AdaptationPartial)")
    }

    @Test
    fun `closed loop fuel trim in range`() = runBlocking {
        val mock = MockLoggerProcess()
        mock.connectWithFile(CLOSED_LOOP_LOG)
        mock.startLogging()


        val session = mock.session.value!!
        val fraIdx = mock.variables.value.indexOfFirst { it.name == "fra_w" }
        assertTrue(fraIdx >= 0, "fra_w not found")

        for (sample in session.samples) {
            val trim = sample.values[fraIdx]
            assertTrue(trim in 0.7..1.3,
                "Fuel trim out of range: $trim at t=${sample.timestamp}")
        }
    }

    // ── LDRPID log replay ──────────────────────────────────────────────

    @Test
    fun `replay LDRPID log has boost signals`() = runBlocking {
        val mock = MockLoggerProcess()
        mock.connectWithFile(LDRPID_LOG)
        mock.startLogging()


        val session = mock.session.value
        assertNotNull(session)
        assertTrue(session!!.sampleCount > 50, "Expected 50+ samples")

        val names = mock.variables.value.map { it.name }
        assertTrue("pvdks_w" in names, "Missing pvdks_w (BoostPressureActual)")
        assertTrue("ldtvm" in names, "Missing ldtvm (WastegateDutyCycle)")
    }

    @Test
    fun `LDRPID boost pressure in range`() = runBlocking {
        val mock = MockLoggerProcess()
        mock.connectWithFile(LDRPID_LOG)
        mock.startLogging()


        val session = mock.session.value!!
        val pvdksIdx = mock.variables.value.indexOfFirst { it.name == "pvdks_w" }
        assertTrue(pvdksIdx >= 0, "pvdks_w not found")

        for (sample in session.samples) {
            val pressure = sample.values[pvdksIdx]
            assertTrue(pressure in 0.0..3500.0,
                "Boost pressure out of range: $pressure at t=${sample.timestamp}")
        }
    }

    // ── CSV export round-trip ──────────────────────────────────────────

    @Test
    fun `mock replay then export produces importable CSV`() = runBlocking {
        val mock = MockLoggerProcess()
        mock.connectWithFile(OPEN_LOOP_LOG)
        mock.startLogging()


        val session = mock.session.value!!
        val originalCount = session.sampleCount
        val originalVarCount = session.variables.size
        assertTrue(originalCount > 0, "No samples to export")

        // Export
        val tmpFile = File.createTempFile("mock_export_", ".csv")
        tmpFile.deleteOnExit()
        CsvExporter.export(session, tmpFile)

        // Re-import
        val reimporter = Me7LoggerProcess()
        val reimported = reimporter.parseLogFile(tmpFile)

        assertEquals(originalVarCount, reimported.variables.size, "Variable count mismatch")
        assertEquals(originalCount, reimported.sampleCount, "Sample count mismatch")

        // Spot-check first sample RPM
        val nmotIdx = mock.variables.value.indexOfFirst { it.name == "nmot_w" }
        if (nmotIdx >= 0) {
            assertEquals(
                session.samples.first().values[nmotIdx],
                reimported.samples.first().values[nmotIdx],
                1.0,
                "First sample RPM mismatch after round-trip"
            )
        }
    }

    @Test
    fun `export round-trip preserves variable names and aliases`() = runBlocking {
        val mock = MockLoggerProcess()
        mock.connectWithFile(CLOSED_LOOP_LOG)
        mock.startLogging()


        val session = mock.session.value!!

        val tmpFile = File.createTempFile("mock_alias_", ".csv")
        tmpFile.deleteOnExit()
        CsvExporter.export(session, tmpFile)

        val reimporter = Me7LoggerProcess()
        val reimported = reimporter.parseLogFile(tmpFile)

        for (i in session.variables.indices) {
            assertEquals(session.variables[i].name, reimported.variables[i].name,
                "Variable name mismatch at index $i")
            assertEquals(session.variables[i].alias, reimported.variables[i].alias,
                "Alias mismatch at index $i for ${session.variables[i].name}")
        }
    }

    // ── Timed replay ───────────────────────────────────────────────────

    @Test
    fun `timed replay emits samples with delay`() = runBlocking {
        // Use a very short delay to keep test fast
        val mock = MockLoggerProcess(replayDelayMs = 5)
        mock.connectWithFile(OPEN_LOOP_LOG)

        val collected = mutableListOf<LogSample>()
        val collectJob = launch {
            mock.samples.take(10).toList(collected)
        }

        mock.startLogging()
        delay(500)
        mock.stopLogging()
        collectJob.cancel()

        assertTrue(collected.isNotEmpty(), "Should have collected some samples with timed replay")
    }

    // ── Duration tracking ──────────────────────────────────────────────

    @Test
    fun `session duration reflects log timespan`() = runBlocking {
        val mock = MockLoggerProcess()
        mock.connectWithFile(OPEN_LOOP_LOG)
        mock.startLogging()


        val session = mock.session.value!!
        assertTrue(session.duration > 0.0, "Duration should be positive")
        assertTrue(session.duration < 300.0, "Duration seems too long: ${session.duration}s")
    }
}
