package data.model

/**
 * Supported ECU platform families.
 *
 * Gates all platform-specific behaviour throughout the app: which calibration tabs
 * are visible, which log contract is used, which map name labels are displayed,
 * and which optimizer path runs.
 */
enum class EcuPlatform(val displayName: String, val shortName: String) {
    ME7("ME7 (Bosch ME7.x)", "ME7"),
    MED9("MED9 (Bosch MED9.x)", "MED9"),
    MED17("MED17 (Bosch MED17.x)", "MED17");
}

