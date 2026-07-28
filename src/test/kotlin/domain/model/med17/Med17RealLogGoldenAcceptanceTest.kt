package domain.model.med17

import data.parser.med17log.Med17LogAdapter
import data.parser.med17log.Med17LogParser
import domain.math.map.Map3d
import domain.model.fueltrim.FuelTrimAnalyzer
import domain.model.ldrpid.LdrpidCalculator
import domain.model.pfi.PfiShareCalculator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Customer-behaviour acceptance tests backed by an independent Python oracle.
 *
 * Unlike the older real-log smoke tests, these compare complete output grids,
 * evidence weights, rejection decisions, and PID diagnostics for every MED17
 * log fixture.  The oracle consumes raw CSV and does not call production code.
 */
class Med17RealLogGoldenAcceptanceTest {

    private data class Fixture(
        val file: String,
        val sha256: String,
        val pfi: JsonObject,
        val fuelTrim: JsonObject,
        val ldrpid: JsonObject
    )

    private val fixtures: List<Fixture> by lazy {
        val resource = javaClass.classLoader.getResource(
            "golden/med17/real-log-acceptance-v1.json"
        ) ?: error("MED17 real-log golden resource is missing")
        val root = Json.parseToJsonElement(resource.readText()).jsonObject
        assertEquals(1, root.getValue("schemaVersion").jsonPrimitive.int)
        assertEquals(
            "tools/golden/med17_oracle.py:v1",
            root.getValue("oracle").jsonPrimitive.content
        )
        root.getValue("fixtures").jsonArray.map { element ->
            val objectValue = element.jsonObject
            Fixture(
                file = objectValue.getValue("file").jsonPrimitive.content,
                sha256 = objectValue.getValue("sha256").jsonPrimitive.content,
                pfi = objectValue.getValue("pfi").jsonObject,
                fuelTrim = objectValue.getValue("fuelTrim").jsonObject,
                ldrpid = objectValue.getValue("ldrpid").jsonObject
            )
        }
    }

    private fun logFile(name: String): File {
        val resource = javaClass.classLoader.getResource("logs/$name")
            ?: error("MED17 log fixture is missing: $name")
        return File(resource.toURI())
    }

    @Test
    fun `all real MED17 fixture hashes match the independently reviewed inputs`() {
        assertEquals(6, fixtures.size, "Every public MED17 log must have a golden case")
        fixtures.forEach { fixture ->
            assertEquals(
                fixture.sha256,
                sha256(logFile(fixture.file)),
                "${fixture.file}: input changed without an explicit golden review"
            )
        }
    }

    @Test
    fun `PFI split surfaces for all real logs match the independent oracle`() {
        fixtures.forEach { fixture ->
            val parsed = Med17LogParser().parseLogFile(
                Med17LogParser.LogType.PFI_SPLIT,
                logFile(fixture.file)
            )
            val actual = PfiShareCalculator.refineFromLog2d(parsed)
            val expected = fixture.pfi

            assertDoubleArray(expected.array("rpmAxis"), actual.rpmAxis, fixture.file, "PFI RPM")
            assertDoubleArray(expected.array("loadAxis"), actual.loadAxis, fixture.file, "PFI load")
            assertEquals(
                expected.getValue("totalSamples").jsonPrimitive.int,
                actual.totalSamples,
                "${fixture.file}: PFI total samples"
            )
            assertDoubleMatrix(
                expected.matrix("values"),
                actual.pfiSharePercent2d,
                fixture.file,
                "PFI share"
            )
            assertIntMatrix(
                expected.intMatrix("counts"),
                actual.sampleCounts,
                fixture.file,
                "PFI contribution count"
            )
            assertDoubleMatrix(
                expected.matrix("effectiveWeights"),
                actual.effectiveSampleWeights,
                fixture.file,
                "PFI effective weight"
            )
            val expectedProvenance = expected.getValue("provenance").jsonArray
            assertEquals(expectedProvenance.size, actual.provenance.size)
            expectedProvenance.forEachIndexed { row, rowElement ->
                rowElement.jsonArray.forEachIndexed { column, cell ->
                    assertEquals(
                        cell.jsonPrimitive.content,
                        actual.provenance[row][column].name,
                        "${fixture.file}: PFI provenance [$row][$column]"
                    )
                }
            }
            assertDoubleArray(
                expected.array("loggedRpmAxis"),
                actual.rpmOnlyCurve.loggedRpmAxis ?: error("${fixture.file}: missing logged RPM"),
                fixture.file,
                "PFI logged RPM"
            )
            assertDoubleArray(
                expected.array("loggedPfiPercent"),
                actual.rpmOnlyCurve.loggedPfiPercent ?: error("${fixture.file}: missing logged PFI"),
                fixture.file,
                "PFI logged percentage"
            )

            actual.sampleCounts.indices.forEach { row ->
                actual.sampleCounts[row].indices.forEach { column ->
                    if (actual.sampleCounts[row][column] > 0) {
                        assertTrue(
                            actual.pfiSharePercent2d[row][column] in 0.0..100.0,
                            "${fixture.file}: measured PFI [$row][$column] must remain a percentage"
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `fuel trim corrections and evidence for all real logs match the independent oracle`() {
        fixtures.forEach { fixture ->
            val parsed = Med17LogParser().parseLogFile(
                Med17LogParser.LogType.FUEL_TRIM,
                logFile(fixture.file)
            )
            val actual = FuelTrimAnalyzer.analyzeMed17TrimsWithDiagnostics(parsed)
            val expected = fixture.fuelTrim

            assertDoubleArray(expected.array("rpmAxis"), actual.rpmBins, fixture.file, "trim RPM")
            assertDoubleArray(expected.array("loadAxis"), actual.loadBins, fixture.file, "trim load")
            assertEquals(
                expected.int("totalSamplesProcessed"),
                actual.totalSamplesProcessed,
                "${fixture.file}: trim processed sample count"
            )
            assertEquals(
                expected.int("samplesFilteredOut"),
                actual.samplesFilteredOut,
                "${fixture.file}: trim filtered sample count"
            )
            assertEquals(
                expected.int("binsWithData"),
                actual.binsWithData,
                "${fixture.file}: trim bins with data"
            )
            assertEquals(
                expected.int("binsRejected"),
                actual.binsRejected,
                "${fixture.file}: trim rejected bins"
            )
            assertDoubleMatrix(
                expected.matrix("corrections"),
                actual.corrections,
                fixture.file,
                "trim correction"
            )

            val expectedDiagnostics = expected.getValue("diagnostics").jsonArray
            expectedDiagnostics.forEachIndexed { row, rowElement ->
                rowElement.jsonArray.forEachIndexed { column, cellElement ->
                    val cell = cellElement.jsonObject
                    val actualCell = actual.diagnostics[row][column]
                    val context = "${fixture.file}: trim diagnostic [$row][$column]"
                    assertEquals(cell.int("sampleCount"), actualCell.sampleCount, "$context count")
                    assertClose(cell.double("mean"), actualCell.meanTrimPercent, "$context mean")
                    assertClose(cell.double("stdDev"), actualCell.stdDevPercent, "$context stddev")
                    assertClose(
                        cell.double("correction"),
                        actualCell.correctionApplied,
                        "$context correction"
                    )
                    assertClose(
                        cell.double("effectiveWeight"),
                        actualCell.effectiveSampleWeight,
                        "$context effective weight"
                    )
                    assertEquals(
                        cell.getValue("rejected").jsonPrimitive.content.toBooleanStrict(),
                        actualCell.rejected,
                        "$context rejected"
                    )
                    val expectedReason = cell["reason"].nullableContent()
                    assertEquals(
                        expectedReason,
                        normalizeRejectReason(actualCell.rejectReason),
                        "$context reason"
                    )
                }
            }
        }
    }

    @Test
    fun `LDRPID output tables and diagnostics for all real logs match the independent oracle`() {
        fixtures.forEach { fixture ->
            val med17 = Med17LogParser().parseLogFile(
                Med17LogParser.LogType.LDRPID,
                logFile(fixture.file)
            )
            val me7 = Med17LogAdapter.toMe7LdrpidFormat(med17)
            val actual = LdrpidCalculator.calculateWithCounts(
                me7,
                kfldrlInput(),
                kfldimxInput()
            )
            val expected = fixture.ldrpid

            assertDoubleArray(expected.array("rpmAxis"), actual.nonLinearOutput.yAxis, fixture.file, "LDR RPM")
            assertDoubleArray(expected.array("dutyAxis"), actual.nonLinearOutput.xAxis, fixture.file, "LDR duty")
            assertDoubleMatrix(
                expected.matrix("nonLinear"),
                actual.nonLinearOutput.zAxis,
                fixture.file,
                "non-linear boost"
            )
            assertDoubleMatrix(
                expected.matrix("linear"),
                actual.linearOutput.zAxis,
                fixture.file,
                "linear boost"
            )
            assertDoubleMatrix(
                expected.matrix("kfldrl"),
                actual.kfldrl.zAxis,
                fixture.file,
                "KFLDRL"
            )
            assertDoubleArray(
                expected.array("kfldimxXAxis"),
                actual.kfldimx.xAxis,
                fixture.file,
                "KFLDIMX pressure axis"
            )
            assertDoubleMatrix(
                expected.matrix("kfldimx"),
                actual.kfldimx.zAxis,
                fixture.file,
                "KFLDIMX"
            )
            assertIntMatrix(
                expected.intMatrix("sampleCounts"),
                actual.nonLinearSampleCounts,
                fixture.file,
                "LDR measured sample count"
            )

            val expectedDiagnostics = expected.getValue("diagnostics").jsonArray
            assertEquals(expectedDiagnostics.size, actual.logDiagnostics.size)
            expectedDiagnostics.forEachIndexed { row, element ->
                val expectedRow = element.jsonObject
                val actualRow = actual.logDiagnostics[row]
                val context = "${fixture.file}: PID diagnostic [$row]"
                assertClose(expectedRow.double("rpm"), actualRow.rpm, "$context RPM")
                assertEquals(expectedRow.int("sampleCount"), actualRow.sampleCount, "$context samples")
                assertEquals(
                    expectedRow.int("measuredDutyCells"),
                    actualRow.measuredDutyCells,
                    "$context measured cells"
                )
                assertNullableClose(
                    expectedRow["averageAbsolutePressureErrorMbar"],
                    actualRow.averageAbsolutePressureErrorMbar,
                    "$context average error"
                )
                assertNullableClose(
                    expectedRow["maximumOvershootMbar"],
                    actualRow.maximumOvershootMbar,
                    "$context overshoot"
                )
                assertNullableClose(
                    expectedRow["percentWithinTolerance"],
                    actualRow.percentWithinTolerance,
                    "$context within tolerance"
                )
            }
        }
    }

    private fun kfldrlInput(): Map3d = Map3d(
        xAxis = fixtureArray("dutyAxis"),
        yAxis = fixtureArray("rpmAxis"),
        zAxis = Array(8) { Array(10) { 30.0 } }
    )

    private fun kfldimxInput(): Map3d = Map3d(
        xAxis = arrayOf(200.0, 400.0, 600.0, 800.0, 1000.0, 1200.0),
        yAxis = fixtureArray("rpmAxis"),
        zAxis = Array(8) { Array(6) { 30.0 } }
    )

    private fun fixtureArray(name: String): Array<Double> =
        fixtures.first().ldrpid.array(name).toTypedArray()

    private fun normalizeRejectReason(reason: String?): String? = when {
        reason == null -> null
        reason == "no samples" -> reason
        reason.startsWith("insufficient effective samples") -> "insufficient effective samples"
        reason.startsWith("std_dev") -> "standard deviation"
        reason.startsWith("within threshold") -> "within threshold"
        else -> reason
    }

    private fun JsonObject.array(name: String): DoubleArray =
        getValue(name).jsonArray.map { it.jsonPrimitive.double }.toDoubleArray()

    private fun JsonObject.matrix(name: String): Array<DoubleArray> =
        getValue(name).jsonArray.map { row ->
            row.jsonArray.map { it.jsonPrimitive.double }.toDoubleArray()
        }.toTypedArray()

    private fun JsonObject.intMatrix(name: String): Array<IntArray> =
        getValue(name).jsonArray.map { row ->
            row.jsonArray.map { it.jsonPrimitive.int }.toIntArray()
        }.toTypedArray()

    private fun JsonObject.int(name: String): Int = getValue(name).jsonPrimitive.int
    private fun JsonObject.double(name: String): Double = getValue(name).jsonPrimitive.double

    private fun JsonElement?.nullableContent(): String? =
        if (this == null || this is JsonNull) null else jsonPrimitive.content

    private fun assertDoubleArray(
        expected: DoubleArray,
        actual: DoubleArray,
        file: String,
        label: String
    ) {
        assertEquals(expected.size, actual.size, "$file: $label size")
        expected.indices.forEach { index ->
            assertClose(expected[index], actual[index], "$file: $label [$index]")
        }
    }

    private fun assertDoubleArray(
        expected: DoubleArray,
        actual: Array<Double>,
        file: String,
        label: String
    ) = assertDoubleArray(expected, actual.toDoubleArray(), file, label)

    private fun assertDoubleMatrix(
        expected: Array<DoubleArray>,
        actual: Array<DoubleArray>,
        file: String,
        label: String
    ) {
        assertEquals(expected.size, actual.size, "$file: $label row count")
        expected.indices.forEach { row ->
            assertEquals(expected[row].size, actual[row].size, "$file: $label row $row size")
            expected[row].indices.forEach { column ->
                assertClose(
                    expected[row][column],
                    actual[row][column],
                    "$file: $label [$row][$column]"
                )
            }
        }
    }

    private fun assertDoubleMatrix(
        expected: Array<DoubleArray>,
        actual: Array<Array<Double>>,
        file: String,
        label: String
    ) = assertDoubleMatrix(
        expected,
        actual.map { it.toDoubleArray() }.toTypedArray(),
        file,
        label
    )

    private fun assertIntMatrix(
        expected: Array<IntArray>,
        actual: Array<IntArray>,
        file: String,
        label: String
    ) {
        assertEquals(expected.size, actual.size, "$file: $label row count")
        expected.indices.forEach { row ->
            assertTrue(
                expected[row].contentEquals(actual[row]),
                "$file: $label row $row expected=${expected[row].contentToString()} " +
                    "actual=${actual[row].contentToString()}"
            )
        }
    }

    private fun assertNullableClose(expected: JsonElement?, actual: Double?, context: String) {
        if (expected == null || expected is JsonNull) {
            assertEquals(null, actual, context)
        } else {
            assertClose(expected.jsonPrimitive.double, actual ?: error("$context missing"), context)
        }
    }

    private fun assertClose(expected: Double, actual: Double, context: String) {
        assertEquals(expected, actual, 1e-6, context)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
