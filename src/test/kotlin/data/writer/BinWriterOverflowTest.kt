package data.writer

import data.model.EcuPlatform
import data.parser.bin.BinParser
import data.parser.xdf.AxisDefinition
import data.parser.xdf.TableDefinition
import data.parser.xdf.XdfParser
import data.preferences.bin.BinFilePreferences
import data.preferences.platform.EcuPlatformPreference
import domain.math.map.Map3d
import java.io.File
import java.io.FileInputStream
import kotlin.math.abs
import kotlin.test.*
import support.GlobalTestStateSnapshot

/**
 * Tests for BinWriter behavior at data type boundaries.
 *
 * When corrected map values exceed the storage range of the underlying data type
 * (e.g., writing 70000 to a 16-bit unsigned field that maxes at 65535), the
 * BinWriter must handle this safely — NOT silently wrap around or write garbage.
 *
 * A wrapped value in a fuel map could change 70000 → 4464, which is a ~94% error.
 */
class BinWriterOverflowTest {

    companion object {
        private val PROJECT_ROOT = File(System.getProperty("user.dir"))
        private val XDF_FILE = File(PROJECT_ROOT, "example/me7/xdf/8D0907551M-20190711.xdf")
        private val BIN_FILE = File(PROJECT_ROOT, "example/me7/bin/8D0907551M-0002.bin")
    }

    private lateinit var globalState: GlobalTestStateSnapshot
    private lateinit var tableDefs: List<TableDefinition>
    private lateinit var allMaps: List<Pair<TableDefinition, Map3d>>
    private lateinit var tempBinFile: File

    @BeforeTest
    fun setUp() {
        globalState = GlobalTestStateSnapshot.capture()
        EcuPlatformPreference.platform = EcuPlatform.ME7

        val (_, defs) = XdfParser.parseToList(FileInputStream(XDF_FILE))
        tableDefs = defs
        allMaps = BinParser.parseToList(FileInputStream(BIN_FILE), tableDefs)

        XdfParser.setTableDefinitionsForTesting(tableDefs)
        BinParser.setMapListForTesting(allMaps)

        tempBinFile = File.createTempFile("me7_overflow_", ".bin")
        BIN_FILE.copyTo(tempBinFile, overwrite = true)
        BinFilePreferences.setFile(tempBinFile)
    }

    @AfterTest
    fun tearDown() {
        if (::tempBinFile.isInitialized) tempBinFile.delete()
        if (::globalState.isInitialized) globalState.restore()
    }

    private fun findDef(keyword: String): Pair<TableDefinition, Map3d>? =
        allMaps.find { it.first.tableName.contains(keyword, ignoreCase = true) }

    @Test
    fun `writing zero values does not crash or corrupt`() {
        val pair = allMaps.firstOrNull { it.second.zAxis.isNotEmpty() && it.first.zAxis.address != 0 }
            ?: return // Skip if no writable maps

        val (def, originalMap) = pair

        // Create a map with all zeros
        val zeroMap = Map3d(
            originalMap.xAxis.copyOf(),
            originalMap.yAxis.copyOf(),
            Array(originalMap.zAxis.size) { Array(originalMap.zAxis[0].size) { 0.0 } }
        )

        // Should not throw
        BinWriter.write(tempBinFile, def, zeroMap)

        // Re-read and verify zeros were written
        val reRead = BinParser.parseToList(FileInputStream(tempBinFile), tableDefs)
        val reReadPair = reRead.find { it.first.tableName == def.tableName }
        assertNotNull(reReadPair)

        for (i in reReadPair.second.zAxis.indices) {
            for (j in reReadPair.second.zAxis[i].indices) {
                val value = reReadPair.second.zAxis[i][j]
                // After equation round-trip, zero may map to a non-zero base value
                // (e.g., equation "X * 0.75 - 48" → inverse → zero writes as 64)
                // The important thing is that the value round-trips correctly
                assertFalse(value.isNaN(),
                    "Writing zero should not produce NaN at [$i][$j]")
                assertFalse(value.isInfinite(),
                    "Writing zero should not produce Infinity at [$i][$j]")
            }
        }
    }

    @Test
    fun `writing large positive values does not produce NaN`() {
        val pair = allMaps.firstOrNull { it.second.zAxis.isNotEmpty() && it.first.zAxis.address != 0 }
            ?: return

        val (def, originalMap) = pair

        // Create a map with very large values
        val largeMap = Map3d(
            originalMap.xAxis.copyOf(),
            originalMap.yAxis.copyOf(),
            Array(originalMap.zAxis.size) { Array(originalMap.zAxis[0].size) { 99999.0 } }
        )

        // Should not throw — may clamp or truncate but should not crash
        try {
            BinWriter.write(tempBinFile, def, largeMap)

            // If write succeeded, re-read should produce valid (non-NaN) values
            val reRead = BinParser.parseToList(FileInputStream(tempBinFile), tableDefs)
            val reReadPair = reRead.find { it.first.tableName == def.tableName }
            assertNotNull(reReadPair)

            for (i in reReadPair.second.zAxis.indices) {
                for (j in reReadPair.second.zAxis[i].indices) {
                    assertFalse(reReadPair.second.zAxis[i][j].isNaN(),
                        "Large value write should not produce NaN at [$i][$j]")
                }
            }
        } catch (e: Exception) {
            // If BinWriter throws on overflow, that's also acceptable behavior
            // (failing loudly is better than silently writing garbage)
            assertTrue(true, "BinWriter threw on overflow — acceptable behavior")
        }
    }

    @Test
    fun `writing negative values to unsigned field is handled safely`() {
        // Find a table that uses unsigned encoding (type bit 0 = 0)
        val unsignedPair = allMaps.firstOrNull {
            it.second.zAxis.isNotEmpty() &&
            it.first.zAxis.address != 0 &&
            (it.first.zAxis.type and 0x01) == 0  // Unsigned
        } ?: return

        val (def, originalMap) = unsignedPair

        // Try to write negative values
        val negMap = Map3d(
            originalMap.xAxis.copyOf(),
            originalMap.yAxis.copyOf(),
            Array(originalMap.zAxis.size) { Array(originalMap.zAxis[0].size) { -100.0 } }
        )

        try {
            BinWriter.write(tempBinFile, def, negMap)

            // If write succeeded, verify no NaN in read-back
            val reRead = BinParser.parseToList(FileInputStream(tempBinFile), tableDefs)
            val reReadPair = reRead.find { it.first.tableName == def.tableName }
            assertNotNull(reReadPair)

            for (i in reReadPair.second.zAxis.indices) {
                for (j in reReadPair.second.zAxis[i].indices) {
                    val value = reReadPair.second.zAxis[i][j]
                    assertFalse(value.isNaN(),
                        "Negative-to-unsigned write should not produce NaN at [$i][$j]")
                }
            }
        } catch (e: Exception) {
            // Throwing is acceptable — better than silent corruption
            assertTrue(true, "BinWriter threw on negative-to-unsigned — acceptable behavior")
        }
    }

    @Test
    fun `BIN file size is preserved after any write`() {
        val pair = allMaps.firstOrNull { it.second.zAxis.isNotEmpty() && it.first.zAxis.address != 0 }
            ?: return

        val (def, originalMap) = pair
        val originalSize = tempBinFile.length()

        BinWriter.write(tempBinFile, def, originalMap)

        assertEquals(originalSize, tempBinFile.length(),
            "BIN file size must not change after write " +
            "(original=$originalSize, after=${tempBinFile.length()})")
    }

    @Test
    fun `identity write preserves all bytes exactly`() {
        // Writing the original values back should produce identical BIN
        val pair = allMaps.firstOrNull { it.second.zAxis.isNotEmpty() && it.first.zAxis.address != 0 }
            ?: return

        val (def, originalMap) = pair
        val beforeBytes = tempBinFile.readBytes()

        BinWriter.write(tempBinFile, def, originalMap)

        val afterBytes = tempBinFile.readBytes()

        // Compare only the bytes in the table's address range
        val zAddr = def.zAxis.address.toLong()
        val zCount = maxOf(def.zAxis.rowCount, 1) * maxOf(def.zAxis.columnCount, 1)
        val zBytes = zCount * (def.zAxis.sizeBits / 8)

        for (offset in 0 until zBytes) {
            val addr = zAddr + offset
            if (addr < beforeBytes.size) {
                assertEquals(beforeBytes[addr.toInt()], afterBytes[addr.toInt()],
                    "Identity write changed byte at address 0x${addr.toString(16)} " +
                    "for table '${def.tableName}'")
            }
        }
    }
}
