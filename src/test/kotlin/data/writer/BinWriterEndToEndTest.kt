package data.writer

import data.model.EcuPlatform
import data.parser.bin.BinParser
import data.parser.xdf.TableDefinition
import data.parser.xdf.XdfParser
import data.preferences.platform.EcuPlatformPreference
import domain.math.map.Map3d
import java.io.*
import kotlin.math.abs
import kotlin.test.*

/**
 * End-to-end BIN write tests.
 *
 * Strategy:
 *  1. Parse the real ME7 XDF to get [TableDefinition]s (addresses, equations, sizes)
 *  2. Parse the real ME7 BIN to get original [Map3d] values
 *  3. Modify specific maps (simulating what the optimizer/LDRPID screens do)
 *  4. Write modified maps to a **copy** of the BIN (never touch the original)
 *  5. Re-parse the modified copy and verify:
 *     - Modified tables have the expected new values
 *     - Unmodified tables are byte-identical to the original
 *     - Bytes outside any known table are untouched
 *
 * Uses ME7 platform — BIN writing is supported for both ME7 and MED17.
 */
class BinWriterEndToEndTest {

    companion object {
        private val PROJECT_ROOT = File(System.getProperty("user.dir"))
        private val XDF_FILE = File(PROJECT_ROOT, "example/me7/xdf/8D0907551M-20190711.xdf")
        private val BIN_FILE = File(PROJECT_ROOT, "example/me7/bin/8D0907551M-0002 (16Bit KFZW).bin")

        /** Tolerance for floating-point comparison after write roundtrip.
         *  16-bit integers with equations like 0.75*X-48 have ~0.75 precision. */
        private const val DEFAULT_TOL = 1.0

        /** Names of tables to use for write tests. */
        private const val KFZW = "KFZW"
        private const val KFMIRL = "KFMIRL"
        private const val KFMIOP = "KFMIOP"
        private const val KFLDRL = "KFLDRL"
        private const val KFLDIMX = "KFLDIMX"
        private const val KFZWOP = "KFZWOP"
    }

    private lateinit var savedPlatform: EcuPlatform
    private lateinit var tableDefs: List<TableDefinition>
    private lateinit var originalMaps: List<Pair<TableDefinition, Map3d>>

    @BeforeTest
    fun setUp() {
        savedPlatform = EcuPlatformPreference.platform
        EcuPlatformPreference.platform = EcuPlatform.ME7

        assertTrue(XDF_FILE.exists(), "XDF file not found: ${XDF_FILE.absolutePath}")
        assertTrue(BIN_FILE.exists(), "BIN file not found: ${BIN_FILE.absolutePath}")

        // Parse XDF → table definitions
        val (_, defs) = XdfParser.parseToList(FileInputStream(XDF_FILE))
        tableDefs = defs
        assertTrue(tableDefs.isNotEmpty(), "XDF produced no table definitions")

        // Parse BIN → original maps
        originalMaps = BinParser.parseToList(FileInputStream(BIN_FILE), tableDefs)
        assertEquals(tableDefs.size, originalMaps.size, "BIN should produce one Map3d per TableDefinition")
    }

    @AfterTest
    fun tearDown() {
        EcuPlatformPreference.platform = savedPlatform
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────

    /** Copy the BIN to a temp file and return it. */
    private fun copyBin(): File {
        val tmp = File.createTempFile("mxt_test_", ".bin")
        tmp.deleteOnExit()
        BIN_FILE.copyTo(tmp, overwrite = true)
        return tmp
    }

    /** Find table definition by exact title match; fall back to starts-with. */
    private fun findDef(name: String): TableDefinition =
        tableDefs.firstOrNull { it.tableName == name }
            ?: tableDefs.first { it.tableName.startsWith(name) }

    /** Find original map for a table by title. */
    private fun findOriginalMap(name: String): Map3d {
        val def = findDef(name)
        return originalMaps.first { it.first.tableName == def.tableName }.second
    }

    /** Re-parse a BIN file and return the map for a specific table. */
    private fun readBackMap(binFile: File, tableName: String): Map3d {
        val maps = BinParser.parseToList(FileInputStream(binFile), tableDefs)
        val def = findDef(tableName)
        return maps.first { it.first.tableName == def.tableName }.second
    }

    /** Assert two Map3d instances have equal values within tolerance. */
    private fun assertMapsEqual(expected: Map3d, actual: Map3d, tol: Double = DEFAULT_TOL, msg: String = "") {
        val prefix = if (msg.isNotEmpty()) "$msg: " else ""

        assertEquals(expected.xAxis.size, actual.xAxis.size, "${prefix}xAxis size mismatch")
        for (i in expected.xAxis.indices) {
            assertTrue(
                abs(expected.xAxis[i] - actual.xAxis[i]) <= tol,
                "${prefix}xAxis[$i]: expected ${expected.xAxis[i]} ± $tol, got ${actual.xAxis[i]}"
            )
        }

        assertEquals(expected.yAxis.size, actual.yAxis.size, "${prefix}yAxis size mismatch")
        for (i in expected.yAxis.indices) {
            assertTrue(
                abs(expected.yAxis[i] - actual.yAxis[i]) <= tol,
                "${prefix}yAxis[$i]: expected ${expected.yAxis[i]} ± $tol, got ${actual.yAxis[i]}"
            )
        }

        assertEquals(expected.zAxis.size, actual.zAxis.size, "${prefix}zAxis row count mismatch")
        for (r in expected.zAxis.indices) {
            assertEquals(expected.zAxis[r].size, actual.zAxis[r].size, "${prefix}zAxis[$r] col count mismatch")
            for (c in expected.zAxis[r].indices) {
                assertTrue(
                    abs(expected.zAxis[r][c] - actual.zAxis[r][c]) <= tol,
                    "${prefix}zAxis[$r][$c]: expected ${expected.zAxis[r][c]} ± $tol, got ${actual.zAxis[r][c]}"
                )
            }
        }
    }

    /** Assert two Map3d instances are byte-exact (no tolerance). */
    private fun assertMapsExact(expected: Map3d, actual: Map3d, msg: String = "") {
        assertMapsEqual(expected, actual, 0.0, msg)
    }

    /** Create a modified copy of a Map3d with an offset added to all z-axis values. */
    private fun offsetZ(original: Map3d, offset: Double): Map3d {
        val newZ = Array(original.zAxis.size) { r ->
            Array(original.zAxis[r].size) { c ->
                original.zAxis[r][c] + offset
            }
        }
        return Map3d(original.xAxis, original.yAxis, newZ)
    }

    // ─────────────────────────────────────────────────────────────────────
    // Test: XDF + BIN parsing sanity
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `XDF contains expected tables`() {
        val names = tableDefs.map { it.tableName }
        assertTrue(KFZW in names, "XDF must contain KFZW")
        assertTrue(KFMIRL in names, "XDF must contain KFMIRL")
        assertTrue(KFMIOP in names, "XDF must contain KFMIOP")
        assertTrue(KFLDRL in names, "XDF must contain KFLDRL")
        assertTrue(KFLDIMX in names, "XDF must contain KFLDIMX")
    }

    @Test
    fun `original maps have non-empty data`() {
        for (tableName in listOf(KFZW, KFMIRL, KFMIOP, KFLDRL, KFLDIMX)) {
            val map = findOriginalMap(tableName)
            assertTrue(map.zAxis.isNotEmpty(), "$tableName z-axis should be non-empty")
            assertTrue(map.zAxis[0].isNotEmpty(), "$tableName z-axis first row should be non-empty")
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Test: Identity roundtrip — write original values back
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `identity roundtrip - writing original values back preserves map values`() {
        val tmpBin = copyBin()

        // Only test known tuning tables (not code pointers, jump addresses, etc.)
        val tuningTables = listOf(KFZW, KFMIRL, KFMIOP, KFLDRL, KFLDIMX, KFZWOP)

        for (tableName in tuningTables) {
            val def = findDef(tableName)
            val originalMap = findOriginalMap(tableName)
            if (def.zAxis.address == 0 || originalMap.zAxis.isEmpty()) continue

            BinWriter.write(tmpBin, def, originalMap)
            val readBack = readBackMap(tmpBin, tableName)
            assertMapsEqual(originalMap, readBack, DEFAULT_TOL,
                "Identity roundtrip for '$tableName'")
        }
        tmpBin.delete()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Test: Write KFZW and read back
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `write modified KFZW - values read back correctly`() {
        val tmpBin = copyBin()
        val def = findDef(KFZW)
        val original = findOriginalMap(KFZW)

        // Add 3 degrees to all ignition timing values
        val modified = offsetZ(original, 3.0)
        BinWriter.write(tmpBin, def, modified)

        val readBack = readBackMap(tmpBin, KFZW)
        assertMapsEqual(modified, readBack, DEFAULT_TOL, "KFZW after +3° offset")
        tmpBin.delete()
    }

    @Test
    fun `write KFZW does not change KFMIRL`() {
        val tmpBin = copyBin()
        val kfzwDef = findDef(KFZW)
        val kfzwOriginal = findOriginalMap(KFZW)
        val kfmirlOriginal = findOriginalMap(KFMIRL)

        // Write KFZW only
        val modified = offsetZ(kfzwOriginal, 5.0)
        BinWriter.write(tmpBin, kfzwDef, modified)

        // Verify KFMIRL is untouched
        val kfmirlAfter = readBackMap(tmpBin, KFMIRL)
        assertMapsExact(kfmirlOriginal, kfmirlAfter, "KFMIRL should be unchanged after KFZW write")
        tmpBin.delete()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Test: Write KFMIRL and read back
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `write modified KFMIRL - values read back correctly`() {
        val tmpBin = copyBin()
        val def = findDef(KFMIRL)
        val original = findOriginalMap(KFMIRL)

        // Scale all load values up by 10%
        val newZ = Array(original.zAxis.size) { r ->
            Array(original.zAxis[r].size) { c ->
                original.zAxis[r][c] * 1.1
            }
        }
        val modified = Map3d(original.xAxis, original.yAxis, newZ)
        BinWriter.write(tmpBin, def, modified)

        val readBack = readBackMap(tmpBin, KFMIRL)
        assertMapsEqual(modified, readBack, DEFAULT_TOL, "KFMIRL after 10% scale")
        tmpBin.delete()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Test: Write KFMIOP and read back
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `write modified KFMIOP - values read back correctly`() {
        val tmpBin = copyBin()
        val def = findDef(KFMIOP)
        val original = findOriginalMap(KFMIOP)

        val modified = offsetZ(original, 2.0)
        BinWriter.write(tmpBin, def, modified)

        val readBack = readBackMap(tmpBin, KFMIOP)
        assertMapsEqual(modified, readBack, DEFAULT_TOL, "KFMIOP after +2% offset")
        tmpBin.delete()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Test: Write KFLDRL and read back
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `write modified KFLDRL - values read back correctly`() {
        val tmpBin = copyBin()
        val def = findDef(KFLDRL)
        val original = findOriginalMap(KFLDRL)

        // Simulate optimizer WGDC adjustment: offset all values by 5%
        val modified = offsetZ(original, 5.0)
        BinWriter.write(tmpBin, def, modified)

        val readBack = readBackMap(tmpBin, KFLDRL)
        assertMapsEqual(modified, readBack, DEFAULT_TOL, "KFLDRL after +5% offset")
        tmpBin.delete()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Test: Write KFLDIMX and read back
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `write modified KFLDIMX - values read back correctly`() {
        val tmpBin = copyBin()
        val def = findDef(KFLDIMX)
        val original = findOriginalMap(KFLDIMX)

        // Simulate KFLDIMX = KFLDRL + 10% overhead
        val modified = offsetZ(original, 8.0)
        BinWriter.write(tmpBin, def, modified)

        val readBack = readBackMap(tmpBin, KFLDIMX)
        assertMapsEqual(modified, readBack, DEFAULT_TOL, "KFLDIMX after +8% offset")
        tmpBin.delete()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Test: Write isolation — only target table changes
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `write KFLDRL - bytes outside KFLDRL are unchanged`() {
        val tmpBin = copyBin()
        val originalBytes = BIN_FILE.readBytes()
        val kfldrlDef = findDef(KFLDRL)
        val kfldrlOriginal = findOriginalMap(KFLDRL)

        // Write only KFLDRL
        BinWriter.write(tmpBin, kfldrlDef, offsetZ(kfldrlOriginal, 10.0))
        val writtenBytes = tmpBin.readBytes()

        // Collect byte ranges owned by KFLDRL (x, y, z axes)
        val kfldrlBytes = mutableSetOf<Int>()
        addCoveredBytes(kfldrlBytes, kfldrlDef.xAxis)
        addCoveredBytes(kfldrlBytes, kfldrlDef.yAxis)
        addCoveredBytes(kfldrlBytes, kfldrlDef.zAxis)

        // Every byte NOT in KFLDRL's range should be identical
        var diffs = 0
        for (i in originalBytes.indices) {
            if (i !in kfldrlBytes && originalBytes[i] != writtenBytes[i]) {
                diffs++
            }
        }
        assertEquals(0, diffs,
            "All bytes outside KFLDRL's address range should be unchanged")

        // KFLDRL z-axis bytes SHOULD have changed (we wrote different values)
        var kfldrlChanges = 0
        for (i in originalBytes.indices) {
            if (i in kfldrlBytes && originalBytes[i] != writtenBytes[i]) {
                kfldrlChanges++
            }
        }
        assertTrue(kfldrlChanges > 0, "KFLDRL bytes should have changed after writing offset values")
        tmpBin.delete()
    }

    @Test
    fun `write KFZW - bytes outside all table ranges are unchanged`() {
        val tmpBin = copyBin()
        val originalBytes = BIN_FILE.readBytes()
        val kfzwDef = findDef(KFZW)
        val kfzwOriginal = findOriginalMap(KFZW)

        BinWriter.write(tmpBin, kfzwDef, offsetZ(kfzwOriginal, 2.0))
        val writtenBytes = tmpBin.readBytes()

        // Collect all byte ranges that are owned by known table axes
        val coveredRanges = mutableSetOf<Int>()
        for (def in tableDefs) {
            addCoveredBytes(coveredRanges, def.xAxis)
            addCoveredBytes(coveredRanges, def.yAxis)
            addCoveredBytes(coveredRanges, def.zAxis)
        }

        // Every byte NOT in a table range should be identical
        var uncoveredDiffs = 0
        for (i in originalBytes.indices) {
            if (i !in coveredRanges && originalBytes[i] != writtenBytes[i]) {
                uncoveredDiffs++
            }
        }
        assertEquals(0, uncoveredDiffs,
            "Bytes outside all table address ranges should be unchanged")
        tmpBin.delete()
    }

    private fun addCoveredBytes(set: MutableSet<Int>, axis: data.parser.xdf.AxisDefinition?) {
        if (axis == null || axis.address == 0 || axis.isVirtual) return
        val count = when {
            axis.rowCount > 0 && axis.columnCount > 0 -> axis.rowCount * axis.columnCount
            axis.indexCount > 0 -> axis.indexCount
            else -> 1
        }
        val stride = axis.sizeBits / 8
        val totalBytes = count * stride
        for (b in axis.address until (axis.address + totalBytes)) {
            set.add(b)
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Test: Multiple sequential writes
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `sequential writes to different tables - all retain correct values`() {
        val tmpBin = copyBin()

        // Write KFLDRL, then KFLDIMX, then KFZW — simulating optimizer batch write
        val kfldrlDef = findDef(KFLDRL)
        val kfldrlMod = offsetZ(findOriginalMap(KFLDRL), 5.0)
        BinWriter.write(tmpBin, kfldrlDef, kfldrlMod)

        val kfldimxDef = findDef(KFLDIMX)
        val kfldimxMod = offsetZ(findOriginalMap(KFLDIMX), 8.0)
        BinWriter.write(tmpBin, kfldimxDef, kfldimxMod)

        val kfzwDef = findDef(KFZW)
        val kfzwMod = offsetZ(findOriginalMap(KFZW), -2.0)
        BinWriter.write(tmpBin, kfzwDef, kfzwMod)

        // All three should read back correctly
        assertMapsEqual(kfldrlMod, readBackMap(tmpBin, KFLDRL), DEFAULT_TOL, "KFLDRL after batch write")
        assertMapsEqual(kfldimxMod, readBackMap(tmpBin, KFLDIMX), DEFAULT_TOL, "KFLDIMX after batch write")
        assertMapsEqual(kfzwMod, readBackMap(tmpBin, KFZW), DEFAULT_TOL, "KFZW after batch write")

        // KFMIRL should be unchanged
        assertMapsExact(findOriginalMap(KFMIRL), readBackMap(tmpBin, KFMIRL), "KFMIRL unchanged after batch")
        tmpBin.delete()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Test: Equation inverse roundtrip for all tables
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `equation roundtrip - read and write back every tuning table`() {
        val tmpBin = copyBin()

        // Test equation roundtrip on known tuning tables only.
        // Non-tuning tables (jump addresses, code pointers) may overflow Int.MAX_VALUE
        // and tables with complex non-invertible equations or virtual axes can't roundtrip.
        val tuningTables = listOf(KFZW, KFMIRL, KFMIOP, KFLDRL, KFLDIMX, KFZWOP)

        for (tableName in tuningTables) {
            val def = findDef(tableName)
            val originalMap = findOriginalMap(tableName)
            if (def.zAxis.address == 0 || originalMap.zAxis.isEmpty()) continue

            BinWriter.write(tmpBin, def, originalMap)
            val readBack = BinParser.parseToList(FileInputStream(tmpBin), listOf(def))
                .first().second

            assertMapsEqual(originalMap, readBack, DEFAULT_TOL,
                "Equation roundtrip failed for '$tableName'")
        }
        tmpBin.delete()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Test: Write value range extremes
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `write zero values to KFLDRL - reads back as zero`() {
        val tmpBin = copyBin()
        val def = findDef(KFLDRL)
        val original = findOriginalMap(KFLDRL)

        // Zero out the entire KFLDRL table (simulate "remove all boost control")
        val zeroed = Map3d(
            original.xAxis,
            original.yAxis,
            Array(original.zAxis.size) { r -> Array(original.zAxis[r].size) { 0.0 } }
        )
        BinWriter.write(tmpBin, def, zeroed)

        val readBack = readBackMap(tmpBin, KFLDRL)
        for (r in readBack.zAxis.indices) {
            for (c in readBack.zAxis[r].indices) {
                assertTrue(abs(readBack.zAxis[r][c]) <= DEFAULT_TOL,
                    "KFLDRL[$r][$c] should be ~0 after zeroing, got ${readBack.zAxis[r][c]}")
            }
        }
        tmpBin.delete()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Test: MED17 platform allows writes (no CRC32 gate)
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `MED17 platform allows write successfully`() {
        EcuPlatformPreference.platform = EcuPlatform.MED17
        val tmpBin = copyBin()
        val def = findDef(KFZW)
        val map = findOriginalMap(KFZW)

        // Should NOT throw — MED17 writes are now supported
        BinWriter.write(tmpBin, def, map)
        tmpBin.delete()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Test: Write + overwrite — second write wins
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `overwriting same table - second write takes effect`() {
        val tmpBin = copyBin()
        val def = findDef(KFMIRL)
        val original = findOriginalMap(KFMIRL)

        // First write: +2
        val first = offsetZ(original, 2.0)
        BinWriter.write(tmpBin, def, first)

        // Second write: +5 (from original)
        val second = offsetZ(original, 5.0)
        BinWriter.write(tmpBin, def, second)

        val readBack = readBackMap(tmpBin, KFMIRL)
        assertMapsEqual(second, readBack, DEFAULT_TOL, "KFMIRL should reflect second write")
        tmpBin.delete()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Test: KFZWOP write (verifies KFZWOP table exists and roundtrips)
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `write modified KFZWOP - values read back correctly`() {
        val tmpBin = copyBin()
        val def = findDef(KFZWOP)
        val original = findOriginalMap(KFZWOP)

        val modified = offsetZ(original, 1.5)
        BinWriter.write(tmpBin, def, modified)

        val readBack = readBackMap(tmpBin, KFZWOP)
        assertMapsEqual(modified, readBack, DEFAULT_TOL, "KFZWOP after +1.5° offset")
        tmpBin.delete()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Test: BIN file size is preserved
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `write does not change BIN file size`() {
        val tmpBin = copyBin()
        val originalSize = BIN_FILE.length()

        val def = findDef(KFZW)
        val modified = offsetZ(findOriginalMap(KFZW), 3.0)
        BinWriter.write(tmpBin, def, modified)

        assertEquals(originalSize, tmpBin.length(), "BIN file size must not change after write")
        tmpBin.delete()
    }
}
