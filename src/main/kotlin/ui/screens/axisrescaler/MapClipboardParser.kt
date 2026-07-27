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
        return try {
            parseTsvOrThrow(text)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun parseTsvOrThrow(text: String): Map3d {
        require(text.isNotBlank()) { "Clipboard is empty" }
        val lines = text.trimEnd().lines().filter { it.isNotBlank() }
        require(lines.size >= 2) { "Paste a header row and at least one data row" }

        val headerCells = lines[0].split("\t").map { it.trim() }

        // Determine whether the top-left cell is empty/non-numeric (standard format)
        // or if the first row is entirely numeric (no Y-axis header column).
        val firstCellIsHeader = headerCells.isNotEmpty() &&
            headerCells[0].toDoubleOrNull() == null

        val xValues: List<Double>
        val dataLines: List<String>

        if (firstCellIsHeader) {
            // Top-left cell is a label or empty — X-axis starts at index 1
            xValues = headerCells.drop(1).mapIndexed { index, value ->
                value.toDoubleOrNull()
                    ?: throw IllegalArgumentException("Invalid X-axis value in column ${index + 1}: '$value'")
            }
            dataLines = lines.drop(1)
        } else {
            // Entire first row is numeric — treat it all as X-axis,
            // and data rows have Y value in first column
            xValues = headerCells.mapIndexed { index, value ->
                value.toDoubleOrNull()
                    ?: throw IllegalArgumentException("Invalid X-axis value in column ${index + 1}: '$value'")
            }
            dataLines = lines.drop(1)
        }

        require(xValues.isNotEmpty()) { "No X-axis values found" }
        require(xValues.all(Double::isFinite)) { "X-axis values must be finite" }
        require(xValues.zipWithNext().all { (left, right) -> right > left }) {
            "X-axis must be strictly monotonically increasing"
        }

        val yValues = mutableListOf<Double>()
        val zRows = mutableListOf<Array<Double>>()

        for ((rowIndex, line) in dataLines.withIndex()) {
            val cells = line.split("\t").map { it.trim() }
            require(cells.size == xValues.size + 1) {
                "Row ${rowIndex + 1} has ${cells.size - 1} Z values; expected ${xValues.size}"
            }

            val yVal = cells[0].toDoubleOrNull()
                ?: throw IllegalArgumentException("Invalid Y-axis value in row ${rowIndex + 1}: '${cells[0]}'")
            yValues.add(yVal)

            val zRow = Array(xValues.size) { columnIndex ->
                val raw = cells[columnIndex + 1]
                raw.toDoubleOrNull() ?: throw IllegalArgumentException(
                    "Invalid Z value at row ${rowIndex + 1}, column ${columnIndex + 1}: '$raw'"
                )
            }
            zRows.add(zRow)
        }

        require(yValues.isNotEmpty()) { "No data rows found" }
        require(yValues.all(Double::isFinite)) { "Y-axis values must be finite" }
        require(yValues.zipWithNext().all { (left, right) -> right > left }) {
            "Y-axis must be strictly monotonically increasing"
        }
        require(zRows.all { row -> row.all(Double::isFinite) }) {
            "Z values must be finite"
        }

        return Map3d(
            xValues.toTypedArray(),
            yValues.toTypedArray(),
            zRows.toTypedArray()
        )
    }
}
