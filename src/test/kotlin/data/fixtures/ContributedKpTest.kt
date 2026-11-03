package data.fixtures

import data.parser.bin.BinParser
import data.parser.kp.KpDefinitionAdapter
import data.parser.kp.KpHintParser
import data.writer.BinWriter
import domain.math.map.Map3d
import org.junit.jupiter.api.Tag
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.*

@Tag("contributed-fixture")
class ContributedKpTest {
    private fun parsed() = KpHintParser.parseFileFull(ContributedFixtures.file("med9/D915C_N46A510.a2l"))

    @Test
    fun `MED9 counted records retain complete supported encodings and no invented axes`() {
        val (hints, maps) = parsed()
        assertEquals(10123, hints.size)
        assertEquals(8451, maps.size)
        assertEquals(maps.size, maps.map { it.name }.distinct().size)
        val defs = KpDefinitionAdapter.toTableDefinitions(maps).associateBy { it.tableName }
        val torque = defs.getValue("KFMIOP")
        assertEquals(0x1cdbc2, torque.zAxis.address)
        assertEquals(11, torque.zAxis.columnCount)
        assertEquals(16, torque.zAxis.rowCount)
        assertFalse(torque.zAxis.lsbFirst)
        assertTrue(torque.zAxis.isColumnMajor)
        assertEquals(0x1cdbac, torque.xAxis!!.address)
        assertEquals(0x1cdb8c, torque.yAxis!!.address)
        val ignition = defs.getValue("KFZW_0_A")
        assertEquals(0x1c8ac4, ignition.zAxis.address)
        assertEquals(8, ignition.zAxis.sizeBits)
        assertTrue(ignition.zAxis.isSigned)
        assertEquals(16, ignition.xAxis!!.sizeBits)
        assertFalse(ignition.xAxis!!.lsbFirst)
        assertEquals(16, ignition.yAxis!!.sizeBits)
        assertTrue(ignition.tableDescription.contains("Zündwinkel"))
        assertTrue(hints.any { it.name == "MSHFMTPH" })
        assertFalse("MSHFMTPH" in defs, "Ordinal axis without physical breakpoints must remain a hint")
    }

    @Test
    fun `MED9 map metadata reads and writes independently encoded synthetic BIN bytes`() {
        val defs = KpDefinitionAdapter.toTableDefinitions(parsed().second).associateBy { it.tableName }
        // This is explicitly synthetic storage, not a matching ECU image.
        for (name in listOf("KFMIOP", "KFZW_0_A")) {
            val def = defs.getValue(name)
            val rows = def.zAxis.rowCount; val cols = def.zAxis.columnCount
            val bytes = ByteArray(2 * 1024 * 1024) { 0x5a }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            repeat(cols) { buffer.putShort(def.xAxis!!.address + it * 2, (100 + it * 100).toShort()) }
            repeat(rows) { buffer.putShort(def.yAxis!!.address + it * 2, (4000 + it * 400).toShort()) }
            for (col in 0 until cols) for (row in 0 until rows) {
                val index = col * rows + row
                if (name == "KFMIOP") buffer.putShort(0x1cdbc2 + index * 2, (1000 + col * 100 + row).toShort())
                else bytes[0x1c8ac4 + index] = (-60 + index % 100).toByte()
            }
            val map = BinParser.parseToList(bytes.inputStream(), listOf(def)).single().second
            assertEquals(100 * 0.0234375, map.xAxis[0])
            assertEquals(1000.0, map.yAxis[0])
            val expected = if (name == "KFMIOP") 1203 * 0.00152587890625 else (-60 + (2 * rows + 3) % 100) * 0.75
            assertEquals(expected, map.zAxis[3][2], 1e-12)
            val file = kotlin.io.path.createTempFile("med9-kp-synthetic", ".bin").toFile()
            try {
                file.writeBytes(bytes)
                BinWriter.write(file, def, map)
                assertTrue(bytes.contentEquals(file.readBytes()), "Identity write: $name")
                val edited = Map3d(map).apply { zAxis[3][2] += if (name == "KFMIOP") 100 * 0.00152587890625 else 0.75 }
                BinWriter.write(file, def, edited)
                val after = file.readBytes()
                val start = def.zAxis.address + (2 * rows + 3) * (def.zAxis.sizeBits / 8)
                val changed = bytes.indices.filter { bytes[it] != after[it] }
                assertTrue(changed.isNotEmpty())
                assertTrue(changed.all { it in start until start + def.zAxis.sizeBits / 8 })
                val reread = BinParser.parseToList(after.inputStream(), listOf(def)).single().second
                assertEquals(edited.zAxis[3][2], reread.zAxis[3][2], 1e-12)
            } finally { file.delete() }
        }
    }
}
