package domain.feedback

import data.contract.Med17LogFileContract.Header
import domain.math.AxisRescaler
import domain.math.map.Map3d
import domain.model.fueltrim.FuelTrimAnalyzer
import domain.model.fueltrim.FuelTrimSettings
import domain.model.kfzw.Kfzw
import domain.model.ldrpid.LdrpidCalculator
import domain.model.pfi.PfiShareCalculator
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Deterministic property-style coverage for the workflows in the tuner feedback.
 * Every failure reports its seed/case index so an adversarial input is reproducible.
 */
class FeedbackWorkflowPropertyTest {

    private companion object {
        const val BASE_SEED = 0x4D7854
    }

    @Test
    fun `bilinear rescaling preserves arbitrary affine surfaces`() {
        val random = Random(BASE_SEED + 1)
        repeat(250) { case ->
            val a = random.nextDouble(-5.0, 5.0)
            val b = random.nextDouble(-5.0, 5.0)
            val c = random.nextDouble(-100.0, 100.0)
            val x = arrayOf(10.0, 20.0, 30.0)
            val y = arrayOf(1000.0, 2000.0, 3000.0)
            val source = Map3d(
                x,
                y,
                Array(y.size) { row ->
                    Array(x.size) { col -> a * x[col] + b * y[row] + c }
                }
            )
            val newX = arrayOf(
                10.0,
                random.nextDouble(10.01, 19.99),
                random.nextDouble(20.01, 29.99),
                30.0
            )
            val newY = arrayOf(
                1000.0,
                random.nextDouble(1000.01, 1999.99),
                random.nextDouble(2000.01, 2999.99),
                3000.0
            )

            val result = AxisRescaler.rescaleMap(source, newX, newY).rescaledMap

            for (row in newY.indices) {
                for (col in newX.indices) {
                    val expected = a * newX[col] + b * newY[row] + c
                    assertEquals(
                        expected,
                        result.zAxis[row][col],
                        1e-8,
                        "affine surface case=$case row=$row col=$col"
                    )
                }
            }
        }
    }

    @Test
    fun `weighted fuel trim binning never dilutes constant trim magnitude`() {
        val random = Random(BASE_SEED + 2)
        repeat(250) { case ->
            val trimFactor = if (case % 2 == 0) {
                random.nextDouble(1.02, 1.20)
            } else {
                random.nextDouble(0.80, 0.98)
            }
            val expectedPercent = (trimFactor - 1.0) * 100.0
            val data = mapOf(
                Header.RPM_COLUMN_HEADER to List(12) {
                    random.nextDouble(1100.0, 1900.0)
                },
                Header.ENGINE_LOAD_HEADER to List(12) {
                    random.nextDouble(55.0, 95.0)
                },
                Header.STFT_MIXED_COLUMN_HEADER to List(12) { trimFactor }
            )
            val result = FuelTrimAnalyzer.analyzeMed17TrimsWithDiagnostics(
                logData = data,
                rpmBins = doubleArrayOf(1000.0, 2000.0),
                loadBins = doubleArrayOf(50.0, 100.0),
                settings = FuelTrimSettings(
                    trimThresholdPercent = 0.0,
                    minimumSamples = 1,
                    standardDeviationLimitPercent = 100.0,
                    maximumRpmChangePerSecond = 10_000.0,
                    requireClosedLoopWhenAvailable = false,
                    maximumLambdaDeviation = 1.0
                )
            )

            result.diagnostics.flatten()
                .filter { it.effectiveSampleWeight > 0.0 }
                .forEach { diagnostic ->
                    assertEquals(
                        expectedPercent,
                        diagnostic.meanTrimPercent,
                        1e-8,
                        "fuel trim magnitude case=$case"
                    )
                    assertEquals(
                        expectedPercent,
                        diagnostic.correctionApplied,
                        1e-8,
                        "fuel trim correction case=$case"
                    )
                }
        }
    }

    @Test
    fun `weighted PFI binning never dilutes constant logged share`() {
        val random = Random(BASE_SEED + 3)
        repeat(250) { case ->
            val share = random.nextDouble(0.05, 0.95)
            val rpm = random.nextDouble(2000.01, 2499.99)
            val load = random.nextDouble(40.01, 59.99)
            val result = PfiShareCalculator.refineFromLog2d(
                logData = mapOf(
                    Header.RPM_COLUMN_HEADER to List(12) { rpm },
                    Header.ENGINE_LOAD_HEADER to List(12) { load },
                    Header.PFI_SPLIT_FACTOR_HEADER to List(12) { share }
                ),
                rpmBins = doubleArrayOf(2000.0, 2500.0),
                loadBins = doubleArrayOf(40.0, 60.0)
            )

            for (row in result.pfiSharePercent2d) {
                for (value in row) {
                    assertEquals(
                        share * 100.0,
                        value,
                        1e-8,
                        "PFI share case=$case"
                    )
                }
            }
        }
    }

    @Test
    fun `KFZW interpolation preserves arbitrary linear timing rows`() {
        val random = Random(BASE_SEED + 4)
        val oldAxis = arrayOf(0.0, 10.0, 20.0, 30.0)
        repeat(250) { case ->
            val slope = random.nextDouble(-0.5, 0.5)
            val intercept = random.nextDouble(5.0, 25.0)
            val rows = Array(4) { row ->
                Array(oldAxis.size) { col ->
                    intercept + row * 2.0 + slope * oldAxis[col]
                }
            }
            val newAxis = arrayOf(
                random.nextDouble(0.0, 7.0),
                random.nextDouble(7.01, 14.0),
                random.nextDouble(14.01, 22.0),
                random.nextDouble(22.01, 30.0)
            )
            val result = Kfzw.generateKfzw(oldAxis, rows, newAxis)

            for (row in rows.indices) {
                for (col in newAxis.indices) {
                    assertEquals(
                        intercept + row * 2.0 + slope * newAxis[col],
                        result[row][col],
                        1e-8,
                        "KFZW linear row case=$case row=$row col=$col"
                    )
                }
            }
        }
    }

    @Test
    fun `LDR linearization is finite monotonic and row-local for random boost rows`() {
        val random = Random(BASE_SEED + 5)
        val x = arrayOf(0.0, 20.0, 40.0, 60.0, 80.0, 100.0)
        val y = arrayOf(1500.0, 3000.0, 4500.0, 6000.0)
        val base = Map3d(x, y, Array(y.size) { Array(x.size) { 0.0 } })

        repeat(250) { case ->
            val nonlinear = Array(y.size) {
                Array(x.size) { random.nextDouble(0.1, 40.0) }
            }
            val result = LdrpidCalculator.calculateLinearTable(nonlinear, base)

            for (row in nonlinear.indices) {
                val expectedMin = nonlinear[row].min()
                val expectedMax = nonlinear[row].max()
                assertEquals(expectedMin, result.zAxis[row].first(), 1e-9, "LDR min case=$case")
                assertEquals(expectedMax, result.zAxis[row].last(), 1e-9, "LDR max case=$case")
                assertTrue(result.zAxis[row].all { it.isFinite() }, "LDR finite case=$case")
                for (col in 1 until result.zAxis[row].size) {
                    assertTrue(
                        result.zAxis[row][col] >= result.zAxis[row][col - 1],
                        "LDR monotonic case=$case row=$row col=$col"
                    )
                }
            }
        }
    }
}
