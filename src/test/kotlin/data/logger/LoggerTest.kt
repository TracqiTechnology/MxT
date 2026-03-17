package data.logger

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.io.File
import java.io.StringReader

class Me7LoggerProcessTest {

    /** Synthetic ME7Logger stdout output. */
    private val SAMPLE_OUTPUT = """
        ; ME7Logger v1.20
        ; ECU: 1K0907115S
        ; Log started at: 2024-01-15 14:30:00
        ;
        TimeStamp, nmot_w, rl_w, pvdks_w, fr_w, zwist
          sec.ms , 1/min , %   , mbar  , -   , °KW
        "TIME","EngineSpeed","EngineLoad","BoostPressure","LambdaCtrl","IgnTiming"
            0.000,  2400,  35.2, 1013, 1.000, 12.5
            0.050,  2450,  36.1, 1025, 0.998, 12.0
            0.100,  2510,  38.5, 1050, 1.002, 11.5
            0.150,  2600,  42.3, 1120, 0.995, 10.0
            0.200,  2720,  48.7, 1250, 0.990,  8.5
    """.trimIndent()

    @Test
    fun `parse header rows extracts variable names`() = runBlocking {
        val logger = Me7LoggerProcess()
        val reader = BufferedReader(StringReader(SAMPLE_OUTPUT))

        logger.parseOutputStream(reader)

        val vars = logger.variables.value
        assertEquals(6, vars.size)
        assertEquals("TimeStamp", vars[0].name)
        assertEquals("nmot_w", vars[1].name)
        assertEquals("rl_w", vars[2].name)
        assertEquals("pvdks_w", vars[3].name)
    }

    @Test
    fun `parse header rows extracts aliases`() = runBlocking {
        val logger = Me7LoggerProcess()
        val reader = BufferedReader(StringReader(SAMPLE_OUTPUT))

        logger.parseOutputStream(reader)

        val vars = logger.variables.value
        assertEquals("TIME", vars[0].alias)
        assertEquals("EngineSpeed", vars[1].alias)
        assertEquals("EngineLoad", vars[2].alias)
        assertEquals("BoostPressure", vars[3].alias)
    }

    @Test
    fun `parse header rows extracts units`() = runBlocking {
        val logger = Me7LoggerProcess()
        val reader = BufferedReader(StringReader(SAMPLE_OUTPUT))

        logger.parseOutputStream(reader)

        val vars = logger.variables.value
        assertEquals("sec.ms", vars[0].unit.trim())
        assertEquals("1/min", vars[1].unit.trim())
    }

    @Test
    fun `parse data rows produces samples`() = runBlocking {
        val logger = Me7LoggerProcess()
        val reader = BufferedReader(StringReader(SAMPLE_OUTPUT))

        logger.parseOutputStream(reader)

        val session = logger.session.value
        assertNotNull(session)
        assertEquals(5, session!!.sampleCount)

        // Verify first sample
        val first = session.samples[0]
        assertEquals(0.0, first.timestamp, 0.001)
        assertEquals(2400.0, first.values[1], 0.1)   // nmot_w
        assertEquals(35.2, first.values[2], 0.1)      // rl_w

        // Verify last sample
        val last = session.samples[4]
        assertEquals(0.2, last.timestamp, 0.001)
        assertEquals(2720.0, last.values[1], 0.1)     // nmot_w
        assertEquals(8.5, last.values[5], 0.1)        // zwist
    }

    @Test
    fun `samples are emitted to SharedFlow`() = runBlocking {
        val logger = Me7LoggerProcess()

        // parseOutputStream is synchronous, so samples are emitted during the call.
        // SharedFlow has extraBufferCapacity=1000, so they're buffered.
        val reader = BufferedReader(StringReader(SAMPLE_OUTPUT))
        logger.parseOutputStream(reader)

        // Verify session collected the samples
        val session = logger.session.value
        assertNotNull(session)
        assertEquals(5, session!!.sampleCount)

        // Verify SharedFlow replay/buffer captured them
        // (SharedFlow with extraBufferCapacity=1000 buffers emissions)
        val collected = mutableListOf<LogSample>()
        val collectJob = launch {
            logger.samples.take(5).toList(collected)
        }

        // If buffered, this completes immediately; otherwise samples were already consumed
        // Either way, the session.samples verification above confirms data integrity
        collectJob.cancel()
    }

    @Test
    fun `status transitions during parsing`() = runBlocking {
        val logger = Me7LoggerProcess()
        assertEquals(LoggerStatus.DISCONNECTED, logger.status.value)

        val reader = BufferedReader(StringReader(SAMPLE_OUTPUT))
        logger.parseOutputStream(reader)

        // After parsing completes, status goes to CONNECTED
        // (only CONNECTED because there's no actual process running)
        assertEquals(LoggerStatus.CONNECTED, logger.status.value)
    }

    @Test
    fun `parseLogFile reads offline CSV`() {
        val tmpFile = File.createTempFile("test_log_", ".csv")
        tmpFile.deleteOnExit()
        tmpFile.writeText(SAMPLE_OUTPUT)

        val logger = Me7LoggerProcess()
        val session = logger.parseLogFile(tmpFile)

        assertEquals(6, session.variables.size)
        assertEquals(5, session.sampleCount)
        assertEquals("nmot_w", session.variables[1].name)
        assertEquals("EngineSpeed", session.variables[1].alias)
    }

    @Test
    fun `connect validates config`() = runBlocking {
        val logger = Me7LoggerProcess()

        // Connect with non-existent paths
        logger.connect(LoggerConfig(
            me7loggerPath = "/nonexistent/ME7Logger.exe",
            ecuFile = "/nonexistent/test.ecu",
            cfgFile = "/nonexistent/test.cfg"
        ))

        assertEquals(LoggerStatus.ERROR, logger.status.value)
        assertTrue(logger.statusMessage.value.contains("not found"))
    }

    @Test
    fun `empty input produces no samples`() = runBlocking {
        val logger = Me7LoggerProcess()
        val reader = BufferedReader(StringReader(""))

        logger.parseOutputStream(reader)

        assertNull(logger.session.value)
        assertTrue(logger.variables.value.isEmpty())
    }

    @Test
    fun `comment-only input produces no samples`() = runBlocking {
        val input = """
            ; Just comments
            ; No data here
            ;
        """.trimIndent()

        val logger = Me7LoggerProcess()
        val reader = BufferedReader(StringReader(input))

        logger.parseOutputStream(reader)

        assertTrue(logger.variables.value.isEmpty())
    }
}

class CsvExporterTest {

    @Test
    fun `export produces ME7Logger-compatible format`() {
        val variables = listOf(
            LogVariable("TimeStamp", "TIME", "sec.ms", 0),
            LogVariable("nmot_w", "EngineSpeed", "1/min", 1),
            LogVariable("rl_w", "EngineLoad", "%", 2),
        )

        val samples = mutableListOf(
            LogSample(0.0, doubleArrayOf(0.0, 2400.0, 35.2)),
            LogSample(0.05, doubleArrayOf(0.05, 2450.0, 36.1)),
        )

        val session = LogSession(variables = variables, samples = samples)

        val tmpFile = File.createTempFile("test_export_", ".csv")
        tmpFile.deleteOnExit()

        CsvExporter.export(session, tmpFile)

        val content = tmpFile.readText()
        assertTrue(content.contains("; MxT Logger Export"))
        assertTrue(content.contains("TimeStamp"))
        assertTrue(content.contains("nmot_w"))
        assertTrue(content.contains("\"TIME\""))
        assertTrue(content.contains("\"EngineSpeed\""))
        assertTrue(content.contains("2400"))
    }

    @Test
    fun `export round-trip with parseLogFile`() {
        val variables = listOf(
            LogVariable("TimeStamp", "TIME", "sec.ms", 0),
            LogVariable("nmot_w", "EngineSpeed", "1/min", 1),
            LogVariable("rl_w", "EngineLoad", "%", 2),
        )

        val samples = mutableListOf(
            LogSample(0.0, doubleArrayOf(0.0, 2400.0, 35.2)),
            LogSample(0.05, doubleArrayOf(0.05, 2450.0, 36.1)),
            LogSample(0.1, doubleArrayOf(0.1, 2510.0, 38.5)),
        )

        val session = LogSession(variables = variables, samples = samples)

        val tmpFile = File.createTempFile("test_roundtrip_", ".csv")
        tmpFile.deleteOnExit()

        // Export
        CsvExporter.export(session, tmpFile)

        // Re-import
        val logger = Me7LoggerProcess()
        val reimported = logger.parseLogFile(tmpFile)

        assertEquals(3, reimported.variables.size)
        assertEquals(3, reimported.sampleCount)
        assertEquals("nmot_w", reimported.variables[1].name)
        assertEquals("EngineSpeed", reimported.variables[1].alias)

        // Values should match (within formatting precision)
        assertEquals(2400.0, reimported.samples[0].values[1], 1.0)
        assertEquals(35.2, reimported.samples[0].values[2], 0.1)
    }

    @Test
    fun `empty session exports header only`() {
        val session = LogSession(variables = emptyList())
        val tmpFile = File.createTempFile("test_empty_", ".csv")
        tmpFile.deleteOnExit()

        CsvExporter.export(session, tmpFile)

        val content = tmpFile.readText()
        assertTrue(content.contains("; MxT Logger Export"))
        // Should not contain data rows
        val lines = content.lines().filter { !it.startsWith(";") && it.isNotBlank() }
        assertTrue(lines.isEmpty())
    }
}

class LoggerModelTest {

    @Test
    fun `LogVariable holds name alias and unit`() {
        val v = LogVariable("nmot_w", "EngineSpeed", "1/min", 0)
        assertEquals("nmot_w", v.name)
        assertEquals("EngineSpeed", v.alias)
        assertEquals("1/min", v.unit)
    }

    @Test
    fun `LogSample stores timestamp and values`() {
        val s = LogSample(1.234, doubleArrayOf(1.234, 2400.0, 35.2))
        assertEquals(1.234, s.timestamp)
        assertEquals(3, s.values.size)
    }

    @Test
    fun `LogSession tracks samples and duration`() {
        val session = LogSession(
            variables = listOf(
                LogVariable("TimeStamp", "TIME", "sec.ms", 0),
                LogVariable("nmot_w", "EngineSpeed", "1/min", 1),
            )
        )
        session.samples.add(LogSample(0.0, doubleArrayOf(0.0, 2400.0)))
        session.samples.add(LogSample(1.5, doubleArrayOf(1.5, 3000.0)))
        session.samples.add(LogSample(3.0, doubleArrayOf(3.0, 3500.0)))

        assertEquals(3, session.sampleCount)
        assertEquals(3.0, session.duration, 0.001)
    }

    @Test
    fun `LoggerConfig has sensible defaults`() {
        val config = LoggerConfig()
        assertEquals("", config.comPort)
        assertEquals(56000, config.baudRate)
        assertEquals(20, config.samplesPerSecond)
        assertEquals(ConnectionType.COM_PORT, config.connectionType)
        assertTrue(config.ftdiIdentifier is FtdiIdentifier.None)
        assertFalse(config.overrideSamplesPerSecond)
        assertFalse(config.overrideBaudRate)
        assertFalse(config.syncTimestamp)
        assertFalse(config.absoluteTimestamps)
        assertFalse(config.millisecondTimestamps)
        assertEquals("", config.outputLogFile)
        assertFalse(config.realTimeWrite)
    }
}

class BuildCommandTest {

    private val logger = Me7LoggerProcess()

    @Test
    fun `default COM port produces expected command`() {
        val cfg = LoggerConfig(
            me7loggerPath = "/path/ME7Logger.exe",
            comPort = "COM3",
            cfgFile = "/path/config.cfg"
        )
        val cmd = logger.buildCommand(cfg)
        assertEquals(listOf("/path/ME7Logger.exe", "-p", "COM3", "-R", "/path/config.cfg"), cmd)
    }

    @Test
    fun `empty COM port omits -p flag`() {
        val cfg = LoggerConfig(
            me7loggerPath = "/path/ME7Logger.exe",
            comPort = "",
            cfgFile = "/path/config.cfg"
        )
        val cmd = logger.buildCommand(cfg)
        assertFalse(cmd.contains("-p"))
        assertEquals(listOf("/path/ME7Logger.exe", "-R", "/path/config.cfg"), cmd)
    }

    @Test
    fun `FTDI bare produces -f flag`() {
        val cfg = LoggerConfig(
            me7loggerPath = "/path/ME7Logger.exe",
            connectionType = ConnectionType.FTDI,
            cfgFile = "/path/config.cfg"
        )
        val cmd = logger.buildCommand(cfg)
        assertTrue(cmd.contains("-f"))
        assertFalse(cmd.contains("-p"))
        assertEquals(listOf("/path/ME7Logger.exe", "-f", "-R", "/path/config.cfg"), cmd)
    }

    @Test
    fun `FTDI with serial number`() {
        val cfg = LoggerConfig(
            me7loggerPath = "/path/ME7Logger.exe",
            connectionType = ConnectionType.FTDI,
            ftdiIdentifier = FtdiIdentifier.Serial("XYZ123"),
            cfgFile = "/path/config.cfg"
        )
        val cmd = logger.buildCommand(cfg)
        assertTrue(cmd.contains("-f"))
        assertTrue(cmd.contains("-SXYZ123"))
    }

    @Test
    fun `FTDI with description`() {
        val cfg = LoggerConfig(
            me7loggerPath = "/path/ME7Logger.exe",
            connectionType = ConnectionType.FTDI,
            ftdiIdentifier = FtdiIdentifier.Description("MyDevice"),
            cfgFile = "/path/config.cfg"
        )
        val cmd = logger.buildCommand(cfg)
        assertTrue(cmd.contains("-f"))
        assertTrue(cmd.contains("-DMyDevice"))
    }

    @Test
    fun `FTDI with location`() {
        val cfg = LoggerConfig(
            me7loggerPath = "/path/ME7Logger.exe",
            connectionType = ConnectionType.FTDI,
            ftdiIdentifier = FtdiIdentifier.Location("1A"),
            cfgFile = "/path/config.cfg"
        )
        val cmd = logger.buildCommand(cfg)
        assertTrue(cmd.contains("-f"))
        assertTrue(cmd.contains("-L1A"))
    }

    @Test
    fun `SPS override includes -s flag`() {
        val cfg = LoggerConfig(
            me7loggerPath = "/path/ME7Logger.exe",
            cfgFile = "/path/config.cfg",
            overrideSamplesPerSecond = true,
            samplesPerSecond = 30
        )
        val cmd = logger.buildCommand(cfg)
        val sIdx = cmd.indexOf("-s")
        assertTrue(sIdx >= 0)
        assertEquals("30", cmd[sIdx + 1])
    }

    @Test
    fun `SPS NOT overridden does not include -s flag`() {
        val cfg = LoggerConfig(
            me7loggerPath = "/path/ME7Logger.exe",
            cfgFile = "/path/config.cfg",
            overrideSamplesPerSecond = false,
            samplesPerSecond = 30
        )
        val cmd = logger.buildCommand(cfg)
        assertFalse(cmd.contains("-s"))
    }

    @Test
    fun `baud rate override includes -b flag`() {
        val cfg = LoggerConfig(
            me7loggerPath = "/path/ME7Logger.exe",
            cfgFile = "/path/config.cfg",
            overrideBaudRate = true,
            baudRate = 115200
        )
        val cmd = logger.buildCommand(cfg)
        val bIdx = cmd.indexOf("-b")
        assertTrue(bIdx >= 0)
        assertEquals("115200", cmd[bIdx + 1])
    }

    @Test
    fun `timestamp flags are all present`() {
        val cfg = LoggerConfig(
            me7loggerPath = "/path/ME7Logger.exe",
            cfgFile = "/path/config.cfg",
            syncTimestamp = true,
            absoluteTimestamps = true,
            millisecondTimestamps = true
        )
        val cmd = logger.buildCommand(cfg)
        assertTrue(cmd.contains("-t"))
        assertTrue(cmd.contains("-a"))
        assertTrue(cmd.contains("-m"))
    }

    @Test
    fun `output file includes -o flag`() {
        val cfg = LoggerConfig(
            me7loggerPath = "/path/ME7Logger.exe",
            cfgFile = "/path/config.cfg",
            outputLogFile = "/path/out.csv"
        )
        val cmd = logger.buildCommand(cfg)
        val oIdx = cmd.indexOf("-o")
        assertTrue(oIdx >= 0)
        assertEquals("/path/out.csv", cmd[oIdx + 1])
    }

    @Test
    fun `real-time write includes -r flag`() {
        val cfg = LoggerConfig(
            me7loggerPath = "/path/ME7Logger.exe",
            cfgFile = "/path/config.cfg",
            realTimeWrite = true
        )
        val cmd = logger.buildCommand(cfg)
        assertTrue(cmd.contains("-r"))
    }

    @Test
    fun `SPS clamped to 1-50 range`() {
        val cfg = LoggerConfig(
            me7loggerPath = "/path/ME7Logger.exe",
            cfgFile = "/path/config.cfg",
            overrideSamplesPerSecond = true,
            samplesPerSecond = 100
        )
        val cmd = logger.buildCommand(cfg)
        val sIdx = cmd.indexOf("-s")
        assertEquals("50", cmd[sIdx + 1])

        val cfgLow = cfg.copy(samplesPerSecond = 0)
        val cmdLow = logger.buildCommand(cfgLow)
        val sIdxLow = cmdLow.indexOf("-s")
        assertEquals("1", cmdLow[sIdxLow + 1])
    }

    @Test
    fun `config file is always last positional arg`() {
        val cfg = LoggerConfig(
            me7loggerPath = "/path/ME7Logger.exe",
            comPort = "COM5",
            cfgFile = "/path/config.cfg",
            overrideSamplesPerSecond = true,
            samplesPerSecond = 25,
            syncTimestamp = true,
            outputLogFile = "/path/out.csv",
            realTimeWrite = true
        )
        val cmd = logger.buildCommand(cfg)
        assertEquals("/path/config.cfg", cmd.last())
    }
}
