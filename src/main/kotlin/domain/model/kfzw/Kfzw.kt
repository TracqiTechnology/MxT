package domain.model.kfzw

import domain.math.Index
import domain.math.LinearExtrapolation

object Kfzw {
    fun generateKfzw(xAxisOld: Array<Double>, kfzwOld: Array<Array<Double>>, xAxisNew: Array<Double>): Array<Array<Double>> {
        if (kfzwOld.isEmpty() || kfzwOld[0].isEmpty() || xAxisOld.isEmpty() || xAxisNew.isEmpty()) {
            return emptyArray()
        }
        return Array(kfzwOld.size) { i ->
            Array(xAxisNew.size) { j ->
                var indexKey = Index.getInsertIndex(xAxisOld.toList(), xAxisNew[j])

                if (indexKey > 0 && indexKey < kfzwOld[i].size - 1 && xAxisNew[j] < xAxisOld[indexKey]) {
                    indexKey--
                }

                val x0: Double
                val x1: Double
                val y0: Double
                val y1: Double

                if (indexKey < kfzwOld[i].size - 1) {
                    x0 = xAxisOld[indexKey]
                    x1 = xAxisOld[indexKey + 1]
                    y0 = kfzwOld[i][indexKey]
                    y1 = kfzwOld[i][indexKey + 1]
                } else {
                    x0 = xAxisOld[indexKey - 1]
                    x1 = xAxisOld[indexKey]
                    y0 = kfzwOld[i][indexKey - 1]
                    y1 = kfzwOld[i][indexKey]
                }

                maxOf(LinearExtrapolation.extrapolate(arrayOf(x0, x1), arrayOf(y0, y1), xAxisNew[j]), -13.5)
            }
        }
    }
}
