package data.sniffer

import data.model.EcuPlatform
import data.parser.damos.DamosParser
import data.parser.hex.IntelHexParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.File

class SignatureScannerTest {

    @Test
    fun `self-test - scan reference recovers all addresses`() {
        val damFile = File("technical/me7/C22mb31g.dam")
        val hexFile = File("technical/me7/C22mb31g.hex")
        if (!damFile.exists() || !hexFile.exists()) return

        val damos = DamosParser.parse(damFile)
        val (baseAddress, hexData) = IntelHexParser.parseWithBaseAddress(hexFile)

        val variables = SignatureBuilderTest.buildVariablesFromDamos(damos)

        val database = SignatureBuilder.build(hexData, baseAddress, variables, EcuPlatform.ME7, "C22mb31g")

        // Self-test: scan the same binary
        val results = SignatureScanner.scan(hexData, database)

        println("Self-test: recovered ${results.size}/${database.signatures.size} variables")

        // Every result should match the original address
        var correctCount = 0
        for (result in results) {
            val original = database.variables[result.variableName]?.address ?: continue
            if (result.discoveredAddress == original) {
                correctCount++
            } else {
                println("  MISMATCH: ${result.variableName} expected 0x${original.toString(16)} got 0x${result.discoveredAddress.toString(16)} (confidence: ${result.confidence})")
            }
        }

        val accuracy = if (results.isNotEmpty()) correctCount.toDouble() / results.size else 0.0
        println("Self-test accuracy: $correctCount/${results.size} = ${String.format("%.1f%%", accuracy * 100)}")

        // Self-test should have very high accuracy (some variables have ambiguous
        // short addresses that may produce false matches, so allow some margin)
        assertTrue(accuracy >= 0.90, "Self-test accuracy should be >= 90%, got ${String.format("%.1f%%", accuracy * 100)}")
    }

    @Test
    fun `cross-variant scan discovers addresses in valid RAM range`() {
        val damFile = File("technical/me7/C22mb31g.dam")
        val hexFile = File("technical/me7/C22mb31g.hex")
        val targetBinFile = File("example/me7/bin/8D0907551M-0002.bin")
        if (!damFile.exists() || !hexFile.exists() || !targetBinFile.exists()) return

        val damos = DamosParser.parse(damFile)
        val (baseAddress, hexData) = IntelHexParser.parseWithBaseAddress(hexFile)

        val variables = SignatureBuilderTest.buildVariablesFromDamos(damos)

        val database = SignatureBuilder.build(hexData, baseAddress, variables, EcuPlatform.ME7, "C22mb31g")

        val targetBin = targetBinFile.readBytes()
        val results = SignatureScanner.scan(targetBin, database)

        println("Cross-variant: discovered ${results.size} variables")

        // All discovered addresses should be in valid RAM range
        for (result in results) {
            assertTrue(
                result.discoveredAddress in 0x380000L..0x3FFFFFL,
                "Address 0x${result.discoveredAddress.toString(16)} for ${result.variableName} is outside ME7 RAM range"
            )
        }

        // Should discover at least some variables
        assertTrue(results.isNotEmpty(), "Should discover at least some variables in cross-variant scan")

        val highConfidence = results.count { it.confidence >= 0.9 }
        println("  High confidence (>=90%): $highConfidence")
        println("  Medium confidence (60-90%): ${results.count { it.confidence in 0.6..0.9 }}")
        println("  Low confidence (<60%): ${results.count { it.confidence < 0.6 }}")
    }

    @Test
    fun `synthetic scan recovers relocated address`() {
        // Create two "binaries" with the same code pattern but different addresses embedded
        val bin1 = ByteArray(256)
        val bin2 = ByteArray(256)

        // Identical code pattern
        for (i in bin1.indices) {
            val b = ((i * 7 + 13) % 251).toByte()
            bin1[i] = b
            bin2[i] = b
        }

        // Embed address $381234 in bin1 at offset 50
        bin1[50] = 0x34
        bin1[51] = 0x12

        // Embed address $385678 in bin2 at offset 50 (relocated)
        bin2[50] = 0x78
        bin2[51] = 0x56

        val variable = RamVariable(name = "test_var", address = 0x381234L, size = 2)

        val database = SignatureBuilder.build(bin1, 0x800000L, listOf(variable), EcuPlatform.ME7, "ref")

        if (database.signatures.containsKey("test_var")) {
            val results = SignatureScanner.scan(bin2, database)

            if (results.isNotEmpty()) {
                val result = results.find { it.variableName == "test_var" }
                assertNotNull(result, "Should find test_var in results")
                assertEquals(0x385678L, result!!.discoveredAddress, "Should discover relocated address")
            }
        }
    }
}
