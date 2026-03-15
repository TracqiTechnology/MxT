package ui.navigation

import data.model.EcuPlatform

enum class CalibrationTab(
    val label: String,
    val tooltip: String,
    val platforms: Set<EcuPlatform> = EcuPlatform.entries.toSet(),
    val labelOverrides: Map<EcuPlatform, String> = emptyMap()
) {
    FUELING("Fueling", "KRKTE Calculator & Injector Scaling"),
    CLOSED_LOOP("Closed Loop", "Closed Loop MLHFM Compensation",
        platforms = setOf(EcuPlatform.ME7, EcuPlatform.MED9)),
    OPEN_LOOP("Open Loop", "Open Loop MLHFM Compensation",
        platforms = setOf(EcuPlatform.ME7, EcuPlatform.MED9)),
    DUAL_INJECTION("Dual Injection", "Port + Direct Injector Split Calculator",
        platforms = setOf(EcuPlatform.MED17)),
    FUEL_TRIM("Fuel Trim", "STFT/LTFT Analysis & rk_w Corrections",
        platforms = setOf(EcuPlatform.MED17)),
    PLSOL("PLSOL", "Requested Boost"),
    KFMIOP("KFMIOP", "KFMIOP Calculator",
        labelOverrides = mapOf(EcuPlatform.MED17 to "KFLMIOP")),
    KFMIRL("KFMIRL", "KFMIRL Calculator",
        labelOverrides = mapOf(EcuPlatform.MED17 to "KFLMIRL")),
    KFZWOP("KFZWOP", "KFZWOP Calculator"),
    KFZW("KFZW", "KFZW Calculator"),
    KFVPDKSD("KFVPDKSD/E", "KFVPDKSD/E Calculator",
        platforms = setOf(EcuPlatform.ME7, EcuPlatform.MED9),
        labelOverrides = mapOf(EcuPlatform.MED9 to "KFVPDKLD")),
    WDKUGDN("WDKUGDN", "KFURL",
        platforms = setOf(EcuPlatform.ME7, EcuPlatform.MED9)),
    LDRPID("LDRPID", "LDRPID");

    /** Returns the display label appropriate for the active platform. */
    fun labelFor(platform: EcuPlatform): String =
        labelOverrides[platform] ?: label
}
