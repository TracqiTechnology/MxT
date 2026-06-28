package data.parser.a2l

import data.parser.xdf.AxisDefinition
import data.parser.xdf.TableDefinition
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

    // ── CHARACTERISTIC parsing ───────────────────────────────────────────────
    // /begin CHARACTERISTIC name "desc" type address recordLayout maxDiff compuMethod lo hi ... /end CHARACTERISTIC
    private val CHARACTERISTIC_PATTERN = Regex(
        """/begin CHARACTERISTIC\s+(\S+)\s+"([^"]*)"\s+(\S+)\s+(0x[0-9A-Fa-f]+|\d+)\s+(\S+)\s+([\d.eE+\-]+)\s+(\S+)\s+([\d.eE+\-]+)\s+([\d.eE+\-]+)(.*?)/end CHARACTERISTIC""",
        RegexOption.DOT_MATCHES_ALL
    )
    // /begin AXIS_DESCR axisType input compuMethod count lo hi ... /end AXIS_DESCR
    private val AXIS_DESCR_PATTERN = Regex(
        """/begin AXIS_DESCR\s+(\S+)\s+(\S+)\s+(\S+)\s+(\d+)\s+([\d.eE+\-]+)\s+([\d.eE+\-]+)(.*?)/end AXIS_DESCR""",
        RegexOption.DOT_MATCHES_ALL
    )
    private val AXIS_PTS_REF_PATTERN = Regex("""AXIS_PTS_REF\s+(\S+)""")

    // ── AXIS_PTS parsing ─────────────────────────────────────────────────────
    // /begin AXIS_PTS name "desc" address inputQty recordLayout maxDiff compuMethod count lo hi ... /end AXIS_PTS
    private val AXIS_PTS_BLOCK_PATTERN = Regex(
        """/begin AXIS_PTS\s+(\S+)\s+"([^"]*)"\s+(0x[0-9A-Fa-f]+|\d+)\s+(\S+)\s+(\S+)\s+([\d.eE+\-]+)\s+(\S+)\s+(\d+)\s+([\d.eE+\-]+)\s+([\d.eE+\-]+)(.*?)/end AXIS_PTS""",
        RegexOption.DOT_MATCHES_ALL
    )

    // ── RECORD_LAYOUT parsing ────────────────────────────────────────────────
    private val RECORD_LAYOUT_PATTERN = Regex(
        """/begin RECORD_LAYOUT\s+(\S+)(.*?)/end RECORD_LAYOUT""",
        RegexOption.DOT_MATCHES_ALL
    )
    private val FNC_VALUES_ENTRY  = Regex("""FNC_VALUES\s+(\d+)\s+(\S+)\s+(\S+)""")
    private val AXIS_PTS_X_ENTRY  = Regex("""AXIS_PTS_X\s+(\d+)\s+(\S+)""")
    private val AXIS_PTS_Y_ENTRY  = Regex("""AXIS_PTS_Y\s+(\d+)\s+(\S+)""")
    private val NO_AXIS_PTS_X_ENTRY = Regex("""NO_AXIS_PTS_X\s+(\d+)\s+(\S+)""")
    private val NO_AXIS_PTS_Y_ENTRY = Regex("""NO_AXIS_PTS_Y\s+(\d+)\s+(\S+)""")

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
     * Parse an A2L file.
     * Extracts COMPU_METHODs, MEASUREMENTs, CHARACTERISTICs, AXIS_PTS, and RECORD_LAYOUTs.
     */
    fun parse(file: File): A2lParseResult {
        val content = file.readText(LATIN1)
        return parse(content)
    }

    /**
     * Parse A2L content from a string (for testing and internal use).
     */
    fun parse(content: String): A2lParseResult {
        val methods        = parseCompuMethods(content)
        val measurements   = parseMeasurements(content)
        val recordLayouts  = parseRecordLayouts(content)
        val axisPts        = parseAxisPts(content)
        val characteristics = parseCharacteristics(content)
        return A2lParseResult(methods, measurements, characteristics, axisPts, recordLayouts)
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
        val seenNames = mutableSetOf<String>()

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
                    val elemName = "${name}_$i"
                    if (!seenNames.add(elemName)) continue   // skip duplicate
                    val elemAddr = addr + i * sizeBytes
                    measurements.add(
                        A2lMeasurement(
                            name = elemName,
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
                if (!seenNames.add(name)) continue          // skip duplicate
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

    // ─────────────────────────────────────────────────────────────────────────
    // RECORD_LAYOUT parser
    // ─────────────────────────────────────────────────────────────────────────

    fun parseRecordLayouts(content: String): Map<String, A2lRecordLayout> {
        val result = mutableMapOf<String, A2lRecordLayout>()
        for (m in RECORD_LAYOUT_PATTERN.findAll(content)) {
            val name  = m.groupValues[1]
            val block = m.groupValues[2]

            val fncMatch  = FNC_VALUES_ENTRY.find(block)
            val axXMatch  = AXIS_PTS_X_ENTRY.find(block)
            val axYMatch  = AXIS_PTS_Y_ENTRY.find(block)
            val noXMatch  = NO_AXIS_PTS_X_ENTRY.find(block)
            val noYMatch  = NO_AXIS_PTS_Y_ENTRY.find(block)

            val isCol = fncMatch?.groupValues?.get(3)?.trim()?.startsWith("COLUMN") ?: false

            result[name] = A2lRecordLayout(
                name        = name,
                fncValues   = fncMatch?.let  { A2lLayoutEntry(it.groupValues[1].toInt(), it.groupValues[2]) },
                axisPtsX    = axXMatch?.let  { A2lLayoutEntry(it.groupValues[1].toInt(), it.groupValues[2]) },
                axisPtsY    = axYMatch?.let  { A2lLayoutEntry(it.groupValues[1].toInt(), it.groupValues[2]) },
                noAxisPtsX  = noXMatch?.let  { A2lLayoutEntry(it.groupValues[1].toInt(), it.groupValues[2]) },
                noAxisPtsY  = noYMatch?.let  { A2lLayoutEntry(it.groupValues[1].toInt(), it.groupValues[2]) },
                isColumnDir = isCol
            )
        }
        return result
    }

    // ─────────────────────────────────────────────────────────────────────────
    // AXIS_PTS parser
    // ─────────────────────────────────────────────────────────────────────────

    fun parseAxisPts(content: String): Map<String, A2lAxisPts> {
        val result = mutableMapOf<String, A2lAxisPts>()
        for (m in AXIS_PTS_BLOCK_PATTERN.findAll(content)) {
            val name        = m.groupValues[1]
            val desc        = m.groupValues[2]
            val addrStr     = m.groupValues[3]
            val inputQty    = m.groupValues[4]
            val recLayout   = m.groupValues[5]
            // group 6 = maxDiff, ignore
            val compuMethod = m.groupValues[7]
            val count       = m.groupValues[8].toIntOrNull() ?: 1

            val address = try { java.lang.Long.decode(addrStr) } catch (e: Exception) { continue }

            result[name] = A2lAxisPts(
                name             = name,
                description      = desc,
                address          = address,
                inputQuantity    = inputQty,
                recordLayoutName = recLayout,
                compuMethodName  = compuMethod,
                count            = count
            )
        }
        return result
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CHARACTERISTIC parser
    // ─────────────────────────────────────────────────────────────────────────

    fun parseCharacteristics(content: String): List<A2lCharacteristic> {
        val result = mutableListOf<A2lCharacteristic>()
        for (m in CHARACTERISTIC_PATTERN.findAll(content)) {
            val name        = m.groupValues[1]
            val desc        = m.groupValues[2]
            val type        = m.groupValues[3]
            val addrStr     = m.groupValues[4]
            val recLayout   = m.groupValues[5]
            // group 6 = maxDiff
            val compuMethod = m.groupValues[7]
            val lo          = m.groupValues[8].toDoubleOrNull() ?: 0.0
            val hi          = m.groupValues[9].toDoubleOrNull() ?: 0.0
            val rest        = m.groupValues[10]

            val address = try { java.lang.Long.decode(addrStr) } catch (e: Exception) { continue }

            val axes = AXIS_DESCR_PATTERN.findAll(rest).map { ax ->
                val axType   = ax.groupValues[1]
                val inputQty = ax.groupValues[2]
                val axCm     = ax.groupValues[3]
                val count    = ax.groupValues[4].toIntOrNull() ?: 1
                val axLo     = ax.groupValues[5].toDoubleOrNull() ?: 0.0
                val axHi     = ax.groupValues[6].toDoubleOrNull() ?: 0.0
                val axRest   = ax.groupValues[7]
                val ptsRef   = AXIS_PTS_REF_PATTERN.find(axRest)?.groupValues?.get(1) ?: ""
                A2lAxisDescr(axType, inputQty, axCm, count, axLo, axHi, ptsRef)
            }.toList()

            result.add(A2lCharacteristic(name, desc, type, address, recLayout, compuMethod, lo, hi, axes))
        }
        return result
    }

    // ─────────────────────────────────────────────────────────────────────────
    // A2L → TableDefinition conversion (for use in BinParser)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Convert a list of A2L CHARACTERISTICs to [TableDefinition]s that BinParser
     * can read directly.
     *
     * MED9 is Big-Endian (PowerPC/MPC562) → [lsbFirst] = false for all axes.
     */
    fun toTableDefinitions(
        characteristics: List<A2lCharacteristic>,
        compuMethods: Map<String, A2lCompuMethod>,
        recordLayouts: Map<String, A2lRecordLayout>,
        axisPtsList: Map<String, A2lAxisPts>
    ): List<TableDefinition> = characteristics.mapNotNull { char ->
        characteristicToTableDef(char, compuMethods, recordLayouts, axisPtsList)
    }

    private fun characteristicToTableDef(
        char: A2lCharacteristic,
        compuMethods: Map<String, A2lCompuMethod>,
        recordLayouts: Map<String, A2lRecordLayout>,
        axisPtsList: Map<String, A2lAxisPts>
    ): TableDefinition? {
        val layout  = recordLayouts[char.recordLayoutName]
        val zCm     = compuMethods[char.compuMethodName]
        val zEq     = zCm?.let { buildEquation(compuToEcuConversion(it)) } ?: "X"
        val zUnit   = zCm?.unit ?: ""
        val zSigned = layout?.fncValues?.let { isSignedType(it.dataType) } ?: false
        val zBits   = layout?.fncValues?.let { dataTypeSizeBits(it.dataType) } ?: 16
        val isColMaj = layout?.isColumnDir ?: false

        return when (char.type) {
            "VALUE" -> {
                val zAxis = makeAxis(
                    id = char.name, address = char.address.toInt(),
                    rows = 1, cols = 1, sizeBits = zBits, signed = zSigned,
                    unit = zUnit, eq = zEq
                )
                TableDefinition(char.name, char.description, null, null, zAxis)
            }

            "CURVE" -> {
                val xDescr  = char.axes.getOrNull(0) ?: return null
                val xCount  = xDescr.count
                val xBits   = layout?.axisPtsX?.let { dataTypeSizeBits(it.dataType) } ?: 8
                val xCm     = compuMethods[xDescr.compuMethodName]
                val xEq     = xCm?.let { buildEquation(compuToEcuConversion(it)) } ?: "X"
                val xUnit   = xCm?.unit ?: ""

                val xAddr = resolveAxisAddress(xDescr, layout, char, axisPtsList, recordLayouts, xCount, 1, isX = true)
                val zAddr = resolveZDataAddress(layout, char, xCount, 1)

                val xAxis = makeAxis("${char.name}_x", xAddr, 1, xCount, xBits, false, xUnit, xEq)
                val zAxis = makeAxis(char.name, zAddr, 1, xCount, zBits, zSigned, zUnit, zEq,
                    isColMaj = isColMaj)
                TableDefinition(char.name, char.description, xAxis, null, zAxis)
            }

            "MAP" -> {
                val xDescr  = char.axes.getOrNull(0) ?: return null
                val yDescr  = char.axes.getOrNull(1) ?: return null
                val xCount  = xDescr.count
                val yCount  = yDescr.count
                val xBits   = layout?.axisPtsX?.let { dataTypeSizeBits(it.dataType) } ?: 8
                val yBits   = layout?.axisPtsY?.let { dataTypeSizeBits(it.dataType) } ?: 8
                val xCm     = compuMethods[xDescr.compuMethodName]
                val yCm     = compuMethods[yDescr.compuMethodName]
                val xEq     = xCm?.let { buildEquation(compuToEcuConversion(it)) } ?: "X"
                val yEq     = yCm?.let { buildEquation(compuToEcuConversion(it)) } ?: "X"
                val xUnit   = xCm?.unit ?: ""
                val yUnit   = yCm?.unit ?: ""

                val xAddr = resolveAxisAddress(xDescr, layout, char, axisPtsList, recordLayouts, xCount, yCount, isX = true)
                val yAddr = resolveAxisAddress(yDescr, layout, char, axisPtsList, recordLayouts, xCount, yCount, isX = false)
                val zAddr = resolveZDataAddress(layout, char, xCount, yCount)

                val xAxis = makeAxis("${char.name}_x", xAddr, 1, xCount, xBits, false, xUnit, xEq)
                val yAxis = makeAxis("${char.name}_y", yAddr, 1, yCount, yBits, false, yUnit, yEq)
                val zAxis = makeAxis(char.name, zAddr, yCount, xCount, zBits, zSigned, zUnit, zEq,
                    isColMaj = isColMaj)
                TableDefinition(char.name, char.description, xAxis, yAxis, zAxis)
            }

            else -> null  // CUBOID, CUBE_4, CUBE_5 not yet supported
        }
    }

    /**
     * Compute the binary address of an axis (x or y) within a CHARACTERISTIC.
     * Handles both STD_AXIS (embedded in the characteristic record) and
     * COM_AXIS (separate AXIS_PTS block with its own address).
     */
    private fun resolveAxisAddress(
        descr: A2lAxisDescr,
        layout: A2lRecordLayout?,
        char: A2lCharacteristic,
        axisPtsList: Map<String, A2lAxisPts>,
        recordLayouts: Map<String, A2lRecordLayout>,
        xCount: Int,
        yCount: Int,
        isX: Boolean
    ): Int {
        return if (descr.axisType == "COM_AXIS" && descr.axisPtsRef.isNotEmpty()) {
            // Independent AXIS_PTS block — locate the actual array data within it
            val pts     = axisPtsList[descr.axisPtsRef] ?: return 0
            val ptsLayout = recordLayouts[pts.recordLayoutName]
            computeAxisPtsDataOffset(pts, ptsLayout)
        } else {
            // STD_AXIS (or FIX_AXIS → virtual, address 0 OK): embedded in the record
            computeEmbeddedAxisOffset(char.address, layout, xCount, yCount, isX)
        }
    }

    /** Address of z-data within a CHARACTERISTIC's record. */
    private fun resolveZDataAddress(
        layout: A2lRecordLayout?,
        char: A2lCharacteristic,
        xCount: Int,
        yCount: Int
    ): Int = computeBlockOffset(char.address, layout, xCount, yCount, role = "z")

    /**
     * Compute the offset of the actual axis values within an AXIS_PTS block.
     * Typically: skip NO_AXIS_PTS_X (1 element), then AXIS_PTS_X data starts.
     */
    private fun computeAxisPtsDataOffset(pts: A2lAxisPts, layout: A2lRecordLayout?): Int {
        if (layout == null) return pts.address.toInt()

        data class Entry(val pos: Int, val dtype: String, val count: Int, val isTarget: Boolean)
        val entries = buildList {
            layout.noAxisPtsX?.let { add(Entry(it.position, it.dataType, 1, false)) }
            layout.axisPtsX?.let   { add(Entry(it.position, it.dataType, pts.count, true)) }
        }.sortedBy { it.pos }

        var offset = pts.address.toInt()
        for (e in entries) {
            if (e.isTarget) return offset
            offset += dataTypeToSizeBytes(e.dtype) * e.count
        }
        return pts.address.toInt()
    }

    /** Compute the offset of an embedded axis (STD_AXIS x or y) within a CHARACTERISTIC record. */
    private fun computeEmbeddedAxisOffset(
        baseAddress: Long,
        layout: A2lRecordLayout?,
        xCount: Int,
        yCount: Int,
        isX: Boolean
    ): Int = computeBlockOffset(baseAddress, layout, xCount, yCount, role = if (isX) "x" else "y")

    /**
     * Walk the RECORD_LAYOUT in position order and return the byte address of
     * the element with [role] ("x", "y", or "z").
     */
    private fun computeBlockOffset(
        baseAddress: Long,
        layout: A2lRecordLayout?,
        xCount: Int,
        yCount: Int,
        role: String
    ): Int {
        if (layout == null) {
            // Fallback: assume axes immediately precede z-data (each UBYTE)
            return (baseAddress + when (role) {
                "x"  -> 0L
                "y"  -> xCount.toLong()
                "z"  -> xCount.toLong() + yCount.toLong()
                else -> 0L
            }).toInt()
        }

        data class Entry(val role2: String, val pos: Int, val dtype: String, val count: Int)
        val entries = buildList {
            layout.noAxisPtsX?.let { add(Entry("no_x", it.position, it.dataType, 1)) }
            layout.noAxisPtsY?.let { add(Entry("no_y", it.position, it.dataType, 1)) }
            layout.axisPtsX?.let   { add(Entry("x",    it.position, it.dataType, xCount)) }
            layout.axisPtsY?.let   { add(Entry("y",    it.position, it.dataType, yCount)) }
            layout.fncValues?.let  { add(Entry("z",    it.position, it.dataType, xCount * maxOf(yCount, 1))) }
        }.sortedBy { it.pos }

        var offset = baseAddress.toInt()
        for (entry in entries) {
            if (entry.role2 == role) return offset
            offset += dataTypeToSizeBytes(entry.dtype) * entry.count
        }
        return baseAddress.toInt()
    }

    /** Build an AxisDefinition for Big-Endian MED9 data. */
    private fun makeAxis(
        id: String,
        address: Int,
        rows: Int,
        cols: Int,
        sizeBits: Int,
        signed: Boolean,
        unit: String,
        eq: String,
        isColMaj: Boolean = false
    ) = AxisDefinition(
        id            = id,
        type          = if (signed) 1 else 0,
        address       = address,
        indexCount    = rows * cols,
        sizeBits      = sizeBits,
        rowCount      = rows,
        columnCount   = cols,
        unit          = unit,
        equation      = eq,
        varId         = "X",
        axisValues    = emptyList(),
        lsbFirst      = false,          // MED9 is PowerPC Big-Endian
        isColumnMajor = isColMaj
    )

    /** Build a JavaScript equation string from an EcuConversion (linear/inverse). */
    private fun buildEquation(conv: EcuConversion): String = when {
        conv.inverse == 1               -> "${conv.factor} / X"
        conv.factor == 1.0 && conv.offset == 0.0 -> "X"
        conv.factor == 1.0              -> "X - ${conv.offset}"
        conv.offset == 0.0              -> "X * ${conv.factor}"
        else                            -> "X * ${conv.factor} - ${conv.offset}"
    }

    private fun dataTypeSizeBits(dtype: String): Int = when (dtype) {
        "UBYTE", "SBYTE"        -> 8
        "UWORD", "SWORD"        -> 16
        "ULONG", "SLONG"        -> 32
        "FLOAT32_IEEE"          -> 32
        else                    -> 16
    }

    private fun isSignedType(dtype: String): Boolean =
        dtype.startsWith("S") || dtype == "FLOAT32_IEEE"

    // ─────────────────────────────────────────────────────────────────────────

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
        "UBYTE", "SBYTE"   -> 1
        "UWORD", "SWORD"   -> 2
        "ULONG", "SLONG"   -> 4
        "FLOAT32_IEEE"     -> 4
        else               -> 2
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
    val measurements: List<A2lMeasurement>,
    val characteristics: List<A2lCharacteristic> = emptyList(),
    val axisPts: Map<String, A2lAxisPts> = emptyMap(),
    val recordLayouts: Map<String, A2lRecordLayout> = emptyMap()
) {
    /**
     * Convert all CHARACTERISTICs to TableDefinitions for use in BinParser.
     * Only VALUE, CURVE, and MAP types are supported.
     */
    fun toTableDefinitions(): List<TableDefinition> =
        A2lParser.toTableDefinitions(characteristics, compuMethods, recordLayouts, axisPts)
}
