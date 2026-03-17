package data.sniffer

import data.model.EcuPlatform
import data.parser.a2l.EcuEntry
import data.parser.damos.DamosParser
import data.parser.ecu.EcuFileParser
import data.parser.hex.IntelHexParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Orchestrates the RAM address sniffing workflow.
 *
 * Workflow:
 * 1. Load reference data (DAMOS + HEX, or .ecu + BIN)
 * 2. Build signature database from reference
 * 3. Scan target BIN to discover relocated addresses
 * 4. Export results as .ecu file
 */
class RamSnifferEngine {

    private val _state = MutableStateFlow(SnifferState())
    val state: StateFlow<SnifferState> = _state

    /**
     * Build signatures from a DAMOS .dam file + Intel HEX reference binary (ME7 path).
     */
    suspend fun buildFromDamos(
        damosFile: File,
        hexFile: File,
        platform: EcuPlatform
    ): SignatureDatabase = withContext(Dispatchers.IO) {
        _state.value = _state.value.copy(phase = SnifferPhase.BUILDING, progress = 0f)

        val damos = DamosParser.parse(damosFile)
        _state.value = _state.value.copy(progress = 0.2f, statusMessage = "Parsed ${damos.umpRecords.size} UMP + ${damos.srcRecords.size} SRC records from DAMOS")

        val (baseAddress, hexData) = IntelHexParser.parseWithBaseAddress(hexFile)
        _state.value = _state.value.copy(progress = 0.4f, statusMessage = "Loaded ${hexData.size / 1024}KB HEX image at base 0x${baseAddress.toString(16)}")

        // Build variables from both UMP and SRC records (SRC has the well-known signals)
        val variableMap = mutableMapOf<String, RamVariable>()
        for (ump in damos.umpRecords.values) {
            val reg = damos.regRecords[ump.regId]
            variableMap[ump.name] = RamVariable(
                name = ump.name,
                address = ump.address,
                size = ump.sizeBytes,
                unit = reg?.unit ?: "",
                factor = if (reg != null && reg.divisor != 0.0) reg.factor / reg.divisor else 1.0,
                offset = reg?.offset ?: 0.0,
                signed = ump.isSigned,
                description = ump.description
            )
        }
        for (src in damos.srcRecords.values) {
            val reg = damos.regRecords[src.typeId]
            variableMap[src.name] = RamVariable(
                name = src.name,
                address = src.address,
                size = src.byteWidth,
                unit = reg?.unit ?: "",
                factor = if (reg != null && reg.divisor != 0.0) reg.factor / reg.divisor else 1.0,
                offset = reg?.offset ?: 0.0,
                signed = src.signed < 0 || src.signed == 1,
                description = src.description
            )
        }
        val variables = variableMap.values.toList()

        _state.value = _state.value.copy(progress = 0.5f, statusMessage = "Building signatures for ${variables.size} variables...")

        val database = SignatureBuilder.build(hexData, baseAddress, variables, platform, damos.programNumber)

        _state.value = _state.value.copy(
            phase = SnifferPhase.READY,
            progress = 1f,
            statusMessage = "Built ${database.signatures.size} signature sets from ${variables.size} variables",
            database = database
        )

        database
    }

    /**
     * Build signatures from an .ecu file + BIN reference binary (MED9 path).
     */
    suspend fun buildFromEcu(
        ecuFile: File,
        binFile: File,
        platform: EcuPlatform
    ): SignatureDatabase = withContext(Dispatchers.IO) {
        _state.value = _state.value.copy(phase = SnifferPhase.BUILDING, progress = 0f)

        val ecu = EcuFileParser.parse(ecuFile)
        _state.value = _state.value.copy(progress = 0.2f, statusMessage = "Parsed ${ecu.entries.size} entries from .ecu file")

        val binData = binFile.readBytes()
        _state.value = _state.value.copy(progress = 0.4f, statusMessage = "Loaded ${binData.size / 1024}KB BIN image")

        val baseAddress = platform.codeRanges.firstOrNull()?.first ?: 0L

        val variables = ecu.entries.values.map { entry ->
            RamVariable(
                name = entry.name,
                alias = entry.alias,
                address = entry.address,
                size = entry.size,
                unit = entry.unit,
                factor = entry.factor,
                offset = entry.offset,
                signed = entry.signed == 1,
                inverse = entry.inverse == 1,
                description = entry.comment
            )
        }

        _state.value = _state.value.copy(progress = 0.5f, statusMessage = "Building signatures for ${variables.size} variables...")

        val database = SignatureBuilder.build(binData, baseAddress, variables, platform, ecu.swNumber)

        _state.value = _state.value.copy(
            phase = SnifferPhase.READY,
            progress = 1f,
            statusMessage = "Built ${database.signatures.size} signature sets from ${variables.size} variables",
            database = database
        )

        database
    }

    /**
     * Scan a target BIN for known variable signatures.
     */
    suspend fun sniff(
        targetBinFile: File,
        database: SignatureDatabase
    ): List<SniffResult> = withContext(Dispatchers.IO) {
        _state.value = _state.value.copy(phase = SnifferPhase.SCANNING, progress = 0f)

        val targetBin = targetBinFile.readBytes()
        _state.value = _state.value.copy(progress = 0.3f, statusMessage = "Scanning ${targetBin.size / 1024}KB target binary...")

        val results = SignatureScanner.scan(targetBin, database)

        val highConfidence = results.count { it.confidence >= 0.9 }
        val medConfidence = results.count { it.confidence in 0.6..0.9 }

        _state.value = _state.value.copy(
            phase = SnifferPhase.COMPLETE,
            progress = 1f,
            statusMessage = "Discovered ${results.size}/${database.variables.size} variables " +
                "(${highConfidence} high, ${medConfidence} medium confidence)",
            results = results
        )

        results
    }

    /**
     * Convert sniff results to EcuEntry list for .ecu export.
     */
    fun exportToEcuEntries(results: List<SniffResult>, database: SignatureDatabase): List<EcuEntry> {
        return results.mapNotNull { result ->
            val variable = database.variables[result.variableName] ?: return@mapNotNull null
            EcuEntry(
                name = variable.name,
                alias = variable.alias,
                address = result.discoveredAddress,
                size = variable.size,
                bitmask = if (variable.size == 1) 0xFF else if (variable.size == 2) 0xFFFF else -1,
                unit = variable.unit,
                signed = if (variable.signed) 1 else 0,
                inverse = if (variable.inverse) 1 else 0,
                factor = variable.factor,
                offset = variable.offset,
                comment = variable.description
            )
        }.sortedBy { it.name }
    }

    fun reset() {
        _state.value = SnifferState()
    }
}

data class SnifferState(
    val phase: SnifferPhase = SnifferPhase.IDLE,
    val progress: Float = 0f,
    val statusMessage: String = "",
    val database: SignatureDatabase? = null,
    val results: List<SniffResult> = emptyList()
)

enum class SnifferPhase {
    IDLE,
    BUILDING,
    READY,
    SCANNING,
    COMPLETE
}
