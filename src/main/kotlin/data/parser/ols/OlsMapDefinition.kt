package data.parser.ols

/**
 * A map definition extracted from a WinOLS OLS project file.
 *
 * OLS files are proprietary WinOLS binary project containers that embed
 * both the ECU binary and all map definitions (name, description, units,
 * axis metadata). This data class captures the metadata we can reliably
 * extract from the OLS string/record format.
 *
 * @property name       Bosch map identifier, e.g. "KFZW.0", "MLHFM"
 * @property description Human-readable description (usually German), e.g. "Zündwinkelkennfeld"
 * @property zUnit      Unit for the z-axis (map values), e.g. "grad KW", "kg/h", "Nm"
 * @property xAxisLabel Description of the x-axis, e.g. "Drehzahlquantisierung 40.00[Upm]"
 * @property xUnit      Unit for the x-axis, e.g. "Upm", "grad C"
 * @property yAxisLabel Description of the y-axis, e.g. "Quantisierung der Einspritzzeit"
 * @property yUnit      Unit for the y-axis, e.g. "ms/Umdr.", "%"
 * @property zAxisLabel Description of the z-axis values, e.g. "Luftmassen-Durchsatzes, 16 Bit, mit Offset"
 */
data class OlsMapDefinition(
    val name: String,
    val description: String,
    val zUnit: String = "",
    val xAxisLabel: String = "",
    val xUnit: String = "",
    val yAxisLabel: String = "",
    val yUnit: String = "",
    val zAxisLabel: String = ""
)
