package domain.model.kfmirl

/**
 * KFMIRL utility — historically contained stock map data for ME7.1.
 *
 * KFMIRL is now computed as the mathematical inverse of KFMIOP via
 * [domain.math.Inverse.calculateInverse] in both KfmirlScreen and
 * OptimizerCalculator. The legacy stock map data has been removed
 * as it had zero production callers.
 */
object Kfmirl
