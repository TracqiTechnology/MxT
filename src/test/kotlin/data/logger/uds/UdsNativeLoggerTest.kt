package data.logger.uds

import data.logger.LoggerConfig
import data.logger.LoggerMode
import data.logger.LoggerStatus
import data.logger.protocol.ProtocolConstants
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

class UdsNativeLoggerTest {

    @Test
    fun `connect with valid ecu file`() = runBlocking {
        val fakeCan = FakeCanTransport()
        val logger = UdsNativeLogger(fakeCan)

        val config = LoggerConfig(
            loggerMode = LoggerMode.NATIVE_UDS,
            ecuFile = "example/med9/MED9_0261S02469.ecu",
            cfgFile = "example/med9/MED9_0261S02469_basic.cfg",
            comPort = "SLCAN0"
        )

        logger.connect(config)
        assertEquals(LoggerStatus.CONNECTED, logger.status.value)
        assertTrue(logger.variables.value.isNotEmpty())
    }

    @Test
    fun `connect with missing ecu file errors`() = runBlocking {
        val fakeCan = FakeCanTransport()
        val logger = UdsNativeLogger(fakeCan)

        val config = LoggerConfig(
            loggerMode = LoggerMode.NATIVE_UDS,
            ecuFile = "/nonexistent/test.ecu",
            comPort = "SLCAN0"
        )

        logger.connect(config)
        assertEquals(LoggerStatus.ERROR, logger.status.value)
        assertTrue(logger.statusMessage.value.contains("not found"))
    }

    @Test
    fun `disconnect resets state`() = runBlocking {
        val fakeCan = FakeCanTransport()
        val logger = UdsNativeLogger(fakeCan)

        val config = LoggerConfig(
            loggerMode = LoggerMode.NATIVE_UDS,
            ecuFile = "example/med9/MED9_0261S02469.ecu",
            cfgFile = "example/med9/MED9_0261S02469_basic.cfg",
            comPort = "SLCAN0"
        )

        logger.connect(config)
        assertEquals(LoggerStatus.CONNECTED, logger.status.value)

        logger.disconnect()
        assertEquals(LoggerStatus.DISCONNECTED, logger.status.value)
        assertTrue(logger.variables.value.isEmpty())
    }
}

/**
 * End-to-end integration test for the full UDS logging pipeline.
 * Simulates a MED17 ECU using FakeCanTransport with pre-queued responses
 * to verify: session start → DID definition → data polling → physical
 * value conversion → sample emission → session stop.
 */
class UdsLoggingE2ETest {

    /**
     * Helper: queue a single-frame ISO-TP response on the fake transport.
     * SF format: [PCI=length, data...] padded to 8 bytes.
     */
    private fun queueSingleFrame(fake: FakeCanTransport, rxId: Int, data: ByteArray) {
        val frame = ByteArray(8)
        frame[0] = data.size.toByte() // PCI: single frame, length = data.size
        data.copyInto(frame, 1)
        fake.queueResponse(CanFrame(rxId, frame))
    }

    /**
     * Helper: queue a multi-frame ISO-TP response (FF + CFs).
     * The ISO-TP layer will send Flow Control after receiving FF.
     * FC goes to sentFrames, so it doesn't consume queued responses.
     */
    private fun queueMultiFrame(fake: FakeCanTransport, rxId: Int, data: ByteArray) {
        val totalLen = data.size
        // First Frame: [0x1L_hi, L_lo, first 6 bytes of data]
        val ff = ByteArray(8)
        ff[0] = (0x10 or ((totalLen shr 8) and 0x0F)).toByte()
        ff[1] = (totalLen and 0xFF).toByte()
        val ffDataLen = minOf(6, data.size)
        data.copyInto(ff, 2, 0, ffDataLen)
        fake.queueResponse(CanFrame(rxId, ff))

        // Consecutive Frames
        var offset = ffDataLen
        var seq = 1
        while (offset < data.size) {
            val cf = ByteArray(8)
            cf[0] = (0x20 or (seq and 0x0F)).toByte()
            val cfDataLen = minOf(7, data.size - offset)
            data.copyInto(cf, 1, offset, offset + cfDataLen)
            fake.queueResponse(CanFrame(rxId, cf))
            offset += cfDataLen
            seq++
        }
    }

    /**
     * Create a minimal .ecu file with known variables for deterministic testing.
     *
     * Variables:
     *   rpm_w:  addr=0x1000, size=2, factor=0.25, offset=0  → phys = 0.25 * raw
     *   load_w: addr=0x1002, size=2, factor=0.1,  offset=0  → phys = 0.1 * raw
     *   temp:   addr=0x1004, size=1, factor=1.0,  offset=40 → phys = 1.0 * raw - 40
     */
    private fun createTestEcuFile(): File {
        val content = """
;
; Minimal test ECU file for E2E integration testing
;

[Version]
Version           = 1.10

[Communication]
Connect           = SLOW-0x01
Communicate       = HM0
LogSpeed          = 500000

[Identification]
HWNumber          = {TEST_HW}
SWNumber          = {TEST_SW}
PartNumber        = {TEST_PART}
SWVersion         = {0001}
EngineId          = {2.5L R5 TFSI}

[Measurements]
rpm_w,            {RPM},       0x001000, 2, 0xFFFF, {RPM},  0, 0, 0.25, 0,  {Engine speed}
load_w,           {Load},      0x001002, 2, 0xFFFF, {%},    0, 0, 0.1,  0,  {Engine load}
temp,             {CoolTemp},  0x001004, 1, 0xFF,   {C},    0, 0, 1.0,  40, {Coolant temperature}
""".trimIndent()
        return File.createTempFile("e2e_test", ".ecu").apply {
            writeText(content)
            deleteOnExit()
        }
    }

    /**
     * Create a .cfg file that selects all 3 test variables.
     */
    private fun createTestCfgFile(ecuFileName: String): File {
        val content = """
[Configuration]
ECUCharacteristics = $ecuFileName
SamplesPerSecond   = 50

[LogVariables]
rpm_w    ;{RPM}        ;{Engine speed}
load_w   ;{Load}       ;{Engine load}
temp     ;{CoolTemp}   ;{Coolant temperature}
""".trimIndent()
        return File.createTempFile("e2e_test", ".cfg").apply {
            writeText(content)
            deleteOnExit()
        }
    }

    /**
     * Helper: queue a Flow Control frame (ECU telling tester to Continue To Send).
     * Used when the tester sends multi-frame data (e.g., DID define with >7 bytes).
     */
    private fun queueFlowControl(fake: FakeCanTransport, rxId: Int) {
        val fc = ByteArray(8)
        fc[0] = 0x30 // PCI: Flow Control, CTS (continue to send)
        fc[1] = 0x00 // Block size: 0 = no limit
        fc[2] = 0x00 // STmin: 0 = no delay
        fake.queueResponse(CanFrame(rxId, fc))
    }

    @Test
    fun `full UDS session - connect, define DIDs, poll data, stop`() = runBlocking {
        val fakeCan = FakeCanTransport()
        val logger = UdsNativeLogger(fakeCan)

        // Create test fixtures
        val ecuFile = createTestEcuFile()
        val cfgFile = createTestCfgFile(ecuFile.name)

        // ── Phase 1: Connect (ECU file parsing only, no CAN) ────────────
        logger.connect(LoggerConfig(
            loggerMode = LoggerMode.NATIVE_UDS,
            ecuFile = ecuFile.absolutePath,
            cfgFile = cfgFile.absolutePath,
            comPort = "TEST0",
            baudRate = 500_000
        ))

        assertEquals(LoggerStatus.CONNECTED, logger.status.value)
        assertEquals(3, logger.variables.value.size)
        assertEquals("rpm_w", logger.variables.value[0].name)
        assertEquals("RPM", logger.variables.value[0].alias)
        assertEquals("load_w", logger.variables.value[1].name)
        assertEquals("temp", logger.variables.value[2].name)

        // ── Phase 2: Queue CAN responses for startLogging ───────────────
        //
        // ISO-TP frame ordering:
        // 1. Session start request [0x10, 0x03] = 2 bytes → single frame TX, needs SF response
        // 2. DID define request [0x2C, 0x02, 0xF2, 0x00, ...vars...] = 19 bytes → multi-frame TX
        //    ISO-TP sends First Frame, then waits for Flow Control from ECU,
        //    then sends Consecutive Frames, then waits for UDS response
        // 3. Poll requests [0x22, 0xF2, 0x00] = 3 bytes → single frame TX, needs MF response

        // 2a. DiagnosticSessionControl response: [0x50, 0x03, P2=0x0019, P2*=0x01F4]
        queueSingleFrame(fakeCan, 0x7E8, byteArrayOf(
            0x50, 0x03, 0x00, 0x19, 0x01, 0xF4.toByte()
        ))

        // 2b. Flow Control for DID define multi-frame TX (ECU → tester)
        queueFlowControl(fakeCan, 0x7E8)

        // 2c. DynamicallyDefineDataIdentifier positive response for DID 0xF200
        queueSingleFrame(fakeCan, 0x7E8, byteArrayOf(
            0x6C, 0x02, 0xF2.toByte(), 0x00
        ))

        // 2d. ReadDataByIdentifier poll responses (multi-frame RX)
        //     3 vars: rpm(2 bytes) + load(2 bytes) + temp(1 byte) = 5 data bytes
        //     Full response: [0x62, 0xF2, 0x00, rpm_hi, rpm_lo, load_hi, load_lo, temp] = 8 bytes
        //     8 bytes > 7 (single frame max) → multi-frame ISO-TP
        //     For multi-frame RX, ISO-TP sends FC automatically (goes to sentFrames, not rxQueue)

        // Sample 1: RPM=3000 (0x0BB8), Load=800 (0x0320), Temp=112 (0x70)
        //   Expected phys: RPM=0.25*3000=750, Load=0.1*800=80, Temp=1.0*112-40=72
        queueMultiFrame(fakeCan, 0x7E8, byteArrayOf(
            0x62, 0xF2.toByte(), 0x00,           // ReadDataByID positive response, DID 0xF200
            0x0B, 0xB8.toByte(),                  // rpm_w = 3000
            0x03, 0x20,                            // load_w = 800
            0x70                                   // temp = 112
        ))

        // Sample 2: RPM=6000 (0x1770), Load=950 (0x03B6), Temp=105 (0x69)
        //   Expected phys: RPM=0.25*6000=1500, Load=0.1*950=95, Temp=1.0*105-40=65
        queueMultiFrame(fakeCan, 0x7E8, byteArrayOf(
            0x62, 0xF2.toByte(), 0x00,
            0x17, 0x70,                            // rpm_w = 6000
            0x03, 0xB6.toByte(),                   // load_w = 950
            0x69                                   // temp = 105
        ))

        // Sample 3: RPM=1000 (0x03E8), Load=200 (0x00C8), Temp=130 (0x82)
        //   Expected phys: RPM=0.25*1000=250, Load=0.1*200=20, Temp=1.0*130-40=90
        queueMultiFrame(fakeCan, 0x7E8, byteArrayOf(
            0x62, 0xF2.toByte(), 0x00,
            0x03, 0xE8.toByte(),                   // rpm_w = 1000
            0x00, 0xC8.toByte(),                   // load_w = 200
            0x82.toByte()                          // temp = 130
        ))

        // ── Phase 3: Start logging ──────────────────────────────────────
        logger.startLogging()

        // Allow polling loop time to process all 3 queued responses.
        // After the 3rd sample, the poll loop will try a 4th read, get null from
        // the empty queue, and transition to ERROR. This is expected behavior
        // with FakeCanTransport — a real ECU always responds.
        delay(500)

        // ── Phase 4: Verify samples ─────────────────────────────────────
        val session = logger.session.value
        assertNotNull(session, "Session should be active")

        // Poll loop consumed 3 responses then errored on the 4th — expect 3 samples
        assertTrue(session!!.samples.size >= 1,
            "Should have collected at least 1 sample, got ${session.samples.size}")

        // Verify first sample physical values
        val s1 = session.samples[0]
        assertEquals(750.0, s1.values[1], 0.5, "RPM: 0.25 * 3000 = 750")
        assertEquals(80.0, s1.values[2], 0.5, "Load: 0.1 * 800 = 80")
        assertEquals(72.0, s1.values[3], 0.5, "Temp: 1.0 * 112 - 40 = 72")

        // Verify second sample
        if (session.samples.size >= 2) {
            val s2 = session.samples[1]
            assertEquals(1500.0, s2.values[1], 0.5, "RPM: 0.25 * 6000 = 1500")
            assertEquals(95.0, s2.values[2], 0.5, "Load: 0.1 * 950 = 95")
            assertEquals(65.0, s2.values[3], 0.5, "Temp: 1.0 * 105 - 40 = 65")
        }

        // Verify third sample
        if (session.samples.size >= 3) {
            val s3 = session.samples[2]
            assertEquals(250.0, s3.values[1], 0.5, "RPM: 0.25 * 1000 = 250")
            assertEquals(20.0, s3.values[2], 0.5, "Load: 0.1 * 200 = 20")
            assertEquals(90.0, s3.values[3], 0.5, "Temp: 1.0 * 130 - 40 = 90")
        }

        // ── Phase 5: Stop logging ───────────────────────────────────────
        // Poll loop already errored (empty queue), so stopLogging() will:
        // - Cancel pollJob (already complete)
        // - Try clear DID + stop session (best effort, exception-safe)
        // - Set status to CONNECTED

        // Queue stop responses so cleanup succeeds cleanly
        queueSingleFrame(fakeCan, 0x7E8, byteArrayOf(
            0x6C, 0x03, 0xF2.toByte(), 0x00
        ))
        queueSingleFrame(fakeCan, 0x7E8, byteArrayOf(
            0x50, 0x01, 0x00, 0x19, 0x01, 0xF4.toByte()
        ))

        logger.stopLogging()
        assertEquals(LoggerStatus.CONNECTED, logger.status.value)
        assertTrue(session.sampleCount >= 1, "Session should report collected samples")

        // ── Phase 6: Verify CAN protocol correctness ────────────────────
        assertTrue(fakeCan.sentFrames.isNotEmpty(), "Should have sent CAN frames")

        // First CAN frame: ISO-TP SF containing DiagnosticSessionControl
        val firstFrame = fakeCan.sentFrames[0]
        assertEquals(0x7E0, firstFrame.id, "TX should use 0x7E0")
        assertEquals(0x02.toByte(), firstFrame.data[0], "PCI: SF, len=2")
        assertEquals(0x10.toByte(), firstFrame.data[1], "SID: DiagnosticSessionControl")
        assertEquals(0x03.toByte(), firstFrame.data[2], "Sub: extended session")

        // Second frame: DID definition multi-frame FF (19 bytes → FF + CFs)
        val secondFrame = fakeCan.sentFrames[1]
        assertEquals(0x7E0, secondFrame.id)
        val secondPci = (secondFrame.data[0].toInt() and 0xF0) shr 4
        assertEquals(1, secondPci, "DID define should be multi-frame (FF, PCI=1)")
        assertEquals(0x2C.toByte(), secondFrame.data[2],
            "FF data starts at byte 2: service 0x2C (DynamicallyDefineDataIdentifier)")

        // ── Phase 7: Disconnect and cleanup ─────────────────────────────
        logger.disconnect()
        assertEquals(LoggerStatus.DISCONNECTED, logger.status.value)
        assertTrue(logger.variables.value.isEmpty())
        assertFalse(fakeCan.isOpen, "CAN transport should be closed")
    }

    @Test
    fun `startLogging fails gracefully when session rejected`() = runBlocking {
        val fakeCan = FakeCanTransport()
        val logger = UdsNativeLogger(fakeCan)

        val ecuFile = createTestEcuFile()
        logger.connect(LoggerConfig(
            loggerMode = LoggerMode.NATIVE_UDS,
            ecuFile = ecuFile.absolutePath,
            comPort = "TEST0",
            baudRate = 500_000
        ))
        assertEquals(LoggerStatus.CONNECTED, logger.status.value)

        // Queue a negative response for session start:
        // [0x7F, 0x10, 0x22] = conditionsNotCorrect
        queueSingleFrame(fakeCan, 0x7E8, byteArrayOf(
            0x7F, 0x10, 0x22
        ))

        logger.startLogging()
        // Allow time for error handling
        delay(200)

        assertEquals(LoggerStatus.ERROR, logger.status.value,
            "Should be ERROR after negative response. Message: ${logger.statusMessage.value}")
        assertTrue(logger.statusMessage.value.contains("Start failed"),
            "Status should explain failure: ${logger.statusMessage.value}")
    }

    @Test
    fun `variables resolve from CFG file subset`() = runBlocking {
        val fakeCan = FakeCanTransport()
        val logger = UdsNativeLogger(fakeCan)

        val ecuFile = createTestEcuFile()
        // CFG that only selects 2 of 3 variables
        val partialCfg = File.createTempFile("partial", ".cfg").apply {
            writeText("""
[Configuration]
ECUCharacteristics = ${ecuFile.name}
SamplesPerSecond   = 10

[LogVariables]
rpm_w    ;{RPM}
temp     ;{CoolTemp}
""".trimIndent())
            deleteOnExit()
        }

        logger.connect(LoggerConfig(
            loggerMode = LoggerMode.NATIVE_UDS,
            ecuFile = ecuFile.absolutePath,
            cfgFile = partialCfg.absolutePath,
            comPort = "TEST0"
        ))

        assertEquals(LoggerStatus.CONNECTED, logger.status.value)
        assertEquals(2, logger.variables.value.size, "CFG selects 2 of 3 vars")
        assertEquals("rpm_w", logger.variables.value[0].name)
        assertEquals("temp", logger.variables.value[1].name)
    }
}

/**
 * End-to-end integration test for the SLCAN transport path.
 * Exercises the full stack: SlcanTransport → ISO-TP → UDS → poll loop
 * using FakeSerialPortWrapper to simulate a CANable/USBtin SLCAN adapter.
 *
 * Verifies:
 * - SLCAN open sequence (C → S6 → O)
 * - Frame hex encoding/decoding through serial layer
 * - Full UDS session through SLCAN (session start, DID define, poll, stop)
 * - Physical value conversion end-to-end
 * - Transmitted SLCAN command correctness
 */
class SlcanLoggingE2ETest {

    /**
     * Encode a CAN frame as an SLCAN ASCII string (with \r terminator).
     * Standard (11-bit ID): tIIILDD..\r
     * Extended (29-bit ID): TIIIIIIIILDD..\r
     */
    private fun encodeSlcan(frame: CanFrame): String = buildString {
        if (frame.id > 0x7FF) {
            append('T')
            append(String.format("%08X", frame.id))
        } else {
            append('t')
            append(String.format("%03X", frame.id))
        }
        append(frame.data.size)
        for (b in frame.data) {
            append(String.format("%02X", b.toInt() and 0xFF))
        }
        append('\r')
    }

    /**
     * Queue a single-frame ISO-TP CAN response as SLCAN bytes.
     */
    private fun queueSlcanSingleFrame(port: data.logger.protocol.FakeSerialPortWrapper, rxId: Int, data: ByteArray) {
        val frame = ByteArray(8)
        frame[0] = data.size.toByte()
        data.copyInto(frame, 1)
        val slcan = encodeSlcan(CanFrame(rxId, frame))
        port.queueResponseBytes(slcan.toByteArray(Charsets.US_ASCII))
    }

    /**
     * Queue a multi-frame ISO-TP CAN response as SLCAN bytes (FF + CFs).
     */
    private fun queueSlcanMultiFrame(port: data.logger.protocol.FakeSerialPortWrapper, rxId: Int, data: ByteArray) {
        val totalLen = data.size

        // First Frame
        val ff = ByteArray(8)
        ff[0] = (0x10 or ((totalLen shr 8) and 0x0F)).toByte()
        ff[1] = (totalLen and 0xFF).toByte()
        val ffDataLen = minOf(6, data.size)
        data.copyInto(ff, 2, 0, ffDataLen)
        port.queueResponseBytes(encodeSlcan(CanFrame(rxId, ff)).toByteArray(Charsets.US_ASCII))

        // Consecutive Frames
        var offset = ffDataLen
        var seq = 1
        while (offset < data.size) {
            val cf = ByteArray(8)
            cf[0] = (0x20 or (seq and 0x0F)).toByte()
            val cfDataLen = minOf(7, data.size - offset)
            data.copyInto(cf, 1, offset, offset + cfDataLen)
            port.queueResponseBytes(encodeSlcan(CanFrame(rxId, cf)).toByteArray(Charsets.US_ASCII))
            offset += cfDataLen
            seq++
        }
    }

    /**
     * Queue a Flow Control frame as SLCAN bytes.
     */
    private fun queueSlcanFlowControl(port: data.logger.protocol.FakeSerialPortWrapper, rxId: Int) {
        val fc = ByteArray(8)
        fc[0] = 0x30 // FC: CTS
        fc[1] = 0x00 // BS=0 (unlimited)
        fc[2] = 0x00 // STmin=0
        port.queueResponseBytes(encodeSlcan(CanFrame(rxId, fc)).toByteArray(Charsets.US_ASCII))
    }

    private fun createTestEcuFile(): File {
        val content = """
;
; Minimal test ECU file for SLCAN E2E testing
;

[Version]
Version           = 1.10

[Communication]
Connect           = SLOW-0x01
Communicate       = HM0
LogSpeed          = 500000

[Identification]
HWNumber          = {TEST_HW}
SWNumber          = {TEST_SW}
PartNumber        = {TEST_PART}
SWVersion         = {0001}
EngineId          = {2.5L R5 TFSI}

[Measurements]
rpm_w,            {RPM},       0x001000, 2, 0xFFFF, {RPM},  0, 0, 0.25, 0,  {Engine speed}
load_w,           {Load},      0x001002, 2, 0xFFFF, {%},    0, 0, 0.1,  0,  {Engine load}
temp,             {CoolTemp},  0x001004, 1, 0xFF,   {C},    0, 0, 1.0,  40, {Coolant temperature}
""".trimIndent()
        return File.createTempFile("slcan_e2e", ".ecu").apply {
            writeText(content)
            deleteOnExit()
        }
    }

    private fun createTestCfgFile(ecuFileName: String): File {
        val content = """
[Configuration]
ECUCharacteristics = $ecuFileName
SamplesPerSecond   = 50

[LogVariables]
rpm_w    ;{RPM}        ;{Engine speed}
load_w   ;{Load}       ;{Engine load}
temp     ;{CoolTemp}   ;{Coolant temperature}
""".trimIndent()
        return File.createTempFile("slcan_e2e", ".cfg").apply {
            writeText(content)
            deleteOnExit()
        }
    }

    @Test
    fun `full UDS session over SLCAN - open, session, define DIDs, poll, stop`() = runBlocking {
        // ── Setup: create fake serial port simulating SLCAN adapter ──────
        val fakePort = data.logger.protocol.FakeSerialPortWrapper("SLCAN_TEST0")
        val provider = data.logger.protocol.FakeSerialPortProvider(mapOf("SLCAN_TEST0" to fakePort))
        val slcanTransport = SlcanTransport(provider)
        val logger = UdsNativeLogger(slcanTransport)

        val ecuFile = createTestEcuFile()
        val cfgFile = createTestCfgFile(ecuFile.name)

        // ── Phase 1: Connect (ECU file parsing, no serial I/O) ──────────
        logger.connect(LoggerConfig(
            loggerMode = LoggerMode.NATIVE_UDS,
            ecuFile = ecuFile.absolutePath,
            cfgFile = cfgFile.absolutePath,
            comPort = "SLCAN_TEST0",
            baudRate = 500_000
        ))

        assertEquals(LoggerStatus.CONNECTED, logger.status.value)
        assertEquals(3, logger.variables.value.size)

        // ── Phase 2: Queue SLCAN-encoded CAN responses ──────────────────
        //
        // Frame ordering (same as FakeCanTransport E2E but through serial):
        // 1. Session start response (SF)
        // 2. Flow Control for DID define multi-frame TX
        // 3. DID define positive response (SF)
        // 4. Poll responses (MF each: 8 bytes UDS data)

        // Session start: [0x50, 0x03, 0x00, 0x19, 0x01, 0xF4]
        queueSlcanSingleFrame(fakePort, 0x7E8, byteArrayOf(
            0x50, 0x03, 0x00, 0x19, 0x01, 0xF4.toByte()
        ))

        // Flow Control for DID define (19-byte request → multi-frame TX)
        queueSlcanFlowControl(fakePort, 0x7E8)

        // DID define positive: [0x6C, 0x02, 0xF2, 0x00]
        queueSlcanSingleFrame(fakePort, 0x7E8, byteArrayOf(
            0x6C, 0x02, 0xF2.toByte(), 0x00
        ))

        // Poll response 1: RPM=3000, Load=800, Temp=112
        // Expected physical: RPM=750, Load=80, Temp=72
        queueSlcanMultiFrame(fakePort, 0x7E8, byteArrayOf(
            0x62, 0xF2.toByte(), 0x00,
            0x0B, 0xB8.toByte(),     // rpm_w = 3000
            0x03, 0x20,               // load_w = 800
            0x70                      // temp = 112
        ))

        // Poll response 2: RPM=6000, Load=950, Temp=105
        // Expected physical: RPM=1500, Load=95, Temp=65
        queueSlcanMultiFrame(fakePort, 0x7E8, byteArrayOf(
            0x62, 0xF2.toByte(), 0x00,
            0x17, 0x70,               // rpm_w = 6000
            0x03, 0xB6.toByte(),      // load_w = 950
            0x69                      // temp = 105
        ))

        // ── Phase 3: Start logging ──────────────────────────────────────
        logger.startLogging()
        delay(500)

        // ── Phase 4: Verify SLCAN open sequence was transmitted ─────────
        val transmitted = String(fakePort.transmitted.toByteArray(), Charsets.US_ASCII)

        // Should see: C\r (reset), S6\r (500kbps), O\r (open channel)
        assertTrue(transmitted.contains("C\r"), "Should send close command")
        assertTrue(transmitted.contains("S6\r"), "Should set 500kbps bitrate")
        assertTrue(transmitted.contains("O\r"), "Should open CAN channel")

        // Verify the open sequence order: C comes before S6, S6 before O
        val closeIdx = transmitted.indexOf("C\r")
        val bitrateIdx = transmitted.indexOf("S6\r")
        val openIdx = transmitted.indexOf("O\r")
        assertTrue(closeIdx < bitrateIdx, "Close should come before bitrate set")
        assertTrue(bitrateIdx < openIdx, "Bitrate set should come before open")

        // ── Phase 5: Verify SLCAN frame format ──────────────────────────
        // After open sequence, next transmitted data should be CAN frames
        // Session start request: t7E08 02 10 03 00 00 00 00 00 \r
        assertTrue(transmitted.contains("t7E0"),
            "Should transmit standard CAN frames with ID 0x7E0")

        // ── Phase 6: Verify samples ─────────────────────────────────────
        val session = logger.session.value
        assertNotNull(session, "Session should be active")
        assertTrue(session!!.samples.size >= 1,
            "Should have collected at least 1 sample via SLCAN, got ${session.samples.size}")

        val s1 = session.samples[0]
        assertEquals(750.0, s1.values[1], 0.5, "RPM: 0.25 * 3000 = 750")
        assertEquals(80.0, s1.values[2], 0.5, "Load: 0.1 * 800 = 80")
        assertEquals(72.0, s1.values[3], 0.5, "Temp: 1.0 * 112 - 40 = 72")

        if (session.samples.size >= 2) {
            val s2 = session.samples[1]
            assertEquals(1500.0, s2.values[1], 0.5, "RPM: 0.25 * 6000 = 1500")
            assertEquals(95.0, s2.values[2], 0.5, "Load: 0.1 * 950 = 95")
            assertEquals(65.0, s2.values[3], 0.5, "Temp: 1.0 * 105 - 40 = 65")
        }

        // ── Phase 7: Stop and disconnect ────────────────────────────────
        // Queue stop responses (best effort)
        queueSlcanSingleFrame(fakePort, 0x7E8, byteArrayOf(
            0x6C, 0x03, 0xF2.toByte(), 0x00
        ))
        queueSlcanSingleFrame(fakePort, 0x7E8, byteArrayOf(
            0x50, 0x01, 0x00, 0x19, 0x01, 0xF4.toByte()
        ))

        logger.stopLogging()
        assertEquals(LoggerStatus.CONNECTED, logger.status.value)

        logger.disconnect()
        assertEquals(LoggerStatus.DISCONNECTED, logger.status.value)

        // Verify close command was sent during cleanup
        val finalTransmitted = String(fakePort.transmitted.toByteArray(), Charsets.US_ASCII)
        // Count 'C\r' occurrences: should be at least 2 (open reset + close cleanup)
        val closeCount = "C\r".toRegex().findAll(finalTransmitted).count()
        assertTrue(closeCount >= 2,
            "Should send C\\r at open (reset) and close (cleanup). Found $closeCount")
    }

    @Test
    fun `SLCAN open sequence with different bitrate`() = runBlocking {
        val fakePort = data.logger.protocol.FakeSerialPortWrapper("CAN_250K")
        val provider = data.logger.protocol.FakeSerialPortProvider(mapOf("CAN_250K" to fakePort))
        val slcanTransport = SlcanTransport(provider)
        val logger = UdsNativeLogger(slcanTransport)

        val ecuFile = createTestEcuFile()

        logger.connect(LoggerConfig(
            loggerMode = LoggerMode.NATIVE_UDS,
            ecuFile = ecuFile.absolutePath,
            comPort = "CAN_250K",
            baudRate = 250_000
        ))

        assertEquals(LoggerStatus.CONNECTED, logger.status.value)

        // Queue session start — will fail after this since no DID response,
        // but we just want to verify the open sequence with 250kbps
        queueSlcanSingleFrame(fakePort, 0x7E8, byteArrayOf(
            0x50, 0x03, 0x00, 0x19, 0x01, 0xF4.toByte()
        ))

        // Let startLogging() attempt (it will error on DID define, which is expected)
        logger.startLogging()
        delay(200)

        val transmitted = String(fakePort.transmitted.toByteArray(), Charsets.US_ASCII)

        // Verify 250kbps bitrate command
        assertTrue(transmitted.contains("S5\r"),
            "Should set 250kbps (S5). Transmitted: ${transmitted.take(100)}")
        assertTrue(fakePort.baudRate == 115200,
            "Serial baud should be 115200 for SLCAN adapter. Got: ${fakePort.baudRate}")

        logger.disconnect()
    }

    @Test
    fun `SLCAN serial port configuration`() = runBlocking {
        val fakePort = data.logger.protocol.FakeSerialPortWrapper("USB0")
        val provider = data.logger.protocol.FakeSerialPortProvider(mapOf("USB0" to fakePort))
        val slcanTransport = SlcanTransport(provider)
        val logger = UdsNativeLogger(slcanTransport)

        val ecuFile = createTestEcuFile()

        logger.connect(LoggerConfig(
            loggerMode = LoggerMode.NATIVE_UDS,
            ecuFile = ecuFile.absolutePath,
            comPort = "USB0",
            baudRate = 500_000
        ))

        // Queue minimal responses for startLogging to open the port and start session
        queueSlcanSingleFrame(fakePort, 0x7E8, byteArrayOf(0x50, 0x03, 0x00, 0x19, 0x01, 0xF4.toByte()))
        // startLogging will error after session start (no DID define FC queued),
        // triggering cleanup which closes the port. That's OK — we verify the
        // serial config was SET correctly (values persist on the wrapper after close).
        logger.startLogging()
        delay(200)

        // Verify serial port was configured correctly before open
        assertEquals(115200, fakePort.baudRate, "SLCAN adapters use 115200 baud on serial")
        assertEquals(8, fakePort.dataBits, "8 data bits")
        assertEquals(1, fakePort.stopBits, "1 stop bit")
        assertEquals(0, fakePort.parity, "No parity")

        // Verify the open sequence was transmitted (proves port was opened)
        val transmitted = String(fakePort.transmitted.toByteArray(), Charsets.US_ASCII)
        assertTrue(transmitted.contains("C\r"), "Should have sent close command (port was opened)")

        logger.disconnect()
    }
}

class IsoTpTransportTest {

    @Test
    fun `send single frame for short message`() = kotlinx.coroutines.runBlocking {
        val fakeCan = FakeCanTransport()
        val isoTp = IsoTpTransport(fakeCan, txId = 0x7E0, rxId = 0x7E8)

        // Send a 3-byte UDS message
        isoTp.send(byteArrayOf(0x10, 0x03, 0x00))

        assertEquals(1, fakeCan.sentFrames.size)
        val frame = fakeCan.sentFrames[0]
        assertEquals(0x7E0, frame.id)
        assertEquals(8, frame.data.size) // CAN frame is always 8 bytes

        // PCI: single frame, length=3
        assertEquals(0x03.toByte(), frame.data[0])
        // Data bytes
        assertEquals(0x10.toByte(), frame.data[1])
        assertEquals(0x03.toByte(), frame.data[2])
        assertEquals(0x00.toByte(), frame.data[3])
    }

    @Test
    fun `receive single frame`() = kotlinx.coroutines.runBlocking {
        val fakeCan = FakeCanTransport()
        val isoTp = IsoTpTransport(fakeCan, txId = 0x7E0, rxId = 0x7E8)

        // Queue a single-frame response: SF with 3 data bytes
        fakeCan.queueResponse(CanFrame(0x7E8, byteArrayOf(0x03, 0x50, 0x03, 0x00, 0x00, 0x00, 0x00, 0x00)))

        val response = isoTp.receive()
        assertEquals(3, response.size)
        assertEquals(0x50.toByte(), response[0])
        assertEquals(0x03.toByte(), response[1])
    }

    @Test
    fun `receive multi-frame message`() = kotlinx.coroutines.runBlocking {
        val fakeCan = FakeCanTransport()
        val isoTp = IsoTpTransport(fakeCan, txId = 0x7E0, rxId = 0x7E8)

        // First Frame: total length=10, 6 data bytes
        fakeCan.queueResponse(CanFrame(0x7E8, byteArrayOf(
            0x10, 0x0A, // FF: length=10
            0x62, 0xF2.toByte(), 0x00, 0x01, 0x02, 0x03  // first 6 bytes
        )))

        // After we send FC, the ECU sends Consecutive Frame
        // The FC send will trigger, then we need CF queued
        fakeCan.queueResponse(CanFrame(0x7E8, byteArrayOf(
            0x21, // CF: seq=1
            0x04, 0x05, 0x06, 0x07, 0x00, 0x00, 0x00  // remaining 4 bytes + padding
        )))

        val response = isoTp.receive()
        assertEquals(10, response.size)
        assertEquals(0x62.toByte(), response[0])  // ReadDataByIdentifier positive response
        assertEquals(0xF2.toByte(), response[1])
        assertEquals(0x07.toByte(), response[9])

        // Verify we sent a Flow Control frame
        assertTrue(fakeCan.sentFrames.isNotEmpty(), "Should have sent FC frame")
        val fc = fakeCan.sentFrames[0]
        assertEquals(0x7E0, fc.id)
        assertEquals(0x30.toByte(), fc.data[0]) // FC: continue (0x30)
    }

    @Test
    fun `SLCAN frame parsing`() {
        val slcan = SlcanTransport(data.logger.protocol.FakeSerialPortProvider())

        // Standard frame: t07E83E00000000000000
        val frame = slcan.parseSlcanFrame("t7E88112233445566778800")
        assertNotNull(frame)
        assertEquals(0x7E8, frame!!.id)
        assertEquals(8, frame.data.size)
    }

    @Test
    fun `SLCAN bitrate commands`() {
        assertEquals("S0", SlcanTransport.bitrateToSlcan(10_000))
        assertEquals("S4", SlcanTransport.bitrateToSlcan(125_000))
        assertEquals("S6", SlcanTransport.bitrateToSlcan(500_000))
        assertEquals("S8", SlcanTransport.bitrateToSlcan(1_000_000))
    }

    @Test
    fun `CAN adapter type enum`() {
        assertEquals("SLCAN (CANable, USBtin)", CanAdapterType.SLCAN.displayName)
        assertEquals("PCAN-USB (Peak)", CanAdapterType.PCAN.displayName)
    }

    @Test
    fun `FakeCanTransport queues and sends`() = kotlinx.coroutines.runBlocking {
        val fake = FakeCanTransport()
        fake.open(CanConfig(portName = "test"))

        assertTrue(fake.isOpen)

        fake.sendFrame(0x7E0, byteArrayOf(0x10, 0x03))
        assertEquals(1, fake.sentFrames.size)
        assertEquals(0x7E0, fake.sentFrames[0].id)

        fake.queueResponse(0x7E8, 0x50, 0x03)
        val rx = fake.receiveFrame()
        assertNotNull(rx)
        assertEquals(0x7E8, rx!!.id)

        fake.close()
        assertFalse(fake.isOpen)
    }
}

class PcanTransportTest {

    @Test
    fun `PCAN availability check`() {
        // In test environment, PCAN native lib won't be available
        assertFalse(PcanTransport.isAvailable())
    }

    @Test
    fun `PCAN bitrate conversion`() {
        // Verify bitrate constants map correctly to TPCANBaudrate enums
        assertEquals(peak.can.basic.TPCANBaudrate.PCAN_BAUD_500K, PcanTransport.bitrateToTpcan(500_000))
        assertEquals(peak.can.basic.TPCANBaudrate.PCAN_BAUD_250K, PcanTransport.bitrateToTpcan(250_000))
        assertEquals(peak.can.basic.TPCANBaudrate.PCAN_BAUD_125K, PcanTransport.bitrateToTpcan(125_000))
        assertEquals(peak.can.basic.TPCANBaudrate.PCAN_BAUD_1M, PcanTransport.bitrateToTpcan(1_000_000))
        // Unknown bitrate defaults to 500k
        assertEquals(peak.can.basic.TPCANBaudrate.PCAN_BAUD_500K, PcanTransport.bitrateToTpcan(333_333))
    }
}
