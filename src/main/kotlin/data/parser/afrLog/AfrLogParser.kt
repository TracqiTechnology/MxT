package data.parser.afrlog

import data.contract.AfrLogFileContract
import data.contract.Me7LogFileContract
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.apache.commons.csv.CSVFormat
import java.io.File
import java.io.FileReader

object AfrLogParser {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _logs = MutableSharedFlow<Map<String, List<Double>>>(extraBufferCapacity = 1)
    val logs: SharedFlow<Map<String, List<Double>>> = _logs.asSharedFlow()

    fun load(file: File) {
        scope.launch {
            try {
                val logMap = parseFile(file)
                _logs.emit(logMap)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun load(logs: Map<Me7LogFileContract.Header, List<Double>>) {
        scope.launch {
            try {
                val logMap = parseMe7Log(logs)
                _logs.emit(logMap)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    internal fun parseMe7Log(log: Map<Me7LogFileContract.Header, List<Double>>): Map<String, List<Double>> {
        val map = mutableMapOf<String, MutableList<Double>>()

        val wideBandO2 = log[Me7LogFileContract.Header.WIDE_BAND_O2_HEADER] ?: return emptyMap()
        if (wideBandO2.isEmpty()) return emptyMap()

        map[AfrLogFileContract.START_TIME] = mutableListOf()
        map[AfrLogFileContract.TIMESTAMP] = mutableListOf()
        map[AfrLogFileContract.RPM_HEADER] = mutableListOf()
        map[AfrLogFileContract.AFR_HEADER] = mutableListOf()
        map[AfrLogFileContract.TPS_HEADER] = mutableListOf()
        map[AfrLogFileContract.BOOST_HEADER] = mutableListOf()

        val startTime = log[Me7LogFileContract.Header.START_TIME_HEADER]!!.first()
        map[AfrLogFileContract.START_TIME]!!.add(startTime)

        val absolutePressure = log[Me7LogFileContract.Header.ABSOLUTE_BOOST_PRESSURE_ACTUAL_HEADER]
        val barometricPressure = log[Me7LogFileContract.Header.BAROMETRIC_PRESSURE_HEADER]
        val hasBoostData = absolutePressure != null && barometricPressure != null && absolutePressure.isNotEmpty()

        val timestamps = log[Me7LogFileContract.Header.TIME_STAMP_COLUMN_HEADER]!!
        val rpms = log[Me7LogFileContract.Header.RPM_COLUMN_HEADER]!!
        val throttleAngles = log[Me7LogFileContract.Header.THROTTLE_PLATE_ANGLE_HEADER]!!

        for (i in timestamps.indices) {
            map[AfrLogFileContract.TIMESTAMP]!!.add(timestamps[i])
            map[AfrLogFileContract.RPM_HEADER]!!.add(rpms[i])
            // ME7.5 afr is normalized. Convert to stoichiometric gasoline ratio
            map[AfrLogFileContract.AFR_HEADER]!!.add(wideBandO2[i] * 14.7)
            map[AfrLogFileContract.TPS_HEADER]!!.add(throttleAngles[i])
            if (hasBoostData) {
                val relativePressure = (absolutePressure!![i] - barometricPressure!![i]) * 0.0145038
                map[AfrLogFileContract.BOOST_HEADER]!!.add(relativePressure)
            } else {
                map[AfrLogFileContract.BOOST_HEADER]!!.add(0.0)
            }
        }

        return map
    }

    private fun parseFile(file: File): Map<String, List<Double>> {
        var timeColumnIndex = -1
        var rpmColumnIndex = -1
        var tpsColumnIndex = -1
        var afrColumnIndex = -1
        var boostColumnIndex = -1
        var lastPsi = 0.0

        val map = mutableMapOf<String, MutableList<Double>>(
            AfrLogFileContract.START_TIME to mutableListOf(),
            AfrLogFileContract.TIMESTAMP to mutableListOf(),
            AfrLogFileContract.RPM_HEADER to mutableListOf(),
            AfrLogFileContract.AFR_HEADER to mutableListOf(),
            AfrLogFileContract.TPS_HEADER to mutableListOf(),
            AfrLogFileContract.BOOST_HEADER to mutableListOf()
        )

        try {
            FileReader(file).use { reader ->
                var headersFound = false
                val records = CSVFormat.RFC4180.parse(reader)
                val iterator = records.iterator()

                while (iterator.hasNext()) {
                    val record = iterator.next()
                    for (i in 0 until record.size()) {
                        when (record.get(i).trim()) {
                            AfrLogFileContract.TIMESTAMP -> timeColumnIndex = i
                            AfrLogFileContract.TPS_HEADER -> tpsColumnIndex = i
                            AfrLogFileContract.RPM_HEADER -> rpmColumnIndex = i
                            AfrLogFileContract.AFR_HEADER -> afrColumnIndex = i
                            AfrLogFileContract.BOOST_HEADER -> boostColumnIndex = i
                        }

                        headersFound = timeColumnIndex != -1 && rpmColumnIndex != -1 && tpsColumnIndex != -1 && afrColumnIndex != -1 && boostColumnIndex != -1
                        if (headersFound) break
                    }

                    if (headersFound) break
                }

                if (headersFound) {
                    while (iterator.hasNext()) {
                        val record = iterator.next()

                        val split = record.get(timeColumnIndex).split(":")
                        val minuteSeconds = split[1].toDouble() * 60
                        val seconds = split[2].toDouble()
                        val timestamp = minuteSeconds + seconds

                        map[AfrLogFileContract.TIMESTAMP]!!.add(timestamp)
                        map[AfrLogFileContract.TPS_HEADER]!!.add(record.get(tpsColumnIndex).toDouble())
                        map[AfrLogFileContract.RPM_HEADER]!!.add(record.get(rpmColumnIndex).toDouble())
                        map[AfrLogFileContract.AFR_HEADER]!!.add(record.get(afrColumnIndex).toDouble())

                        var psi = record.get(boostColumnIndex).toDouble()

                        if (psi > 50) {
                            psi = lastPsi
                        } else {
                            lastPsi = psi
                        }

                        // zeit reports psi for positive pressure and inHg for negative pressure
                        val mbar = if (psi >= 0) {
                            psi * 68.9476
                        } else {
                            psi * 33.8639
                        }

                        map[AfrLogFileContract.BOOST_HEADER]!!.add(mbar)
                    }
                }

                val startTime = map[AfrLogFileContract.TIMESTAMP]!!.first()
                map[AfrLogFileContract.START_TIME]!!.add(startTime)

                return map
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return emptyMap()
    }
}
