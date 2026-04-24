package data.sniffer

import data.model.EcuPlatform
import data.parser.damos.DamosParser
import data.parser.hex.IntelHexParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.File

class SignatureBuilderTest {

    @Test
    fun `encodeAddress C167 strips to 16-bit DPP offset`() {
        // $381E3E → offset $1E3E → bytes 0x3E, 0x1E (little-endian)
        val encoded = SignatureBuilder.encodeAddress(0x381E3EL, EcuPlatform.ME7)
        assertEquals(2, encoded.size)
        assertEquals(0x3E.toByte(), encoded[0])
        assertEquals(0x1E.toByte(), encoded[1])
    }

    @Test
    fun `encodeAddress TriCore uses full 32-bit`() {
        // $7FC215 → bytes 0x15, 0xC2, 0x7F, 0x00 (little-endian)
        val encoded = SignatureBuilder.encodeAddress(0x7FC215L, EcuPlatform.MED9)
        assertEquals(4, encoded.size)
        assertEquals(0x15.toByte(), encoded[0])
        assertEquals(0xC2.toByte(), encoded[1])
        assertEquals(0x7F.toByte(), encoded[2])
        assertEquals(0x00.toByte(), encoded[3])
    }

    @Test
    fun `decodeAddress C167 round-trips`() {
        val original = 0x381E3EL
        val encoded = SignatureBuilder.encodeAddress(original, EcuPlatform.ME7)
        val decoded = SignatureBuilder.decodeAddress(encoded, EcuPlatform.ME7)
        assertEquals(original, decoded)
    }

    @Test
    fun `decodeAddress TriCore round-trips`() {
        val original = 0x7FC215L
        val encoded = SignatureBuilder.encodeAddress(original, EcuPlatform.MED9)
        val decoded = SignatureBuilder.decodeAddress(encoded, EcuPlatform.MED9)
        assertEquals(original, decoded)
    }

    @Test
    fun `build from synthetic binary finds embedded address`() {
        // Create a small "binary" with a known address pattern embedded in "code"
        // Address $381234 → encoded as 0x34, 0x12
        val bin = ByteArray(256)
        // Fill with some pattern
        for (i in bin.indices) bin[i] = (i % 251).toByte() // pseudo-random fill

        // Embed address at offset 50
        bin[50] = 0x34
        bin[51] = 0x12

        val variable = RamVariable(
            name = "test_var",
            address = 0x381234L,
            size = 2
        )

        // Use a platform with code range covering 0..255
        val platform = EcuPlatform.ME7

        // Build with base address 0x800000 so offset 50 maps to 0x800032 (in CODE1 range)
        val database = SignatureBuilder.build(bin, 0x800000L, listOf(variable), platform, "test")

        assertTrue(database.signatures.containsKey("test_var"), "Should have found signatures for test_var")
        val sigs = database.signatures["test_var"]!!
        assertTrue(sigs.isNotEmpty(), "Should have at least one signature")

        // Verify the signature has wildcards at the address position
        val sig = sigs[0]
        assertEquals(2, sig.addressByteIndices.size)
        for (idx in sig.addressByteIndices) {
            assertEquals(0x00.toByte(), sig.mask[idx], "Address byte at index $idx should be wildcard")
        }
    }

    @Test
    fun `build from C22mb31g hex + DAMOS produces signatures`() {
        val damFile = File("technical/me7/C22mb31g.dam")
        val hexFile = File("technical/me7/C22mb31g.hex")
        if (!damFile.exists() || !hexFile.exists()) return

        val damos = DamosParser.parse(damFile)
        val (baseAddress, hexData) = IntelHexParser.parseWithBaseAddress(hexFile)

        val variables = buildVariablesFromDamos(damos)

        val database = SignatureBuilder.build(hexData, baseAddress, variables, EcuPlatform.ME7, "C22mb31g")

        // Should have signatures for a meaningful fraction of variables
        assertTrue(database.signatures.isNotEmpty(), "Should have found some signatures")
        println("Built signatures for ${database.signatures.size}/${variables.size} variables")

        // Variables in $38xxxx range should have a reasonable hit rate
        val inRangeVars = variables.count { it.address in 0x380000..0x3FFFFF }
        val inRangeSigs = database.signatures.keys.count { name ->
            variables.find { it.name == name }?.address?.let { it in 0x380000L..0x3FFFFFL } == true
        }
        assertTrue(inRangeSigs > inRangeVars / 4, "Expected at least 25% of in-range variables to have signatures")
    }

    companion object {
        /** Build variable list from both UMP and SRC records. */
        fun buildVariablesFromDamos(damos: data.parser.damos.DamosFile): List<RamVariable> {
            val variableMap = mutableMapOf<String, RamVariable>()
            for (ump in damos.umpRecords.values) {
                val reg = damos.regRecords[ump.regId]
                variableMap[ump.name] = RamVariable(
                    name = ump.name,
                    address = ump.address,
                    size = ump.sizeBytes,
                    unit = reg?.unit ?: "",
                    factor = if (reg != null && reg.divisor != 0.0) reg.factor / reg.divisor else 1.0,
                    offset = reg?.offset ?: 0.0,
                    signed = ump.isSigned,
                    description = ump.description
                )
            }
            for (src in damos.srcRecords.values) {
                val reg = damos.regRecords[src.typeId]
                variableMap[src.name] = RamVariable(
                    name = src.name,
                    address = src.address,
                    size = src.byteWidth,
                    unit = reg?.unit ?: "",
                    factor = if (reg != null && reg.divisor != 0.0) reg.factor / reg.divisor else 1.0,
                    offset = reg?.offset ?: 0.0,
                    signed = src.signed < 0 || src.signed == 1,
                    description = src.description
                )
            }
            return variableMap.values.toList()
        }
    }
}
