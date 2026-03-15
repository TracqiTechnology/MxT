#!/usr/bin/env python3
"""
Generate ME7Logger-compatible .ecu and .cfg files from MED9 DAMOS A2L.

The MED9.1 ECU uses the same KWP2000 0xB7/0xB8 protocol as ME7 over K-line.
ME7Logger can log MED9 directly given a proper .ecu characteristics file with
MED9 RAM addresses and conversion formulas.

ME7Info.exe can't analyze MED9 BINs (it only knows ME7 patterns), so this script
generates .ecu files by parsing the DAMOS A2L (ASAP2 format) which contains all
15,270 MEASUREMENT entries with RAM addresses, data types, and COMPU_METHOD
conversion formulas.

Usage:
    python3 scripts/generate_med9_ecu.py <a2l_file> [output_dir]

Output:
    <output_dir>/MED9_<part>.ecu          - ME7Logger characteristics file
    <output_dir>/MED9_<part>_basic.cfg    - Basic 4-cyl tuning config
    <output_dir>/MED9_<part>_full.cfg     - Full tuning config
    <output_dir>/MED9_<part>_ldrpid.cfg   - Boost/LDRPID focused config

A2L COMPU_METHOD COEFFS [a,b,c,d,e,f] → ME7Logger conversion:
    INT = (a*PHYS² + b*PHYS + c) / (d*PHYS² + e*PHYS + f)

    Linear case (a=0, d=0, e=0):  phys = INT * (f/b) - (c/b)
        → ME7Logger: Factor=f/b, Offset=c/b, Inverse=0

    Inverse case (a=0, b=0, d=0, f=0):  phys = c / (e*INT)
        → ME7Logger: Factor=c/e, Offset=0, Inverse=1
"""

import re
import sys
import os
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional


@dataclass
class CompuMethod:
    name: str
    description: str
    unit: str
    coeffs: tuple  # (a, b, c, d, e, f)
    format_str: str = ""


@dataclass
class Measurement:
    name: str
    description: str
    data_type: str  # UBYTE, SBYTE, UWORD, SWORD, ULONG, SLONG
    compu_method_name: str
    resolution: float
    accuracy: float
    lower_limit: float
    upper_limit: float
    ecu_address: int = 0
    format_str: str = ""
    # Computed from compu_method
    compu_method: Optional[CompuMethod] = None


@dataclass
class EcuEntry:
    """A single ME7Logger .ecu measurement entry."""
    name: str
    alias: str
    address: int
    size: int  # 1=byte, 2=word
    bitmask: int
    unit: str
    signed: int  # 0=unsigned, 1=signed
    inverse: int  # 0=normal, 1=inverse
    factor: float
    offset: float
    comment: str


# ─── Well-known aliases for common signals ──────────────────────────────
ALIASES = {
    "nmot_w": "EngineSpeed",
    "nmot": "EngineSpeed",
    "rl_w": "EngineLoad",
    "rl": "EngineLoad",
    "rlsol_w": "EngineLoadRequested",
    "rlmax_w": "EngineLoadCorrected",
    "rlmx_w": "EngineLoadSpecified",
    "mshfm_w": "MassAirFlow",
    "pvdks_w": "BoostPressureActual",
    "pssol_w": "ManifoldPressureRequested",
    "plsol_w": "BoostPressureSpecified",
    "ldtvm": "WastegateDutyCycle",
    "fr_w": "LambdaControl",
    "fra_w": "AdaptationPartial",
    "frm_w": "LambdaControlAvg",
    "ti_w": "InjectionTime",
    "ti_l": "InjectionTimeLong",
    "wped_w": "AccelPedalPosition",
    "wdkba": "ThrottlePlateAngle",
    "tmot": "CoolantTemperature",
    "tmotlin": "CoolantTemperatureLinear",
    "tans": "IntakeAirTemperature",
    "tanslin": "IntakeAirTemperatureLinear",
    "wub": "BatteryVoltage",
    "gangi": "SelectedGear",
    "vfil_w": "VehicleSpeed",
    "lamfa_w": "TargetAFRDriverRequest",
    "lamsbg_w": "AirFuelRatioRequired",
    "lamsoni_w": "AirFuelRatioCurrent",
    "zwist_w": "IgnitionTimingAngle",
    "zwist": "IgnitionTimingAngle",
    "zwout": "IgnitionTimingAngleOverall",
    "pu_w": "AtmosphericPressure",
    "pus_w": "AtmosphericPressure",
    "fho_w": "AltitudeCorrectionFactor",
    "tabgm_w": "EGTModelBeforeCat",
    "prhd_w": "RailPressure",
    "prhdsol_w": "RailPressureTarget",
    "B_bl": "BrakeLight",
    "B_br": "BrakePedal",
    "dwkrz_0": "IgnitionRetardCyl1",
    "dwkrz_1": "IgnitionRetardCyl2",
    "dwkrz_2": "IgnitionRetardCyl3",
    "dwkrz_3": "IgnitionRetardCyl4",
    "wkrm": "AvgIgnitionRetardKnockControl",
    "rkrn_w_0": "KnockVoltageCyl1",
    "rkrn_w_1": "KnockVoltageCyl2",
    "rkrn_w_2": "KnockVoltageCyl3",
    "rkrn_w_3": "KnockVoltageCyl4",
    "fzabg_w_0": "CountMisfireCyl1",
    "fzabg_w_1": "CountMisfireCyl2",
    "fzabg_w_2": "CountMisfireCyl3",
    "fzabg_w_3": "CountMisfireCyl4",
    "fzabgs_w": "CountMisfireTotal",
    "dmllri_w": "IdleSpeedPID-I",
    "dlahi_w": "LambdaPID-I",
    "dlahp_w": "LambdaPID-P",
    "uhfm_w": "MAFVoltage",
    # MED9-specific signal names (different from ME7)
    "zwist": "IgnitionTimingAngle",
    "zwout": "IgnitionTimingAngleOverall",
    "prist_w": "RailPressure",
    "prsoll_w": "RailPressureTarget",
    "prist_u": "RailPressure8bit",
    "dwkr": "IgnitionRetardKnockControl",
    "dwkrz": "IgnitionRetardCylArray",
    "rkrn_w": "KnockVoltageArray",
    "frm_w": "LambdaControlAvg",
    "fzabg_w": "CountMisfireArray",
}

# Aliases for array-expanded entries (element_0, element_1, etc.)
ARRAY_ELEMENT_ALIASES = {
    ("dwkrz", 0): "IgnitionRetardCyl1",
    ("dwkrz", 1): "IgnitionRetardCyl2",
    ("dwkrz", 2): "IgnitionRetardCyl3",
    ("dwkrz", 3): "IgnitionRetardCyl4",
    ("rkrn_w", 0): "KnockVoltageCyl1",
    ("rkrn_w", 1): "KnockVoltageCyl2",
    ("rkrn_w", 2): "KnockVoltageCyl3",
    ("rkrn_w", 3): "KnockVoltageCyl4",
    ("fzabg_w", 0): "CountMisfireCyl1",
    ("fzabg_w", 1): "CountMisfireCyl2",
    ("fzabg_w", 2): "CountMisfireCyl3",
    ("fzabg_w", 3): "CountMisfireCyl4",
    ("fzabgs_w", 0): "CountMisfireCyl1",
    ("fzabgs_w", 1): "CountMisfireCyl2",
    ("fzabgs_w", 2): "CountMisfireCyl3",
    ("fzabgs_w", 3): "CountMisfireCyl4",
    ("fzabgzyl_w", 0): "CountMisfireCyl1",
    ("fzabgzyl_w", 1): "CountMisfireCyl2",
    ("fzabgzyl_w", 2): "CountMisfireCyl3",
    ("fzabgzyl_w", 3): "CountMisfireCyl4",
}

# Important array signals to expand (name → max elements to expand)
EXPAND_ARRAYS = {
    "dwkrz": 4,        # 4 cylinders (array has 8 but 4 are relevant)
    "rkrn_w": 4,       # 4 cylinders
    "fzabgzyl_w": 4,   # 4 cylinders (per-cyl misfire counter)
    "prista_w": 4,     # per-cylinder rail pressure
}


def parse_compu_methods(content: str) -> dict[str, CompuMethod]:
    """Parse all COMPU_METHOD blocks from A2L content."""
    pattern = re.compile(
        r'/begin COMPU_METHOD\s+(\S+)\s+"([^"]*)".*?/end COMPU_METHOD',
        re.DOTALL
    )
    coeffs_pattern = re.compile(
        r'COEFFS\s+([\d.eE+\-]+)\s+([\d.eE+\-]+)\s+([\d.eE+\-]+)\s+'
        r'([\d.eE+\-]+)\s+([\d.eE+\-]+)\s+([\d.eE+\-]+)'
    )
    unit_pattern = re.compile(r'"([^"]*)"', re.DOTALL)

    methods = {}
    for m in pattern.finditer(content):
        name = m.group(1)
        desc = m.group(2)
        block = m.group(0)

        # A2L: /begin COMPU_METHOD name "desc" type "format" "unit"
        # Extract unit: third quoted string (index 2), format: second (index 1)
        units = unit_pattern.findall(block)
        unit = units[2] if len(units) > 2 else ""
        fmt = units[1] if len(units) > 1 else ""

        c_match = coeffs_pattern.search(block)
        if c_match:
            coeffs = tuple(float(x) for x in c_match.groups())
        else:
            coeffs = (0.0, 1.0, 0.0, 0.0, 0.0, 1.0)  # identity

        format_match = re.search(r'"(%[\d.]+[a-zA-Z])"', block)
        fmt = format_match.group(1) if format_match else ""

        methods[name] = CompuMethod(
            name=name, description=desc, unit=unit,
            coeffs=coeffs, format_str=fmt
        )

    return methods


def parse_measurements(content: str) -> list[Measurement]:
    """Parse all MEASUREMENT blocks from A2L content, expanding arrays."""
    pattern = re.compile(
        r'/begin MEASUREMENT\s+(\S+)\s+"([^"]*)"'
        r'\s+(\S+)'       # data_type
        r'\s+(\S+)'       # compu_method
        r'\s+(\S+)'       # resolution
        r'\s+(\S+)'       # accuracy
        r'\s+([\d.eE+\-]+)'  # lower_limit
        r'\s+([\d.eE+\-]+)'  # upper_limit
        r'(.*?)/end MEASUREMENT',
        re.DOTALL
    )
    addr_pattern = re.compile(r'ECU_ADDRESS\s+(0x[0-9A-Fa-f]+)')
    array_pattern = re.compile(r'ARRAY_SIZE\s+(\d+)')

    measurements = []
    for m in pattern.finditer(content):
        name = m.group(1)
        desc = m.group(2)
        dtype = m.group(3)
        cm_name = m.group(4)
        res = float(m.group(5))
        acc = float(m.group(6))
        lo = float(m.group(7))
        hi = float(m.group(8))
        rest = m.group(9)

        addr_match = addr_pattern.search(rest)
        if not addr_match:
            continue
        addr = int(addr_match.group(1), 16)

        array_match = array_pattern.search(rest)
        array_size = int(array_match.group(1)) if array_match else 0

        size_bytes = {"UBYTE": 1, "SBYTE": 1, "UWORD": 2, "SWORD": 2,
                      "ULONG": 4, "SLONG": 4}.get(dtype, 2)

        if array_size > 0 and name in EXPAND_ARRAYS:
            # Expand array into per-element entries
            count = min(array_size, EXPAND_ARRAYS[name])
            for i in range(count):
                elem_addr = addr + i * size_bytes
                elem_name = f"{name}_{i}"
                elem_desc = f"{desc} (Zyl {i+1})"
                meas = Measurement(
                    name=elem_name, description=elem_desc, data_type=dtype,
                    compu_method_name=cm_name, resolution=res,
                    accuracy=acc, lower_limit=lo, upper_limit=hi,
                    ecu_address=elem_addr
                )
                measurements.append(meas)
        else:
            meas = Measurement(
                name=name, description=desc, data_type=dtype,
                compu_method_name=cm_name, resolution=res,
                accuracy=acc, lower_limit=lo, upper_limit=hi,
                ecu_address=addr
            )
            measurements.append(meas)

    return measurements


def compu_to_ecu_conversion(cm: CompuMethod) -> tuple[float, float, int]:
    """
    Convert A2L COMPU_METHOD to ME7Logger (Factor, Offset, Inverse).

    A2L RAT_FUNC: INT = (a*PHYS² + b*PHYS + c) / (d*PHYS² + e*PHYS + f)

    Linear (a=0, d=0, e=0): PHYS = INT * (f/b) - (c/b)
        → Factor=f/b, Offset=c/b, Inverse=0

    Inverse (a=0, b=0, d=0, f=0): PHYS = c / (e*INT)
        → Factor=c/e, Offset=0, Inverse=1
    """
    a, b, c, d, e, f = cm.coeffs

    # Inverse case: INT = c / (e*PHYS)  →  PHYS = c / (e*INT)
    if b == 0 and a == 0 and d == 0 and f == 0 and e != 0:
        factor = c / e
        return (factor, 0.0, 1)

    # Linear case: INT = (b*PHYS + c) / f  →  PHYS = INT * (f/b) - (c/b)
    if a == 0 and d == 0 and e == 0 and b != 0:
        factor = f / b
        offset = c / b
        return (factor, offset, 0)

    # Identity / unsupported
    return (1.0, 0.0, 0)


def dtype_to_size_signed(dtype: str) -> tuple[int, int]:
    """Convert A2L data type to (size_bytes, signed_flag)."""
    mapping = {
        "UBYTE": (1, 0), "SBYTE": (1, 1),
        "UWORD": (2, 0), "SWORD": (2, 1),
        "ULONG": (4, 0), "SLONG": (4, 1),
    }
    return mapping.get(dtype, (2, 0))


def build_ecu_entries(
    measurements: list[Measurement],
    methods: dict[str, CompuMethod]
) -> list[EcuEntry]:
    """Convert parsed A2L measurements to ME7Logger .ecu entries."""
    entries = []

    for meas in measurements:
        cm = methods.get(meas.compu_method_name)
        if not cm:
            continue

        size, signed = dtype_to_size_signed(meas.data_type)
        factor, offset, inverse = compu_to_ecu_conversion(cm)

        # Skip 4-byte (ULONG/SLONG) — ME7Logger only supports 1 and 2 byte
        if size > 2:
            continue

        alias = ALIASES.get(meas.name, "")
        # Check array element aliases (e.g., dwkrz_0 → IgnitionRetardCyl1)
        if not alias:
            # Parse base_name and index from names like "dwkrz_0"
            arr_match = re.match(r'^(\w+)_(\d+)$', meas.name)
            if arr_match:
                base = arr_match.group(1)
                idx = int(arr_match.group(2))
                alias = ARRAY_ELEMENT_ALIASES.get((base, idx), "")
        unit = cm.unit if cm.unit else ""

        entries.append(EcuEntry(
            name=meas.name,
            alias=alias,
            address=meas.ecu_address,
            size=size,
            bitmask=0x0000,
            unit=unit,
            signed=signed,
            inverse=inverse,
            factor=factor,
            offset=offset,
            comment=meas.description,
        ))

    entries.sort(key=lambda e: e.address)
    return entries


def format_factor(f: float) -> str:
    """Format factor value compactly."""
    if f == int(f) and abs(f) < 1e6:
        return str(int(f))
    if abs(f) >= 0.001:
        s = f"{f:.10g}"
        return s
    return f"{f:.6e}"


def write_ecu_file(
    entries: list[EcuEntry],
    output_path: Path,
    part_number: str = "1K0907115S",
    sw_number: str = "0261S02469",
    engine_id: str = "2.0L R4 TFSI"
):
    """Write ME7Logger-compatible .ecu file."""
    with open(output_path, 'w', encoding='latin-1') as f:
        f.write(";\n")
        f.write("; ECU characteristics for logging MED9.1 with ME7Logger\n")
        f.write(";\n")
        f.write("; Generated from DAMOS A2L: Golf V 2.0 GTI TFSI\n")
        f.write("; by ME7Tuner generate_med9_ecu.py\n")
        f.write(";\n")
        f.write(f"; Part number: {part_number}\n")
        f.write(f"; SW number:   {sw_number}\n")
        f.write(f"; Engine:      {engine_id}\n")
        f.write(";\n")
        f.write("; IMPORTANT: MED9.1 uses KWP2000 over K-line, same as ME7.\n")
        f.write("; Connection: K-line adapter (FTDI/CH340), pin 7 on OBD-II.\n")
        f.write("; Protocol:   KWP2000 services 0xB7 (DefineVariables) + 0xB8 (ReadVariables)\n")
        f.write(";\n")
        f.write("; NOTE: RAM addresses are from DAMOS A2L for SW version 0261S02469.\n")
        f.write("; Other SW versions will have DIFFERENT addresses. Use at your own risk.\n")
        f.write(";\n\n")

        f.write("[Version]\n")
        f.write("Version           = 1.10\n\n")

        f.write("[Communication]\n")
        f.write("Connect      = SLOW-0x01    ; MED9.1 K-line: 5-baud init at address 0x01\n")
        f.write("Communicate  = HM0          ; Headerless mode (same as ME7)\n")
        f.write("LogSpeed     = 56000        ; 56000 baud recommended; try 125000 for more vars\n\n")

        f.write("[Identification]\n")
        f.write(f"HWNumber          = {{{part_number}}}\n")
        f.write(f"SWNumber          = {{{sw_number}}}\n")
        f.write(f"PartNumber        = {{{part_number}}}\n")
        f.write(f"SWVersion         = {{0001}}\n")
        f.write(f"EngineId          = {{{engine_id}}}\n\n")

        f.write("[Measurements]\n")
        f.write("; Conversion: Normal: phys = Factor * raw - Offset\n")
        f.write(";             Inverse: phys = Factor / (raw - Offset)\n")
        f.write(";\n")
        f.write(f";{'Name':<16}, {'{Alias}':<36}, {'Address':>8}, "
                f"{'Size':>4}, {'Bitmask':>8}, {'  {Unit}':<12}, "
                f"{'S':>1}, {'I':>1}, {'Factor':>14}, {'Offset':>7}, Comment\n")

        for e in entries:
            alias_str = f"{{{e.alias}}}" if e.alias else "{}"
            unit_str = f"{{{e.unit}}}" if e.unit else "{}"
            comment_str = f"{{{e.comment}}}" if e.comment else "{}"
            factor_str = format_factor(e.factor)
            offset_str = format_factor(e.offset)

            f.write(
                f"{e.name:<16}, {alias_str:<36}, "
                f"0x{e.address:06X}, {e.size:>2}, "
                f"0x{e.bitmask:04X},  {unit_str:<10}, "
                f"{e.signed}, {e.inverse}, "
                f"{factor_str:>14}, {offset_str:>7}, "
                f"{comment_str}\n"
            )

    print(f"  Wrote {len(entries)} entries to {output_path}")


def write_cfg_file(
    output_path: Path,
    ecu_filename: str,
    variables: list[tuple[str, str, str]],
    samples_per_second: int = 20,
    description: str = ""
):
    """
    Write ME7Logger .cfg log configuration file.

    variables: list of (name, alias_comment, description_comment)
    """
    with open(output_path, 'w', encoding='latin-1') as f:
        f.write(";\n")
        f.write(f"; {description}\n")
        f.write("; Generated by ME7Tuner generate_med9_ecu.py\n")
        f.write(";\n\n")

        f.write("[Configuration]\n")
        f.write(f"ECUCharacteristics = {ecu_filename}\n")
        f.write(f"SamplesPerSecond   = {samples_per_second}\n\n")

        f.write("[LogVariables]\n")
        f.write(";Name            [Alias]                             [; Comment]\n")

        for name, alias_comment, desc_comment in variables:
            if name == "":
                f.write("\n")
                continue
            line = f"{name:<17}"
            if alias_comment:
                line += f";{{{alias_comment}}}"
                if desc_comment:
                    padding = max(1, 36 - len(alias_comment) - 2)
                    line += " " * padding
            if desc_comment:
                line += f" ; {{{desc_comment}}}"
            f.write(line.rstrip() + "\n")

    print(f"  Wrote {len(variables)} variables to {output_path}")


# ─── Curated variable lists for .cfg configs ────────────────────────────

BASIC_VARS = [
    # (name, alias, comment)
    ("nmot_w", "EngineSpeed", "Motordrehzahl"),
    ("gangi", "SelectedGear", "Ist-Gang"),
    ("vfil_w", "VehicleSpeed", "gefilterte Geschwindigkeit"),
    ("wub", "BatteryVoltage", "Batteriespannung"),
    ("tmot", "CoolantTemperature", "Motor-Temperatur"),
    ("tans", "IntakeAirTemperature", "Ansaugluft-Temperatur"),
    ("wped_w", "AccelPedalPosition", "normierter Fahrpedalwinkel"),
    ("wdkba", "ThrottlePlateAngle", "Drosselklappenwinkel"),
    ("", "", ""),
    ("ti_w", "InjectionTime", "Einspritzzeit"),
    ("rl_w", "EngineLoad", "Relative Luftfüllung"),
    ("rlsol_w", "EngineLoadRequested", "Soll-Füllung"),
    ("", "", ""),
    ("fr_w", "LambdaControl", "Lambda-Regler-Ausgang"),
    ("fra_w", "AdaptationPartial", "multiplikative Gemischkorrektur"),
    ("lamsoni_w", "AirFuelRatioCurrent", "Lambda-Istwert"),
    ("lamsbg_w", "AirFuelRatioRequired", "Lambdasoll Begrenzung"),
    ("", "", ""),
    ("pvdks_w", "BoostPressureActual", "Druck vor Drosselklappe"),
    ("plsol_w", "BoostPressureSpecified", "Soll-Ladedruck"),
    ("ldtvm", "WastegateDutyCycle", "LDR Tastverhältnis moduliert"),
    ("mshfm_w", "MassAirFlow", "Massenstrom HFM"),
]

FULL_VARS = BASIC_VARS + [
    ("", "", ""),
    ("zwist", "IgnitionTimingAngle", "Ist-Zündwinkel"),
    ("zwout", "IgnitionTimingAngleOverall", "Zündwinkel-Ausgabe"),
    ("dwkrz_0", "IgnitionRetardCyl1", "ZW-Spätverstellung Zyl 1"),
    ("dwkrz_1", "IgnitionRetardCyl2", "ZW-Spätverstellung Zyl 2"),
    ("dwkrz_2", "IgnitionRetardCyl3", "ZW-Spätverstellung Zyl 3"),
    ("dwkrz_3", "IgnitionRetardCyl4", "ZW-Spätverstellung Zyl 4"),
    ("wkrm", "AvgIgnitionRetardKnockControl", "Mittelwert ZW-Spätverstellungen KR"),
    ("", "", ""),
    ("rkrn_w_0", "KnockVoltageCyl1", "Referenzpegel Klopfregelung Zyl 1"),
    ("rkrn_w_1", "KnockVoltageCyl2", "Referenzpegel Klopfregelung Zyl 2"),
    ("rkrn_w_2", "KnockVoltageCyl3", "Referenzpegel Klopfregelung Zyl 3"),
    ("rkrn_w_3", "KnockVoltageCyl4", "Referenzpegel Klopfregelung Zyl 4"),
    ("", "", ""),
    ("prist_w", "RailPressure", "Ist-Raildruck"),
    ("prsoll_w", "RailPressureTarget", "Soll-Raildruck"),
    ("pu_w", "AtmosphericPressure", "Umgebungsdruck"),
    ("fho_w", "AltitudeCorrectionFactor", "Korrekturfaktor Höhe"),
    ("tabgm_w", "EGTModelBeforeCat", "Abgastemperatur vor Kat aus Modell"),
    ("", "", ""),
    ("fzabgzyl_w_0", "CountMisfireCyl1", "Aussetzerzähler Zyl 1"),
    ("fzabgzyl_w_1", "CountMisfireCyl2", "Aussetzerzähler Zyl 2"),
    ("fzabgzyl_w_2", "CountMisfireCyl3", "Aussetzerzähler Zyl 3"),
    ("fzabgzyl_w_3", "CountMisfireCyl4", "Aussetzerzähler Zyl 4"),
]

LDRPID_VARS = [
    ("nmot_w", "EngineSpeed", "Motordrehzahl"),
    ("rl_w", "EngineLoad", "Relative Luftfüllung"),
    ("rlsol_w", "EngineLoadRequested", "Soll-Füllung"),
    ("rlmax_w", "EngineLoadCorrected", "maximal erreichbare Füllung"),
    ("", "", ""),
    ("pvdks_w", "BoostPressureActual", "Druck vor Drosselklappe"),
    ("plsol_w", "BoostPressureSpecified", "Soll-Ladedruck"),
    ("ldtvm", "WastegateDutyCycle", "LDR Tastverhältnis moduliert"),
    ("mshfm_w", "MassAirFlow", "Massenstrom HFM"),
    ("wdkba", "ThrottlePlateAngle", "Drosselklappenwinkel"),
    ("", "", ""),
    ("wped_w", "AccelPedalPosition", "normierter Fahrpedalwinkel"),
    ("gangi", "SelectedGear", "Ist-Gang"),
    ("vfil_w", "VehicleSpeed", "gefilterte Geschwindigkeit"),
    ("tmot", "CoolantTemperature", "Motor-Temperatur"),
    ("tans", "IntakeAirTemperature", "Ansaugluft-Temperatur"),
    ("pu_w", "AtmosphericPressure", "Umgebungsdruck"),
    ("fho_w", "AltitudeCorrectionFactor", "Korrekturfaktor Höhe"),
    ("", "", ""),
    ("fr_w", "LambdaControl", "Lambda-Regler-Ausgang"),
    ("lamsbg_w", "AirFuelRatioRequired", "Lambdasoll Begrenzung"),
    ("lamsoni_w", "AirFuelRatioCurrent", "Lambda-Istwert"),
    ("ti_w", "InjectionTime", "Einspritzzeit"),
    ("prist_w", "RailPressure", "Ist-Raildruck"),
    ("prsoll_w", "RailPressureTarget", "Soll-Raildruck"),
]


def main():
    if len(sys.argv) < 2:
        print("Usage: python3 scripts/generate_med9_ecu.py <a2l_file> [output_dir]")
        print("\nExample:")
        print("  python3 scripts/generate_med9_ecu.py '/tmp/med9_damos/Golf V 2.0 GTI TFSI 0261S02469 387445 Damos.A2L' technical/med9/me7logger/")
        sys.exit(1)

    a2l_path = Path(sys.argv[1])
    output_dir = Path(sys.argv[2]) if len(sys.argv) > 2 else Path("technical/med9/me7logger")
    output_dir.mkdir(parents=True, exist_ok=True)

    print(f"Reading A2L: {a2l_path}")
    with open(a2l_path, 'r', encoding='latin-1') as f:
        content = f.read()

    # Parse COMPU_METHODs
    print("Parsing COMPU_METHOD entries...")
    methods = parse_compu_methods(content)
    print(f"  Found {len(methods)} COMPU_METHODs")

    # Parse MEASUREMENTs
    print("Parsing MEASUREMENT entries...")
    measurements = parse_measurements(content)
    print(f"  Found {len(measurements)} MEASUREMENTs with ECU addresses")

    # Build .ecu entries
    print("Building .ecu entries...")
    entries = build_ecu_entries(measurements, methods)
    print(f"  Generated {len(entries)} valid entries (1-2 byte, with conversion)")

    # Statistics
    with_alias = sum(1 for e in entries if e.alias)
    inverse = sum(1 for e in entries if e.inverse)
    signed = sum(1 for e in entries if e.signed)
    byte_size = sum(1 for e in entries if e.size == 1)
    word_size = sum(1 for e in entries if e.size == 2)
    print(f"  Stats: {with_alias} aliased, {inverse} inverse, {signed} signed, "
          f"{byte_size} byte, {word_size} word")

    # Extract part number from filename
    a2l_name = a2l_path.stem
    part_match = re.search(r'(\d+S\d+)', a2l_name)
    part = part_match.group(1) if part_match else "MED9"

    ecu_filename = f"MED9_{part}.ecu"
    ecu_path = output_dir / ecu_filename

    # Write .ecu
    print(f"\nWriting .ecu file...")
    write_ecu_file(
        entries, ecu_path,
        part_number="1K0907115S",
        sw_number=part,
        engine_id="2.0L R4/4V TFSI"
    )

    # Verify key signals are present
    entry_names = {e.name for e in entries}
    print("\nKey signal verification:")
    key_sigs = [
        "nmot_w", "rl_w", "mshfm_w", "pvdks_w", "fr_w", "fra_w",
        "ti_w", "ldtvm", "wped_w", "wdkba", "tmot", "tans",
        "plsol_w", "lamsoni_w", "lamsbg_w", "gangi", "vfil_w",
        "prist_w", "prsoll_w", "zwist", "zwout", "dwkrz_0", "wub",
        "rkrn_w_0", "fzabgzyl_w_0",
    ]
    for sig in key_sigs:
        status = "✓" if sig in entry_names else "✗ MISSING"
        print(f"  {sig}: {status}")

    # Filter .cfg variables to only include those present in .ecu
    def filter_vars(var_list):
        return [(n, a, c) for n, a, c in var_list if n == "" or n in entry_names]

    # Write .cfg files
    print(f"\nWriting .cfg files...")
    write_cfg_file(
        output_dir / f"MED9_{part}_basic.cfg",
        ecu_filename,
        filter_vars(BASIC_VARS),
        samples_per_second=20,
        description=f"MED9.1 Basic 4-cylinder tuning config (Golf V 2.0 GTI TFSI)"
    )

    write_cfg_file(
        output_dir / f"MED9_{part}_full.cfg",
        ecu_filename,
        filter_vars(FULL_VARS),
        samples_per_second=10,  # more vars → lower sample rate
        description=f"MED9.1 Full tuning config with knock/timing/EGT"
    )

    write_cfg_file(
        output_dir / f"MED9_{part}_ldrpid.cfg",
        ecu_filename,
        filter_vars(LDRPID_VARS),
        samples_per_second=20,
        description=f"MED9.1 LDRPID / boost control focused config"
    )

    # Summary
    print(f"\n{'='*60}")
    print("Generation complete!")
    print(f"  .ecu: {ecu_path} ({len(entries)} measurements)")
    print(f"  .cfg: 3 configs (basic, full, ldrpid)")
    print(f"\nTo use with ME7Logger:")
    print(f"  1. Copy {ecu_filename} to ME7Logger/ecus/")
    print(f"  2. Copy a .cfg file to ME7Logger/logs/")
    print(f"  3. Edit .cfg: ECUCharacteristics = {ecu_filename}")
    print(f"  4. Connect K-line adapter to OBD-II pin 7")
    print(f"  5. Run: ME7Logger.exe logs/<config>.cfg")
    print(f"\nWARNING: Addresses are specific to SW {part}.")
    print(f"  Other SW versions will have DIFFERENT RAM addresses!")


if __name__ == "__main__":
    main()
