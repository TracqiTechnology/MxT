package ui.screens.kfmiop

import data.model.EcuPlatform
import data.parser.xdf.AxisDefinition
import data.parser.xdf.TableDefinition
import domain.math.map.Map3d

/**
 * KFMIOP axis-convention normalization.
 *
 * [Kfmiop.calculateKfmiop] and the whole KFMIOP screen expect the map in
 * **algorithm convention**: `xAxis = load (%)`, `yAxis = RPM`.
 *
 * MED9.1 binaries store KFMIOP the other way round — `xAxis = nmot_w (RPM)`,
 * `yAxis = rl_w (load)` — so the raw parsed map must be transposed before any
 * calculation or display, and transposed back before it is written to file.
 * ME7 already stores it in algorithm convention and passes through untouched.
 *
 * The decision of *which* orientation a given table is stored in is made from the
 * axis metadata ([AxisDefinition.unit] / [AxisDefinition.id] / [AxisDefinition.varId]),
 * not from a value-magnitude heuristic — a load axis in absolute units or an
 * aggressively-tuned axis must never be able to trigger a wrong swap on an ECU
 * write path. Platform is used only as a last-resort fallback for tables whose
 * axes carry no identifying metadata.
 */
internal object KfmiopAxisConvention {

    private val RPM_UNIT = Regex("""rpm|1\s*/\s*min|/\s*min|min-1|min\^-1|u/min""", RegexOption.IGNORE_CASE)
    private val RPM_ID = Regex("""nmot|drehz|rpm""", RegexOption.IGNORE_CASE)
    private val LOAD_UNIT = Regex("""%|mg|load""", RegexOption.IGNORE_CASE)
    private val LOAD_ID = Regex("""\brl\b|rl_w|load""", RegexOption.IGNORE_CASE)

    /** True when this axis is positively identifiable as an engine-speed (RPM) axis. */
    fun isRpmAxis(axis: AxisDefinition?): Boolean {
        if (axis == null) return false
        return RPM_UNIT.containsMatchIn(axis.unit) ||
            RPM_ID.containsMatchIn(axis.id) ||
            RPM_ID.containsMatchIn(axis.varId)
    }

    /** True when this axis is positively identifiable as a load axis. */
    fun isLoadAxis(axis: AxisDefinition?): Boolean {
        if (axis == null) return false
        return LOAD_UNIT.containsMatchIn(axis.unit) ||
            LOAD_ID.containsMatchIn(axis.id) ||
            LOAD_ID.containsMatchIn(axis.varId)
    }

    /**
     * True when [tableDef] stores KFMIOP with RPM on the x-axis (MED9 binary
     * convention) and therefore needs a normalize/denormalize swap.
     *
     * Precedence — identity first, platform only as a fallback:
     *  1. x is RPM        → swap (true)
     *  2. x is load       → no swap (false)
     *  3. y is RPM        → x is the other axis (load) → no swap (false)
     *  4. y is load       → x is RPM → swap (true)
     *  5. unlabeled       → fall back to `platform == MED9`
     */
    fun storedRpmOnX(tableDef: TableDefinition?, platform: EcuPlatform): Boolean {
        val x = tableDef?.xAxis
        val y = tableDef?.yAxis
        return when {
            isRpmAxis(x) -> true
            isLoadAxis(x) -> false
            isRpmAxis(y) -> false
            isLoadAxis(y) -> true
            else -> platform == EcuPlatform.MED9
        }
    }

    /**
     * Normalize a raw parsed KFMIOP map to algorithm convention (load on x, RPM on y).
     * When [swap] is false, or the map is scalar/empty (MED17 1×1), the map is
     * returned unchanged.
     */
    fun normalize(map: Map3d, swap: Boolean): Map3d = if (swap) transpose(map) else map

    /**
     * Inverse of [normalize] — restore the original binary layout (RPM on x) before
     * writing. Symmetric with [normalize] for the same [swap] value.
     */
    fun denormalize(map: Map3d, swap: Boolean): Map3d = if (swap) transpose(map) else map

    /** Swap x/y axes and transpose zAxis. Self-inverse. No-op on empty/degenerate maps. */
    private fun transpose(map: Map3d): Map3d {
        if (map.xAxis.isEmpty() || map.yAxis.isEmpty()) return map
        val rows = map.zAxis.size
        val cols = if (rows > 0) map.zAxis[0].size else 0
        if (rows == 0 || cols == 0) return map
        return Map3d(
            map.yAxis,
            map.xAxis,
            Array(cols) { j -> Array(rows) { i -> map.zAxis[i][j] } }
        )
    }
}
