package ui.screens.axisrescaler

import domain.math.map.Map3d

/**
 * Parses TSV (tab-separated values) clipboard content into a [Map3d].
 *
 * Expected format (e.g. from Excel or TunerPro):
 * ```
 *        10    20    30    40
 * 1000   1.0   2.0   3.0   4.0
 * 2000   5.0   6.0   7.0   8.0
 * ```
 * First row is the X-axis, first column of subsequent rows is the Y-axis,
 * and the remaining cells are Z data.
 */
object MapClipboardParser {

    /**
     * Parse a TSV table into [Map3d].
     *
     * @return a [Map3d] with xAxis, yAxis, and zAxis populated, or `null`
     *         if the input is blank or cannot be parsed into a valid map.
     */
    fun parseTsv(text: String): Map3d? {
        val lines = text.trim().lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return null

        val headerCells = lines[0].split("\t").map { it.trim() }

        // Determine whether the top-left cell is empty/non-numeric (standard format)
        // or if the first row is entirely numeric (no Y-axis header column).
        val firstCellIsHeader = headerCells.isNotEmpty() &&
            headerCells[0].toDoubleOrNull() == null

        val xValues: List<Double>
        val dataLines: List<String>

        if (firstCellIsHeader) {
            // Top-left cell is a label or empty — X-axis starts at index 1
            xValues = headerCells.drop(1).mapNotNull { it.toDoubleOrNull() }
            dataLines = lines.drop(1)
        } else {
            // Entire first row is numeric — treat it all as X-axis,
            // and data rows have Y value in first column
            xValues = headerCells.mapNotNull { it.toDoubleOrNull() }
            dataLines = lines.drop(1)
        }

        if (xValues.isEmpty() || dataLines.isEmpty()) return null

        val yValues = mutableListOf<Double>()
        val zRows = mutableListOf<Array<Double>>()

        for (line in dataLines) {
            val cells = line.split("\t").map { it.trim() }
            if (cells.isEmpty()) continue

            val yVal = cells[0].toDoubleOrNull() ?: continue
            yValues.add(yVal)

            val zRow = cells.drop(1).map { it.toDoubleOrNull() ?: 0.0 }
            // Pad or trim to match X-axis length
            val paddedRow = Array(xValues.size) { idx ->
                if (idx < zRow.size) zRow[idx] else 0.0
            }
            zRows.add(paddedRow)
        }

        if (yValues.isEmpty() || zRows.isEmpty()) return null

        return Map3d(
            xValues.toTypedArray().toDoubleArray().toTypedArray(),
            yValues.toTypedArray().toDoubleArray().toTypedArray(),
            zRows.toTypedArray()
        )
    }
}
