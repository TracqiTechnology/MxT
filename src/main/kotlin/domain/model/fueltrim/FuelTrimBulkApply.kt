package domain.model.fueltrim

import data.parser.xdf.TableDefinition
import domain.math.map.Map3d
import kotlin.math.abs

/**
 * Utility for applying fuel trim corrections to multiple rk_w tables.
 *
 * Each correction is a percentage adjustment per RPM × Load cell.
 * Application is multiplicative: `new = old * (1 + correction / 100)`.
 */
object FuelTrimBulkApply {

    /**
     * Enumerate all rk_w tables from [mapList] and parse their metadata.
     *
     * @return List of [RkwTableMetadata] with corresponding [TableDefinition] and [Map3d].
     */
    fun enumerateRkwTables(
        mapList: List<Pair<TableDefinition, Map3d>>
    ): List<RkwTableWithData> {
        return mapList.mapNotNull { (def, map) ->
            val meta = RkwTableMetadata.parse(def.tableName, def.tableDescription)
            if (meta != null) RkwTableWithData(meta, def, map) else null
        }
    }

    /**
     * Apply corrections to a target rk_w map.
     *
     * Corrections are from the source analysis grid (sourceRpmBins × sourceLoadBins).
     * For each cell in the target map, find the nearest correction bin and apply
     * the multiplicative adjustment.
     *
     * @param targetMap    The rk_w Map3d to correct (will be deep-copied).
     * @param corrections  Correction percentages `[rpmIdx][loadIdx]` from the source grid.
     * @param sourceRpmBins  RPM bins of the correction grid.
     * @param sourceLoadBins Load bins of the correction grid.
     * @return New Map3d with corrections applied.
     */
    fun applyCorrections(
        targetMap: Map3d,
        corrections: Array<DoubleArray>,
        sourceRpmBins: DoubleArray,
        sourceLoadBins: DoubleArray
    ): Map3d {
        val output = Map3d(targetMap)
        for (r in output.zAxis.indices) {
            val rpmVal = if (r < output.yAxis.size) output.yAxis[r] else continue
            for (c in output.zAxis[r].indices) {
                val loadVal = if (c < output.xAxis.size) output.xAxis[c] else continue
                val rpmIdx = FuelTrimAnalyzer.nearestBinIndex(rpmVal, sourceRpmBins)
                val loadIdx = FuelTrimAnalyzer.nearestBinIndex(loadVal, sourceLoadBins)
                val correction = corrections[rpmIdx][loadIdx]
                if (correction != 0.0) {
                    output.zAxis[r][c] = targetMap.zAxis[r][c] * (1.0 + correction / 100.0)
                }
            }
        }
        return output
    }

    /**
     * Group rk_w tables by fuel type.
     */
    fun groupByFuelType(
        tables: List<RkwTableWithData>
    ): Map<RkwTableMetadata.FuelType, List<RkwTableWithData>> {
        return tables.groupBy { it.metadata.fuelType }
    }

    /**
     * Select tables matching a fuel type filter.
     */
    fun selectByFuelType(
        tables: List<RkwTableWithData>,
        fuelType: RkwTableMetadata.FuelType
    ): Set<String> {
        return tables.filter { it.metadata.fuelType == fuelType }
            .map { it.metadata.tableName }
            .toSet()
    }

    /**
     * Select tables matching map-switch status.
     */
    fun selectByMapSwitch(
        tables: List<RkwTableWithData>,
        isMapSwitch: Boolean
    ): Set<String> {
        return tables.filter { it.metadata.isMapSwitch == isMapSwitch }
            .map { it.metadata.tableName }
            .toSet()
    }

    /**
     * Select tables matching an HO variant.
     */
    fun selectByHoVariant(
        tables: List<RkwTableWithData>,
        hoVariant: Int
    ): Set<String> {
        return tables.filter { it.metadata.hoVariant == hoVariant }
            .map { it.metadata.tableName }
            .toSet()
    }

    /**
     * Select all table names.
     */
    fun selectAll(tables: List<RkwTableWithData>): Set<String> {
        return tables.map { it.metadata.tableName }.toSet()
    }
}

/**
 * An rk_w table with its parsed metadata, XDF definition, and current map data.
 */
data class RkwTableWithData(
    val metadata: RkwTableMetadata,
    val tableDefinition: TableDefinition,
    val map: Map3d
)
