package data.preferences.fueltrim

import domain.model.fueltrim.FuelTrimSettings
import java.util.prefs.Preferences

object FuelTrimPreferences {
    private val prefs = Preferences.userNodeForPackage(FuelTrimPreferences::class.java)

    fun load(): FuelTrimSettings = FuelTrimSettings(
        trimThresholdPercent = prefs.getDouble("trim_threshold_percent", 3.0),
        minimumSamples = prefs.getInt("minimum_samples", 3),
        standardDeviationLimitPercent = prefs.getDouble("std_dev_limit_percent", 5.0),
        maximumRpmChangePerSecond = prefs.getDouble("maximum_rpm_change_per_second", 1000.0),
        requireClosedLoopWhenAvailable = prefs.getBoolean("require_closed_loop", true),
        maximumLambdaDeviation = prefs.getDouble("maximum_lambda_deviation", 0.05)
    )

    fun save(settings: FuelTrimSettings) {
        prefs.putDouble("trim_threshold_percent", settings.trimThresholdPercent)
        prefs.putInt("minimum_samples", settings.minimumSamples)
        prefs.putDouble("std_dev_limit_percent", settings.standardDeviationLimitPercent)
        prefs.putDouble("maximum_rpm_change_per_second", settings.maximumRpmChangePerSecond)
        prefs.putBoolean("require_closed_loop", settings.requireClosedLoopWhenAvailable)
        prefs.putDouble("maximum_lambda_deviation", settings.maximumLambdaDeviation)
    }
}
