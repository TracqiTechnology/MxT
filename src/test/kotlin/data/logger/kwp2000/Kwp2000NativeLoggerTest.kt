package data.logger.kwp2000

import data.logger.LoggerConfig
import data.logger.LoggerMode
import data.logger.LoggerStatus
import data.logger.protocol.FakeSerialPortProvider
import data.logger.protocol.FakeSerialPortWrapper
import data.logger.protocol.ProtocolConstants
import data.parser.a2l.EcuEntry
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

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
    fun `connect with valid ecu file`() = kotlinx.coroutines.runBlocking {
        val fakePort = FakeSerialPortWrapper("COM3")
        val provider = FakeSerialPortProvider(mapOf("COM3" to fakePort))
        val logger = Kwp2000NativeLogger(provider)

        val config = LoggerConfig(
            loggerMode = LoggerMode.NATIVE_KWP2000,
            ecuFile = "technical/med9/me7logger/MED9_0261S02469.ecu",
            cfgFile = "technical/med9/me7logger/MED9_0261S02469_basic.cfg",
            comPort = "COM3"
        )

        logger.connect(config)
        assertEquals(LoggerStatus.CONNECTED, logger.status.value)
        assertTrue(logger.variables.value.isNotEmpty())
    }

    @Test
    fun `connect with missing ecu file errors`() = kotlinx.coroutines.runBlocking {
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
    fun `connect resolves cfg variables against ecu entries`() = kotlinx.coroutines.runBlocking {
        val provider = FakeSerialPortProvider()
        val logger = Kwp2000NativeLogger(provider)

        val config = LoggerConfig(
            loggerMode = LoggerMode.NATIVE_KWP2000,
            ecuFile = "technical/med9/me7logger/MED9_0261S02469.ecu",
            cfgFile = "technical/med9/me7logger/MED9_0261S02469_basic.cfg",
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
