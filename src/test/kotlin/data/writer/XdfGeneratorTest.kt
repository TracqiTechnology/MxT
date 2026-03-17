package data.writer

import kotlin.test.*

/**
 * Tests for [XdfGenerator] — TunerPro XDF file generator.
 *
 * Verifies that generated XDF files are valid XML and contain expected
 * structure elements for tables, constants, axes, and metadata.
 */
class XdfGeneratorTest {

    @Test
    fun `generates valid XDF header`() {
        val gen = XdfGenerator(
            title = "TestECU",
            description = "Test Description",
            author = "Test Author"
        )
        gen.addCategory(0, "Test Category")

        val xdf = gen.generate()

        assertTrue(xdf.contains("""<XDFFORMAT version="1.50">"""))
        assertTrue(xdf.contains("<deftitle>TestECU</deftitle>"))
        assertTrue(xdf.contains("<description>Test Description</description>"))
        assertTrue(xdf.contains("<author>Test Author</author>"))
        assertTrue(xdf.contains("""<CATEGORY index="0x0" name="Test Category" />"""))
        assertTrue(xdf.contains("</XDFFORMAT>"))
    }

    @Test
    fun `generates 1D table (MLHFM-like)`() {
        val gen = XdfGenerator(title = "Test")

        gen.addTable(
            name = "MLHFM",
            description = "MAF linearization",
            zAxis = AxisSpec(
                address = 0x7540,
                rowCount = 266,
                colCount = 1,
                sizeBits = 16,
                lsbFirst = true,
                unit = "kg/h",
                equation = "0.125 * X",
                decimalPl = 2
            )
        )

        val xdf = gen.generate()

        assertTrue(xdf.contains("<title>MLHFM</title>"))
        assertTrue(xdf.contains("<description>MAF linearization</description>"))
        assertTrue(xdf.contains("""mmedaddress="0x7540""""))
        assertTrue(xdf.contains("""mmedelementsizebits="16""""))
        assertTrue(xdf.contains("""mmedrowcount="266""""))
        assertTrue(xdf.contains("<units>kg/h</units>"))
        assertTrue(xdf.contains("""equation="0.125 * X""""))
        assertTrue(xdf.contains("""mmedtypeflags="0x02"""")) // unsigned + lsbFirst
    }

    @Test
    fun `generates 2D table with embedded axes`() {
        val gen = XdfGenerator(title = "Test")

        gen.addTable(
            name = "KFZW",
            description = "Ignition angle map",
            xAxis = AxisSpec(
                address = 0x100FF,
                count = 12,
                sizeBits = 8,
                unit = "%",
                equation = "0.75 * X"
            ),
            yAxis = AxisSpec(
                address = 0x100C2,
                count = 16,
                sizeBits = 8,
                unit = "RPM",
                equation = "40.0 * X"
            ),
            zAxis = AxisSpec(
                address = 0x11C72,
                rowCount = 16,
                colCount = 12,
                sizeBits = 8,
                signed = true,
                lsbFirst = false,
                unit = "grad KW",
                equation = "0.75 * X",
                decimalPl = 2
            )
        )

        val xdf = gen.generate()

        assertTrue(xdf.contains("<title>KFZW</title>"))
        // X axis
        assertTrue(xdf.contains("""mmedaddress="0x100FF""""))
        assertTrue(xdf.contains("<indexcount>12</indexcount>"))
        assertTrue(xdf.contains("""embedinfo type="1""""))
        // Y axis
        assertTrue(xdf.contains("""mmedaddress="0x100C2""""))
        assertTrue(xdf.contains("<indexcount>16</indexcount>"))
        // Z axis
        assertTrue(xdf.contains("""mmedaddress="0x11C72""""))
        assertTrue(xdf.contains("""mmedrowcount="16""""))
        assertTrue(xdf.contains("""mmedcolcount="12""""))
        assertTrue(xdf.contains("""mmedtypeflags="0x01"""")) // signed, not lsbFirst
    }

    @Test
    fun `generates constant (scalar)`() {
        val gen = XdfGenerator(title = "Test")

        gen.addConstant(
            name = "MLOFS",
            description = "MAF offset",
            address = 0x7554,
            sizeBits = 16,
            lsbFirst = true,
            unit = "kg/h",
            equation = "0.125 * X"
        )

        val xdf = gen.generate()

        assertTrue(xdf.contains("<title>MLOFS</title>"))
        assertTrue(xdf.contains("XDFCONSTANT"))
        assertTrue(xdf.contains("""mmedaddress="0x7554""""))
    }

    @Test
    fun `generates virtual axis with labels`() {
        val gen = XdfGenerator(title = "Test")

        gen.addTable(
            name = "TEST_1D",
            yAxis = AxisSpec(
                count = 3,
                labels = listOf("Low", "Mid", "High")
            ),
            zAxis = AxisSpec(
                address = 0x1000,
                rowCount = 3,
                colCount = 1,
                sizeBits = 8,
                unit = "V"
            )
        )

        val xdf = gen.generate()

        assertTrue(xdf.contains("""<LABEL index="0" value="Low" />"""))
        assertTrue(xdf.contains("""<LABEL index="1" value="Mid" />"""))
        assertTrue(xdf.contains("""<LABEL index="2" value="High" />"""))
    }

    @Test
    fun `escapes XML special characters`() {
        val gen = XdfGenerator(title = "Test <&> \"Special\"")
        val xdf = gen.generate()

        assertTrue(xdf.contains("Test &lt;&amp;&gt; &quot;Special&quot;"))
    }

    @Test
    fun `writeTo creates file`() {
        val gen = XdfGenerator(title = "Test")
        gen.addTable(
            name = "T1",
            zAxis = AxisSpec(address = 0x100, rowCount = 1, colCount = 1)
        )

        val tmpFile = java.io.File.createTempFile("xdf_test_", ".xdf")
        try {
            gen.writeTo(tmpFile)
            assertTrue(tmpFile.exists())
            assertTrue(tmpFile.length() > 100)
            val content = tmpFile.readText()
            assertTrue(content.contains("XDFFORMAT"))
        } finally {
            tmpFile.delete()
        }
    }
}
