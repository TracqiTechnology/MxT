package data.preferences.dualinjection

import java.util.prefs.Preferences

object DualInjectionPreferences {
    private val prefs = Preferences.userNodeForPackage(DualInjectionPreferences::class.java)

    var portInjectorFlowRateCcMin: Double
        get() = prefs.get("port_flow_rate", "220.0").toDouble()
        set(value) = prefs.put("port_flow_rate", value.toString())

    /** PFI rail pressure in bar gauge (relative to atmospheric). Typical range: 3–5 bar. */
    var portInjectorFuelPressureBar: Double
        get() = prefs.get("port_fuel_pressure", "4.0").toDouble()
        set(value) = prefs.put("port_fuel_pressure", value.toString())

    var portInjectorDeadTimeMs: Double
        get() = prefs.get("port_dead_time", "0.0").toDouble()
        set(value) = prefs.put("port_dead_time", value.toString())

    var directInjectorFlowRateCcMin: Double
        get() = prefs.get("direct_flow_rate", "160.0").toDouble()
        set(value) = prefs.put("direct_flow_rate", value.toString())

    /** GDI rail pressure in bar absolute. Nominal 240 bar at full load, varies with RPM/load. */
    var directInjectorFuelPressureBar: Double
        get() = prefs.get("direct_fuel_pressure", "240.0").toDouble()
        set(value) = prefs.put("direct_fuel_pressure", value.toString())

    var directInjectorDeadTimeMs: Double
        get() = prefs.get("direct_dead_time", "0.0").toDouble()
        set(value) = prefs.put("direct_dead_time", value.toString())

    var portSharePercentDefault: Double
        get() = prefs.get("port_share_percent", "30.0").toDouble()
        set(value) = prefs.put("port_share_percent", value.toString())

    var krkateAlreadyPressureCompensated: Boolean
        get() = prefs.getBoolean("krkate_already_pressure_compensated", false)
        set(value) = prefs.putBoolean("krkate_already_pressure_compensated", value)

    var referencePortDifferentialPressureBar: Double
        get() = prefs.getDouble("reference_port_differential_pressure", 4.0)
        set(value) = prefs.putDouble("reference_port_differential_pressure", value)

    var operatingManifoldPressureBarGauge: Double
        get() = prefs.getDouble("operating_manifold_pressure_gauge", 0.0)
        set(value) = prefs.putDouble("operating_manifold_pressure_gauge", value)

    var referenceDirectPressureBarAbsolute: Double
        get() = prefs.getDouble("reference_direct_pressure_absolute", 240.0)
        set(value) = prefs.putDouble("reference_direct_pressure_absolute", value)

    var numPortInjectors: Int
        get() = prefs.get("num_port_injectors", "5").toInt()
        set(value) = prefs.put("num_port_injectors", value.toString())

    var numDirectInjectors: Int
        get() = prefs.get("num_direct_injectors", "5").toInt()
        set(value) = prefs.put("num_direct_injectors", value.toString())
}
