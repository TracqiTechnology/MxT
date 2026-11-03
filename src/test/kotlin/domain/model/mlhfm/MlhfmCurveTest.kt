package domain.model.mlhfm

import data.parser.bin.BinParser
import data.parser.me7log.Me7LogParser
import data.parser.afrlog.AfrLogParser
import data.parser.xdf.AxisDefinition
import data.parser.xdf.TableDefinition
import data.writer.BinWriter
import domain.math.map.Map3d
import domain.model.closedloopfueling.ClosedLoopFuelingCorrectionManager
import domain.model.openloopfueling.correction.OpenLoopMlhfmCorrectionManager
import java.io.File
import kotlin.test.*

class MlhfmCurveTest {
    private fun horizontal() = Map3d(
        arrayOf(0.0, 1.0, 2.0, 3.0, 4.0, 5.0), emptyArray(),
        arrayOf(arrayOf(0.0, 50.0, 100.0, 200.0, 400.0, 800.0))
    )

    @Test
    fun `both fueling algorithms give identical results for row and column curves`() {
        val row = horizontal()
        val column = MlhfmCurve.toColumn(row)
        val parser = Me7LogParser()
        val logFile = File("example/me7/logs/me7/log_typical_20200825_141742.csv")
        val closedLogs = parser.parseLogFile(Me7LogParser.LogType.CLOSED_LOOP, logFile)
        fun closed(map: Map3d): Map3d {
            val manager = ClosedLoopFuelingCorrectionManager(0.0, 0.0, 50.0)
            manager.correct(closedLogs, map)
            return manager.closedLoopMlhfmCorrection!!.correctedMlhfm
        }
        assertEquals(closed(column), closed(row))
        val openLogs = parser.parseLogFile(Me7LogParser.LogType.OPEN_LOOP, logFile)
        val method = AfrLogParser::class.java.getDeclaredMethod("parseFile", File::class.java).apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val afr = method.invoke(AfrLogParser, File("example/me7/logs/ziet/zeitronix.csv")) as Map<String, List<Double>>
        fun open(map: Map3d): Map3d {
            val manager = OpenLoopMlhfmCorrectionManager(80.0, 2000.0, 75, 150, 16.0)
            manager.correct(openLogs, afr, map)
            return manager.openLoopCorrection!!.correctedMlhfm
        }
        assertEquals(open(column), open(row))
        assertEquals(row, MlhfmCurve.forWrite(column, row))
    }

    @Test
    fun `corrected column restores horizontal storage and only changes intended bytes`() {
        val native = horizontal()
        val axis = AxisDefinition("z", 0, 16, 0, 16, 1, 6, "kg/h", "X", "X", emptyList())
        val x = axis.copy(id = "x", address = 0, indexCount = 6, axisValues = native.xAxis.mapIndexed { i, v -> i to v.toFloat() })
        val def = TableDefinition("synthetic MLHFM", "Explicit synthetic voltage axis", x, null, axis)
        val file = kotlin.io.path.createTempFile("horizontal-mlhfm", ".bin").toFile()
        try {
            file.writeBytes(ByteArray(64))
            BinWriter.write(file, def, native)
            val before = file.readBytes()
            val correction = MlhfmCurve.toColumn(native).apply { zAxis[3][0] = 210.0 }
            BinWriter.write(file, def, MlhfmCurve.forWrite(correction, native))
            val changed = before.indices.filter { before[it] != file.readBytes()[it] }
            assertTrue(changed.isNotEmpty())
            assertTrue(changed.all { it in 22..23 })
            val decoded = file.inputStream().use { BinParser.parseToList(it, listOf(def)).single().second }
            assertEquals(210.0, decoded.zAxis[0][3])
            assertContentEquals(native.xAxis, decoded.xAxis)
        } finally { file.delete() }
    }

    @Test
    fun `invalid axes shapes and changed breakpoints are rejected`() {
        val row = horizontal()
        assertFailsWith<IllegalArgumentException> { MlhfmCurve.toColumn(Map3d(row).apply { xAxis.fill(0.0) }) }
        assertFailsWith<IllegalArgumentException> { MlhfmCurve.toColumn(Map3d(row).apply { xAxis[2] = Double.NaN }) }
        assertFailsWith<IllegalArgumentException> { MlhfmCurve.toColumn(Map3d(row).apply { xAxis = arrayOf(1.0) }) }
        assertFailsWith<IllegalArgumentException> { MlhfmCurve.toColumn(Map3d(row).apply { zAxis = arrayOf(arrayOf(1.0, 2.0), arrayOf(3.0, 4.0)) }) }
        val changed = MlhfmCurve.toColumn(row).apply { yAxis[1] = 0.5 }
        assertFailsWith<IllegalArgumentException> { MlhfmCurve.forWrite(changed, row) }
    }
}
