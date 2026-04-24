package data.parser.damos

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.File

class DamosParserTest {

    @Test
    fun `parse synthetic DAMOS snippet`() {
        val snippet = """
*** Created by ASAP2DAM Version 6.05 02.09.1998 ***
/EPR, {X105F22MB}, {6025.02}, DAMPAR_03
/EAD, ${'$'}810007
6025.02
/EPK, {41/1/ME7.1/5/6025.02//X22MB/Dst5F/221100/}
/PNR, {X105F22MB}
/KNR, {5}
/PRO, {}
/BEA, {}
/DST, {PrV = X22MB mit 5F}, {}, {}
/TEL, {}
/SPC, 4475
/UMR, 3
/UTB, 1
/SRC, 2
/UMV, 1
/UP,  {S80166}
/SGB, {ME7.1}
/ABL, 3
/RFG, 2
/DAB, 8
/SND, CODE1 ${'$'}800000 ${'$'}80FFFF
/SND, CODE2 ${'$'}820000 ${'$'}87FFFF
/SND, DATA1 ${'$'}810000 ${'$'}81FFFF

1, /REG, nmot_ub_q40, {}, 6, 0, {1/min}, 1, 5, 0, 10200
/REP, 1, 0, 0, 40, 0, 0;

2, /REG, rel_uw_b0p75, {}, 6, 0, {%}, 2, 5, 0, 196.4462890625
/REP, 256, 0, 0, 3, 0, 0;

1, /SRC, nmot, {Motordrehzahl}, ${'$'}F878, 1, 1, 2, 2;
2, /SRC, tans, {Ansaugluft - Temperatur}, ${'$'}380BBF, 1, 2, 2, 3;

/UMP, {}, nmot_w, {Motordrehzahl}, ${'$'}381E3E, 514, 1, nmot_ub_q40, 2, ${'$'}FFFF, K;
/UMP, {}, rl_w, {Relative Luftfuellung}, ${'$'}381C9C, 514, 2, rel_uw_b0p75, 3, ${'$'}FFFF, K;
/UMP, {}, adastep, {Adaptionsschritt}, ${'$'}380B81, 529, 1, nmot_ub_q40, 3, ${'$'}FF, K;
        """.trimIndent()

        val damos = DamosParser.parse(snippet)

        // Header
        assertEquals("X105F22MB", damos.programNumber)
        assertEquals("6025.02", damos.version)
        assertEquals("ME7.1", damos.ecuFamily)
        assertEquals("S80166", damos.cpuType)
        assertEquals(4475, damos.spzCount)
        assertEquals(3, damos.umpCount)
        assertEquals(2, damos.srcCount)

        // Segments
        assertEquals(3, damos.segments.size)
        val code1 = damos.segments.first { it.name == "CODE1" }
        assertEquals(0x800000L, code1.start)
        assertEquals(0x80FFFFL, code1.end)

        // REG records
        assertEquals(2, damos.regRecords.size)
        val reg1 = damos.regRecords[1]!!
        assertEquals("nmot_ub_q40", reg1.name)
        assertEquals("1/min", reg1.unit)
        assertEquals(40.0, reg1.factor)
        assertEquals(1.0, reg1.divisor)
        assertEquals(0.0, reg1.offset)

        val reg2 = damos.regRecords[2]!!
        assertEquals(3.0 / 256.0, reg2.factor / reg2.divisor, 1e-10)

        // UMP records
        assertEquals(3, damos.umpRecords.size)

        val nmotW = damos.umpRecords["nmot_w"]!!
        assertEquals(0x381E3EL, nmotW.address)
        assertEquals(514, nmotW.typeId)
        assertEquals(2, nmotW.sizeBytes)
        assertFalse(nmotW.isSigned)
        assertEquals(0xFFFFL, nmotW.bitmask)

        val adastep = damos.umpRecords["adastep"]!!
        assertEquals(0x380B81L, adastep.address)
        assertEquals(529, adastep.typeId)
        assertEquals(1, adastep.sizeBytes)
        assertTrue(adastep.isSigned)

        // SRC records
        assertEquals(2, damos.srcRecords.size)
        val nmotSrc = damos.srcRecords["nmot"]!!
        assertEquals(0xF878L, nmotSrc.address)
        assertEquals("Motordrehzahl", nmotSrc.description)
    }

    @Test
    fun `buildEcuEntries converts UMP + REG to EcuEntry`() {
        val snippet = """
/SPC, 0
/UMR, 1
/SRC, 0
/UP, {S80166}
/SGB, {ME7.1}

1, /REG, nmot_ub_q40, {}, 6, 0, {1/min}, 1, 5, 0, 10200
/REP, 1, 0, 0, 40, 0, 0;

/UMP, {}, nmot_w, {Motordrehzahl}, ${'$'}381E3E, 514, 1, nmot_ub_q40, 2, ${'$'}FFFF, K;
        """.trimIndent()

        val damos = DamosParser.parse(snippet)
        val entries = DamosParser.buildEcuEntries(damos)

        assertEquals(1, entries.size)
        val entry = entries[0]
        assertEquals("nmot_w", entry.name)
        assertEquals(0x381E3EL, entry.address)
        assertEquals(2, entry.size)
        assertEquals("1/min", entry.unit)
        assertEquals(40.0, entry.factor)
        assertEquals(0.0, entry.offset)
        assertEquals(0, entry.signed)  // unsigned word
    }

    @Test
    fun `parse real C22mb31g dam file`() {
        val damFile = File("technical/me7/C22mb31g.dam")
        if (!damFile.exists()) return  // skip if fixture not available

        val damos = DamosParser.parse(damFile)

        // Header validation
        assertEquals("X105F22MB", damos.programNumber)
        assertEquals("ME7.1", damos.ecuFamily)
        assertEquals("S80166", damos.cpuType)
        assertEquals(4475, damos.spzCount)
        assertEquals(452, damos.umpCount)

        // UMP count matches header
        assertTrue(damos.umpRecords.size >= 400, "Expected at least 400 UMP records, got ${damos.umpRecords.size}")

        // Most UMP addresses are in $38xxxx range (ME7 DPP3 RAM), but some are
        // in SFR space ($Fxxx) or other DPP segments — this is expected for C167.
        val inRange = damos.umpRecords.values.count { it.address in 0x380000..0x3FFFFF }
        assertTrue(inRange > 200, "Expected at least 200 UMP records in \$38xxxx range, got $inRange")

        // Well-known variables must be present in either UMP or SRC records
        val wellKnown = listOf("nmot_w", "rl_w", "mshfm_w", "pvdks_w", "zwist", "ldtvm", "ti_w", "fr_w")
        val allNames = damos.umpRecords.keys + damos.srcRecords.keys
        for (name in wellKnown) {
            assertTrue(name in allNames, "Missing well-known variable: $name (not in UMP or SRC)")
        }

        // REG records should be populated
        assertTrue(damos.regRecords.isNotEmpty(), "No REG records parsed")

        // SRC records should be populated
        assertTrue(damos.srcRecords.isNotEmpty(), "No SRC records parsed")
        assertTrue(damos.srcRecords.containsKey("nmot"), "Missing SRC: nmot")

        // Segments
        assertTrue(damos.segments.isNotEmpty(), "No segments parsed")
    }

    @Test
    fun `buildEcuEntries from real DAMOS produces valid entries`() {
        val damFile = File("technical/me7/C22mb31g.dam")
        if (!damFile.exists()) return

        val damos = DamosParser.parse(damFile)
        val entries = DamosParser.buildEcuEntries(damos)

        assertTrue(entries.size >= 400, "Expected at least 400 entries, got ${entries.size}")

        // nmot_w comes from SRC records; REG 169 = factor 0.25/1, unit U/min
        val nmotW = entries.find { it.name == "nmot_w" }
        assertNotNull(nmotW, "nmot_w not found in entries")
        assertEquals(0.25, nmotW!!.factor)
        assertEquals("U/min", nmotW.unit)
        assertEquals(2, nmotW.size)

        // All entries have valid sizes
        for (entry in entries) {
            assertTrue(entry.size in 1..4, "Invalid size ${entry.size} for ${entry.name}")
        }
    }
}
