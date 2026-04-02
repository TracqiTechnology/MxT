package data.logger.uds

import data.logger.LoggerConfig
import data.logger.LoggerMode
import data.logger.LoggerStatus
import data.logger.protocol.ProtocolConstants
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class UdsNativeLoggerTest {

    @Test
    fun `connect with valid ecu file`() = kotlinx.coroutines.runBlocking {
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
    fun `connect with missing ecu file errors`() = kotlinx.coroutines.runBlocking {
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
    fun `disconnect resets state`() = kotlinx.coroutines.runBlocking {
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
