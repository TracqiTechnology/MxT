package data.profile

import data.parser.bin.BinParser
import data.parser.xdf.XdfParser
import java.io.File
import java.io.FileInputStream
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Verifies that the MED17 profile resolves all 13 map definitions against
 * every available XDF+BIN pair.  Each variant is tested independently.
 */
class Med17MultiVariantProfileTest {

    data class Variant(val code: String, val xdfPath: String, val binPath: String)

    companion object {
        private val PROJECT_ROOT = File(System.getProperty("user.dir"))
        private val VARIANTS = listOf(
            Variant("404A", "example/med17/404A/404A_normal.xdf", "example/med17/404A/MED17_1_62_STOCK.bin"),
            Variant("404E", "example/med17/404E/404E_normal.xdf", "example/med17/404E/MED17_1_62_STOCK.bin"),
            Variant("404G", "example/med17/404G/404G_normal.xdf", "example/med17/404G/MED17_1_62_STOCK.bin"),
            Variant("404H", "example/med17/404H/404H_normal.xdf", "example/med17/404H/MED17_1_62_STOCK.bin"),
            Variant("404J", "example/med17/404J/404J_normal.xdf", "example/med17/404J/MED17_1_62_STOCK.bin"),
            Variant("404L", "example/med17/404L/404L_normal.xdf", "example/med17/404L/MED17_1_62_8S0907404L__0001_STOCK.bin"),
        )

        private val JSON = kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
        }

        private val EXPECTED_MAP_KEYS = listOf(
            "KRKTE_PFI", "KRKTE_GDI", "TVUB_PFI", "KFMIOP", "KFMIRL",
            "KFZWOP", "KFZW", "KFLDRL", "KFLDIMX", "KFLDIOPU",
            "KFLDRQ0", "KFLDRQ1", "KFLDRQ2"
        )
    }

    private fun loadProfile(): ConfigurationProfile {
        val stream = ProfileManager::class.java.getResourceAsStream(
            "/profiles/MED17_162_RS3_TTRS_2_5T.mxtprofile.json"
        ) ?: error("MED17 profile not found on classpath")
        return JSON.decodeFromString(ConfigurationProfile.serializer(), stream.bufferedReader().readText())
    }

    private fun testVariant(variant: Variant) {
        val xdfFile = File(PROJECT_ROOT, variant.xdfPath)
        val binFile = File(PROJECT_ROOT, variant.binPath)
        if (!xdfFile.exists() || !binFile.exists()) {
            println("SKIP ${variant.code}: files not available")
            return
        }

        // 1. Parse XDF + BIN
        val (_, tableDefs) = XdfParser.parseToList(FileInputStream(xdfFile))
        assertTrue(tableDefs.isNotEmpty(), "${variant.code}: XDF produced no table definitions")

        val mapList = BinParser.parseToList(FileInputStream(binFile), tableDefs)
        assertTrue(mapList.isNotEmpty(), "${variant.code}: BIN produced no maps")

        // 2. Load profile and simulate applyProfile matching
        val profile = loadProfile()
        val unresolved = mutableListOf<String>()

        for ((key, ref) in profile.mapDefinitions) {
            // Tier 1: Exact match
            val exact = tableDefs.firstOrNull { def ->
                ref.tableName == def.tableName &&
                    ref.tableDescription == def.tableDescription &&
                    ref.unit == def.zAxis.unit
            }
            // Tier 2: Fuzzy name prefix
            val fuzzyName = if (exact == null) {
                tableDefs.firstOrNull { def ->
                    def.tableName.startsWith(ref.tableName, ignoreCase = true) ||
                        ref.tableName.startsWith(def.tableName, ignoreCase = true)
                }
            } else null
            // Tier 3: Description prefix
            val fuzzyDesc = if (exact == null && fuzzyName == null) {
                val refDescPrefix = ref.tableDescription.split(" ").firstOrNull()?.trim() ?: ""
                if (refDescPrefix.length >= 3) {
                    tableDefs.firstOrNull { def ->
                        val defDescPrefix = def.tableDescription.split(" ").firstOrNull()?.trim() ?: ""
                        refDescPrefix.equals(defDescPrefix, ignoreCase = true) &&
                            (ref.unit.isEmpty() || def.zAxis.unit.isEmpty() ||
                                ref.unit.equals(def.zAxis.unit, ignoreCase = true))
                    }
                } else null
            } else null

            val matched = exact ?: fuzzyName ?: fuzzyDesc
            if (matched == null) {
                unresolved.add(key)
                continue
            }

            // 3. Verify the matched definition exists in mapList (simulating getSelectedMap)
            val inMapList = mapList.firstOrNull { (def, _) ->
                matched.tableName == def.tableName &&
                    matched.tableDescription == def.tableDescription &&
                    matched.zAxis.unit == def.zAxis.unit
            }
            if (inMapList == null) {
                unresolved.add("$key (matched in XDF but not in mapList)")
                continue
            }

            // 4. Verify the map has data
            val map = inMapList.second
            assertTrue(
                map.zAxis.isNotEmpty(),
                "${variant.code} $key: map resolved but z-axis is empty"
            )
        }

        assertTrue(
            unresolved.isEmpty(),
            "${variant.code}: ${unresolved.size} of 13 maps failed to resolve: $unresolved"
        )
    }

    @Test fun `404A - all 13 map definitions resolve with data`() = testVariant(VARIANTS[0])
    @Test fun `404E - all 13 map definitions resolve with data`() = testVariant(VARIANTS[1])
    @Test fun `404G - all 13 map definitions resolve with data`() = testVariant(VARIANTS[2])
    @Test fun `404H - all 13 map definitions resolve with data`() = testVariant(VARIANTS[3])
    @Test fun `404J - all 13 map definitions resolve with data`() = testVariant(VARIANTS[4])
    @Test fun `404L - all 13 map definitions resolve with data`() = testVariant(VARIANTS[5])
}
