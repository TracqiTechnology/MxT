package data.fixtures

import data.parser.a2l.A2lParser
import data.generator.A2lToXdfGenerator
import data.parser.xdf.XdfParser
import org.junit.jupiter.api.Tag
import java.io.File
import kotlin.test.*

@Tag("contributed-fixture")
class ContributedA2lTest {
    @Test
    fun `issue 93 A2Ls parse and round trip definitions`() {
        val files = ContributedFixtures.paths.filter { it.startsWith("med9/") }.map(ContributedFixtures::file)
        assertEquals(5, files.size)
        for (file in files) {
            if (file.name.startsWith("D915C")) {
                assertFailsWith<IllegalArgumentException> { A2lParser.parse(file) }
                val kp = data.parser.kp.KpHintParser.parseFileFull(file)
                println("MED9 KP hints=${kp.first.size}, definitions=${kp.second.size}")
                assertTrue(kp.first.size > 10000)
                assertTrue(kp.second.size > 7000)
                continue
            }
            val parsed = A2lParser.parse(file)
            val defs = parsed.toTableDefinitions()
            assertEquals(parsed.characteristics.count { it.type in setOf("VALUE", "CURVE", "MAP") }, defs.size,
                "Every supported characteristic must produce a definition")
            println("${file.name}: measurements=${parsed.measurements.size}, characteristics=${parsed.characteristics.size}, definitions=${defs.size}")
            val (_, roundTrip) = XdfParser.parseToList(A2lToXdfGenerator.generate(defs).byteInputStream())
            assertEquals(defs.size, roundTrip.size, file.name)
            println("${file.name}: out of 2MB bounds=${defs.count { it.zAxis.address < 0 || it.zAxis.address >= 2097152 }}")
            val expected = when {
                file.name.startsWith("D9156") -> Triple(14757, 8857, 7540)
                file.name.startsWith("D915A_41") -> Triple(14651, 8818, 7485)
                file.name.startsWith("D915A_N43") -> Triple(15204, 9430, 8051)
                else -> Triple(16617, 10332, 8774)
            }
            assertEquals(expected, Triple(parsed.measurements.size, parsed.characteristics.size, defs.size), file.name)
            defs.sortedBy { it.toString() }.zip(roundTrip).forEach { (original, restored) ->
                assertEquals(original.tableName, restored.tableName)
                for ((axis, copy) in listOf(original.xAxis to restored.xAxis, original.yAxis to restored.yAxis, original.zAxis to restored.zAxis)) {
                    if (axis == null) { assertNull(copy); continue }
                    assertNotNull(copy)
                    assertEquals(axis.address, copy.address)
                    assertEquals(axis.sizeBits, copy.sizeBits)
                    assertEquals(axis.indexCount, copy.indexCount)
                    assertEquals(axis.unit.ifBlank { "-" }, copy.unit)
                    assertEquals(axis.rowCount, copy.rowCount)
                    assertEquals(axis.columnCount, copy.columnCount)
                    assertEquals(axis.equation, copy.equation)
                    assertEquals(axis.lsbFirst, copy.lsbFirst)
                    assertEquals(axis.isSigned, copy.isSigned)
                    assertEquals(axis.isColumnMajor, copy.isColumnMajor)
                    assertTrue(axis.address >= 0 && axis.address.toLong() + maxOf(1, axis.rowCount) * maxOf(1, axis.columnCount) * (axis.sizeBits / 8) <= 2097152, original.tableName)
                }
            }
        }
    }
}
