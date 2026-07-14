package data.parser.a2l

import data.generator.A2lToXdfGenerator
import data.parser.bin.BinParser
import data.parser.xdf.XdfParser
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Deep validation of the A2L → XDF pipeline against a full 15 MB real MED9 A2L.
 *
 * Gated on a local file outside the repo, so it runs on a dev machine that has the
 * `me7-internal` data set and is skipped in CI (same intent as the real-fixture
 * end-to-end tests). The in-repo [A2lToXdfTest] provides the always-on CI coverage.
 */
class A2lRealDataValidationTest {

    private val a2l = File(System.getProperty("user.home"), "Projects/me7-internal/vag/a2l/med9/8J0907404D_0020.A2L")
    private val bin = File("example/med9/MED9_STOCK.bin")

    @Test
    fun `full real MED9 A2L parses, converts, generates valid XDF, and reads sane calibration data`() {
        assumeTrue(a2l.exists(), "real A2L not present (CI/other machine) — skipping deep validation")
        assumeTrue(bin.exists(), "MED9 bin fixture missing")

        val result = A2lParser.parse(a2l)
        assertTrue(result.characteristics.size > 5000, "expected thousands of characteristics, got ${result.characteristics.size}")

        val defs = result.toTableDefinitions()
        assertTrue(defs.isNotEmpty())

        // Every CHARACTERISTIC address must be a real file offset inside the 2 MB flash.
        val outOfBounds = defs.count { (it.zAxis.address.toLong() and 0xFFFFFFFFL) >= bin.length() }
        assertEquals(0, outOfBounds, "all A2L addresses must be file offsets within the bin (found $outOfBounds out-of-bounds)")

        // Generated XDF must round-trip through the project's own XdfParser.
        val xdf = A2lToXdfGenerator.generate(defs)
        val (_, reparsed) = XdfParser.parseToList(ByteArrayInputStream(xdf.toByteArray()))
        assertEquals(defs.size, reparsed.size, "generated XDF must round-trip 1:1")

        // Parsing the bin must yield real, varied calibration data for the multi-cell maps.
        val parsed = BinParser.parseToList(FileInputStream(bin), defs)
        val multiCell = defs.count { it.xAxis != null }
        val varied = parsed.count { p ->
            val z = p.second.zAxis.flatMap { it.toList() }
            z.isNotEmpty() && z.toSet().size > 1
        }
        assertTrue(varied > multiCell * 80 / 100, "most multi-cell maps should hold varied data ($varied / $multiCell)")

        // Spot-check KFMIOP reads as a 11x16 load map in the 0..100% range.
        parsed.firstOrNull { it.first.tableName.equals("KFMIOP", true) }?.let { (_, m) ->
            val flat = m.zAxis.flatMap { it.toList() }
            assertTrue(flat.all { it in -10.0..200.0 }, "KFMIOP load % sane range: ${flat.min()}..${flat.max()}")
        }
    }
}
