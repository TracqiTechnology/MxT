package data.parser.a2l

import java.io.File
import java.nio.charset.Charset

/**
 * Parser for ASAP2/A2L files.
 *
 * Extracts COMPU_METHOD and MEASUREMENT blocks from DAMOS A2L files.
 * MED9 A2L files contain ~15,270 MEASUREMENT entries with RAM addresses,
 * data types, and conversion formulas — everything needed to generate
 * ME7Logger .ecu files.
 *
 * A2L files use latin-1 encoding.
 */
object A2lParser {

    private val LATIN1 = Charset.forName("ISO-8859-1")

    // COMPU_METHOD block: /begin COMPU_METHOD name "desc" ... /end COMPU_METHOD
    private val COMPU_METHOD_PATTERN = Regex(
        """/begin COMPU_METHOD\s+(\S+)\s+"([^"]*)".*?/end COMPU_METHOD""",
        RegexOption.DOT_MATCHES_ALL
    )

    // COEFFS line within COMPU_METHOD
    private val COEFFS_PATTERN = Regex(
        """COEFFS\s+([\d.eE+\-]+)\s+([\d.eE+\-]+)\s+([\d.eE+\-]+)\s+([\d.eE+\-]+)\s+([\d.eE+\-]+)\s+([\d.eE+\-]+)"""
    )

    // Quoted strings in COMPU_METHOD block (desc, format, unit)
    private val QUOTED_STRING_PATTERN = Regex(""""([^"]*)"""")

    // MEASUREMENT block
    private val MEASUREMENT_PATTERN = Regex(
        """/begin MEASUREMENT\s+(\S+)\s+"([^"]*)"\s+(\S+)\s+(\S+)\s+(\S+)\s+(\S+)\s+([\d.eE+\-]+)\s+([\d.eE+\-]+)(.*?)/end MEASUREMENT""",
        RegexOption.DOT_MATCHES_ALL
    )

    private val ECU_ADDRESS_PATTERN = Regex("""ECU_ADDRESS\s+(0x[0-9A-Fa-f]+)""")
    private val ARRAY_SIZE_PATTERN = Regex("""ARRAY_SIZE\s+(\d+)""")

    /** Well-known aliases for common signals. */
    val ALIASES = mapOf(
        "nmot_w" to "EngineSpeed",
        "nmot" to "EngineSpeed",
        "rl_w" to "EngineLoad",
        "rl" to "EngineLoad",
        "rlsol_w" to "EngineLoadRequested",
        "rlmax_w" to "EngineLoadCorrected",
        "rlmx_w" to "EngineLoadSpecified",
        "mshfm_w" to "MassAirFlow",
        "pvdks_w" to "BoostPressureActual",
        "pssol_w" to "ManifoldPressureRequested",
        "plsol_w" to "BoostPressureSpecified",
        "ldtvm" to "WastegateDutyCycle",
        "fr_w" to "LambdaControl",
        "fra_w" to "AdaptationPartial",
        "frm_w" to "LambdaControlAvg",
        "ti_w" to "InjectionTime",
        "ti_l" to "InjectionTimeLong",
        "wped_w" to "AccelPedalPosition",
        "wdkba" to "ThrottlePlateAngle",
        "tmot" to "CoolantTemperature",
        "tmotlin" to "CoolantTemperatureLinear",
        "tans" to "IntakeAirTemperature",
        "tanslin" to "IntakeAirTemperatureLinear",
        "wub" to "BatteryVoltage",
        "gangi" to "SelectedGear",
        "vfil_w" to "VehicleSpeed",
        "lamfa_w" to "TargetAFRDriverRequest",
        "lamsbg_w" to "AirFuelRatioRequired",
        "lamsoni_w" to "AirFuelRatioCurrent",
        "zwist_w" to "IgnitionTimingAngle",
        "zwist" to "IgnitionTimingAngle",
        "zwout" to "IgnitionTimingAngleOverall",
        "pu_w" to "AtmosphericPressure",
        "pus_w" to "AtmosphericPressure",
        "fho_w" to "AltitudeCorrectionFactor",
        "tabgm_w" to "EGTModelBeforeCat",
        "prhd_w" to "RailPressure",
        "prhdsol_w" to "RailPressureTarget",
        "prist_w" to "RailPressure",
        "prsoll_w" to "RailPressureTarget",
        "prist_u" to "RailPressure8bit",
        "B_bl" to "BrakeLight",
        "B_br" to "BrakePedal",
        "dwkr" to "IgnitionRetardKnockControl",
        "wkrm" to "AvgIgnitionRetardKnockControl",
        "uhfm_w" to "MAFVoltage",
        "dmllri_w" to "IdleSpeedPID-I",
        "dlahi_w" to "LambdaPID-I",
        "dlahp_w" to "LambdaPID-P",
        "fzabgs_w" to "CountMisfireTotal",
    )

    /** Per-cylinder array element aliases. */
    val ARRAY_ELEMENT_ALIASES = mapOf(
        ("dwkrz" to 0) to "IgnitionRetardCyl1",
        ("dwkrz" to 1) to "IgnitionRetardCyl2",
        ("dwkrz" to 2) to "IgnitionRetardCyl3",
        ("dwkrz" to 3) to "IgnitionRetardCyl4",
        ("rkrn_w" to 0) to "KnockVoltageCyl1",
        ("rkrn_w" to 1) to "KnockVoltageCyl2",
        ("rkrn_w" to 2) to "KnockVoltageCyl3",
        ("rkrn_w" to 3) to "KnockVoltageCyl4",
        ("fzabgzyl_w" to 0) to "CountMisfireCyl1",
        ("fzabgzyl_w" to 1) to "CountMisfireCyl2",
        ("fzabgzyl_w" to 2) to "CountMisfireCyl3",
        ("fzabgzyl_w" to 3) to "CountMisfireCyl4",
    )

    /** Array signals to expand to per-cylinder entries. */
    val EXPAND_ARRAYS = mapOf(
        "dwkrz" to 4,
        "rkrn_w" to 4,
        "fzabgzyl_w" to 4,
        "prista_w" to 4,
    )

    /**
     * Parse an A2L file, returning all COMPU_METHODs and MEASUREMENT entries.
     * Handles latin-1 encoding and ARRAY_SIZE expansion.
     */
    fun parse(file: File): A2lParseResult {
        val content = file.readText(LATIN1)
        val methods = parseCompuMethods(content)
        val measurements = parseMeasurements(content)
        return A2lParseResult(methods, measurements)
    }

    /**
     * Parse A2L content from a string (for testing).
     */
    fun parse(content: String): A2lParseResult {
        val methods = parseCompuMethods(content)
        val measurements = parseMeasurements(content)
        return A2lParseResult(methods, measurements)
    }

    fun parseCompuMethods(content: String): Map<String, A2lCompuMethod> {
        val methods = mutableMapOf<String, A2lCompuMethod>()

        for (m in COMPU_METHOD_PATTERN.findAll(content)) {
            val name = m.groupValues[1]
            val desc = m.groupValues[2]
            val block = m.value

            // Unit is the 3rd quoted string (index 2); format is 2nd (index 1)
            val quotedStrings = QUOTED_STRING_PATTERN.findAll(block).map { it.groupValues[1] }.toList()
            val unit = if (quotedStrings.size > 2) quotedStrings[2] else ""
            val formatStr = if (quotedStrings.size > 1) quotedStrings[1] else ""

            val coeffsMatch = COEFFS_PATTERN.find(block)
            val coeffs = if (coeffsMatch != null) {
                doubleArrayOf(
                    coeffsMatch.groupValues[1].toDouble(),
                    coeffsMatch.groupValues[2].toDouble(),
                    coeffsMatch.groupValues[3].toDouble(),
                    coeffsMatch.groupValues[4].toDouble(),
                    coeffsMatch.groupValues[5].toDouble(),
                    coeffsMatch.groupValues[6].toDouble(),
                )
            } else {
                doubleArrayOf(0.0, 1.0, 0.0, 0.0, 0.0, 1.0)  // identity
            }

            methods[name] = A2lCompuMethod(
                name = name,
                description = desc,
                unit = unit,
                coeffs = coeffs,
                formatStr = formatStr
            )
        }

        return methods
    }

    fun parseMeasurements(content: String): List<A2lMeasurement> {
        val measurements = mutableListOf<A2lMeasurement>()

        for (m in MEASUREMENT_PATTERN.findAll(content)) {
            val name = m.groupValues[1]
            val desc = m.groupValues[2]
            val dtype = m.groupValues[3]
            val cmName = m.groupValues[4]
            val resolution = m.groupValues[5].toDoubleOrNull() ?: 0.0
            val accuracy = m.groupValues[6].toDoubleOrNull() ?: 0.0
            val lo = m.groupValues[7].toDouble()
            val hi = m.groupValues[8].toDouble()
            val rest = m.groupValues[9]

            val addrMatch = ECU_ADDRESS_PATTERN.find(rest) ?: continue
            val addr = java.lang.Long.decode(addrMatch.groupValues[1])

            val arrayMatch = ARRAY_SIZE_PATTERN.find(rest)
            val arraySize = arrayMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0

            val sizeBytes = dataTypeToSizeBytes(dtype)

            if (arraySize > 0 && name in EXPAND_ARRAYS) {
                val count = minOf(arraySize, EXPAND_ARRAYS[name]!!)
                for (i in 0 until count) {
                    val elemAddr = addr + i * sizeBytes
                    measurements.add(
                        A2lMeasurement(
                            name = "${name}_$i",
                            description = "$desc (Zyl ${i + 1})",
                            dataType = dtype,
                            compuMethodName = cmName,
                            resolution = resolution,
                            accuracy = accuracy,
                            lowerLimit = lo,
                            upperLimit = hi,
                            ecuAddress = elemAddr,
                            arraySize = 0
                        )
                    )
                }
            } else {
                measurements.add(
                    A2lMeasurement(
                        name = name,
                        description = desc,
                        dataType = dtype,
                        compuMethodName = cmName,
                        resolution = resolution,
                        accuracy = accuracy,
                        lowerLimit = lo,
                        upperLimit = hi,
                        ecuAddress = addr,
                        arraySize = arraySize
                    )
                )
            }
        }

        return measurements
    }

    /**
     * Convert A2L COMPU_METHOD to ME7Logger conversion parameters.
     *
     * A2L RAT_FUNC: INT = (a·PHYS² + b·PHYS + c) / (d·PHYS² + e·PHYS + f)
     *
     * Linear (a=0, d=0, e=0): PHYS = INT·(f/b) - (c/b) → Factor=f/b, Offset=c/b, Inverse=0
     * Inverse (a=0, b=0, d=0, f=0): PHYS = c/(e·INT) → Factor=c/e, Offset=0, Inverse=1
     */
    fun compuToEcuConversion(cm: A2lCompuMethod): EcuConversion {
        val a = cm.coeffs[0]; val b = cm.coeffs[1]; val c = cm.coeffs[2]
        val d = cm.coeffs[3]; val e = cm.coeffs[4]; val f = cm.coeffs[5]

        // Inverse: INT = c / (e*PHYS) → PHYS = c / (e*INT)
        if (b == 0.0 && a == 0.0 && d == 0.0 && f == 0.0 && e != 0.0) {
            return EcuConversion(factor = c / e, offset = 0.0, inverse = 1)
        }

        // Linear: INT = (b*PHYS + c) / f → PHYS = INT * (f/b) - (c/b)
        if (a == 0.0 && d == 0.0 && e == 0.0 && b != 0.0) {
            return EcuConversion(factor = f / b, offset = c / b, inverse = 0)
        }

        // Identity / unsupported
        return EcuConversion(factor = 1.0, offset = 0.0, inverse = 0)
    }

    /**
     * Build ME7Logger .ecu entries from parsed A2L data.
     */
    fun buildEcuEntries(
        measurements: List<A2lMeasurement>,
        methods: Map<String, A2lCompuMethod>
    ): List<EcuEntry> {
        return measurements.mapNotNull { meas ->
            val cm = methods[meas.compuMethodName] ?: return@mapNotNull null
            val (size, signed) = dataTypeToSizeSigned(meas.dataType)
            if (size > 2) return@mapNotNull null  // ME7Logger only supports 1 and 2 byte

            val conv = compuToEcuConversion(cm)
            val alias = resolveAlias(meas.name)

            EcuEntry(
                name = meas.name,
                alias = alias,
                address = meas.ecuAddress,
                size = size,
                bitmask = 0x0000,
                unit = cm.unit,
                signed = signed,
                inverse = conv.inverse,
                factor = conv.factor,
                offset = conv.offset,
                comment = meas.description
            )
        }.sortedBy { it.address }
    }

    private fun resolveAlias(name: String): String {
        ALIASES[name]?.let { return it }
        // Check array element pattern: "dwkrz_0" → lookup (dwkrz, 0)
        val arrMatch = Regex("""^(\w+)_(\d+)$""").find(name)
        if (arrMatch != null) {
            val base = arrMatch.groupValues[1]
            val idx = arrMatch.groupValues[2].toInt()
            ARRAY_ELEMENT_ALIASES[base to idx]?.let { return it }
        }
        return ""
    }

    private fun dataTypeToSizeBytes(dtype: String): Int = when (dtype) {
        "UBYTE", "SBYTE" -> 1
        "UWORD", "SWORD" -> 2
        "ULONG", "SLONG" -> 4
        else -> 2
    }

    private fun dataTypeToSizeSigned(dtype: String): Pair<Int, Int> = when (dtype) {
        "UBYTE" -> 1 to 0
        "SBYTE" -> 1 to 1
        "UWORD" -> 2 to 0
        "SWORD" -> 2 to 1
        "ULONG" -> 4 to 0
        "SLONG" -> 4 to 1
        else -> 2 to 0
    }
}

/** Result of parsing an A2L file. */
data class A2lParseResult(
    val compuMethods: Map<String, A2lCompuMethod>,
    val measurements: List<A2lMeasurement>
)
