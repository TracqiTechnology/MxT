package data.parser.hex

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.File

class IntelHexParserTest {

    @Test
    fun `parse synthetic Intel HEX`() {
        // Record format: :LLAAAATT[DD...]CC
        // 3 bytes at address 0x0000: 0x02, 0x00, 0x60
        //   sum = 03+00+00+00+02+00+60 = 65, checksum = 9B
        // 2 bytes at address 0x0003: 0xAB, 0xCD
        //   sum = 02+00+03+00+AB+CD = 17C, checksum = 84
        val hex = """
            :030000000200609B
            :02000300ABCD84
            :00000001FF
        """.trimIndent()

        val result = IntelHexParser.parse(hex)

        assertEquals(5, result.size)
        assertEquals(0x02.toByte(), result[0])
        assertEquals(0x00.toByte(), result[1])
        assertEquals(0x60.toByte(), result[2])
        assertEquals(0xAB.toByte(), result[3])
        assertEquals(0xCD.toByte(), result[4])
    }

    @Test
    fun `parse with extended linear address`() {
        // Extended Linear Address: 0x0080 → base = 0x00800000
        // Data: 3 bytes at offset 0x0000
        val hex = """
            :020000040080FA
            :03000000112233B7
            :00000001FF
        """.trimIndent()

        val (baseAddr, data) = IntelHexParser.parseWithBaseAddress(hex)

        assertEquals(0x00800000L, baseAddr)
        assertEquals(3, data.size)
        assertEquals(0x11.toByte(), data[0])
        assertEquals(0x22.toByte(), data[1])
        assertEquals(0x33.toByte(), data[2])
    }

    @Test
    fun `parse with extended segment address`() {
        // Extended Segment Address: 0x1000 → base = 0x10000
        // Data: 2 bytes at offset 0x0010
        val hex = """
            :020000021000EC
            :0200100041427B
            :00000001FF
        """.trimIndent()

        val (baseAddr, data) = IntelHexParser.parseWithBaseAddress(hex)

        assertEquals(0x10010L, baseAddr)
        assertEquals(2, data.size)
        assertEquals(0x41.toByte(), data[0])
        assertEquals(0x42.toByte(), data[1])
    }

    @Test
    fun `empty input returns empty array`() {
        val result = IntelHexParser.parse("")
        assertEquals(0, result.size)
    }

    @Test
    fun `parse real C22mb31g hex file`() {
        val hexFile = File("technical/me7/C22mb31g.hex")
        if (!hexFile.exists()) return

        val (baseAddr, data) = IntelHexParser.parseWithBaseAddress(hexFile)

        // ME7 flash starts at $800000
        assertEquals(0x800000L, baseAddr)

        // Should be ~1MB (ME7 flash image)
        assertTrue(data.size >= 512 * 1024, "Expected at least 512KB, got ${data.size}")
        assertTrue(data.size <= 2 * 1024 * 1024, "Expected at most 2MB, got ${data.size}")

        // Should not be all zeros or all 0xFF
        val nonZero = data.count { it != 0.toByte() }
        assertTrue(nonZero > 1000, "Data appears to be mostly zeros")
        val nonFF = data.count { it != 0xFF.toByte() }
        assertTrue(nonFF > 1000, "Data appears to be mostly 0xFF")
    }

    @Test
    fun `parse Bora ME7_5 hex file`() {
        val hexFile = File("technical/vag/hex/06A906012C_0002.hex")
        if (!hexFile.exists()) return

        val (baseAddr, data) = IntelHexParser.parseWithBaseAddress(hexFile)

        // ME7.5 flash should start in ME7 address range
        assertTrue(baseAddr >= 0x000000L, "Base address should be valid: 0x${baseAddr.toString(16)}")

        // ME7.5 29F400 is 512KB (4Mbit), ME7.5 29F800 is 1MB
        assertTrue(data.size >= 256 * 1024, "Expected at least 256KB, got ${data.size}")
        assertTrue(data.size <= 2 * 1024 * 1024, "Expected at most 2MB, got ${data.size}")

        // Should contain real data (not all zeros or 0xFF)
        val nonZero = data.count { it != 0.toByte() }
        assertTrue(nonZero > 1000, "Data appears to be mostly zeros")
        val nonFF = data.count { it != 0xFF.toByte() }
        assertTrue(nonFF > 1000, "Data appears to be mostly 0xFF")
    }

    @Test
    fun `Bora ME7_5 hex simple parse returns valid array`() {
        val hexFile = File("technical/vag/hex/06A906012C_0002.hex")
        if (!hexFile.exists()) return

        val data = IntelHexParser.parse(hexFile)

        assertTrue(data.isNotEmpty(), "Parsed data should not be empty")
        assertTrue(data.size >= 256 * 1024, "Expected at least 256KB, got ${data.size}")
    }
}
