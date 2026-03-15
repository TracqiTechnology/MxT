package data.logger

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Exports a LogSession to ME7Logger-compatible CSV format.
 * The output is importable by ME7Tuner's existing Me7LogParser.
 */
object CsvExporter {

    /**
     * Export a logging session to CSV in ME7Logger format.
     *
     * Format:
     *   ; ME7Tuner Logger Export
     *   ; Log started at: 2024-01-15 14:30:00
     *   TimeStamp, nmot_w, rl_w, ...         ← names
     *     sec.ms , 1/min , %   , ...         ← units
     *   "TIME","EngineSpeed","EngineLoad",... ← aliases
     *   0.000, 2320, 51, ...                 ← data
     */
    fun export(session: LogSession, outputFile: File) {
        outputFile.bufferedWriter().use { w ->
            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            val now = LocalDateTime.now().format(formatter)

            // Comment header
            w.write("; ME7Tuner Logger Export\n")
            w.write("; Log started at: $now\n")
            w.write("; Variables: ${session.variables.size}\n")
            w.write("; Samples: ${session.samples.size}\n")
            w.write(";\n")

            if (session.variables.isEmpty()) return

            // Row 1: Signal names
            w.write(session.variables.joinToString(", ") { it.name })
            w.newLine()

            // Row 2: Units
            w.write(session.variables.joinToString(", ") { it.unit.padStart(it.name.length) })
            w.newLine()

            // Row 3: Aliases (quoted)
            w.write(session.variables.joinToString(",") { "\"${it.alias}\"" })
            w.newLine()

            // Data rows
            for (sample in session.samples) {
                w.write(sample.values.joinToString(", ") { formatValue(it) })
                w.newLine()
            }
        }
    }

    private fun formatValue(v: Double): String {
        if (v == v.toLong().toDouble() && kotlin.math.abs(v) < 1e9) {
            return v.toLong().toString()
        }
        return String.format("%.3f", v)
    }
}
