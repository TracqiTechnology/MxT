package domain.model.plsol

import domain.math.Point

class Plsol {
    val points: Array<Point>

    constructor(pu: Double, tans: Double, kfurl: Double) {
        points = Array(400) { i ->
            Point(0.0, 0.0) // placeholder
        }
        var ps = pu
        for (i in points.indices) {
            val x = i.toDouble()
            val y = plsol(pu, ps, tans, 96.0, kfurl, x)
            ps = y
            points[i] = Point(x, y)
        }
    }

    constructor(pu: Double, tans: Double, kfurl: Double, load: List<Double>) {
        points = Array(load.size) { Point(0.0, 0.0) }
        var ps = pu
        for (i in points.indices) {
            val x = load[i]
            val y = plsol(pu, ps, tans, 96.0, kfurl, x)
            ps = y
            points[i] = Point(x, y)
        }
    }

    companion object {
        /**
         * Compute pssol (target manifold pressure) from rlsol (target load).
         *
         * BGSRM VE model forward path (me7-raw.txt line 54297–54327):
         *   pssol = (rlsol + rfagr) / fupsrl / FPBRKDS / VPSSPLS
         *
         * @param pu Barometric pressure (mbar)
         * @param ps Previous manifold pressure estimate (mbar)
         * @param tans Intake air temperature (°C)
         * @param tmot Coolant temperature (°C)
         * @param kfurl VE slope constant (%/hPa)
         * @param rlsol Target relative load (%)
         * @param kfprg Residual gas partial pressure (hPa) — from KFPRG map or default 70.0
         * @param fpbrkds Combustion chamber pressure correction factor — from KFPBRK map or default 1.016
         */
        fun plsol(
            pu: Double, ps: Double, tans: Double, tmot: Double, kfurl: Double, rlsol: Double,
            kfprg: Double = 70.0,
            fpbrkds: Double = 1.016
        ): Double {
            val KFFWTBR = 0.02
            val VPSSPLS = 1.016

            val fho = pu / 1013.0
            val pirg = fho * kfprg
            val pbr = ps * fpbrkds
            val psagr = 250.0

            val evtmod = tans + (tmot - tans) * KFFWTBR
            val fwft = (tans + 673.425) / 731.334
            val ftbr = 273.0 / (evtmod + 273.0) * fwft

            val fupsrl = kfurl * ftbr
            // Guard: if fupsrl is zero/near-zero, the VE model can't invert — return baro
            if (fupsrl < 1e-6) return pu

            // Guard: ps must be positive for rfagr calculation
            val safePs = maxOf(ps, 100.0) // minimum 100 mBar (never physical < this)
            val rfagr = maxOf(pbr - pirg, 0.0) * fupsrl * psagr / safePs
            val pssol = (rlsol + rfagr) / fupsrl / maxOf(fpbrkds, 0.001)

            // Clamp to physically reasonable range (0 to 6000 mBar ≈ 87 PSI abs)
            return (pssol / VPSSPLS).coerceIn(0.0, 6000.0)
        }
    }
}
