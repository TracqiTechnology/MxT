package domain.model.fueltrim

/**
 * Parsed metadata for an rk_w (fuel trim correction) table from XDF definitions.
 *
 * MED17 DS1 has multiple rk_w tables organized by:
 * - Fuel type (Gasoline/Ethanol)
 * - Map switch index (0-8)
 * - HO variant (HO1/HO2/HO3 = homogeneous injection strategy variants)
 *
 * Table name examples from real XDFs:
 *   "Rel fuel mass fac inj type corr HO1 Gasoline 0"
 *   "Rel fuel mass fac inj type corr HO2"
 *
 * Table description examples:
 *   "InjSys_RelMCorHom1_MAP Gasoline 0 rl_w(%) vs nmot_w(1/min) = --"
 *   "InjSys_RelMCorHom2_MAP rl_w(%) vs nmot_w(1/min) = --"
 */
data class RkwTableMetadata(
    val tableName: String,
    val tableDescription: String,
    val fuelType: FuelType,
    val mapSwitchIndex: Int,
    val hoVariant: Int,
    val isMapSwitch: Boolean,
    val displayLabel: String
) {
    enum class FuelType { GASOLINE, ETHANOL, UNKNOWN }

    companion object {
        /**
         * Parse metadata from an XDF table name and description.
         * Returns null if the table is not an rk_w table.
         */
        fun parse(tableName: String, tableDescription: String): RkwTableMetadata? {
            if (!isRkwTable(tableName, tableDescription)) return null

            val fuelType = parseFuelType(tableName, tableDescription)
            val mapSwitchIndex = parseMapSwitchIndex(tableName, tableDescription)
            val hoVariant = parseHoVariant(tableName, tableDescription)
            val isMapSwitch = FuelTrimAnalyzer.isMapSwitchTable(tableDescription)

            val displayLabel = buildDisplayLabel(fuelType, mapSwitchIndex, hoVariant, isMapSwitch)

            return RkwTableMetadata(
                tableName = tableName,
                tableDescription = tableDescription,
                fuelType = fuelType,
                mapSwitchIndex = mapSwitchIndex,
                hoVariant = hoVariant,
                isMapSwitch = isMapSwitch,
                displayLabel = displayLabel
            )
        }

        /**
         * Returns `true` when the table name or description indicate an rk_w
         * (relative fuel-mass correction) table.
         */
        fun isRkwTable(tableName: String, tableDescription: String): Boolean {
            return tableName.contains("RelMCor", ignoreCase = true) ||
                tableName.contains("Rel fuel mass fac inj type corr", ignoreCase = true) ||
                tableDescription.contains("rk_w", ignoreCase = true) ||
                tableDescription.contains("RelMCor", ignoreCase = true)
        }

        private fun parseFuelType(name: String, desc: String): FuelType {
            val combined = "$name $desc"
            return when {
                combined.contains("Gasoline", ignoreCase = true) -> FuelType.GASOLINE
                combined.contains("Ethanol", ignoreCase = true) -> FuelType.ETHANOL
                combined.contains("E100", ignoreCase = true) -> FuelType.ETHANOL
                combined.contains("E0", ignoreCase = true) -> FuelType.GASOLINE
                else -> FuelType.UNKNOWN
            }
        }

        private val MAP_SWITCH_INDEX_PATTERN =
            Regex("""(?:Gasoline|Ethanol)\s+(\d)""", RegexOption.IGNORE_CASE)

        private fun parseMapSwitchIndex(name: String, desc: String): Int {
            val combined = "$name $desc"
            val match = MAP_SWITCH_INDEX_PATTERN.find(combined)
            return match?.groupValues?.get(1)?.toIntOrNull() ?: -1
        }

        private val HO_VARIANT_PATTERN =
            Regex("""(?:HO|Hom)(\d)""", RegexOption.IGNORE_CASE)

        private fun parseHoVariant(name: String, desc: String): Int {
            val combined = "$name $desc"
            val match = HO_VARIANT_PATTERN.find(combined)
            return match?.groupValues?.get(1)?.toIntOrNull() ?: 1
        }

        private fun buildDisplayLabel(
            fuelType: FuelType,
            switchIdx: Int,
            ho: Int,
            isMapSwitch: Boolean
        ): String {
            val fuel = when (fuelType) {
                FuelType.GASOLINE -> "Gasoline"
                FuelType.ETHANOL -> "Ethanol"
                FuelType.UNKNOWN -> "Unknown"
            }
            val switchPart = if (switchIdx >= 0) " $switchIdx" else ""
            val msPart = if (isMapSwitch) " (MAP switch)" else ""
            return "$fuel$switchPart \u2014 HO$ho$msPart"
        }

        /**
         * Parse all rk_w tables from a list of (name, description) pairs.
         * Non-rk_w tables are filtered out.
         */
        fun parseAll(tables: List<Pair<String, String>>): List<RkwTableMetadata> {
            return tables.mapNotNull { (name, desc) -> parse(name, desc) }
        }
    }
}
