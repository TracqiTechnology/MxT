package data.contract

/**
 * MED17 log signal name contract — parallel to [Me7LogFileContract].
 *
 * MED17 shares many signal names with ME7 (nmot_w, rl_w, etc.) but adds
 * dual-injection signals (port + direct injector on-times and fuel share) and does
 * NOT have MAF-based signals (uhfm_w, mshfm_w).
 *
 * Defaults use Dyno Spectrum (DS1) signal names. Users can remap
 * headers in the Configuration screen via LogHeaderPreference.
 */
object Med17LogFileContract {
    const val START_TIME_LABEL = "start_time"
    const val TIME_COLUMN_LABEL = "TIME"
    const val RPM_COLUMN_LABEL = "nmot_w"
    const val STFT_COLUMN_LABEL = "fr_w"
    const val STFT_MIXED_COLUMN_LABEL = "frm_w"
    const val LTFT_COLUMN_LABEL = "fra_w"
    const val THROTTLE_PLATE_ANGLE_LABEL = "wdkba"
    const val LAMBDA_CONTROL_ACTIVE_LABEL = "B_lr"
    const val REQUESTED_LAMBDA_LABEL = "lamsbg_w"
    const val FUEL_INJECTOR_ON_TIME_PORT_LABEL = "ti_b1_pfi"
    const val FUEL_INJECTOR_ON_TIME_DIRECT_LABEL = "ti_b1_gdi"
    const val ENGINE_LOAD_LABEL = "rl_w"
    const val WASTEGATE_DUTY_CYCLE_LABEL = "tvldste_w"
    const val BAROMETRIC_PRESSURE_LABEL = "pu_w"
    const val ABSOLUTE_BOOST_PRESSURE_ACTUAL_LABEL = "psrg_w"
    const val SELECTED_GEAR_LABEL = "gangi"
    const val WIDE_BAND_O2_LABEL = "lamsoni_w"
    const val REQUESTED_PRESSURE_LABEL = "pvds_w"
    const val REQUESTED_LOAD_LABEL = "rlsol_w"
    const val REQUESTED_LOAD_ALT_LABEL = "rlmds_w"
    const val ACTUAL_LOAD_LABEL = "rl"
    const val PORT_FUEL_SHARE_LABEL = "tqfuel_pfi_w"
    const val DIRECT_FUEL_SHARE_LABEL = "tqfuel_gdi_w"
    const val FUEL_PRESSURE_PORT_LABEL = "pfuel_pfi_w"
    const val FUEL_PRESSURE_DIRECT_LABEL = "pfuel_hde_w"
    const val LDR_DUTY_CYCLE_LABEL = "ldtvm_w"
    const val FUPSRLS_LABEL = "fupsrls_w"
    const val BATTERY_VOLTAGE_LABEL = "ubsq"
    const val FUEL_INJECTOR_ON_TIME_LABEL = "ti_l"
    const val FUEL_INJECTOR_FIRST_PULSE_LABEL = "ti1a_l"
    const val FUEL_MASS_REL_LABEL = "rk_w"
    const val PORT_FUEL_MASS_REL_LABEL = "rkpfi_w"
    const val LOAD_FOR_INJECTION_LABEL = "rlp_w"
    const val LONG_TERM_FT_LABEL = "longft1_w"
    const val PBRINTS_LABEL = "pbrints_w"
    const val PFI_INJECTION_TIME_LABEL = "te_l"
    const val PFI_SPLIT_FACTOR_LABEL = "InjSys_facPrtnPfiTar"
    const val PFI_SPLIT_FACTOR_UNLIM_LABEL = "InjSys_facPrtnPfiSpUnlimModNew"
    const val REQUESTED_PRESSURE_MAX_LABEL = "pvdxs_w"

    enum class Header(var header: String, val title: String) {
        START_TIME_HEADER(START_TIME_LABEL, "Start Time"),
        TIME_STAMP_COLUMN_HEADER(TIME_COLUMN_LABEL, "Timestamp"),
        RPM_COLUMN_HEADER(RPM_COLUMN_LABEL, "RPM"),
        STFT_COLUMN_HEADER(STFT_COLUMN_LABEL, "Short Term Fuel Trim"),
        STFT_MIXED_COLUMN_HEADER(STFT_MIXED_COLUMN_LABEL, "Short Term Fuel Trim (Mixed)"),
        LTFT_COLUMN_HEADER(LTFT_COLUMN_LABEL, "Long Term Fuel Trim"),
        THROTTLE_PLATE_ANGLE_HEADER(THROTTLE_PLATE_ANGLE_LABEL, "Throttle Plate Angle"),
        LAMBDA_CONTROL_ACTIVE_HEADER(LAMBDA_CONTROL_ACTIVE_LABEL, "Lambda Control"),
        REQUESTED_LAMBDA_HEADER(REQUESTED_LAMBDA_LABEL, "Requested Lambda"),
        FUEL_INJECTOR_ON_TIME_PORT_HEADER(FUEL_INJECTOR_ON_TIME_PORT_LABEL, "Port Injector On-Time"),
        FUEL_INJECTOR_ON_TIME_DIRECT_HEADER(FUEL_INJECTOR_ON_TIME_DIRECT_LABEL, "Direct Injector On-Time"),
        ENGINE_LOAD_HEADER(ENGINE_LOAD_LABEL, "Engine Load"),
        WASTEGATE_DUTY_CYCLE_HEADER(WASTEGATE_DUTY_CYCLE_LABEL, "Wastegate Duty Cycle"),
        BAROMETRIC_PRESSURE_HEADER(BAROMETRIC_PRESSURE_LABEL, "Barometric Pressure"),
        ABSOLUTE_BOOST_PRESSURE_ACTUAL_HEADER(ABSOLUTE_BOOST_PRESSURE_ACTUAL_LABEL, "Absolute Pressure"),
        SELECTED_GEAR_HEADER(SELECTED_GEAR_LABEL, "Selected Gear"),
        WIDE_BAND_O2_HEADER(WIDE_BAND_O2_LABEL, "Wide Band O2"),
        REQUESTED_PRESSURE_HEADER(REQUESTED_PRESSURE_LABEL, "Requested Pressure"),
        REQUESTED_LOAD_HEADER(REQUESTED_LOAD_LABEL, "Requested Load"),
        REQUESTED_LOAD_ALT_HEADER(REQUESTED_LOAD_ALT_LABEL, "Requested Load (Alt)"),
        ACTUAL_LOAD_HEADER(ACTUAL_LOAD_LABEL, "Actual Load"),
        PORT_FUEL_SHARE_HEADER(PORT_FUEL_SHARE_LABEL, "Port Fuel Share"),
        DIRECT_FUEL_SHARE_HEADER(DIRECT_FUEL_SHARE_LABEL, "Direct Fuel Share"),
        FUEL_PRESSURE_PORT_HEADER(FUEL_PRESSURE_PORT_LABEL, "Port Fuel Rail Pressure"),
        FUEL_PRESSURE_DIRECT_HEADER(FUEL_PRESSURE_DIRECT_LABEL, "Direct Fuel Rail Pressure"),
        LDR_DUTY_CYCLE_HEADER(LDR_DUTY_CYCLE_LABEL, "LDR Duty Cycle"),
        FUPSRLS_HEADER(FUPSRLS_LABEL, "VE Correction Factor"),
        BATTERY_VOLTAGE_HEADER(BATTERY_VOLTAGE_LABEL, "Battery Voltage"),
        FUEL_INJECTOR_ON_TIME_HEADER(FUEL_INJECTOR_ON_TIME_LABEL, "GDI Injector On-Time"),
        FUEL_INJECTOR_FIRST_PULSE_HEADER(FUEL_INJECTOR_FIRST_PULSE_LABEL, "First Injection Pulse"),
        FUEL_MASS_REL_HEADER(FUEL_MASS_REL_LABEL, "Relative Fuel Mass"),
        PORT_FUEL_MASS_REL_HEADER(PORT_FUEL_MASS_REL_LABEL, "Port Relative Fuel Mass"),
        LOAD_FOR_INJECTION_HEADER(LOAD_FOR_INJECTION_LABEL, "Load for Injection"),
        LONG_TERM_FT_HEADER(LONG_TERM_FT_LABEL, "Long Term Fuel Trim (Alt)"),
        PBRINTS_HEADER(PBRINTS_LABEL, "Intake Manifold Model Pressure"),
        PFI_INJECTION_TIME_HEADER(PFI_INJECTION_TIME_LABEL, "PFI Injection Time"),
        PFI_SPLIT_FACTOR_HEADER(PFI_SPLIT_FACTOR_LABEL, "PFI Split Factor"),
        PFI_SPLIT_FACTOR_UNLIM_HEADER(PFI_SPLIT_FACTOR_UNLIM_LABEL, "PFI Split Factor (Unlimited)"),
        REQUESTED_PRESSURE_MAX_HEADER(REQUESTED_PRESSURE_MAX_LABEL, "Max Requested Pressure")
    }
}
