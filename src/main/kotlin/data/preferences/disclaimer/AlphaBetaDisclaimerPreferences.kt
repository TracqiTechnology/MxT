package data.preferences.disclaimer

import java.util.prefs.Preferences

object AlphaBetaDisclaimerPreferences {
    private val prefs = Preferences.userNodeForPackage(AlphaBetaDisclaimerPreferences::class.java)
    private const val KEY = "alpha_beta_disclaimer_accepted"

    var accepted: Boolean
        get() = prefs.getBoolean(KEY, false)
        set(value) = prefs.putBoolean(KEY, value)
}
