package ui.navigation

import data.model.EcuPlatform
import data.model.StabilityLevel

enum class CalibrationTab(
    val label: String,
    val tooltip: String,
    val platforms: Set<EcuPlatform> = EcuPlatform.entries.toSet(),
    val labelOverrides: Map<EcuPlatform, String> = emptyMap(),
    val stability: StabilityLevel = StabilityLevel.STABLE
) {
    FUELING("Fueling", "KRKTE Calculator & Injector Scaling"),
    CLOSED_LOOP("Closed Loop", "Closed Loop MLHFM Compensation",
        platforms = setOf(EcuPlatform.ME7, EcuPlatform.MED9, EcuPlatform.MOTRONIC)),
    OPEN_LOOP("Open Loop", "Open Loop MLHFM Compensation",
        platforms = setOf(EcuPlatform.ME7, EcuPlatform.MED9, EcuPlatform.MOTRONIC)),
    DUAL_INJECTION("Dual Injection", "Port + Direct Injector Split Calculator",
        platforms = setOf(EcuPlatform.MED17),
        stability = StabilityLevel.BETA),
    FUEL_TRIM("Fuel Trim", "STFT/LTFT Analysis & rk_w Corrections",
        platforms = setOf(EcuPlatform.MED17),
        stability = StabilityLevel.BETA),
    PLSOL("PLSOL", "Requested Boost",
        platforms = setOf(EcuPlatform.ME7, EcuPlatform.MED9, EcuPlatform.MED17)),
    KFMIOP("KFMIOP", "KFMIOP Calculator",
        labelOverrides = mapOf(EcuPlatform.MED17 to "KFLMIOP", EcuPlatform.MOTRONIC to "KFMDOPT")),
    KFMIRL("KFMIRL", "KFMIRL Calculator",
        platforms = setOf(EcuPlatform.ME7, EcuPlatform.MED9, EcuPlatform.MED17),
        labelOverrides = mapOf(EcuPlatform.MED17 to "KFLMIRL")),
    KFZWOP("KFZWOP", "KFZWOP Calculator",
        labelOverrides = mapOf(EcuPlatform.MOTRONIC to "KFZWOPT")),
    KFZW("KFZW", "KFZW Calculator"),
    KFVPDKSD("KFVPDKSD/E", "KFVPDKSD/E Calculator",
        platforms = setOf(EcuPlatform.ME7, EcuPlatform.MED9),
        labelOverrides = mapOf(EcuPlatform.MED9 to "KFVPDKLD")),
    WDKUGDN("WDKUGDN", "KFURL",
        platforms = setOf(EcuPlatform.ME7, EcuPlatform.MED9, EcuPlatform.MOTRONIC),
        labelOverrides = mapOf(EcuPlatform.MOTRONIC to "KFTLWS")),
    LDRPID("LDRPID", "LDRPID");

    /** Returns the display label appropriate for the active platform. */
    fun labelFor(platform: EcuPlatform): String =
        labelOverrides[platform] ?: label
}
