package data.parser.kp

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.*

class KpV2ParserTest {
    private class Wire {
        val out = ByteArrayOutputStream()
        fun bytes(n: Int) { out.write(ByteArray(n)) }
        fun flag(value: Boolean = false) { out.write(if (value) 1 else 0) }
        fun int(value: Int) { out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()) }
        fun double(value: Double) { out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(value).array()) }
        fun string(value: String) {
            val bytes = value.toByteArray(Charsets.ISO_8859_1)
            int(bytes.size)
            if (bytes.isNotEmpty()) { out.write(bytes); flag() }
        }
        fun value(factor: Double, offset: Double) { string(""); string("%"); double(factor); double(offset) }
        fun axis(source: Int, mirrored: Boolean = false) {
            value(0.5, -10.0); int(source); int(64); int(2); int(2); int(10); int(if (mirrored) 1 else 0)
            bytes(9); flag(); int(2); flag(true); int(0); int(0); int(-1)
        }
        fun map(address: Int, cols: Int, source: Int, mirrored: Boolean = false) {
            flag(); int(-1); string(""); flag(); string("Synthetic signed offset")
            int(if (cols > 1) 3 else 2); int(0); int(2); int(2); int(10); int(0); string("TEST")
            bytes(57); flag(); flag(true); flag(); flag(); int(cols); int(1); bytes(12)
            value(0.25, -48.0); int(address); int(address + cols * 2); int(1024); bytes(28)
            axis(source, mirrored); axis(0); bytes(6); int(cols); int(1); bytes(63)
            repeat(3) { flag(true) }
        }
    }
    private fun fixture(count: Int = 1, cols: Int = 1, source: Int = 1, mirrored: Boolean = false): ByteArray = Wire().apply {
        flag(); int(count)
        repeat(count) { map(128 + it * 32, cols, source, mirrored) }
    }.out.toByteArray()

    @Test
    fun `explicit encoding survives adapter without legacy assumptions`() {
        val (_, definitions) = KpV2Parser.parse(fixture(cols = 3))
        val table = KpDefinitionAdapter.toTableDefinition(definitions.single())
        assertTrue(table.zAxis.isSigned)
        assertFalse(table.zAxis.lsbFirst)
        assertEquals("0.25 * X + -48.0", table.zAxis.equation)
        assertTrue(table.xAxis!!.isSigned)
        assertFalse(table.xAxis!!.lsbFirst)
        assertEquals("0.5 * X + -10.0", table.xAxis!!.equation)
    }

    @Test
    fun `unsupported physical axis modes remain hints`() {
        for (source in listOf(0, 2, 3, 4, 5)) {
            val (hints, definitions) = KpV2Parser.parse(fixture(cols = 3, source = source))
            assertEquals(1, hints.size)
            assertTrue(definitions.isEmpty())
        }
        assertTrue(KpV2Parser.parse(fixture(cols = 3, mirrored = true)).second.isEmpty())
    }

    @Test
    fun `conflicting same-name maps are not silently selected`() {
        val (hints, definitions) = KpV2Parser.parse(fixture(count = 2))
        assertEquals(2, hints.size)
        assertTrue(definitions.isEmpty())
    }

    @Test
    fun `malformed and truncated streams never return partial definitions`() {
        val full = fixture(count = 2)
        for (length in listOf(0, 4, 20, full.size / 2, full.size - 1)) {
            assertFailsWith<IllegalArgumentException> { KpV2Parser.parse(full.copyOf(length)) }
        }
        assertFailsWith<IllegalArgumentException> { KpV2Parser.parse(full + byteArrayOf(0)) }
        val count = full.copyOf().apply { repeat(4) { this[it + 1] = 0x7f } }
        assertFailsWith<IllegalArgumentException> { KpV2Parser.parse(count) }
    }
}
