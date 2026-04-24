package data.parser.ecu

/**
 * Parsed representation of an ME7Logger .cfg file.
 */
data class CfgFile(
    val ecuFilename: String,
    val samplesPerSecond: Int,
    val variables: List<CfgVariable>
)

/**
 * A variable entry from the [LogVariables] section of a .cfg file.
 */
data class CfgVariable(
    val name: String,
    val alias: String,
    val comment: String
)

/**
 * Parses ME7Logger-format .cfg files into [CfgFile].
 *
 * Format:
 * ```
 * [Configuration]
 * ECUCharacteristics = filename.ecu
 * SamplesPerSecond   = 20
 *
 * [LogVariables]
 * varname           ;{Alias}          ; {Comment}
 * ```
 */
object CfgFileParser {

    fun parse(text: String): CfgFile {
        var ecuFilename = ""
        var samplesPerSecond = 20
        val variables = mutableListOf<CfgVariable>()
        var currentSection: String? = null

        for (line in text.lines()) {
            val trimmed = line.trim()

            // Skip comments and blanks
            if (trimmed.isEmpty() || trimmed.startsWith(";")) continue

            // Section header
            val sectionMatch = SECTION_REGEX.matchEntire(trimmed)
            if (sectionMatch != null) {
                currentSection = sectionMatch.groupValues[1]
                continue
            }

            when (currentSection) {
                "Configuration" -> {
                    val eqIdx = trimmed.indexOf('=')
                    if (eqIdx > 0) {
                        val key = trimmed.substring(0, eqIdx).trim()
                        val value = trimmed.substring(eqIdx + 1).split(';').first().trim()
                        when (key) {
                            "ECUCharacteristics" -> ecuFilename = value
                            "SamplesPerSecond" -> samplesPerSecond = value.toIntOrNull() ?: 20
                        }
                    }
                }
                "LogVariables" -> {
                    val variable = parseLogVariable(trimmed) ?: continue
                    variables.add(variable)
                }
            }
        }

        return CfgFile(
            ecuFilename = ecuFilename,
            samplesPerSecond = samplesPerSecond,
            variables = variables
        )
    }

    fun parse(file: java.io.File): CfgFile = parse(file.readText())

    /**
     * Parse a log variable line:
     * `varname           ;{Alias}          ; {Comment}`
     *
     * The name is the first whitespace-delimited token.
     * Everything after the first `;` is split for alias and comment.
     */
    private fun parseLogVariable(line: String): CfgVariable? {
        // Split on first semicolon: name part ; rest
        val semiIdx = line.indexOf(';')
        val name = if (semiIdx >= 0) line.substring(0, semiIdx).trim() else line.trim()
        if (name.isEmpty()) return null

        var alias = ""
        var comment = ""

        if (semiIdx >= 0) {
            val rest = line.substring(semiIdx + 1)
            // Second semicolon separates alias from comment
            val parts = rest.split(';', limit = 2)
            alias = stripBraces(parts[0].trim())
            if (parts.size > 1) {
                comment = stripBraces(parts[1].trim())
            }
        }

        return CfgVariable(name = name, alias = alias, comment = comment)
    }

    private fun stripBraces(s: String): String =
        s.removeSurrounding("{", "}").trim()

    private val SECTION_REGEX = Regex("""\[(\w+)]""")
}
