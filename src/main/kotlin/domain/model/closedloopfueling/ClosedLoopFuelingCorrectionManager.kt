package domain.model.closedloopfueling

import data.contract.Me7LogFileContract
import domain.derivative.Derivative
import domain.math.Index
import domain.math.map.Map3d
import domain.model.mlhfm.MlhfmCurve
import domain.util.Util
import org.apache.commons.math3.stat.StatUtils
import org.apache.commons.math3.stat.descriptive.moment.Mean

class ClosedLoopFuelingCorrectionManager(
    private val minThrottleAngle: Double,
    private val minRpm: Double,
    private val maxDerivative: Double
) {
    private val correctedMlhfm = Map3d()
    private val correctionsAfrMap = mutableMapOf<Double, MutableList<Double>>()
    private val filteredVoltageDt = mutableMapOf<Double, MutableList<Double>>()
    private val meanAfrMap = mutableMapOf<Double, Double>()
    private val modeAfrMap = mutableMapOf<Double, DoubleArray>()
    private val correctedAfrMap = mutableMapOf<Double, Double>()

    var closedLoopMlhfmCorrection: ClosedLoopFuelingCorrection? = null
        private set

    fun correct(me7LogMap: Map<Me7LogFileContract.Header, List<Double>>, nativeMlhfm: Map3d) {
        val mlhfm = MlhfmCurve.toColumn(nativeMlhfm)
        val correctionErrorMap = mutableMapOf<Double, MutableList<Double>>()

        for (voltage in mlhfm.yAxis) {
            correctionErrorMap[voltage] = mutableListOf()
            filteredVoltageDt[voltage] = mutableListOf()
            correctionsAfrMap[voltage] = mutableListOf()
            meanAfrMap[voltage] = 0.0
            modeAfrMap[voltage] = doubleArrayOf()
            correctedAfrMap[voltage] = 0.0
        }

        calculateCorrections(correctionErrorMap, me7LogMap, mlhfm)
        val correctionErrorList = mutableListOf<Double>()
        val maxCorrectionIndex = processCorrections(correctionErrorList, correctionErrorMap, mlhfm)
        postProcessCorrections(correctionErrorList, maxCorrectionIndex)
        smooth(correctionErrorList)
        applyCorrections(correctionErrorList, mlhfm)

        closedLoopMlhfmCorrection = ClosedLoopFuelingCorrection(
            mlhfm, correctedMlhfm, correctedMlhfm, filteredVoltageDt, correctionsAfrMap,
            meanAfrMap, modeAfrMap, correctedAfrMap
        )
    }

    private fun calculateCorrections(correctionError: MutableMap<Double, MutableList<Double>>, me7LogMap: Map<Me7LogFileContract.Header, List<Double>>, mlhfm: Map3d) {
        val me7Voltages = me7LogMap[Me7LogFileContract.Header.MAF_VOLTAGE_HEADER]!!
        val me7Timestamps = me7LogMap[Me7LogFileContract.Header.TIME_STAMP_COLUMN_HEADER]!!
        val me7voltageDt = Derivative.getDt(me7Voltages, me7Timestamps)
        val stft = me7LogMap[Me7LogFileContract.Header.STFT_COLUMN_HEADER]!!
        val ltft = me7LogMap[Me7LogFileContract.Header.LTFT_COLUMN_HEADER]!!
        val lambdaControl = me7LogMap[Me7LogFileContract.Header.LAMBDA_CONTROL_ACTIVE_HEADER]!!
        val throttleAngle = me7LogMap[Me7LogFileContract.Header.THROTTLE_PLATE_ANGLE_HEADER]!!
        val rpm = me7LogMap[Me7LogFileContract.Header.RPM_COLUMN_HEADER]!!

        for (i in me7voltageDt.indices) {
            if (lambdaControl[i + 1] == LAMBDA_CONTROL_ENABLED && throttleAngle[i + 1] > minThrottleAngle && rpm[i + 1] > minRpm && me7voltageDt[i] < maxDerivative) {
                val me7Voltage = me7Voltages[i + 1]
                val mlhfmVoltageIndex = Index.getInsertIndex(mlhfm.yAxis.toList(), me7Voltage)
                val mlhfmVoltageKey = mlhfm.yAxis[mlhfmVoltageIndex]
                val voltageScaler = me7Voltage / mlhfmVoltageKey

                val stftValue = (stft[i + 1] - 1) * voltageScaler
                val ltftValue = (ltft[i + 1] - 1) * voltageScaler
                val afrCorrectionError = stftValue + ltftValue

                correctionError[mlhfmVoltageKey]!!.add(afrCorrectionError)
                filteredVoltageDt[mlhfmVoltageKey]!!.add(me7voltageDt[i])
                correctionsAfrMap[mlhfmVoltageKey]!!.add(afrCorrectionError)
            }
        }
    }

    private fun processCorrections(correctionErrorList: MutableList<Double>, correctionErrorMap: Map<Double, MutableList<Double>>, mlhfm: Map3d): Int {
        var maxCorrectionIndex = 0
        var index = 0
        val mean = Mean()

        for (voltage in mlhfm.yAxis) {
            val corrections = correctionErrorMap[voltage]!!
            if (corrections.size > MIN_SAMPLES_THRESHOLD) {
                val meanValue = mean.evaluate(Util.toDoubleArray(corrections.toTypedArray()), 0, corrections.size)
                val mode = StatUtils.mode(Util.toDoubleArray(corrections.toTypedArray()))

                meanAfrMap[voltage] = meanValue
                modeAfrMap[voltage] = mode

                var correction = meanValue
                for (v in mode) correction += v
                correction /= 1 + mode.size

                if (!correction.isNaN()) maxCorrectionIndex = index
                correctionErrorList.add(correction)
            } else {
                correctionErrorList.add(0.0)
            }
            index++
        }

        return maxCorrectionIndex
    }

    private fun postProcessCorrections(correctionErrorList: MutableList<Double>, maxCorrectionIndex: Int) {
        var foundStart = false
        var lastValidCorrectionIndex = -1

        for (i in correctionErrorList.indices) {
            val value = correctionErrorList[i]
            when {
                value.isNaN() && !foundStart -> correctionErrorList[i] = 0.0
                !value.isNaN() && !foundStart -> { foundStart = true; lastValidCorrectionIndex = i }
                value.isNaN() -> correctionErrorList[i] = if (i < maxCorrectionIndex) correctionErrorList[lastValidCorrectionIndex] else 0.0
                else -> lastValidCorrectionIndex = i
            }
        }
    }

    private fun smooth(correctionErrorList: MutableList<Double>) {
        val mean = Mean()
        val meanTempCorrections = Util.toDoubleArray(correctionErrorList.toTypedArray())
        for (i in meanTempCorrections.indices) {
            if (i > 2 && i < meanTempCorrections.size - 2) {
                correctionErrorList[i] = mean.evaluate(meanTempCorrections, i - 2, 5)
            }
        }
    }

    private fun applyCorrections(correctionErrorList: List<Double>, mlhfm: Map3d) {
        val totalCorrectionError = mutableMapOf<Double, Double>()
        val voltage = mlhfm.yAxis.toList()

        for (i in voltage.indices) {
            totalCorrectionError[voltage[i]] = correctionErrorList[i]
            correctedAfrMap[voltage[i]] = correctionErrorList[i]
        }

        correctedMlhfm.yAxis = voltage.toTypedArray()
        val oldKghr = mlhfm.zAxis.map { it[0] }
        val newKghr = Array(oldKghr.size) { i ->
            arrayOf(maxOf(0.0, oldKghr[i] * (totalCorrectionError[voltage[i]]!! + 1)))
        }
        correctedMlhfm.zAxis = newKghr
    }

    companion object {
        private const val MIN_SAMPLES_THRESHOLD = 5
        private const val LAMBDA_CONTROL_ENABLED = 1.0
    }
}
