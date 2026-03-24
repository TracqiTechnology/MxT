package domain.model.presets

enum class InjectorType { GDI, PFI }

data class InjectorPreset(
    val name: String,
    val flowRateCcPerMin: Double,
    val fuelPressureBar: Double,
    val engine: String = "EA855 2.5T",
    val type: InjectorType = if ("GDI" in name) InjectorType.GDI else InjectorType.PFI
)

object InjectorPresets {
    // EA855 EVO 2.5T — factory KRKATE values from stock BINs:
    //   DAZA (FL 400PS): KRKATE=0.0322 (DI), KRKATEPFI=0.2384 (PFI)
    //   DNWA (PFL 367PS): KRKATE=0.0260 (DI), KRKATEPFI=0.2492 (PFI)
    // Smaller KRKATE = bigger injector. DNWA has ~24% larger DI injectors.
    // HPFP nominal peak pressure: 240 bar (both variants).
    val DAZA_GDI = InjectorPreset("DAZA GDI (Stock RS3 FL)", 380.0, 240.0, "EA855 2.5T", InjectorType.GDI)
    val DNWA_GDI = InjectorPreset("DNWA GDI (Stock RS3 PFL)", 470.0, 240.0, "EA855 2.5T", InjectorType.GDI)
    val DAZA_PFI = InjectorPreset("DAZA PFI (Stock RS3 FL)", 270.0, 4.0, "EA855 2.5T", InjectorType.PFI)
    val DNWA_PFI = InjectorPreset("DNWA PFI (Stock RS3 PFL)", 220.0, 4.0, "EA855 2.5T", InjectorType.PFI)

    // EA825 4.0T — TODO: need real specs
    // 5.2 V10 FSI — TODO: need real specs

    val all = listOf(DAZA_GDI, DNWA_GDI, DAZA_PFI, DNWA_PFI)

    /** All engines that have at least one preset. */
    val engines: List<String> get() = all.map { it.engine }.distinct()

    fun byEngine(engine: String) = all.filter { it.engine == engine }
    fun byType(type: InjectorType) = all.filter { it.type == type }
    fun byEngineAndType(engine: String, type: InjectorType) = all.filter { it.engine == engine && it.type == type }
}
