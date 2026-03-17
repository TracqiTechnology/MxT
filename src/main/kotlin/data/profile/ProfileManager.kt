package data.profile

import data.contract.Me7LogFileContract
import data.contract.Med17LogFileContract
import data.parser.xdf.XdfParser
import data.preferences.closedloopfueling.ClosedLoopFuelingLogPreferences
import data.preferences.kfzw.KfzwSwitchMapPreferences
import data.preferences.kfldiopu.KfldioPuPreferences
import data.preferences.kfldrq0.Kfldrq0Preferences
import data.preferences.kfldrq1.Kfldrq1Preferences
import data.preferences.kfldrq2.Kfldrq2Preferences
import data.preferences.kfldimx.KfldimxPreferences
import data.preferences.kfldrl.KfldrlPreferences
import data.preferences.kfmiop.KfmiopPreferences
import data.preferences.kfmirl.KfmirlPreferences
import data.preferences.kfpbrk.KfpbrkPreferences
import data.preferences.kfpbrknw.KfpbrknwPreferences
import data.preferences.kfprg.KfprgPreferences
import data.preferences.kfvpdksd.KfvpdksdPreferences
import data.preferences.kfwdkmsn.KfwdkmsnPreferences
import data.preferences.kfzw.KfzwPreferences
import data.preferences.kfzwop.KfzwopPreferences
import data.preferences.krkte.KrktePreferences
import data.preferences.krkte.KrktePfiPreferences
import data.preferences.krkte.KrkteGdiPreferences
import data.preferences.logheaderdefinition.LogHeaderPreference
import data.preferences.logheaderdefinition.Med17LogHeaderPreference
import data.preferences.MapPreference
import data.preferences.mlhfm.MlhfmPreferences
import data.preferences.openloopfueling.OpenLoopFuelingLogFilterPreferences
import data.preferences.plsol.PlsolPreferences
import data.preferences.primaryfueling.PrimaryFuelingPreferences
import data.preferences.rkw.RkwPreferences
import data.preferences.tvub.TvubPfiPreferences
import data.preferences.dualinjection.DualInjectionPreferences
import data.preferences.wdkugdn.WdkugdnPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.io.File

object ProfileManager {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _defaultProfiles = MutableStateFlow<List<ConfigurationProfile>>(emptyList())
    val defaultProfiles: StateFlow<List<ConfigurationProfile>> = _defaultProfiles.asStateFlow()

    private val _userProfiles = MutableStateFlow<List<ConfigurationProfile>>(emptyList())
    val userProfiles: StateFlow<List<ConfigurationProfile>> = _userProfiles.asStateFlow()

    private val mapPreferencesByKey: Map<String, MapPreference> = mapOf(
        "KRKTE" to KrktePreferences,
        "KRKATE" to KrktePreferences, // MED9 equivalent of KRKTE
        "MLHFM" to MlhfmPreferences,
        "KFMIOP" to KfmiopPreferences,
        "KFMIRL" to KfmirlPreferences,
        "KFZWOP" to KfzwopPreferences,
        "KFZW" to KfzwPreferences,
        "KFVPDKSD" to KfvpdksdPreferences,
        "WDKUGDN" to WdkugdnPreferences,
        "KFWDKMSN" to KfwdkmsnPreferences,
        "KFLDRL" to KfldrlPreferences,
        "KFLDIMX" to KfldimxPreferences,
        "KFPBRK" to KfpbrkPreferences,
        "KFPBRKNW" to KfpbrknwPreferences,
        "KFPRG" to KfprgPreferences,
        // MED17 dual-injection map slots
        "KRKTE_PFI" to KrktePfiPreferences,
        "KRKTE_GDI" to KrkteGdiPreferences,
        "TVUB_PFI" to TvubPfiPreferences,
        // MED17 boost control maps
        "KFLDIOPU" to KfldioPuPreferences,
        "KFLDRQ0" to Kfldrq0Preferences,
        "KFLDRQ1" to Kfldrq1Preferences,
        "KFLDRQ2" to Kfldrq2Preferences,
        // MED17 fuel trim correction map
        "RKW" to RkwPreferences,
    )

    init {
        _defaultProfiles.value = loadBundledProfiles()
    }

    fun exportCurrentProfile(name: String): ConfigurationProfile {
        val mapDefs = mutableMapOf<String, MapDefinitionRef>()
        for ((key, pref) in mapPreferencesByKey) {
            val selected = pref.getSelectedMap()
            if (selected != null) {
                val def = selected.first
                mapDefs[key] = MapDefinitionRef(
                    tableName = def.tableName,
                    tableDescription = def.tableDescription,
                    unit = def.zAxis.unit
                )
            }
        }

        val logHeaders = mutableMapOf<String, String>()
        for (header in Me7LogFileContract.Header.entries) {
            logHeaders[header.name] = LogHeaderPreference.getHeader(header)
        }

        val med17LogHeaders = mutableMapOf<String, String>()
        for (header in Med17LogFileContract.Header.entries) {
            med17LogHeaders[header.name] = Med17LogHeaderPreference.getHeader(header)
        }

        // Export multi-map definitions (e.g. KFZW switch maps)
        val multiMapDefs = mutableMapOf<String, List<MapDefinitionRef>>()
        val switchMaps = KfzwSwitchMapPreferences.getAllSelectedMaps()
        if (switchMaps.isNotEmpty()) {
            multiMapDefs["KFZW_SWITCH"] = switchMaps.mapNotNull { (_, pair) ->
                val (def, _) = pair ?: return@mapNotNull null
                MapDefinitionRef(
                    tableName = def.tableName,
                    tableDescription = def.tableDescription,
                    unit = def.zAxis.unit
                )
            }
        }

        return ConfigurationProfile(
            name = name,
            description = "",
            ecuPartNumbers = emptyList(),
            mapDefinitions = mapDefs,
            primaryFueling = PrimaryFuelingConfig(
                airDensity = PrimaryFuelingPreferences.airDensity,
                displacement = PrimaryFuelingPreferences.displacement,
                numCylinders = PrimaryFuelingPreferences.numCylinders,
                stoichiometricAfr = PrimaryFuelingPreferences.stoichiometricAfr,
                gasolineGramsPerCcm = PrimaryFuelingPreferences.gasolineGramsPerCubicCentimeter,
                fuelInjectorSize = PrimaryFuelingPreferences.fuelInjectorSize
            ),
            plsol = PlsolConfig(
                barometricPressure = PlsolPreferences.barometricPressure,
                intakeAirTemperature = PlsolPreferences.intakeAirTemperature,
                kfurl = PlsolPreferences.kfurl,
                displacement = PlsolPreferences.displacement,
                rpm = PlsolPreferences.rpm
            ),
            kfmiop = KfmiopConfig(
                maxMapPressure = KfmiopPreferences.maxMapPressure,
                maxBoostPressure = KfmiopPreferences.maxBoostPressure
            ),
            kfvpdksd = KfvpdksdConfig(
                maxWastegateCrackingPressure = KfvpdksdPreferences.maxWastegateCrackingPressure
            ),
            wdkugdn = WdkugdnConfig(
                displacement = WdkugdnPreferences.displacement
            ),
            closedLoopFueling = ClosedLoopFuelingConfig(
                minThrottleAngle = ClosedLoopFuelingLogPreferences.minThrottleAngle,
                minRpm = ClosedLoopFuelingLogPreferences.minRpm,
                maxDerivative = ClosedLoopFuelingLogPreferences.maxDerivative
            ),
            openLoopFueling = OpenLoopFuelingConfig(
                minThrottleAngle = OpenLoopFuelingLogFilterPreferences.minThrottleAngle,
                minRpm = OpenLoopFuelingLogFilterPreferences.minRpm,
                minMe7Points = OpenLoopFuelingLogFilterPreferences.minMe7Points,
                minAfrPoints = OpenLoopFuelingLogFilterPreferences.minAfrPoints,
                maxAfr = OpenLoopFuelingLogFilterPreferences.maxAfr,
                fuelInjectorSize = OpenLoopFuelingLogFilterPreferences.fuelInjectorSize,
                gasolineGramsPerCcm = OpenLoopFuelingLogFilterPreferences.gasolineGramsPerCubicCentimeter,
                numFuelInjectors = OpenLoopFuelingLogFilterPreferences.numFuelInjectors
            ),
            dualInjection = DualInjectionConfig(
                portInjectorFlowRateCcMin = DualInjectionPreferences.portInjectorFlowRateCcMin,
                portInjectorFuelPressureBar = DualInjectionPreferences.portInjectorFuelPressureBar,
                directInjectorFlowRateCcMin = DualInjectionPreferences.directInjectorFlowRateCcMin,
                directInjectorFuelPressureBar = DualInjectionPreferences.directInjectorFuelPressureBar,
                portSharePercentDefault = DualInjectionPreferences.portSharePercentDefault,
                numPortInjectors = DualInjectionPreferences.numPortInjectors,
                numDirectInjectors = DualInjectionPreferences.numDirectInjectors
            ),
            logHeaders = logHeaders,
            med17LogHeaders = med17LogHeaders,
            multiMapDefinitions = multiMapDefs
        )
    }

    fun applyProfile(profile: ConfigurationProfile) {
        // Apply map definitions — try exact 3-way match first, then fuzzy fallbacks.
        // Fuzzy fallback enables profiles to work across XDF variants that use slightly different
        // descriptions or units for the same map (e.g. MBox vs ABox XDFs, vlmspec vs Normal).
        val tableDefs = XdfParser.tableDefinitions.value
        for ((key, ref) in profile.mapDefinitions) {
            val pref = mapPreferencesByKey[key] ?: continue
            // 1. Exact match: tableName + tableDescription + unit (original behaviour)
            val exact = tableDefs.firstOrNull { def ->
                ref.tableName == def.tableName &&
                    ref.tableDescription == def.tableDescription &&
                    ref.unit == def.zAxis.unit
            }
            // 2. Fuzzy: tableName-only (case-insensitive prefix match)
            val fuzzyName = if (exact == null) {
                tableDefs.firstOrNull { def ->
                    def.tableName.startsWith(ref.tableName, ignoreCase = true) ||
                        ref.tableName.startsWith(def.tableName, ignoreCase = true)
                }
            } else null
            // 3. Description prefix: XDF descriptions start with Bosch identifiers
            //    (e.g., "KFLDRL ldtvr_w(%) vs ..."), match the description prefix token
            val fuzzyDesc = if (exact == null && fuzzyName == null) {
                val refDescPrefix = ref.tableDescription.split(" ").firstOrNull()?.trim() ?: ""
                if (refDescPrefix.length >= 3) {
                    tableDefs.firstOrNull { def ->
                        val defDescPrefix = def.tableDescription.split(" ").firstOrNull()?.trim() ?: ""
                        refDescPrefix.equals(defDescPrefix, ignoreCase = true) &&
                            (ref.unit.isEmpty() || def.zAxis.unit.isEmpty() || ref.unit.equals(def.zAxis.unit, ignoreCase = true))
                    }
                } else null
            } else null
            pref.setSelectedMap(exact ?: fuzzyName ?: fuzzyDesc)
        }

        // Apply primary fueling
        profile.primaryFueling.let {
            PrimaryFuelingPreferences.airDensity = it.airDensity
            PrimaryFuelingPreferences.displacement = it.displacement
            PrimaryFuelingPreferences.numCylinders = it.numCylinders
            PrimaryFuelingPreferences.stoichiometricAfr = it.stoichiometricAfr
            PrimaryFuelingPreferences.gasolineGramsPerCubicCentimeter = it.gasolineGramsPerCcm
            PrimaryFuelingPreferences.fuelInjectorSize = it.fuelInjectorSize
        }

        // Apply PLSOL
        profile.plsol.let {
            PlsolPreferences.barometricPressure = it.barometricPressure
            PlsolPreferences.intakeAirTemperature = it.intakeAirTemperature
            PlsolPreferences.kfurl = it.kfurl
            PlsolPreferences.displacement = it.displacement
            PlsolPreferences.rpm = it.rpm
        }

        // Apply KFMIOP
        profile.kfmiop.let {
            KfmiopPreferences.maxMapPressure = it.maxMapPressure
            KfmiopPreferences.maxBoostPressure = it.maxBoostPressure
        }

        // Apply KFVPDKSD
        KfvpdksdPreferences.maxWastegateCrackingPressure = profile.kfvpdksd.maxWastegateCrackingPressure

        // Apply WDKUGDN
        WdkugdnPreferences.displacement = profile.wdkugdn.displacement

        // Apply dual injection
        profile.dualInjection.let {
            DualInjectionPreferences.portInjectorFlowRateCcMin = it.portInjectorFlowRateCcMin
            DualInjectionPreferences.portInjectorFuelPressureBar = it.portInjectorFuelPressureBar
            DualInjectionPreferences.directInjectorFlowRateCcMin = it.directInjectorFlowRateCcMin
            DualInjectionPreferences.directInjectorFuelPressureBar = it.directInjectorFuelPressureBar
            DualInjectionPreferences.portSharePercentDefault = it.portSharePercentDefault
            DualInjectionPreferences.numPortInjectors = it.numPortInjectors
            DualInjectionPreferences.numDirectInjectors = it.numDirectInjectors
        }

        // Apply closed loop fueling
        profile.closedLoopFueling.let {
            ClosedLoopFuelingLogPreferences.minThrottleAngle = it.minThrottleAngle
            ClosedLoopFuelingLogPreferences.minRpm = it.minRpm
            ClosedLoopFuelingLogPreferences.maxDerivative = it.maxDerivative
        }

        // Apply open loop fueling
        profile.openLoopFueling.let {
            OpenLoopFuelingLogFilterPreferences.minThrottleAngle = it.minThrottleAngle
            OpenLoopFuelingLogFilterPreferences.minRpm = it.minRpm
            OpenLoopFuelingLogFilterPreferences.minMe7Points = it.minMe7Points
            OpenLoopFuelingLogFilterPreferences.minAfrPoints = it.minAfrPoints
            OpenLoopFuelingLogFilterPreferences.maxAfr = it.maxAfr
            OpenLoopFuelingLogFilterPreferences.fuelInjectorSize = it.fuelInjectorSize
            OpenLoopFuelingLogFilterPreferences.gasolineGramsPerCubicCentimeter = it.gasolineGramsPerCcm
            OpenLoopFuelingLogFilterPreferences.numFuelInjectors = it.numFuelInjectors
        }

        // Apply log headers
        for ((headerName, value) in profile.logHeaders) {
            val header = Me7LogFileContract.Header.entries.firstOrNull { it.name == headerName } ?: continue
            LogHeaderPreference.setHeader(header, value)
        }

        // Apply MED17 log headers
        for ((headerName, value) in profile.med17LogHeaders) {
            val header = Med17LogFileContract.Header.entries.firstOrNull { it.name == headerName } ?: continue
            Med17LogHeaderPreference.setHeader(header, value)
        }

        // Apply multi-map definitions (e.g. KFZW switch maps)
        val switchRefs = profile.multiMapDefinitions["KFZW_SWITCH"]
        if (switchRefs != null) {
            KfzwSwitchMapPreferences.clear()
            for (ref in switchRefs) {
                // Same fuzzy matching logic as single maps
                val exact = tableDefs.firstOrNull { def ->
                    ref.tableName == def.tableName &&
                        ref.tableDescription == def.tableDescription &&
                        ref.unit == def.zAxis.unit
                }
                val fuzzyName = if (exact == null) {
                    tableDefs.firstOrNull { def ->
                        def.tableName.startsWith(ref.tableName, ignoreCase = true) ||
                            ref.tableName.startsWith(def.tableName, ignoreCase = true)
                    }
                } else null
                val matched = exact ?: fuzzyName
                if (matched != null) {
                    KfzwSwitchMapPreferences.addMap(matched)
                }
            }
        }
    }

    fun saveToFile(profile: ConfigurationProfile, file: File) {
        val jsonString = json.encodeToString(ConfigurationProfile.serializer(), profile)
        file.writeText(jsonString)
    }

    fun loadFromFile(file: File): ConfigurationProfile {
        val jsonString = file.readText()
        return json.decodeFromString(ConfigurationProfile.serializer(), jsonString)
    }

    fun loadBundledProfiles(): List<ConfigurationProfile> {
        val index = ProfileManager::class.java.getResourceAsStream("/profiles/index.txt")
            ?.bufferedReader()?.readLines() ?: return emptyList()
        return index
            .filter { it.isNotBlank() && it.endsWith(".mxtprofile.json") }
            .mapNotNull { fileName ->
                ProfileManager::class.java.getResourceAsStream("/profiles/$fileName")?.let { stream ->
                    runCatching {
                        json.decodeFromString(ConfigurationProfile.serializer(), stream.bufferedReader().readText())
                    }.getOrNull()
                }
            }
    }

    fun addUserProfile(profile: ConfigurationProfile) {
        _userProfiles.value = _userProfiles.value.filter { it.name != profile.name } + profile
    }
}
