package data.writer

import data.parser.bin.BinParser
import data.parser.xdf.AxisDefinition
import data.parser.xdf.TableDefinition
import domain.math.map.Map3d
import java.io.File
import java.io.FileInputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Byte-level endianness tests for [BinWriter]. MED9 (MPC562 PowerPC) is big-endian;
 * ME7 is little-endian. These assert the actual bytes written to disk, not just the
 * round-trip, so an accidental byte-order regression on the MED9 write path is caught
 * directly rather than masked by a symmetric read.
 */
class BinWriterEndiannessTest {

    private val tmpFiles = mutableListOf<File>()

    @AfterTest
    fun cleanup() = tmpFiles.forEach { it.delete() }

    private val addr = 16

    private fun bin(size: Int): File =
        File.createTempFile("mxt-endian", ".bin").also { tmpFiles.add(it); it.writeBytes(ByteArray(size)) }

    private fun scalarTable(sizeBits: Int, lsbFirst: Boolean, signed: Boolean = false): TableDefinition {
        val z = AxisDefinition(
            id = "z", type = if (signed) 1 else 0, address = addr, indexCount = 1, sizeBits = sizeBits,
            rowCount = 1, columnCount = 1, unit = "", equation = "X", varId = "X",
            axisValues = emptyList(), lsbFirst = lsbFirst
        )
        return TableDefinition("SYN", "", null, null, z)
    }

    private fun oneCell(v: Double) = Map3d(emptyArray(), emptyArray(), arrayOf(arrayOf(v)))

    private fun bytesAt(f: File, offset: Int, n: Int): List<Int> =
        f.readBytes().copyOfRange(offset, offset + n).map { it.toInt() and 0xFF }

    @Test
    fun `16-bit big-endian writes most-significant byte first`() {
        val f = bin(64)
        BinWriter.write(f, scalarTable(16, lsbFirst = false), oneCell(0x1234.toDouble()))
        assertEquals(listOf(0x12, 0x34), bytesAt(f, addr, 2), "MED9 BE: 0x1234 -> 12 34")
    }

    @Test
    fun `16-bit little-endian writes least-significant byte first`() {
        val f = bin(64)
        BinWriter.write(f, scalarTable(16, lsbFirst = true), oneCell(0x1234.toDouble()))
        assertEquals(listOf(0x34, 0x12), bytesAt(f, addr, 2), "ME7 LE: 0x1234 -> 34 12")
    }

    @Test
    fun `32-bit big-endian byte order`() {
        val f = bin(64)
        BinWriter.write(f, scalarTable(32, lsbFirst = false), oneCell(0x11223344.toDouble()))
        assertEquals(listOf(0x11, 0x22, 0x33, 0x44), bytesAt(f, addr, 4))
    }

    @Test
    fun `32-bit little-endian byte order`() {
        val f = bin(64)
        BinWriter.write(f, scalarTable(32, lsbFirst = true), oneCell(0x11223344.toDouble()))
        assertEquals(listOf(0x44, 0x33, 0x22, 0x11), bytesAt(f, addr, 4))
    }

    @Test
    fun `big-endian bytes round-trip back through the parser`() {
        val f = bin(64)
        val def = scalarTable(16, lsbFirst = false)
        BinWriter.write(f, def, oneCell(4660.0)) // 0x1234
        val z = BinParser.parseToList(FileInputStream(f), listOf(def)).single().second.zAxis
        assertEquals(4660.0, z[0][0])
    }
}
