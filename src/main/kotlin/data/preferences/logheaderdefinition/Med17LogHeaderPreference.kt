package data.preferences.logheaderdefinition

import data.contract.Med17LogFileContract
import java.util.prefs.Preferences

/**
 * Persists user-customized MED17 log header mappings, parallel to [LogHeaderPreference]
 * for ME7. Users can override signal names to match their specific logging tool's
 * column headers (Dyno Spectrum, VCDS, etc.).
 */
object Med17LogHeaderPreference {
    private val prefs = Preferences.userNodeForPackage(Med17LogHeaderPreference::class.java)

    fun getHeader(header: Med17LogFileContract.Header): String {
        return prefs.get(header.name, header.header)
    }

    fun setHeader(header: Med17LogFileContract.Header, value: String) {
        prefs.put(header.name, value)
        header.header = value
    }

    fun loadHeaders() {
        for (header in Med17LogFileContract.Header.entries) {
            header.header = prefs.get(header.name, header.header)
        }
    }
}

