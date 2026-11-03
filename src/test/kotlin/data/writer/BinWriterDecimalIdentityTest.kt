package data.writer

import data.parser.bin.BinParser
import data.parser.xdf.AxisDefinition
import data.parser.xdf.TableDefinition
import domain.math.map.Map3d
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.*

class BinWriterDecimalIdentityTest {
    @Test
    fun `decimal scaled signed values preserve identity while real fractions still truncate`() {
        val raw = shortArrayOf(-32768, -905, -73, -1, 0, 1, 73, 905, 32767)
        val def = TableDefinition("signed decimal", "", null, null,
            AxisDefinition("z", 1, 16, 0, 16, 1, raw.size, "kg/h", "X*0.1", "X", emptyList()))
        val file = kotlin.io.path.createTempFile("decimal-identity", ".bin").toFile()
        try {
            val bytes = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN).apply {
                position(16); raw.forEach { putShort(it) }
            }.array()
            file.writeBytes(bytes)
            val map = file.inputStream().use { BinParser.parseToList(it, listOf(def)).single().second }
            BinWriter.write(file, def, map)
            assertTrue(bytes.contentEquals(file.readBytes()), "Decimal identity write must preserve bytes")
            val edited = Map3d(map).apply { zAxis[0][2] = -7.39; zAxis[0][6] = 7.39 }
            BinWriter.write(file, def, edited)
            val reread = file.inputStream().use { BinParser.parseToList(it, listOf(def)).single().second }
            assertEquals(-7.3, reread.zAxis[0][2], 1e-10)
            assertEquals(7.3, reread.zAxis[0][6], 1e-10)
        } finally { file.delete() }
    }
}
