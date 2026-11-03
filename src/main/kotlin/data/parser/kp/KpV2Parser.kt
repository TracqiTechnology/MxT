package data.parser.kp

import data.parser.xdf.AxisDefinition
import data.parser.xdf.TableDefinition
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Counted v2 `intern` stream (outer format 0x124/0x149).
 * Field order was checked against the contributed MED9 bytes and the public
 * format notes at https://github.com/nyetlabs/xdfkit/blob/master/docs/kp-format.md.
 * This reader is independent of xdfkit and does not use its implementation.
 */
internal object KpV2Parser {
    private data class Value(val description: String, val unit: String, val factor: Double, val offset: Double)
    private data class Axis(val value: Value, val source: Int, val address: Int, val type: Int,
                            val width: Int, val mirrored: Boolean, val reciprocal: Boolean, val signed: Boolean)
    private class Reader(val bytes: ByteArray) {
        var position = 0
        fun take(count: Int): ByteArray {
            require(count >= 0 && count <= bytes.size - position) { "Truncated KP record" }
            return bytes.copyOfRange(position, position + count).also { position += count }
        }
        fun int(): Int = ByteBuffer.wrap(take(4)).order(ByteOrder.LITTLE_ENDIAN).int
        fun double(): Double = ByteBuffer.wrap(take(8)).order(ByteOrder.LITTLE_ENDIAN).double
        fun flag(): Boolean = when (val value = take(1)[0].toInt()) {
            0 -> false
            1 -> true
            else -> error("Invalid KP flag: $value")
        }
        fun string(): String {
            val count = int()
            require(count in 0..65536) { "Invalid KP string length" }
            if (count == 0) return ""
            val text = take(count).toString(Charsets.ISO_8859_1)
            require(take(1)[0] == 0.toByte()) { "Unterminated KP string" }
            return text
        }
        fun value(): Value = Value(string(), string(), double(), double())
        fun axis(): Axis {
            val value = value()
            val source = int(); val address = int(); val type = int(); val width = int()
            int() // display base
            val mirror = int(); require(mirror in 0..1)
            take(9)
            val reciprocal = flag(); int() // precision
            val signed = flag()
            val length = int(); require(length in 0..400000 && length % 4 == 0)
            take(length) // free-editable values; not promoted to writable axes
            int(); int() // header length before the data address, signature byte
            return Axis(value, source, address, type, width, mirror == 1, reciprocal, signed)
        }
    }

    fun parse(blob: ByteArray): Pair<List<KpMapHint>, List<KpMapDefinition>> {
        val r = Reader(blob)
        require(!r.flag()) { "Unsupported KP intern flag" }
        val count = r.int(); require(count in 0..100000)
        val hints = mutableListOf<KpMapHint>()
        val definitions = mutableListOf<KpMapDefinition>()
        repeat(count) {
            r.flag(); r.int(); r.string(); r.flag() // marker, link, comment, comment flag
            val description = r.string()
            val organization = r.int(); r.int() // display pane
            val type = r.int(); val width = r.int(); r.int(); r.int() // base, folder
            val name = r.string()
            r.take(9 + 48) // ID state and raw display ranges
            val reciprocal = r.flag(); val signed = r.flag(); r.flag(); r.flag()
            val cols = r.int(); val rows = r.int()
            require(cols in 1..1000 && rows in 1..1000)
            r.take(12) // cursor and precision
            val value = r.value()
            val address = r.int(); val end = r.int(); val imageSize = r.int()
            r.take(28) // image padding, secondary address and image metadata
            val x = r.axis(); val y = r.axis()
            r.take(6)
            val storedCols = r.int(); val storedRows = r.int()
            r.take(63)
            require(r.take(3).all { it == 1.toByte() }) { "Unsupported KP record trailer" }
            require(name.isNotBlank() && imageSize > 0 && address > 0 && end > address && end <= imageSize)
            hints += KpMapHint(name, description, address)

            // Keep unsupported encodings as hints, never fabricate writable definitions.
            if (organization !in 2..5 || reciprocal || !encoding(type, width) || !valid(value)) return@repeat
            if (end.toLong() - address != rows.toLong() * cols * width) return@repeat
            val columnMajor = organization == 5
            if (storedCols != (if (columnMajor) rows else cols) || storedRows != (if (columnMajor) cols else rows)) return@repeat
            val xa = if (cols > 1) axisDefinition("x", x, cols, imageSize) ?: return@repeat else null
            val ya = if (rows > 1) axisDefinition("y", y, rows, imageSize) ?: return@repeat else null
            val z = definition("z", value, address, type, width, signed, rows, cols, columnMajor)
            definitions += KpMapDefinition(
                name, description, address, cols, rows, width * 8, value.factor, value.unit, address,
                xa?.address ?: -1, ya?.address ?: -1, x.value.factor, y.value.factor, x.value.unit, y.value.unit,
                TableDefinition(name, description, xa, ya, z)
            )
        }
        require(r.position == blob.size) { "Trailing or unparsed KP records" }
        // Several folders contain duplicate copies of the same map. Conflicting
        // definitions for a name are withheld, since downstream merging is by name.
        val distinct = definitions.distinct()
        val safe = distinct.groupBy { it.name }.values.filter { it.size == 1 }.flatten().sortedBy { it.name }
        return hints.distinct().sortedBy { it.name } to safe
    }

    private fun encoding(type: Int, width: Int): Boolean = when (type) {
        1 -> width == 1
        2, 3 -> width == 2
        4, 5, 6, 7 -> width == 4
        else -> false
    }
    private fun valid(v: Value) = v.factor.isFinite() && v.factor != 0.0 && v.offset.isFinite()
    private fun axisDefinition(id: String, a: Axis, count: Int, imageSize: Int): AxisDefinition? {
        if (a.source != 1 || a.mirrored || a.reciprocal || !encoding(a.type, a.width) || !valid(a.value)) return null
        if (a.address <= 0 || a.address.toLong() + count.toLong() * a.width > imageSize) return null
        return definition(id, a.value, a.address, a.type, a.width, a.signed, 1, count, false)
    }
    private fun definition(id: String, v: Value, address: Int, type: Int, width: Int, signed: Boolean,
                           rows: Int, cols: Int, columnMajor: Boolean): AxisDefinition = AxisDefinition(
        id = id, type = if (signed) 1 else 0, address = address, indexCount = rows * cols,
        sizeBits = width * 8, rowCount = rows, columnCount = cols, unit = v.unit,
        equation = "${v.factor} * X + ${v.offset}", varId = "X", axisValues = emptyList(),
        lsbFirst = type in setOf(1, 3, 5, 7), isFloat = type in setOf(6, 7), isColumnMajor = columnMajor
    )
}
