package data.parser.ecu

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

class EcuFileParserTest {

    private val fixtureFile = File("example/med9/MED9_0261S02469.ecu")

    @Test
    fun `parse version section`() {
        val ecu = EcuFileParser.parse(fixtureFile)
        assertEquals("1.10", ecu.version)
    }

    @Test
    fun `parse communication section`() {
        val ecu = EcuFileParser.parse(fixtureFile)
        assertEquals("SLOW-0x01", ecu.connectMethod)
        assertEquals("HM0", ecu.communicateMode)
        assertEquals(56000, ecu.logSpeed)
    }

    @Test
    fun `parse identification section`() {
        val ecu = EcuFileParser.parse(fixtureFile)
        assertEquals("1K0907115S", ecu.hwNumber)
        assertEquals("0261S02469", ecu.swNumber)
        assertEquals("1K0907115S", ecu.partNumber)
        assertEquals("0001", ecu.swVersion)
        assertEquals("2.0L R4/4V TFSI", ecu.engineId)
    }

    @Test
    fun `parse measurements section has entries`() {
        val ecu = EcuFileParser.parse(fixtureFile)
        assertTrue(ecu.entries.isNotEmpty(), "Should have measurement entries")
        assertTrue(ecu.entries.size > 100, "Fixture has thousands of entries, got ${ecu.entries.size}")
    }

    @Test
    fun `parse measurement entry fields`() {
        val ecu = EcuFileParser.parse(fixtureFile)
        val entry = ecu.entries["nmot_w"]
        assertNotNull(entry, "nmot_w should exist in MED9 .ecu file")
        entry!!

        assertTrue(entry.address > 0, "Address should be non-zero")
        assertTrue(entry.size == 1 || entry.size == 2, "Size should be 1 or 2")
        assertTrue(entry.factor > 0, "Factor should be positive")
    }

    @Test
    fun `parse unsigned entry`() {
        val ecu = EcuFileParser.parse(fixtureFile)
        // B_adcc_um is unsigned (S=0, I=0)
        val entry = ecu.entries["B_adcc_um"]
        assertNotNull(entry)
        assertEquals(0, entry!!.signed)
        assertEquals(0, entry.inverse)
        assertEquals(0x7F8104, entry.address)
        assertEquals(1, entry.size)
    }

    @Test
    fun `parse signed entry`() {
        val ecu = EcuFileParser.parse(fixtureFile)
        // zw1_um is signed (S=1)
        val entry = ecu.entries["zw1_um"]
        assertNotNull(entry, "zw1_um should exist")
        assertEquals(1, entry!!.signed)
        assertEquals(0, entry.inverse)
        assertEquals(0.75, entry.factor)
    }

    @Test
    fun `parse inverse entry`() {
        val ecu = EcuFileParser.parse(fixtureFile)
        // tnwsbge is inverse (I=1)
        val entry = ecu.entries["tnwsbge"]
        assertNotNull(entry, "tnwsbge should exist")
        assertEquals(0, entry!!.signed)
        assertEquals(1, entry.inverse)
        assertEquals(2.55, entry.factor)
    }

    @Test
    fun `parse word-size entry`() {
        val ecu = EcuFileParser.parse(fixtureFile)
        // lsu_c_um is size=2 (word)
        val entry = ecu.entries["lsu_c_um"]
        assertNotNull(entry)
        assertEquals(2, entry!!.size)
        assertEquals(0.04, entry.factor, 0.001)
    }

    @Test
    fun `parse entry with unit`() {
        val ecu = EcuFileParser.parse(fixtureFile)
        val entry = ecu.entries["adcc_c_um"]
        assertNotNull(entry)
        assertEquals("ms", entry!!.unit)
        assertEquals(20.0, entry.factor)
    }

    @Test
    fun `parse entry with negative offset`() {
        val ecu = EcuFileParser.parse(fixtureFile)
        val entry = ecu.entries["ausg_c_um"]
        assertNotNull(entry)
        assertEquals(-1.0, entry!!.offset)
    }

    @Test
    fun `parse measurement line directly`() {
        val line = "nmot_test, {EngineSpeed}, 0x380010, 2, 0x0000, {1/min}, 0, 0, 0.25, 0, {Engine speed}"
        val entry = EcuFileParser.parseMeasurementLine(line)
        assertNotNull(entry)
        assertEquals("nmot_test", entry!!.name)
        assertEquals("EngineSpeed", entry.alias)
        assertEquals(0x380010, entry.address)
        assertEquals(2, entry.size)
        assertEquals(0, entry.bitmask)
        assertEquals("1/min", entry.unit)
        assertEquals(0, entry.signed)
        assertEquals(0, entry.inverse)
        assertEquals(0.25, entry.factor)
        assertEquals(0.0, entry.offset)
        assertEquals("Engine speed", entry.comment)
    }

    @Test
    fun `parse from string`() {
        val text = """
            ; Test ECU file

            [Version]
            Version = 2.00

            [Communication]
            Connect      = SLOW-0x11
            Communicate  = HM0
            LogSpeed     = 125000

            [Identification]
            HWNumber     = {TestHW}
            SWNumber     = {TestSW}
            PartNumber   = {TestPN}
            SWVersion    = {0002}
            EngineId     = {Test Engine}

            [Measurements]
            ; Name, Alias, Address, Size, Bitmask, Unit, S, I, Factor, Offset, Comment
            rpm_w, {RPM}, 0x380010, 2, 0x0000, {1/min}, 0, 0, 0.25, 0, {Engine speed}
            temp, {CoolantTemp}, 0x380020, 1, 0x0000, {C}, 1, 0, 0.75, 48, {Coolant temperature}
        """.trimIndent()

        val ecu = EcuFileParser.parse(text)
        assertEquals("2.00", ecu.version)
        assertEquals("SLOW-0x11", ecu.connectMethod)
        assertEquals(125000, ecu.logSpeed)
        assertEquals("TestHW", ecu.hwNumber)
        assertEquals(2, ecu.entries.size)

        val rpm = ecu.entries["rpm_w"]!!
        assertEquals(0x380010, rpm.address)
        assertEquals(2, rpm.size)
        assertEquals(0.25, rpm.factor)

        val temp = ecu.entries["temp"]!!
        assertEquals(1, temp.signed)
        assertEquals(48.0, temp.offset)
    }
}
