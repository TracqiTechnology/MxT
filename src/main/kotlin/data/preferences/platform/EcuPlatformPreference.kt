package data.preferences.platform

import data.model.EcuPlatform
import data.preferences.SharedAxisPreferences
import java.util.prefs.Preferences

/**
 * Persists the user's selected ECU platform across app restarts.
 */
object EcuPlatformPreference {
    private val prefs = Preferences.userNodeForPackage(EcuPlatformPreference::class.java)
    private const val PLATFORM_KEY = "ecu_platform"

    var platform: EcuPlatform
        get() {
            val name = prefs.get(PLATFORM_KEY, EcuPlatform.ME7.name)
            return try {
                EcuPlatform.valueOf(name)
            } catch (_: IllegalArgumentException) {
                EcuPlatform.ME7
            }
        }
        set(value) {
            prefs.put(PLATFORM_KEY, value.name)
            SharedAxisPreferences.clear()
        }
}
