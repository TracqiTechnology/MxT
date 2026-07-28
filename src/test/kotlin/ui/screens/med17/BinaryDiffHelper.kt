package ui.screens.med17

import java.io.File
import java.io.RandomAccessFile
import kotlin.test.fail

/**
 * Utility for binary-level write verification in MED17 tests.
 *
 * Compares an original BIN file against a modified copy and asserts that
 * only the expected address range was mutated.
 */
object BinaryDiffHelper {

    /**
     * Result of comparing two BIN files.
     *
     * @param changedRanges list of (startAddress, length) pairs where bytes differ
     * @param totalBytesChanged total number of individual bytes that differ
     */
    data class DiffResult(
        val changedRanges: List<Pair<Long, Int>>,
        val totalBytesChanged: Int
    ) {
        val hasChanges: Boolean get() = totalBytesChanged > 0
    }

    /**
     * Compare two BIN files byte-by-byte and return all changed address ranges.
     * Adjacent changed bytes are coalesced into a single range.
     */
    fun diff(original: File, modified: File): DiffResult {
        val origBytes = original.readBytes()
        val modBytes = modified.readBytes()
        require(origBytes.size == modBytes.size) {
            "BIN files differ in size: original=${origBytes.size}, modified=${modBytes.size}"
        }

        val ranges = mutableListOf<Pair<Long, Int>>()
        var totalChanged = 0
        var rangeStart = -1L
        var rangeLen = 0

        for (i in origBytes.indices) {
            if (origBytes[i] != modBytes[i]) {
                totalChanged++
                if (rangeStart < 0) {
                    rangeStart = i.toLong()
                    rangeLen = 1
                } else {
                    rangeLen++
                }
            } else {
                if (rangeStart >= 0) {
                    ranges.add(Pair(rangeStart, rangeLen))
                    rangeStart = -1L
                    rangeLen = 0
                }
            }
        }
        if (rangeStart >= 0) {
            ranges.add(Pair(rangeStart, rangeLen))
        }

        return DiffResult(ranges, totalChanged)
    }

    /**
     * Collect all address ranges that a [data.parser.xdf.TableDefinition] write
     * would touch (x-axis, y-axis, z-axis).
     */
    fun expectedWriteRanges(tableDef: data.parser.xdf.TableDefinition): List<LongRange> {
        val ranges = mutableListOf<LongRange>()

        tableDef.xAxis?.takeIf { it.address != 0 }?.let { axis ->
            val count = maxOf(axis.indexCount, 1)
            val bytes = count * (axis.sizeBits / 8)
            ranges.add(axis.address.toLong() until axis.address.toLong() + bytes)
        }

        tableDef.yAxis?.takeIf { it.address != 0 }?.let { axis ->
            val count = maxOf(axis.indexCount, 1)
            val bytes = count * (axis.sizeBits / 8)
            ranges.add(axis.address.toLong() until axis.address.toLong() + bytes)
        }

        tableDef.zAxis.takeIf { it.address != 0 }?.let { axis ->
            val count = maxOf(axis.rowCount, 1) * maxOf(axis.columnCount, 1)
            val bytes = count * (axis.sizeBits / 8)
            ranges.add(axis.address.toLong() until axis.address.toLong() + bytes)
        }

        return ranges
    }

    /**
     * Assert that writing a map to a BIN only mutated bytes within the
     * expected address ranges for the given table definition.
     *
     * @param original stock BIN (unmodified)
     * @param modified BIN after the write
     * @param tableDef the table definition that was written
     * @param requireChanges if true, fails when no bytes changed at all
     */
    fun assertOnlyExpectedBytesChanged(
        original: File,
        modified: File,
        tableDef: data.parser.xdf.TableDefinition,
        requireChanges: Boolean = true
    ) {
        val result = diff(original, modified)
        val expected = expectedWriteRanges(tableDef)

        if (requireChanges && !result.hasChanges) {
            fail("Expected BIN to change after writing '${tableDef.tableName}' but no bytes were modified")
        }

        for ((changeAddr, changeLen) in result.changedRanges) {
            val changeRange = changeAddr until changeAddr + changeLen
            val covered = expected.any { expectedRange ->
                changeRange.first >= expectedRange.first && changeRange.last <= expectedRange.last
            }
            if (!covered) {
                val expectedStr = expected.joinToString { "[0x${it.first.toString(16)}..0x${it.last.toString(16)}]" }
                fail(
                    "BIN write for '${tableDef.tableName}' modified unexpected address range " +
                    "[0x${changeAddr.toString(16)}..0x${(changeAddr + changeLen - 1).toString(16)}] " +
                    "(${changeLen} bytes). Expected ranges: $expectedStr"
                )
            }
        }
    }

    /**
     * Read raw bytes from a BIN file at a given address.
     */
    fun readBytes(file: File, address: Long, length: Int): ByteArray {
        val bytes = ByteArray(length)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(address)
            raf.readFully(bytes)
        }
        return bytes
    }
}
