package data.parser.kp

import java.io.File
import kotlin.test.*

/**
 * Smoke test for KP parsing — verifies basic counts and that the parser
 * successfully extracts both hints and full definitions from the fixture.
 */
class KpDebugTest {
    @Test
    fun `parser extracts both hints and definitions`() {
        val kpFile = File("example/me7/kp/8D0907551M-20190711.kp")
        if (!kpFile.exists()) return

        val (hints, defs) = KpHintParser.parseFileFull(kpFile)

        assertTrue(hints.size >= 80, "Expected >= 80 hints, got ${hints.size}")
        assertTrue(defs.size >= 10, "Expected >= 10 full definitions, got ${defs.size}")
        assertTrue(hints.count { it.hasAddress } >= 60, "Most hints should have addresses")

        // Every definition should also appear as a hint
        for (def in defs) {
            assertTrue(hints.any { it.name == def.name }, "${def.name} should also be in hints")
        }
    }
}
