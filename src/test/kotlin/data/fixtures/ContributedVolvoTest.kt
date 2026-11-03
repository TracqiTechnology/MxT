package data.fixtures

import data.parser.bin.BinParser
import data.parser.xdf.XdfParser
import data.writer.BinWriter
import domain.math.map.Map3d
import domain.model.mlhfm.MlhfmCurve
import org.junit.jupiter.api.Tag
import kotlin.test.*

@Tag("contributed-fixture")
class ContributedVolvoTest {
    @Test
    fun `Volvo signed horizontal MLHFM round trips but missing voltage labels block correction`() {
        val bin = ContributedFixtures.file("VVT16.bin")
        val (_, defs) = ContributedFixtures.file("2350WRHJ.xdf").inputStream().use(XdfParser::parseToList)
        val def = defs.single { it.tableName.startsWith("[MLHFM]") }
        val map = bin.inputStream().use { BinParser.parseToList(it, listOf(def)).single().second }
        assertEquals(129, map.xAxis.size)
        assertTrue(map.xAxis.all { it == 0.0 }, "Upstream XDF has empty voltage labels")
        assertEquals(1, map.zAxis.size)
        assertEquals(129, map.zAxis[0].size)
        assertEquals(-100.2, map.zAxis[0][0], 0.00001)
        assertEquals(2388.6, map.zAxis[0][128], 0.00001)
        assertFailsWith<IllegalArgumentException> { MlhfmCurve.toColumn(map) }
        val temp = kotlin.io.path.createTempFile("volvo-roundtrip", ".bin").toFile()
        try {
            bin.copyTo(temp, overwrite = true)
            val before = temp.readBytes()
            BinWriter.write(temp, def, map)
            assertTrue(before.contentEquals(temp.readBytes()), "Identity write must preserve all BIN bytes")
            val edited = Map3d(map).apply { zAxis[0][64] += 1.0 }
            BinWriter.write(temp, def, edited)
            val after = temp.readBytes()
            val changed = before.indices.filter { before[it] != after[it] }
            assertTrue(changed.isNotEmpty())
            assertTrue(changed.all { it in (def.zAxis.address + 128)..(def.zAxis.address + 129) })
            val reread = temp.inputStream().use { BinParser.parseToList(it, listOf(def)).single().second }
            assertEquals(edited.zAxis[0][64], reread.zAxis[0][64], 0.00001)
            assertEquals(map.zAxis[0][0], reread.zAxis[0][0], "Signed reverse-flow cells survive editing")
        } finally { temp.delete() }
    }
}
