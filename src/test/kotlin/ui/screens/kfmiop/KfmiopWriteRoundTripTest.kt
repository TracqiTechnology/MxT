package ui.screens.kfmiop

import data.model.EcuPlatform
import data.parser.bin.BinParser
import data.parser.xdf.AxisDefinition
import data.parser.xdf.TableDefinition
import data.writer.BinWriter
import domain.math.map.Map3d
import java.io.File
import java.io.FileInputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * End-to-end integration of the MED9 KFMIOP fix: it exercises the exact production
 * flow — parse a binary → [KfmiopAxisConvention.normalize] → edit → denormalize →
 * [BinWriter.write] → re-parse → normalize — against a synthetic MED9 KFMIOP table
 * (RPM-on-x, load-on-y, column-major, big-endian). This is the test that proves an
 * edit made in algorithm convention lands at the correct byte in the binary and
 * survives a full round-trip. Without both fixes (axis normalization AND
 * column-major write) this cycle corrupts data.
 */
class KfmiopWriteRoundTripTest {

    private val tmpFiles = mutableListOf<File>()

    @AfterTest
    fun cleanup() = tmpFiles.forEach { it.delete() }

    private val xAddr = 0x100  // RPM axis (16 pts)
    private val yAddr = 0x200  // load axis (11 pts)
    private val zAddr = 0x300  // z data (11 load rows x 16 rpm cols), column-major

    /** Synthetic MED9 KFMIOP: xAxis=RPM(1/min, 16), yAxis=load(%, 11), z column-major BE. */
    private fun med9KfmiopTable(): TableDefinition {
        val x = AxisDefinition("KFMIOP_x", 0, xAddr, 16, 16, 1, 16, "1/min", "X", "X", emptyList(), lsbFirst = false)
        val y = AxisDefinition("KFMIOP_y", 0, yAddr, 11, 16, 1, 11, "%", "X", "X", emptyList(), lsbFirst = false)
        val z = AxisDefinition("KFMIOP", 0, zAddr, 176, 16, 11, 16, "%", "X", "X", emptyList(),
            lsbFirst = false, isColumnMajor = true)
        return TableDefinition("KFMIOP", "Optimal load", x, y, z)
    }

    /** Raw binary layout: RPM on x (16), load on y (11), z[11 load][16 rpm], distinct cells. */
    private fun rawMap(): Map3d {
        val rpm = Array(16) { (500 + it * 400).toDouble() }
        val load = Array(11) { (it * 10).toDouble() }
        val z = Array(11) { i -> Array(16) { j -> (i * 100 + j).toDouble() } }
        return Map3d(rpm, load, z)
    }

    private fun freshBin(): File =
        File.createTempFile("mxt-kfmiop", ".bin").also { tmpFiles.add(it); it.writeBytes(ByteArray(0x300 + 176 * 2 + 16)) }

    private fun parse(bin: File, def: TableDefinition): Map3d =
        BinParser.parseToList(FileInputStream(bin), listOf(def)).single().second

    private fun deepCopyZ(m: Map3d): Array<Array<Double>> = Array(m.zAxis.size) { i -> m.zAxis[i].copyOf() }

    @Test
    fun `full parse-normalize-edit-denormalize-write-reparse cycle preserves the edit and everything else`() {
        val def = med9KfmiopTable()
        val bin = freshBin()

        // Seed the binary with the raw MED9 layout and confirm it parses back exactly.
        BinWriter.write(bin, def, rawMap())
        val parsed = parse(bin, def)
        assertEquals(11, parsed.zAxis.size)
        assertEquals(16, parsed.zAxis[0].size)
        for (i in 0 until 11) for (j in 0 until 16)
            assertEquals(rawMap().zAxis[i][j], parsed.zAxis[i][j], "seed round-trip z[$i][$j]")

        // Normalize to algorithm convention (load on x, RPM on y).
        val swap = KfmiopAxisConvention.storedRpmOnX(def, EcuPlatform.MED9)
        assertTrue(swap, "MED9 KFMIOP with 1/min x-axis must be detected as RPM-on-x")
        val normalized = KfmiopAxisConvention.normalize(parsed, swap)
        assertEquals(11, normalized.xAxis.size, "x is now load")
        assertEquals(16, normalized.zAxis.size, "z is now 16 rpm rows")

        // Edit a single cell in algorithm convention: rpm-row 5, load-col 3.
        val preEditZ = deepCopyZ(normalized)
        normalized.zAxis[5][3] = 9999.0

        // Denormalize back to binary convention and write.
        val denormalized = KfmiopAxisConvention.denormalize(normalized, swap)
        assertEquals(11, denormalized.zAxis.size, "z back to 11 load rows")
        assertEquals(16, denormalized.zAxis[0].size, "z back to 16 rpm cols")
        assertEquals(9999.0, denormalized.zAxis[3][5], "edit lands at binary cell [load=3][rpm=5]")
        BinWriter.write(bin, def, denormalized)

        // Re-parse and re-normalize; the edit must be at [5][3] and nothing else changed.
        val renormalized = KfmiopAxisConvention.normalize(parse(bin, def), swap)
        assertEquals(9999.0, renormalized.zAxis[5][3], "edit survived the write/read round-trip")
        for (i in renormalized.zAxis.indices) for (j in renormalized.zAxis[i].indices) {
            val expected = if (i == 5 && j == 3) 9999.0 else preEditZ[i][j]
            assertEquals(expected, renormalized.zAxis[i][j], "cell [$i][$j] integrity")
        }
        assertEquals(normalized.xAxis.toList(), renormalized.xAxis.toList(), "load axis preserved")
        assertEquals(normalized.yAxis.toList(), renormalized.yAxis.toList(), "rpm axis preserved")
    }

    @Test
    fun `orientation matters - raw parse and normalized differ by transpose`() {
        val def = med9KfmiopTable()
        val bin = freshBin()
        BinWriter.write(bin, def, rawMap())
        val parsed = parse(bin, def)
        val normalized = KfmiopAxisConvention.normalize(parsed, swap = true)

        // Dimensions are swapped, and cell [i][j] maps to [j][i]: reading the binary
        // without normalization would present RPM as load and vice-versa.
        assertTrue(parsed.zAxis.size != normalized.zAxis.size, "z dimensions must differ (11x16 vs 16x11)")
        assertEquals(parsed.zAxis[7][2], normalized.zAxis[2][7], "cell transposed, not relocated")
    }

    @Test
    fun `writing a map whose RPM breakpoint count changed is refused end-to-end`() {
        val def = med9KfmiopTable()
        val bin = freshBin()
        BinWriter.write(bin, def, rawMap())
        val normalized = KfmiopAxisConvention.normalize(parse(bin, def), swap = true)

        // Simulate an RPM-breakpoint-count edit: drop one RPM row (16 -> 15).
        val resized = Map3d(
            normalized.xAxis, normalized.yAxis.copyOfRange(0, normalized.yAxis.size - 1),
            normalized.zAxis.copyOfRange(0, normalized.zAxis.size - 1)
        )
        val denormResized = KfmiopAxisConvention.denormalize(resized, swap = true)
        assertFailsWith<IllegalArgumentException>("resized KFMIOP must be refused, not silently corrupted") {
            BinWriter.write(bin, def, denormResized)
        }
    }
}
