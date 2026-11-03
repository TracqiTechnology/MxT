package domain.model.openloopfueling.correction

import data.contract.AfrLogFileContract
import data.contract.Me7LogFileContract
import domain.math.Index
import domain.math.map.Map3d
import domain.model.mlhfm.MlhfmCurve
import domain.model.openloopfueling.util.AfrLogUtil
import domain.model.openloopfueling.util.Me7LogUtil
import org.apache.commons.math3.stat.StatUtils
import org.apache.commons.math3.stat.descriptive.moment.Mean

class OpenLoopMlhfmCorrectionManager(
    private val minThrottleAngle: Double,
    private val minRpm: Double,
    private val minPointsMe7: Int,
    private val minPointsAfr: Int,
    private val maxAfr: Double
) {
    private val correctedMlhfm = Map3d()
    private val correctionsAfrMap = mutableMapOf<Double, MutableList<Double>>()
    val meanAfrMap = mutableMapOf<Double, Double>()
    val modeAfrMap = mutableMapOf<Double, DoubleArray>()
    val correctedAfrMap = mutableMapOf<Double, Double>()

    var openLoopCorrection: OpenLoopMlhfmCorrection? = null
        private set

    fun correct(me7Log: Map<Me7LogFileContract.Header, List<Double>>, afrLog: Map<String, List<Double>>, nativeMlhfm: Map3d) {
        val mlhfm = MlhfmCurve.toColumn(nativeMlhfm)
        val me7LogList = Me7LogUtil.findMe7Logs(me7Log, minThrottleAngle, LAMBDA_CONTROL_ENABLED, minRpm, minPointsMe7)
        val afrLogList = AfrLogUtil.findAfrLogs(afrLog, minThrottleAngle, minRpm, maxAfr, minPointsAfr)
        generateMlhfm(mlhfm, me7LogList, afrLogList)
        openLoopCorrection = OpenLoopMlhfmCorrection(mlhfm, correctedMlhfm, correctedMlhfm, correctionsAfrMap, meanAfrMap, modeAfrMap, correctedAfrMap)
    }

    private fun generateMlhfm(mlhfm: Map3d, me7LogList: List<Map<Me7LogFileContract.Header, List<Double>>>, afrLogList: List<Map<String, List<Double>>>) {
        val mlhfmVoltage = mlhfm.yAxis.toList()
        calculateCorrections(me7LogList, afrLogList, mlhfmVoltage)
        val correctedAfrList = processCorrections(mlhfmVoltage)
        postProcessCorrections(correctedAfrList)
        smooth(correctedAfrList, 5)
        applyCorrections(mlhfm, correctedAfrList)
    }

    private fun applyCorrections(mlhfm: Map3d, correctedAfrList: ArrayList<Double>) {
        val totalCorrectionError = mutableMapOf<Double, Double>()
        val voltage = mlhfm.yAxis.toList()
        for (i in voltage.indices) {
            totalCorrectionError[voltage[i]] = correctedAfrList[i]
            correctedAfrMap[voltage[i]] = correctedAfrList[i]
        }
        correctedMlhfm.yAxis = voltage.toTypedArray()
        val oldKghr = mlhfm.zAxis.map { it[0] }
        val newKghr = Array(oldKghr.size) { i ->
            arrayOf(maxOf(0.0, oldKghr[i] * (totalCorrectionError[voltage[i]]!! + 1)))
        }
        correctedMlhfm.zAxis = newKghr
    }

    private fun smooth(correctedAfrList: ArrayList<Double>, window: Int) {
        val halfWindow = window / 2
        val meanTempCorrections = correctedAfrList.map { it }.toDoubleArray()
        val mean = Mean()
        for (i in meanTempCorrections.indices) {
            if (i > halfWindow && i < meanTempCorrections.size - halfWindow) {
                correctedAfrList[i] = mean.evaluate(meanTempCorrections, i - halfWindow, window)
            }
        }
    }

    private fun postProcessCorrections(correctedAfrList: ArrayList<Double>) {
        var foundStart = false
        var lastValidCorrectionIndex = -1
        for (i in correctedAfrList.indices) {
            val value = correctedAfrList[i]
            when {
                value.isNaN() && !foundStart -> correctedAfrList[i] = 0.0
                !value.isNaN() && !foundStart -> { foundStart = true; lastValidCorrectionIndex = i }
                value.isNaN() -> correctedAfrList[i] = correctedAfrList[lastValidCorrectionIndex]
                else -> lastValidCorrectionIndex = i
            }
        }
    }

    private fun processCorrections(mlhfmVoltage: List<Double>): ArrayList<Double> {
        val correctedAfrList = arrayListOf<Double>()
        val mean = Mean()
        for (voltage in mlhfmVoltage) {
            val corrections = correctionsAfrMap[voltage]!!
            val meanValue = mean.evaluate(corrections.toDoubleArray(), 0, corrections.size)
            meanAfrMap[voltage] = meanValue
            val mode = StatUtils.mode(corrections.toDoubleArray())
            modeAfrMap[voltage] = mode
            var correction = meanValue
            for (v in mode) correction += v
            correction /= 1 + mode.size
            correctedAfrList.add(correction)
        }
        return correctedAfrList
    }

    private fun calculateCorrections(me7LogList: List<Map<Me7LogFileContract.Header, List<Double>>>, afrLogList: List<Map<String, List<Double>>>, mlhfmVoltageList: List<Double>) {
        val size = minOf(me7LogList.size, afrLogList.size)
        if (me7LogList.size != afrLogList.size) {
            System.err.println("[OpenLoopMlhfmCorrectionManager] Warning: ME7 pull count (${me7LogList.size}) does not match AFR pull count (${afrLogList.size}). Only the first $size pulls will be used.")
        }

        for (mlhfmVoltage in mlhfmVoltageList) {
            correctionsAfrMap[mlhfmVoltage] = mutableListOf()
        }

        for (i in 0 until size) {
            val me7Log = me7LogList[i]
            val afrLog = afrLogList[i]

            for (j in mlhfmVoltageList.indices) {
                val mlhfmVoltage = mlhfmVoltageList[j]
                val me7VoltageList = me7Log[Me7LogFileContract.Header.MAF_VOLTAGE_HEADER]!!
                val me7VoltageIndices = getVoltageToMatchIndices(j, mlhfmVoltageList, me7VoltageList)

                for (me7Index in me7VoltageIndices) {
                    if (me7Index != 0 && me7Index != me7VoltageList.size - 1) {
                        val stft = me7Log[Me7LogFileContract.Header.STFT_COLUMN_HEADER]!![me7Index] - 1
                        val ltft = me7Log[Me7LogFileContract.Header.LTFT_COLUMN_HEADER]!![me7Index] - 1
                        val rpm = me7Log[Me7LogFileContract.Header.RPM_COLUMN_HEADER]!![me7Index]
                        val targetAfr = me7Log[Me7LogFileContract.Header.REQUESTED_LAMBDA_HEADER]!![me7Index]

                        val afrIndex = Index.getInsertIndex(afrLog[AfrLogFileContract.RPM_HEADER]!!, rpm)
                        val afrList = afrLog[AfrLogFileContract.AFR_HEADER]!!
                        val afr = afrList[minOf(afrIndex, afrList.size - 1)] / 14.7
                        val rawAfr = afr / (1 - (stft + ltft))
                        val afrCorrection = (rawAfr / targetAfr) - 1

                        correctionsAfrMap[mlhfmVoltage]!!.add(afrCorrection)
                    } else {
                        correctionsAfrMap[mlhfmVoltage]!!.add(Double.NaN)
                    }
                }
            }
        }
    }

    private fun getVoltageToMatchIndices(mlhfmVoltageToMatchIndex: Int, mlhfmVoltageList: List<Double>, me7VoltageList: List<Double>): List<Int> {
        val previousIndex = mlhfmVoltageToMatchIndex - 1
        val nextIndex = mlhfmVoltageToMatchIndex + 1

        val lowValue = if (previousIndex >= 0) mlhfmVoltageList[previousIndex] else mlhfmVoltageList[mlhfmVoltageToMatchIndex] - 0.0001
        val highValue = if (nextIndex <= mlhfmVoltageList.size - 1) mlhfmVoltageList[nextIndex] else mlhfmVoltageList[mlhfmVoltageToMatchIndex] + 0.0001

        return me7VoltageList.indices.filter { me7VoltageList[it] in (lowValue + Double.MIN_VALUE)..highValue }
    }

    companion object {
        private const val LAMBDA_CONTROL_ENABLED = 0.0
    }
}
