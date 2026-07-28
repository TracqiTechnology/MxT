package support

import data.model.EcuPlatform
import data.parser.bin.BinParser
import data.parser.xdf.TableDefinition
import data.parser.xdf.XdfParser
import data.preferences.MapPreferenceManager
import data.preferences.SharedAxisPreferences
import data.preferences.bin.BinFilePreferences
import data.preferences.dualinjection.DualInjectionPreferences
import data.preferences.fueltrim.FuelTrimPreferences
import data.preferences.platform.EcuPlatformPreference
import data.preferences.xdf.XdfFilePreferences
import data.profile.ConfigurationProfile
import data.profile.ProfileManager
import domain.math.map.Map3d
import domain.model.fueltrim.FuelTrimSettings
import java.io.File

/**
 * Captures the process-wide state touched by end-to-end tests.
 *
 * The desktop app deliberately uses singleton parsers and Java Preferences.
 * Tests must restore both layers or a test run can change the next test—and the
 * developer's real selected BIN/profile.
 */
class GlobalTestStateSnapshot private constructor(
    private val platform: EcuPlatform,
    private val binFile: File,
    private val xdfFile: File,
    private val tableDefinitions: List<TableDefinition>,
    private val maps: List<Pair<TableDefinition, Map3d>>,
    private val profile: ConfigurationProfile,
    private val mapPreferences: MapPreferenceManager.Snapshot,
    private val fuelTrimSettings: FuelTrimSettings,
    private val krkateAlreadyPressureCompensated: Boolean,
    private val referencePortDifferentialPressureBar: Double,
    private val operatingManifoldPressureBarGauge: Double,
    private val referenceDirectPressureBarAbsolute: Double
) {
    fun restore() {
        SharedAxisPreferences.clear()
        EcuPlatformPreference.platform = platform
        restoreBinFile(binFile)
        restoreXdfFile(xdfFile)
        XdfParser.setTableDefinitionsForTesting(tableDefinitions)
        BinParser.setMapListForTesting(maps)
        ProfileManager.applyProfile(profile)
        FuelTrimPreferences.save(fuelTrimSettings)
        DualInjectionPreferences.krkateAlreadyPressureCompensated =
            krkateAlreadyPressureCompensated
        DualInjectionPreferences.referencePortDifferentialPressureBar =
            referencePortDifferentialPressureBar
        DualInjectionPreferences.operatingManifoldPressureBarGauge =
            operatingManifoldPressureBarGauge
        DualInjectionPreferences.referenceDirectPressureBarAbsolute =
            referenceDirectPressureBarAbsolute
        // Raw map preference values are restored last so selections that were
        // persisted for a currently unloaded definition are not lost.
        MapPreferenceManager.restore(mapPreferences)
    }

    companion object {
        fun capture(): GlobalTestStateSnapshot {
            // Initializing ProfileManager registers all known MapPreference
            // singletons before their raw values are snapshotted.
            val profile = ProfileManager.exportCurrentProfile("test-state-snapshot")
            return GlobalTestStateSnapshot(
                platform = EcuPlatformPreference.platform,
                binFile = BinFilePreferences.getStoredFile(),
                xdfFile = XdfFilePreferences.getStoredFile(),
                tableDefinitions = XdfParser.tableDefinitions.value,
                maps = BinParser.mapList.value,
                profile = profile,
                mapPreferences = MapPreferenceManager.snapshot(),
                fuelTrimSettings = FuelTrimPreferences.load(),
                krkateAlreadyPressureCompensated =
                    DualInjectionPreferences.krkateAlreadyPressureCompensated,
                referencePortDifferentialPressureBar =
                    DualInjectionPreferences.referencePortDifferentialPressureBar,
                operatingManifoldPressureBarGauge =
                    DualInjectionPreferences.operatingManifoldPressureBarGauge,
                referenceDirectPressureBarAbsolute =
                    DualInjectionPreferences.referenceDirectPressureBarAbsolute
            )
        }

        private fun restoreBinFile(file: File) {
            if (file.path.isEmpty()) BinFilePreferences.clear()
            else BinFilePreferences.setFile(file)
        }

        private fun restoreXdfFile(file: File) {
            if (file.path.isEmpty()) XdfFilePreferences.clear()
            else XdfFilePreferences.setFile(file)
        }
    }
}
