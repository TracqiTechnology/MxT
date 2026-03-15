#!/usr/bin/env python3
"""
Generate synthetic MED9 ME7Logger-format CSV logs for testing.

Produces realistic log data for a Golf V 2.0 TFSI (K03 turbo) pull
in 3rd gear from ~2000 RPM to ~6500 RPM, simulating:
  - open_loop_log.csv: Full pull with lambda/AFR data (open loop enrichment)
  - closed_loop_log.csv: Idle/cruise with fuel trims (closed loop control)
  - ldrpid_log.csv: Boost control focused data

All signal names match the MED9 profile logHeaders.
"""

import math
import random

random.seed(42)

ECU_HEADER = """###################################################################################
Logfile created by ME7-Logger v1.20 (c) mki, 12/2010

Used EcuDefinition file: MED9_0261S02469.ecu

ECU identified with following data:
HWNumber    = 1K0907115S       (requested was 1K0907115S)
SWNumber    = 0261S02469       (requested was 0261S02469)
PartNumber  = 1K0907115S       (requested was 1K0907115S)
SWVersion   = 0001             (requested was 0001)
EngineId    = 2.0L R4/4V TFSI  (requested was 2.0L R4/4V TFSI)
VAGHWNumber = 1K0907115S
ModelId     = MED9.1

Log packet size: 40 bytes
Logging with:    20 samples/second
Used speed is:   56000 baud
Used mode is:    HM0
"""


def noise(base, pct=0.02):
    """Add ±pct% random noise."""
    return base * (1 + random.gauss(0, pct))


def generate_open_loop_log(filepath: str):
    """Generate a 3rd-gear WOT pull log (open loop enrichment)."""
    # MED9 signal names matching profile logHeaders
    headers = [
        "TIME", "nmot_w", "rl_w", "rlsol_w", "mshfm_w", "uhfm_w",
        "pvdks_w", "plsol_w", "pssol_w", "ldtvm", "fr_w", "fra_w",
        "lamsbg_w", "lamsoni_w", "ti_w", "wped_w", "wdkba",
        "B_lr", "gangi", "vfil_w", "tmot", "tans", "pus_w", "rl",
        "zwist", "prist_w"
    ]
    units = [
        "sec.ms", "1/min", "%", "%", "kg/h", "V",
        "hPa", "hPa", "hPa", "%TV", "-", "-",
        "-", "-", "s", "% PED", "% DK",
        "", "gear", "km/h", "Grad C", "Grad C", "hPa", "%",
        "Grad KW", "MPa"
    ]
    aliases = [
        "TIME", "EngineSpeed", "EngineLoad", "EngineLoadRequested",
        "MassAirFlow", "MAFVoltage", "BoostPressureActual",
        "BoostPressureSpecified", "RequestedPressure",
        "WastegateDutyCycle", "LambdaControl", "AdaptationPartial",
        "AirFuelRatioRequired", "AirFuelRatioCurrent",
        "InjectionTime", "AccelPedalPosition", "ThrottlePlateAngle",
        "LambdaControlActive", "SelectedGear", "VehicleSpeed",
        "CoolantTemperature", "IntakeAirTemperature",
        "AtmosphericPressure", "ActualLoad",
        "IgnitionTimingAngle", "RailPressure"
    ]

    with open(filepath, 'w') as f:
        f.write(ECU_HEADER)
        f.write("Log started at:  15.06.2024 14:23:17.500\n\n")

        # Header rows
        f.write(", ".join(f"{h:<14}" for h in headers) + "\n")
        f.write(", ".join(f"{u:<14}" for u in units) + "\n")
        f.write(",".join(f'"{a}"' for a in aliases) + "\n")

        # Simulate 3rd gear pull: 2000 RPM → 6500 RPM over ~5 seconds
        dt = 0.050  # 20 samples/sec
        t = 0.0
        rpm = 2000.0
        tmot = 88.5
        tans = 28.0
        pus = 1013.0  # sea level
        gear = 3

        for _ in range(100):  # 5 seconds
            # RPM ramp (accelerating)
            rpm_target = min(6500, 2000 + (t * 900))
            rpm = rpm + (rpm_target - rpm) * 0.3

            # Load increases with RPM under boost
            rl = min(200, 40 + (rpm - 2000) * 0.035)

            # Boost builds above ~3000 RPM
            if rpm > 3000:
                boost_frac = min(1.0, (rpm - 3000) / 2000)
                pvdks = noise(1013 + 900 * boost_frac, 0.01)
                plsol = 1013 + 900 * boost_frac
            else:
                pvdks = noise(900, 0.02)
                plsol = 1013.0

            # MAF scales with load and RPM
            mshfm = noise(rl * rpm / 1000 * 0.15, 0.02)
            uhfm = noise(1.5 + mshfm * 0.015, 0.01)

            # Lambda goes rich under boost (0.82 target)
            if rpm > 3500 and rl > 80:
                lamsbg = 0.82
                lamsoni = noise(0.82, 0.02)
                B_lr = 0  # open loop
            else:
                lamsbg = 1.0
                lamsoni = noise(1.0, 0.01)
                B_lr = 1  # closed loop

            fr = noise(1.0, 0.005) if B_lr else 1.0
            fra = noise(0.98, 0.002)
            ti = noise(0.001 * rl * 0.06, 0.02)

            wped = min(100, 30 + t * 15)
            wdkba = min(90, wped * 0.85)
            ldtvm_val = max(0, min(95, (pvdks - 1013) * 0.08))
            speed = rpm / 42.0  # rough 3rd gear ratio

            # Timing retards under boost
            zwist = max(5, 28 - (rl - 50) * 0.15)

            # Rail pressure increases under load (100-120 bar typical for GDI)
            prist = noise(0.100 + rl * 0.0002, 0.01)  # MPa

            vals = [
                f"{t:10.3f}",
                f"{noise(rpm, 0.005):10.1f}",
                f"{noise(rl, 0.01):10.3f}",
                f"{noise(rl * 1.02, 0.01):10.3f}",
                f"{mshfm:10.3f}",
                f"{uhfm:10.5f}",
                f"{pvdks:10.1f}",
                f"{plsol:10.1f}",
                f"{plsol:10.1f}",
                f"{noise(ldtvm_val, 0.03):10.2f}",
                f"{fr:10.6f}",
                f"{fra:10.6f}",
                f"{lamsbg:10.6f}",
                f"{lamsoni:10.6f}",
                f"{ti:10.6f}",
                f"{wped:10.4f}",
                f"{wdkba:10.4f}",
                f"{B_lr:10.0f}",
                f"{gear:10.0f}",
                f"{noise(speed, 0.01):10.3f}",
                f"{tmot:10.1f}",
                f"{noise(tans, 0.02):10.1f}",
                f"{noise(pus, 0.001):10.1f}",
                f"{noise(rl, 0.01):10.3f}",
                f"{noise(zwist, 0.03):10.2f}",
                f"{prist:10.6f}",
            ]
            f.write(", ".join(vals) + "\n")
            t += dt

    print(f"  Generated: {filepath} (100 samples, WOT pull)")


def generate_closed_loop_log(filepath: str):
    """Generate idle/cruise closed loop log with fuel trims."""
    headers = [
        "TIME", "nmot_w", "rl_w", "mshfm_w", "uhfm_w",
        "fr_w", "fra_w", "wdkba", "B_lr",
        "lamsbg_w", "lamsoni_w", "ti_w", "gangi", "pus_w", "rl"
    ]
    units = [
        "sec.ms", "1/min", "%", "kg/h", "V",
        "-", "-", "% DK", "",
        "-", "-", "s", "gear", "hPa", "%"
    ]
    aliases = [
        "TIME", "EngineSpeed", "EngineLoad", "MassAirFlow", "MAFVoltage",
        "LambdaControl", "AdaptationPartial", "ThrottlePlateAngle",
        "LambdaControlActive", "AirFuelRatioRequired", "AirFuelRatioCurrent",
        "InjectionTime", "SelectedGear", "AtmosphericPressure", "ActualLoad"
    ]

    with open(filepath, 'w') as f:
        f.write(ECU_HEADER)
        f.write("Log started at:  15.06.2024 14:30:00.000\n\n")

        f.write(", ".join(f"{h:<14}" for h in headers) + "\n")
        f.write(", ".join(f"{u:<14}" for u in units) + "\n")
        f.write(",".join(f'"{a}"' for a in aliases) + "\n")

        dt = 0.050
        t = 0.0

        for _ in range(200):  # 10 seconds of idle/cruise
            # Oscillate around idle with some cruise points
            phase = t * 0.5
            rpm = 760 + 20 * math.sin(phase * 2 * math.pi)
            rl = 25 + 3 * math.sin(phase * 2 * math.pi)
            mshfm = noise(rl * rpm / 1000 * 0.1, 0.03)
            uhfm = noise(1.2 + mshfm * 0.01, 0.02)

            # Closed loop — fr_w oscillates around 1.0 with small corrections
            fr = noise(1.0 + 0.02 * math.sin(t * 3), 0.01)
            fra = noise(0.975, 0.003)  # slightly lean adaptation (realistic)

            lamsbg = 1.0
            lamsoni = noise(1.0, 0.015)
            ti = noise(0.002, 0.03)
            wdkba = noise(3.5, 0.05)

            vals = [
                f"{t:10.3f}",
                f"{noise(rpm, 0.005):10.1f}",
                f"{noise(rl, 0.01):10.3f}",
                f"{mshfm:10.3f}",
                f"{uhfm:10.5f}",
                f"{fr:10.6f}",
                f"{fra:10.6f}",
                f"{wdkba:10.4f}",
                f"{1:10.0f}",
                f"{lamsbg:10.6f}",
                f"{lamsoni:10.6f}",
                f"{ti:10.6f}",
                f"{0:10.0f}",
                f"{noise(1013, 0.001):10.1f}",
                f"{noise(rl, 0.01):10.3f}",
            ]
            f.write(", ".join(vals) + "\n")
            t += dt

    print(f"  Generated: {filepath} (200 samples, idle/cruise)")


def generate_ldrpid_log(filepath: str):
    """Generate boost control focused log data."""
    headers = [
        "TIME", "nmot_w", "rl_w", "rlsol_w", "mshfm_w",
        "pvdks_w", "plsol_w", "ldtvm", "wped_w", "wdkba",
        "gangi", "vfil_w", "tmot", "tans", "pus_w",
        "fr_w", "lamsbg_w", "lamsoni_w", "ti_w"
    ]
    units = [
        "sec.ms", "1/min", "%", "%", "kg/h",
        "hPa", "hPa", "%TV", "% PED", "% DK",
        "gear", "km/h", "Grad C", "Grad C", "hPa",
        "-", "-", "-", "s"
    ]
    aliases = [
        "TIME", "EngineSpeed", "EngineLoad", "EngineLoadRequested",
        "MassAirFlow", "BoostPressureActual", "BoostPressureSpecified",
        "WastegateDutyCycle", "AccelPedalPosition", "ThrottlePlateAngle",
        "SelectedGear", "VehicleSpeed", "CoolantTemperature",
        "IntakeAirTemperature", "AtmosphericPressure",
        "LambdaControl", "AirFuelRatioRequired", "AirFuelRatioCurrent",
        "InjectionTime"
    ]

    with open(filepath, 'w') as f:
        f.write(ECU_HEADER)
        f.write("Log started at:  15.06.2024 14:35:00.000\n\n")

        f.write(", ".join(f"{h:<14}" for h in headers) + "\n")
        f.write(", ".join(f"{u:<14}" for u in units) + "\n")
        f.write(",".join(f'"{a}"' for a in aliases) + "\n")

        dt = 0.050
        t = 0.0
        rpm = 2500.0

        for _ in range(150):  # 7.5 seconds
            rpm_target = min(6000, 2500 + t * 700)
            rpm = rpm + (rpm_target - rpm) * 0.25

            # Boost ramps with RPM
            boost_frac = max(0, min(1.0, (rpm - 2800) / 2500))
            pvdks = noise(800 + 1200 * boost_frac, 0.015)
            plsol = 1013 + 900 * boost_frac
            rl = min(180, 35 + 145 * boost_frac)
            rlsol = rl * 1.05

            mshfm = noise(rl * rpm / 1000 * 0.13, 0.02)
            ldtvm_val = max(0, min(95, boost_frac * 70))
            wped = min(100, 40 + t * 12)
            wdkba = min(90, wped * 0.82)
            speed = rpm / 42
            fr = noise(1.0, 0.005)
            lamsbg = 0.82 if (rpm > 3500 and rl > 80) else 1.0
            lamsoni = noise(lamsbg, 0.02)
            ti = noise(0.001 * rl * 0.055, 0.02)

            vals = [
                f"{t:10.3f}",
                f"{noise(rpm, 0.005):10.1f}",
                f"{noise(rl, 0.01):10.3f}",
                f"{noise(rlsol, 0.01):10.3f}",
                f"{mshfm:10.3f}",
                f"{pvdks:10.1f}",
                f"{plsol:10.1f}",
                f"{noise(ldtvm_val, 0.03):10.2f}",
                f"{wped:10.4f}",
                f"{wdkba:10.4f}",
                f"{3:10.0f}",
                f"{noise(speed, 0.01):10.3f}",
                f"{88.5:10.1f}",
                f"{noise(28, 0.02):10.1f}",
                f"{noise(1013, 0.001):10.1f}",
                f"{fr:10.6f}",
                f"{lamsbg:10.6f}",
                f"{lamsoni:10.6f}",
                f"{ti:10.6f}",
            ]
            f.write(", ".join(vals) + "\n")
            t += dt

    print(f"  Generated: {filepath} (150 samples, boost pull)")


if __name__ == "__main__":
    outdir = "example/med9/logs"
    print("Generating MED9 synthetic log fixtures...")
    generate_open_loop_log(f"{outdir}/open_loop_log.csv")
    generate_closed_loop_log(f"{outdir}/closed_loop_log.csv")
    generate_ldrpid_log(f"{outdir}/ldrpid_log.csv")
    print("Done!")
