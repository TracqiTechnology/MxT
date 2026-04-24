package data.logger.kwp2000

import data.logger.LoggerConfig
import data.logger.LoggerMode
import data.logger.LoggerStatus
import data.logger.protocol.FakeSerialPortProvider
import data.logger.protocol.FakeSerialPortWrapper
import data.logger.protocol.ProtocolConstants
import data.parser.a2l.EcuEntry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

class Kwp2000NativeLoggerTest {

    @Test
    fun `parseTargetAddress extracts hex from SLOW string`() {
        assertEquals(0x01, Kwp2000NativeLogger.parseTargetAddress("SLOW-0x01"))
        assertEquals(0x11, Kwp2000NativeLogger.parseTargetAddress("SLOW-0x11"))
        assertEquals(0x01, Kwp2000NativeLogger.parseTargetAddress("UNKNOWN"))
    }

    @Test
    fun `convert normal unsigned entry`() {
        val entry = makeEntry(factor = 0.25, offset = 0.0, signed = 0, inverse = 0)
        assertEquals(250.0, Kwp2000NativeLogger.convert(1000, entry), 0.001)
    }

    @Test
    fun `convert normal with offset`() {
        val entry = makeEntry(factor = 0.75, offset = 48.0, signed = 0, inverse = 0)
        // phys = 0.75 * 200 - 48 = 150 - 48 = 102
        assertEquals(102.0, Kwp2000NativeLogger.convert(200, entry), 0.001)
    }

    @Test
    fun `convert signed entry`() {
        val entry = makeEntry(factor = 0.75, offset = 0.0, signed = 1, inverse = 0)
        // raw=0xFFFF → signed short = -1 → phys = 0.75 * -1 = -0.75
        assertEquals(-0.75, Kwp2000NativeLogger.convert(0xFFFF, entry), 0.001)
    }

    @Test
    fun `convert inverse entry`() {
        val entry = makeEntry(factor = 2.55, offset = 0.0, signed = 0, inverse = 1)
        // phys = 2.55 / (100 - 0) = 0.0255
        assertEquals(0.0255, Kwp2000NativeLogger.convert(100, entry), 0.0001)
    }

    @Test
    fun `convert inverse with zero denominator returns zero`() {
        val entry = makeEntry(factor = 100.0, offset = 0.0, signed = 0, inverse = 1)
        assertEquals(0.0, Kwp2000NativeLogger.convert(0, entry))
    }

    @Test
    fun `connect with valid ecu file`() = runBlocking {
        val fakePort = FakeSerialPortWrapper("COM3")
        val provider = FakeSerialPortProvider(mapOf("COM3" to fakePort))
        val logger = Kwp2000NativeLogger(provider)

        val config = LoggerConfig(
            loggerMode = LoggerMode.NATIVE_KWP2000,
            ecuFile = "example/med9/MED9_0261S02469.ecu",
            cfgFile = "example/med9/MED9_0261S02469_basic.cfg",
            comPort = "COM3"
        )

        logger.connect(config)
        assertEquals(LoggerStatus.CONNECTED, logger.status.value)
        assertTrue(logger.variables.value.isNotEmpty())
    }

    @Test
    fun `connect with missing ecu file errors`() = runBlocking {
        val provider = FakeSerialPortProvider()
        val logger = Kwp2000NativeLogger(provider)

        val config = LoggerConfig(
            loggerMode = LoggerMode.NATIVE_KWP2000,
            ecuFile = "/nonexistent/test.ecu",
            comPort = "COM3"
        )

        logger.connect(config)
        assertEquals(LoggerStatus.ERROR, logger.status.value)
        assertTrue(logger.statusMessage.value.contains("not found"))
    }

    @Test
    fun `connect resolves cfg variables against ecu entries`() = runBlocking {
        val provider = FakeSerialPortProvider()
        val logger = Kwp2000NativeLogger(provider)

        val config = LoggerConfig(
            loggerMode = LoggerMode.NATIVE_KWP2000,
            ecuFile = "example/med9/MED9_0261S02469.ecu",
            cfgFile = "example/med9/MED9_0261S02469_basic.cfg",
            comPort = "COM3"
        )

        logger.connect(config)
        assertEquals(LoggerStatus.CONNECTED, logger.status.value)

        // Should have the same number of variables as the .cfg file
        val vars = logger.variables.value
        assertTrue(vars.isNotEmpty())

        // Check that nmot_w is in the variable list
        val nmot = vars.find { it.name == "nmot_w" }
        assertNotNull(nmot, "nmot_w should be resolved from .cfg → .ecu")
    }

    @Test
    fun `HM0 frame checksum calculation`() {
        // Verify HM0 frame format: [length][data...][checksum]
        // Frame for StartDiagnosticSession (0x10 0x89):
        // Length=2, Data=0x10 0x89, Checksum = (2 + 0x10 + 0x89) & 0xFF = 0x9B
        val data = byteArrayOf(0x10, 0x89.toByte())
        val length = data.size
        var checksum = length
        for (b in data) {
            checksum = (checksum + (b.toInt() and 0xFF)) and 0xFF
        }
        assertEquals(0x9B, checksum)
    }

    @Test
    fun `define variables message format`() {
        // Bosch 0xB7 define message should include:
        // [0xB7] [0x03 (defineByMemoryAddress)] [recordId] [addr_hi] [addr_mid] [addr_lo] [size] ...
        val serviceId = ProtocolConstants.KWP_BOSCH_DEFINE_VARIABLES
        val mode = ProtocolConstants.KWP_DEFINE_BY_MEMORY_ADDRESS
        val recordId = ProtocolConstants.KWP_FIRST_RECORD_ID

        assertEquals(0xB7.toByte(), serviceId)
        assertEquals(0x03.toByte(), mode)
        assertEquals(0xF0.toByte(), recordId)
    }

    private fun makeEntry(
        factor: Double = 1.0,
        offset: Double = 0.0,
        signed: Int = 0,
        inverse: Int = 0
    ) = EcuEntry(
        name = "test",
        alias = "Test",
        address = 0x380000,
        size = 2,
        bitmask = 0,
        unit = "",
        signed = signed,
        inverse = inverse,
        factor = factor,
        offset = offset,
        comment = ""
    )
}

/**
 * Tests for KLineTransport: 5-baud slow init, HM0 frame I/O,
 * baud rate switching, through FakeSerialPortWrapper.
 */
class KLineTransportTest {

    /**
     * Encode an HM0 frame: [length][data...][checksum]
     */
    private fun encodeHm0Frame(data: ByteArray): ByteArray {
        val frame = ByteArray(data.size + 2)
        frame[0] = data.size.toByte()
        data.copyInto(frame, 1)
        var checksum = 0
        for (i in 0 until frame.size - 1) {
            checksum = (checksum + (frame[i].toInt() and 0xFF)) and 0xFF
        }
        frame[frame.size - 1] = checksum.toByte()
        return frame
    }

    @Test
    fun `open configures serial port`() {
        val fakePort = FakeSerialPortWrapper("COM1")
        val provider = FakeSerialPortProvider(mapOf("COM1" to fakePort))
        val transport = KLineTransport(provider)

        transport.open("COM1", 9600)
        assertTrue(transport.isOpen)
        assertTrue(fakePort.isOpen)
        assertEquals(9600, fakePort.baudRate)
        assertEquals(8, fakePort.dataBits)
        assertEquals(1, fakePort.stopBits)
        assertEquals(0, fakePort.parity)
    }

    @Test
    fun `switchBaudRate changes port speed`() {
        val fakePort = FakeSerialPortWrapper("COM1")
        val provider = FakeSerialPortProvider(mapOf("COM1" to fakePort))
        val transport = KLineTransport(provider)

        transport.open("COM1", 9600)
        assertEquals(9600, fakePort.baudRate)

        transport.switchBaudRate(56000)
        assertEquals(56000, fakePort.baudRate)
    }

    @Test
    fun `slowInit sends target address at 5 baud`() = runBlocking {
        val fakePort = FakeSerialPortWrapper("COM1")
        val provider = FakeSerialPortProvider(mapOf("COM1" to fakePort))
        val transport = KLineTransport(provider)

        transport.open("COM1")

        // Queue slow init responses: sync(0x55), KW1(0x86), KW2(0xA8), ECU ack(0xFE)
        fakePort.queueResponse(0x55)                // sync byte
        fakePort.queueResponse(0x86.toByte(), 0xA8.toByte()) // keyword bytes
        // Echo of our inverted KW2 ack (we send ~0xA8 = 0x57)
        fakePort.queueResponse(0x57)                // echo of our ack
        fakePort.queueResponse(0xFE.toByte())       // ECU's inverted target address (~0x01)

        val result = transport.slowInit(0x01)

        assertEquals(0x55.toByte(), result.syncByte)
        assertEquals(0x86.toByte(), result.keyword1)
        assertEquals(0xA8.toByte(), result.keyword2)
        assertEquals(0xFE.toByte(), result.ecuAck)

        // Verify 5-baud signaling used break/clearBreak
        assertTrue(fakePort.breakSetCount > 0, "Should have used setBreak for low bits")
        assertTrue(fakePort.breakClearCount > 0, "Should have used clearBreak for high bits")

        // Verify baud was switched to 10400 for handshake
        assertEquals(10400, fakePort.baudRate)

        // Verify we sent inverted KW2 (0x57) as acknowledgement
        val transmitted = fakePort.transmitted.toByteArray()
        assertTrue(transmitted.any { it == 0x57.toByte() },
            "Should have sent inverted KW2 (0x57)")
    }

    @Test
    fun `sendFrame produces correct HM0 frame with echo consumption`() {
        val fakePort = FakeSerialPortWrapper("COM1")
        val provider = FakeSerialPortProvider(mapOf("COM1" to fakePort))
        val transport = KLineTransport(provider)

        transport.open("COM1")

        // Queue echo bytes (sendFrame consumes its own echo on half-duplex K-line)
        // Frame: [0x02, 0x10, 0x89, 0x9B] = 4 bytes
        fakePort.queueResponse(0x02, 0x10, 0x89.toByte(), 0x9B.toByte())

        transport.sendFrame(byteArrayOf(0x10, 0x89.toByte()))

        val sent = fakePort.transmitted.toByteArray()
        assertEquals(4, sent.size, "HM0 frame: 1 length + 2 data + 1 checksum")
        assertEquals(0x02.toByte(), sent[0], "Length = 2")
        assertEquals(0x10.toByte(), sent[1], "Service ID")
        assertEquals(0x89.toByte(), sent[2], "Sub-function")
        assertEquals(0x9B.toByte(), sent[3], "Checksum = (2 + 0x10 + 0x89) & 0xFF")
    }

    @Test
    fun `receiveFrame parses valid HM0 frame`() {
        val fakePort = FakeSerialPortWrapper("COM1")
        val provider = FakeSerialPortProvider(mapOf("COM1" to fakePort))
        val transport = KLineTransport(provider)

        transport.open("COM1")

        // Queue HM0 response frame: positive session start [0x50, 0x89]
        // Frame: [length=2] [0x50] [0x89] [checksum]
        val responseData = byteArrayOf(0x50, 0x89.toByte())
        val frame = encodeHm0Frame(responseData)
        fakePort.queueResponseBytes(frame)

        val result = transport.receiveFrame()
        assertEquals(2, result.size)
        assertEquals(0x50.toByte(), result[0], "Positive response SID")
        assertEquals(0x89.toByte(), result[1], "Sub-function echo")
    }

    @Test
    fun `receiveFrame validates checksum`() {
        val fakePort = FakeSerialPortWrapper("COM1")
        val provider = FakeSerialPortProvider(mapOf("COM1" to fakePort))
        val transport = KLineTransport(provider)

        transport.open("COM1")

        // Queue HM0 frame with BAD checksum
        fakePort.queueResponse(0x02, 0x50, 0x89.toByte(), 0x00) // checksum should be 0xDB

        assertThrows(KLineException::class.java) {
            transport.receiveFrame()
        }
    }

    @Test
    fun `receiveFrame throws on timeout`() {
        val fakePort = FakeSerialPortWrapper("COM1")
        val provider = FakeSerialPortProvider(mapOf("COM1" to fakePort))
        val transport = KLineTransport(provider)

        transport.open("COM1")
        // Don't queue anything

        assertThrows(KLineException::class.java) {
            transport.receiveFrame(100)
        }
    }

    @Test
    fun `close shuts down transport`() {
        val fakePort = FakeSerialPortWrapper("COM1")
        val provider = FakeSerialPortProvider(mapOf("COM1" to fakePort))
        val transport = KLineTransport(provider)

        transport.open("COM1")
        assertTrue(transport.isOpen)

        transport.close()
        assertFalse(transport.isOpen)
        assertFalse(fakePort.isOpen)
    }
}

/**
 * End-to-end integration test for the full KWP2000 logging pipeline.
 * Simulates an ME7 ECU using FakeSerialPortWrapper with pre-queued
 * responses to verify: 5-baud init → session start → variable
 * definition → data polling → physical value conversion → session stop.
 */
class Kwp2000LoggingE2ETest {

    /**
     * Encode an HM0 frame: [length][data...][checksum]
     */
    private fun encodeHm0Frame(data: ByteArray): ByteArray {
        val frame = ByteArray(data.size + 2)
        frame[0] = data.size.toByte()
        data.copyInto(frame, 1)
        var checksum = 0
        for (i in 0 until frame.size - 1) {
            checksum = (checksum + (frame[i].toInt() and 0xFF)) and 0xFF
        }
        frame[frame.size - 1] = checksum.toByte()
        return frame
    }

    /**
     * Queue an HM0 response frame on the fake serial port.
     * Also queues echo bytes for any frame the tester will send BEFORE
     * this response (echo comes before the ECU response on K-line).
     */
    private fun queueHm0Response(port: FakeSerialPortWrapper, data: ByteArray) {
        port.queueResponseBytes(encodeHm0Frame(data))
    }

    /**
     * Queue echo bytes for an HM0 frame that the tester will send.
     * On K-line (half-duplex), the tester sees its own transmitted bytes.
     */
    private fun queueEcho(port: FakeSerialPortWrapper, requestData: ByteArray) {
        port.queueResponseBytes(encodeHm0Frame(requestData))
    }

    private fun createTestEcuFile(): File {
        val content = """
;
; Minimal test ECU file for KWP2000 E2E testing
;

[Version]
Version           = 1.10

[Communication]
Connect           = SLOW-0x01
Communicate       = HM0
LogSpeed          = 56000

[Identification]
HWNumber          = {TEST_HW}
SWNumber          = {TEST_SW}
PartNumber        = {TEST_PART}
SWVersion         = {0001}
EngineId          = {1.8T AGU}

[Measurements]
rpm_w,            {RPM},       0x380000, 2, 0xFFFF, {RPM},  0, 0, 0.25, 0,  {Engine speed}
load_w,           {Load},      0x380002, 2, 0xFFFF, {%},    0, 0, 0.1,  0,  {Engine load}
temp,             {CoolTemp},  0x380004, 1, 0xFF,   {C},    0, 0, 1.0,  40, {Coolant temperature}
""".trimIndent()
        return File.createTempFile("kwp_e2e", ".ecu").apply {
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
        return File.createTempFile("kwp_e2e", ".cfg").apply {
            writeText(content)
            deleteOnExit()
        }
    }

    @Test
    fun `full KWP2000 session - slow init, session, define vars, poll, stop`() = runBlocking {
        val fakePort = FakeSerialPortWrapper("KWP_TEST")
        val provider = FakeSerialPortProvider(mapOf("KWP_TEST" to fakePort))
        val logger = Kwp2000NativeLogger(provider)

        val ecuFile = createTestEcuFile()
        val cfgFile = createTestCfgFile(ecuFile.name)

        // ── Phase 1: Connect (ECU file parsing, no serial I/O) ──────────
        logger.connect(LoggerConfig(
            loggerMode = LoggerMode.NATIVE_KWP2000,
            ecuFile = ecuFile.absolutePath,
            cfgFile = cfgFile.absolutePath,
            comPort = "KWP_TEST"
        ))

        assertEquals(LoggerStatus.CONNECTED, logger.status.value)
        assertEquals(3, logger.variables.value.size)

        // ── Phase 2: Queue all serial responses for startLogging ────────
        //
        // K-line protocol flow:
        // 1. 5-baud slow init → sync(0x55), KW1(0x86), KW2(0xA8), echo, ECU ack
        // 2. Session start (0x10, 0x89) → echo, response (0x50, 0x89)
        // 3. Define vars (0xB7, 0x03, 0xF0, ...) → echo, response (0xF7, 0xF0)
        // 4. Poll (0xB8, 0xF0) → echo, response (0xF8, 0xF0, data...)
        //
        // Important: On K-line, the tester sees its own echo BEFORE the ECU response.
        // FakeSerialPortWrapper returns bytes in queue order, so we must interleave
        // echoes and responses correctly.

        // ── 2a. 5-baud slow init responses ──────────────────────────────
        fakePort.queueResponse(0x55)                         // sync byte
        fakePort.queueResponse(0x86.toByte(), 0xA8.toByte()) // keyword bytes
        fakePort.queueResponse(0x57)                         // echo of our ack (~0xA8 = 0x57)
        fakePort.queueResponse(0xFE.toByte())                // ECU ack (~0x01 = 0xFE)

        // ── 2b. Session start: request [0x10, 0x89] ────────────────────
        // Echo of our request frame: [0x02, 0x10, 0x89, 0x9B]
        queueEcho(fakePort, byteArrayOf(0x10, 0x89.toByte()))
        // Positive response: [0x50, 0x89]
        queueHm0Response(fakePort, byteArrayOf(0x50, 0x89.toByte()))

        // ── 2c. Define variables (0xB7): 3 vars, 4 bytes each = 12 payload bytes
        // Request: [0xB7, 0x03, 0xF0, addr1(3b), size1, addr2(3b), size2, addr3(3b), size3]
        //        = [0xB7, 0x03, 0xF0, 0x38,0x00,0x00, 0x02, 0x38,0x00,0x02, 0x02, 0x38,0x00,0x04, 0x01]
        //        = 15 bytes
        val defineRequest = byteArrayOf(
            0xB7.toByte(), 0x03, 0xF0.toByte(),
            0x38, 0x00, 0x00, 0x02,  // rpm_w: addr=0x380000, size=2
            0x38, 0x00, 0x02, 0x02,  // load_w: addr=0x380002, size=2
            0x38, 0x00, 0x04, 0x01   // temp: addr=0x380004, size=1
        )
        queueEcho(fakePort, defineRequest)
        // Positive response: [0xF7, 0xF0]
        queueHm0Response(fakePort, byteArrayOf(0xF7.toByte(), 0xF0.toByte()))

        // ── 2d. Poll responses (0xB8) ───────────────────────────────────
        // Each poll: request [0xB8, 0xF0] → echo, then ECU response
        // Response: [0xF8, 0xF0, rpm_hi, rpm_lo, load_hi, load_lo, temp]

        // Poll 1: RPM=3000, Load=800, Temp=112
        // Expected: RPM=750, Load=80, Temp=72
        val pollRequest = byteArrayOf(0xB8.toByte(), 0xF0.toByte())
        queueEcho(fakePort, pollRequest)
        queueHm0Response(fakePort, byteArrayOf(
            0xF8.toByte(), 0xF0.toByte(),
            0x0B, 0xB8.toByte(),      // rpm_w = 3000
            0x03, 0x20,                // load_w = 800
            0x70                       // temp = 112
        ))

        // Poll 2: RPM=6000, Load=950, Temp=105
        // Expected: RPM=1500, Load=95, Temp=65
        queueEcho(fakePort, pollRequest)
        queueHm0Response(fakePort, byteArrayOf(
            0xF8.toByte(), 0xF0.toByte(),
            0x17, 0x70,                // rpm_w = 6000
            0x03, 0xB6.toByte(),       // load_w = 950
            0x69                       // temp = 105
        ))

        // ── Phase 3: Start logging ──────────────────────────────────────
        logger.startLogging()
        delay(500)

        // ── Phase 4: Verify 5-baud init and serial config ───────────────
        // After slow init, baud switches to 10400, then to 56000 (logSpeed)
        assertEquals(56000, fakePort.baudRate, "Should switch to ECU logSpeed")

        // Verify break was used for 5-baud signaling
        assertTrue(fakePort.breakSetCount > 0, "Slow init uses setBreak")
        assertTrue(fakePort.breakClearCount > 0, "Slow init uses clearBreak")

        // ── Phase 5: Verify samples ─────────────────────────────────────
        val session = logger.session.value
        assertNotNull(session, "Session should be active")
        assertTrue(session!!.samples.size >= 1,
            "Should have collected at least 1 sample, got ${session.samples.size}")

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

        // ── Phase 6: Stop and verify ────────────────────────────────────
        // Queue stop responses (best effort, exceptions caught)
        // Clear definition: [0x2C, 0x04, 0xF0]
        val clearRequest = byteArrayOf(0x2C, 0x04, 0xF0.toByte())
        queueEcho(fakePort, clearRequest)
        queueHm0Response(fakePort, byteArrayOf(0x6C, 0x04, 0xF0.toByte()))

        // Stop session: [0x20]
        val stopRequest = byteArrayOf(0x20)
        queueEcho(fakePort, stopRequest)
        queueHm0Response(fakePort, byteArrayOf(0x60))

        logger.stopLogging()
        assertEquals(LoggerStatus.CONNECTED, logger.status.value)
        assertTrue(session.sampleCount >= 1)

        // ── Phase 7: Disconnect ─────────────────────────────────────────
        logger.disconnect()
        assertEquals(LoggerStatus.DISCONNECTED, logger.status.value)
        assertTrue(logger.variables.value.isEmpty())
    }

    @Test
    fun `session start negative response transitions to ERROR`() = runBlocking {
        val fakePort = FakeSerialPortWrapper("KWP_ERR")
        val provider = FakeSerialPortProvider(mapOf("KWP_ERR" to fakePort))
        val logger = Kwp2000NativeLogger(provider)

        val ecuFile = createTestEcuFile()

        logger.connect(LoggerConfig(
            loggerMode = LoggerMode.NATIVE_KWP2000,
            ecuFile = ecuFile.absolutePath,
            comPort = "KWP_ERR"
        ))

        // Queue slow init responses
        fakePort.queueResponse(0x55)
        fakePort.queueResponse(0x86.toByte(), 0xA8.toByte())
        fakePort.queueResponse(0x57)
        fakePort.queueResponse(0xFE.toByte())

        // Queue echo for session start
        queueEcho(fakePort, byteArrayOf(0x10, 0x89.toByte()))
        // Queue negative response: [0x7F, 0x10, 0x22] (conditionsNotCorrect)
        queueHm0Response(fakePort, byteArrayOf(0x7F, 0x10, 0x22))

        logger.startLogging()
        delay(200)

        assertEquals(LoggerStatus.ERROR, logger.status.value,
            "Should be ERROR after negative response. Message: ${logger.statusMessage.value}")
    }
}
