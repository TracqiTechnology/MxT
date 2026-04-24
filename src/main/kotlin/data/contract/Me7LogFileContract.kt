package data.contract

object Me7LogFileContract {
    const val START_TIME_LABEL = "start_time"
    const val TIME_COLUMN_LABEL = "TIME"
    const val RPM_COLUMN_LABEL = "nmot"
    const val STFT_COLUMN_LABEL = "fr_w"
    const val LTFT_COLUMN_LABEL = "fra_w"
    const val MAF_VOLTAGE_LABEL = "uhfm_w"
    const val MAF_GRAMS_PER_SECOND_LABEL = "mshfm_w"
    const val THROTTLE_PLATE_ANGLE_LABEL = "wdkba"
    const val LAMBDA_CONTROL_ACTIVE_LABEL = "B_lr"
    const val REQUESTED_LAMBDA_LABEL = "lamsbg_w"
    const val FUEL_INJECTOR_ON_TIME_LABEL = "ti_b1"
    const val ENGINE_LOAD_LABEL = "rl_w"
    const val WASTEGATE_DUTY_CYCLE_LABEL = "ldtvm"
    const val BAROMETRIC_PRESSURE_LABEL = "pus_w"
    const val ABSOLUTE_BOOST_PRESSURE_ACTUAL_LABEL = "pvdks_w"
    const val SELECTED_GEAR_LABEL = "gangi"
    const val WIDE_BAND_O2_LABEL = "lamsoni_w"
    const val REQUESTED_PRESSURE_LABEL = "pssol_w"
    const val REQUESTED_LOAD_LABEL = "rlsol_w"
    const val ACTUAL_LOAD_LABEL = "rl"
    const val THROTTLE_MODEL_AIRFLOW_LABEL = "msdk_w"
    const val INTAKE_TEMPERATURE_LABEL = "tans"
    const val REQUESTED_PRESSURE_MAX_LABEL = "pvdxs_w"

    enum class Header(var header: String, val title: String) {
        START_TIME_HEADER(START_TIME_LABEL, "Start Time"),
        TIME_STAMP_COLUMN_HEADER(TIME_COLUMN_LABEL, "Timestamp"),
        RPM_COLUMN_HEADER(RPM_COLUMN_LABEL, "RPM"),
        STFT_COLUMN_HEADER(STFT_COLUMN_LABEL, "Short Term Fuel Trim"),
        LTFT_COLUMN_HEADER(LTFT_COLUMN_LABEL, "Long Term Fuel Trim"),
        MAF_VOLTAGE_HEADER(MAF_VOLTAGE_LABEL, "MAF Voltage"),
        MAF_GRAMS_PER_SECOND_HEADER(MAF_GRAMS_PER_SECOND_LABEL, "MAF g/sec"),
        THROTTLE_PLATE_ANGLE_HEADER(THROTTLE_PLATE_ANGLE_LABEL, "Throttle Plate Angle"),
        LAMBDA_CONTROL_ACTIVE_HEADER(LAMBDA_CONTROL_ACTIVE_LABEL, "Lambda Control"),
        REQUESTED_LAMBDA_HEADER(REQUESTED_LAMBDA_LABEL, "Requested Lambda"),
        FUEL_INJECTOR_ON_TIME_HEADER(FUEL_INJECTOR_ON_TIME_LABEL, "Fuel Injector On-Time"),
        ENGINE_LOAD_HEADER(ENGINE_LOAD_LABEL, "Engine Load"),
        WASTEGATE_DUTY_CYCLE_HEADER(WASTEGATE_DUTY_CYCLE_LABEL, "Wastegate Duty Cycle"),
        BAROMETRIC_PRESSURE_HEADER(BAROMETRIC_PRESSURE_LABEL, "Barometric Pressure"),
        ABSOLUTE_BOOST_PRESSURE_ACTUAL_HEADER(ABSOLUTE_BOOST_PRESSURE_ACTUAL_LABEL, "Absolute Pressure"),
        SELECTED_GEAR_HEADER(SELECTED_GEAR_LABEL, "Selected Gear"),
        WIDE_BAND_O2_HEADER(WIDE_BAND_O2_LABEL, "Wide Band O2"),
        REQUESTED_PRESSURE_HEADER(REQUESTED_PRESSURE_LABEL, "Requested Pressure"),
        REQUESTED_LOAD_HEADER(REQUESTED_LOAD_LABEL, "Requested Load"),
        ACTUAL_LOAD_HEADER(ACTUAL_LOAD_LABEL, "Actual Load"),
        THROTTLE_MODEL_AIRFLOW_HEADER(THROTTLE_MODEL_AIRFLOW_LABEL, "Throttle Model Airflow"),
        INTAKE_TEMPERATURE_HEADER(INTAKE_TEMPERATURE_LABEL, "Intake Air Temperature"),
        REQUESTED_PRESSURE_MAX_HEADER(REQUESTED_PRESSURE_MAX_LABEL, "Max Requested Pressure")
    }
}
