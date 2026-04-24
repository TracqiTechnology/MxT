package data.diagnostics

import data.parser.xdf.XdfParser
import data.preferences.bin.BinFilePreferences
import data.preferences.platform.EcuPlatformPreference
import data.preferences.xdf.XdfFilePreferences
import data.profile.ProfileManager

object DiagnosticCollector {

    private fun appVersion(): String {
        return DiagnosticCollector::class.java.getResourceAsStream("/version.txt")
            ?.bufferedReader()?.readLine()?.trim()
            ?: "dev"
    }

    fun collect(): String {
        val version = appVersion()
        val os = System.getProperty("os.name", "unknown")
        val osVersion = System.getProperty("os.version", "unknown")
        val osArch = System.getProperty("os.arch", "unknown")
        val javaVersion = System.getProperty("java.version", "unknown")
        val javaVendor = System.getProperty("java.vendor", "unknown")

        val platform = EcuPlatformPreference.platform.shortName
        val binFile = runCatching { BinFilePreferences.file.value.name }.getOrDefault("(none)")
        val xdfFile = runCatching { XdfFilePreferences.file.value.name }.getOrDefault("(none)")
        val mapCount = XdfParser.tableDefinitions.value.size
        val profileName = ProfileManager.defaultProfiles.value
            .firstOrNull()?.name ?: "(none)"

        return buildString {
            appendLine("### Environment")
            appendLine("| | |")
            appendLine("|---|---|")
            appendLine("| App version | $version |")
            appendLine("| OS | $os $osVersion ($osArch) |")
            appendLine("| Java | $javaVersion ($javaVendor) |")
            appendLine("| ECU platform | $platform |")
            appendLine("| BIN file | $binFile |")
            appendLine("| XDF file | $xdfFile |")
            appendLine("| Map count | $mapCount |")
            appendLine("| Profile | $profileName |")
        }.trim()
    }
}
