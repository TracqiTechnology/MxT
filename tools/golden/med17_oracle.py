#!/usr/bin/env python3
"""
Independent MED17 golden-data oracle.

This intentionally does not import or invoke MxT production code.  It reads the
raw DS1 CSV files, applies the documented PFI, fuel-trim, and LDRPID equations,
and writes deterministic JSON fixtures consumed by the Kotlin acceptance tests.

Golden files are review artifacts.  This script is never run by the normal test
task and never overwrites a golden unless --output is supplied explicitly.
"""

from __future__ import annotations

import argparse
import bisect
import csv
import hashlib
import json
import math
from pathlib import Path
from typing import Any, Iterable


PFI_RPM = [1000.0, 1500.0, 2000.0, 2500.0, 3000.0, 3500.0,
           4000.0, 4500.0, 5000.0, 5500.0, 6000.0, 6500.0, 7000.0]
PFI_LOAD = [20.0, 40.0, 60.0, 80.0, 100.0, 120.0, 140.0, 160.0, 180.0, 200.0]
DEFAULT_RPM = [1000.0, 2000.0, 3000.0, 4000.0, 4500.0,
               5000.0, 5500.0, 6000.0, 6500.0, 7000.0]
DEFAULT_PFI = [30.0, 40.0, 50.0, 58.0, 60.0, 60.0, 60.0, 55.0, 35.0, 20.0]

FUEL_RPM = [750.0, 1000.0, 1500.0, 2000.0, 2500.0, 3000.0,
            3500.0, 4000.0, 4500.0, 5000.0, 5500.0, 6000.0, 6500.0, 7000.0]
FUEL_LOAD = [10.0, 20.0, 30.0, 40.0, 50.0, 60.0, 70.0,
             80.0, 90.0, 100.0, 120.0, 150.0, 180.0, 200.0]

LDR_RPM = [3000.0, 4000.0, 5000.0, 5500.0, 6000.0, 6500.0, 7000.0, 7500.0]
LDR_DUTY = [10.0, 20.0, 30.0, 40.0, 50.0, 60.0, 70.0, 80.0, 90.0, 95.0]
KFLDIMX_NATIVE_X = [200.0, 400.0, 600.0, 800.0, 1000.0, 1200.0]

SIGNALS = {
    "time": "TIME",
    "rpm": "nmot_w",
    "load": "rl_w",
    "pfi": "InjSys_facPrtnPfiTar",
    "pfi_unlimited": "InjSys_facPrtnPfiSpUnlimModNew",
    "stft": "frm_w",
    "stft_alt": "fr_w",
    "ltft": "fra_w",
    "ltft_alt": "longft1_w",
    "closed_loop": "B_lr",
    "lambda_request": "lamsbg_w",
    "throttle": "wdkba",
    "baro": "pu_w",
    "wgdc": "tvldste_w",
    "wgdc_alt": "ldtvm_w",
    "boost_actual": "psrg_w",
    "boost_requested": "pvds_w",
}


def finite(value: float | None) -> bool:
    return value is not None and math.isfinite(value)


def parse_float(value: str | None) -> float | None:
    if value is None or not value.strip():
        return None
    try:
        return float(value.strip())
    except ValueError:
        return None


def signal_name(raw: str) -> str:
    groups: list[str] = []
    start = 0
    while True:
        left = raw.find("(", start)
        if left < 0:
            break
        right = raw.find(")", left + 1)
        if right < 0:
            break
        groups.append(raw[left + 1:right])
        start = right + 1
    if len(groups) >= 2:
        return groups[-2]
    if len(groups) == 1:
        return groups[0]
    return raw.strip()


def read_ds1(path: Path) -> dict[str, list[float]]:
    with path.open(newline="", encoding="utf-8-sig") as handle:
        rows = list(csv.reader(handle))
    if not rows:
        return {}
    header_index = 1 if rows[0] and any(
        marker in rows[0][0] for marker in ("firmware", "DS1", "MED17")
    ) else 0
    headers = rows[header_index]
    indices: dict[str, int] = {}
    for index, raw in enumerate(headers):
        name = signal_name(raw.strip())
        for key, expected in SIGNALS.items():
            if name == expected or raw.strip() == expected:
                indices[key] = index
        if raw.strip().startswith("Time(") or raw.strip() == "Time":
            indices["time"] = index

    result = {key: [] for key in SIGNALS}
    for raw_row in rows[header_index + 1:]:
        for key, index in indices.items():
            result[key].append(
                parse_float(raw_row[index]) if index < len(raw_row) else None
            )
    return result


def rows_for(data: dict[str, list[float]], required: Iterable[str],
             optional: Iterable[str] = ()) -> list[dict[str, float | None]]:
    required = list(required)
    optional = list(optional)
    if any(not data.get(key) for key in required):
        return []
    size = min(len(data[key]) for key in required)
    output = []
    for index in range(size):
        row = {key: data[key][index] for key in required}
        if any(value is None for value in row.values()):
            continue
        for key in optional:
            values = data.get(key, [])
            row[key] = values[index] if index < len(values) else None
        output.append(row)
    return output


def interpolated_weights(value: float, bins: list[float]) -> list[tuple[int, float]]:
    if len(bins) <= 1:
        return [(0, 1.0)]
    if value <= bins[0]:
        return [(0, 1.0)]
    if value >= bins[-1]:
        return [(len(bins) - 1, 1.0)]
    hi = bisect.bisect_left(bins, value)
    lo = hi - 1
    span = bins[hi] - bins[lo]
    if span <= 0.0:
        return [(lo, 1.0)]
    fraction = (value - bins[lo]) / span
    if fraction < 1e-9:
        return [(lo, 1.0)]
    if fraction > 1.0 - 1e-9:
        return [(hi, 1.0)]
    return [(lo, 1.0 - fraction), (hi, fraction)]


def interpolate_clamped(value: float, xs: list[float], ys: list[float]) -> float:
    if value <= xs[0]:
        return ys[0]
    if value >= xs[-1]:
        return ys[-1]
    hi = bisect.bisect_right(xs, value)
    lo = hi - 1
    fraction = (value - xs[lo]) / (xs[hi] - xs[lo])
    return ys[lo] + fraction * (ys[hi] - ys[lo])


def pfi_oracle(data: dict[str, list[float]]) -> dict[str, Any]:
    parsed = []
    source_size = min(len(data.get("time", [])), len(data.get("rpm", [])))
    for index in range(source_size):
        time = data["time"][index]
        rpm = data["rpm"][index]
        primary = data.get("pfi", [])
        fallback = data.get("pfi_unlimited", [])
        pfi = primary[index] if index < len(primary) else None
        if pfi is None and index < len(fallback):
            pfi = fallback[index]
        if time is None or rpm is None or pfi is None:
            continue
        load = data.get("load", [])
        parsed.append({
            "rpm": rpm,
            "pfi": pfi,
            "load": load[index] if index < len(load) else None,
        })

    sums = [[0.0 for _ in PFI_LOAD] for _ in PFI_RPM]
    weights = [[0.0 for _ in PFI_LOAD] for _ in PFI_RPM]
    counts = [[0 for _ in PFI_LOAD] for _ in PFI_RPM]
    valid = 0
    for row in parsed:
        rpm, load, pfi = row["rpm"], row["load"], row["pfi"]
        if (not finite(rpm) or not finite(load) or not finite(pfi)
                or rpm <= 0.0 or load <= 0.0 or pfi < 0.0 or pfi > 1.0):
            continue
        valid += 1
        for rpm_index, rpm_weight in interpolated_weights(rpm, PFI_RPM):
            for load_index, load_weight in interpolated_weights(load, PFI_LOAD):
                weight = rpm_weight * load_weight
                sums[rpm_index][load_index] += pfi * weight
                weights[rpm_index][load_index] += weight
                counts[rpm_index][load_index] += 1

    values = [[math.nan for _ in PFI_LOAD] for _ in PFI_RPM]
    for row in range(len(PFI_RPM)):
        for column in range(len(PFI_LOAD)):
            if weights[row][column] > 0.0:
                values[row][column] = max(
                    0.0, min(100.0, sums[row][column] / weights[row][column] * 100.0)
                )

    fallback = [interpolate_clamped(rpm, DEFAULT_RPM, DEFAULT_PFI) for rpm in PFI_RPM]
    for row in range(len(PFI_RPM)):
        for column in range(len(PFI_LOAD)):
            if counts[row][column] != 0:
                continue
            neighbours = []
            if row > 0 and counts[row - 1][column] > 0:
                neighbours.append(values[row - 1][column])
            if row + 1 < len(PFI_RPM) and counts[row + 1][column] > 0:
                neighbours.append(values[row + 1][column])
            if column > 0 and counts[row][column - 1] > 0:
                neighbours.append(values[row][column - 1])
            if column + 1 < len(PFI_LOAD) and counts[row][column + 1] > 0:
                neighbours.append(values[row][column + 1])
            values[row][column] = sum(neighbours) / len(neighbours) if neighbours else fallback[row]

    provenance = []
    for row in range(len(PFI_RPM)):
        provenance_row = []
        for column in range(len(PFI_LOAD)):
            measured_neighbour = (
                (row > 0 and counts[row - 1][column] > 0)
                or (row + 1 < len(PFI_RPM) and counts[row + 1][column] > 0)
                or (column > 0 and counts[row][column - 1] > 0)
                or (column + 1 < len(PFI_LOAD) and counts[row][column + 1] > 0)
            )
            provenance_row.append(
                "MEASURED" if counts[row][column] > 0
                else "INTERPOLATED" if measured_neighbour
                else "DEFAULT_CURVE"
            )
        provenance.append(provenance_row)

    buckets: dict[int, list[float]] = {}
    for row in parsed:
        rpm, pfi = row["rpm"], row["pfi"]
        if finite(rpm) and finite(pfi) and rpm > 0.0 and 0.0 <= pfi <= 1.0:
            key = int(rpm / 500) * 500
            buckets.setdefault(key, []).append(pfi)

    return {
        "rpmAxis": PFI_RPM,
        "loadAxis": PFI_LOAD,
        "totalSamples": valid,
        "values": values,
        "counts": counts,
        "effectiveWeights": weights,
        "provenance": provenance,
        "loggedRpmAxis": [float(key) for key in sorted(buckets)],
        "loggedPfiPercent": [
            sum(buckets[key]) / len(buckets[key]) * 100.0 for key in sorted(buckets)
        ],
    }


def fuel_trim_rows(data: dict[str, list[float]]) -> list[dict[str, float | None]]:
    size = min(len(data.get("time", [])), len(data.get("rpm", [])),
               len(data.get("load", [])))
    rows = []
    for index in range(size):
        time, rpm, load = data["time"][index], data["rpm"][index], data["load"][index]
        if time is None or rpm is None or load is None:
            continue
        stft_values = data.get("stft", [])
        stft_alt_values = data.get("stft_alt", [])
        ltft_values = data.get("ltft", [])
        ltft_alt_values = data.get("ltft_alt", [])
        stft = stft_values[index] if index < len(stft_values) else None
        if stft is None and index < len(stft_alt_values):
            stft = stft_alt_values[index]
        ltft = ltft_values[index] if index < len(ltft_values) else None
        if ltft is None and index < len(ltft_alt_values):
            ltft = ltft_alt_values[index]
        if stft is None and ltft is None:
            continue
        row: dict[str, float | None] = {
            "time": time, "rpm": rpm, "load": load, "stft": stft, "ltft": ltft
        }
        for key in ("closed_loop", "lambda_request"):
            values = data.get(key, [])
            row[key] = values[index] if index < len(values) else None
        rows.append(row)
    return rows


def fuel_trim_oracle(data: dict[str, list[float]]) -> dict[str, Any]:
    rows = fuel_trim_rows(data)
    sums = [[0.0 for _ in FUEL_LOAD] for _ in FUEL_RPM]
    sum_squares = [[0.0 for _ in FUEL_LOAD] for _ in FUEL_RPM]
    weights = [[0.0 for _ in FUEL_LOAD] for _ in FUEL_RPM]
    counts = [[0 for _ in FUEL_LOAD] for _ in FUEL_RPM]
    filtered = 0
    transient_filtered = 0
    for index, row in enumerate(rows):
        closed_loop = row["closed_loop"]
        requested_lambda = row["lambda_request"]
        if finite(closed_loop) and closed_loop != 1.0:
            filtered += 1
            continue
        if finite(requested_lambda) and abs(requested_lambda - 1.0) >= 0.05:
            filtered += 1
            continue
        if index > 0:
            previous = rows[index - 1]
            if (finite(previous["time"]) and finite(row["time"])
                    and row["time"] > previous["time"]):
                rate = abs(row["rpm"] - previous["rpm"]) / (row["time"] - previous["time"])
                if rate > 1000.0:
                    transient_filtered += 1
                    continue
        stft = row["stft"] if finite(row["stft"]) else None
        ltft = row["ltft"] if finite(row["ltft"]) else None
        if stft is None and ltft is None:
            filtered += 1
            continue
        trim = ((stft - 1.0) * 100.0 if stft is not None else 0.0)
        trim += ((ltft - 1.0) * 100.0 if ltft is not None else 0.0)
        for rpm_index, rpm_weight in interpolated_weights(row["rpm"], FUEL_RPM):
            for load_index, load_weight in interpolated_weights(row["load"], FUEL_LOAD):
                weight = rpm_weight * load_weight
                sums[rpm_index][load_index] += trim * weight
                sum_squares[rpm_index][load_index] += trim * trim * weight
                weights[rpm_index][load_index] += weight
                counts[rpm_index][load_index] += 1

    corrections = [[0.0 for _ in FUEL_LOAD] for _ in FUEL_RPM]
    diagnostics = []
    bins_with_data = 0
    bins_rejected = 0
    for row in range(len(FUEL_RPM)):
        diagnostic_row = []
        for column in range(len(FUEL_LOAD)):
            count, weight = counts[row][column], weights[row][column]
            if weight > 0.0:
                bins_with_data += 1
            if count == 0 or weight <= 0.0:
                diagnostic_row.append({
                    "sampleCount": 0, "mean": 0.0, "stdDev": 0.0,
                    "correction": 0.0, "effectiveWeight": 0.0,
                    "rejected": True, "reason": "no samples",
                })
                continue
            mean = sums[row][column] / weight
            variance = sum_squares[row][column] / weight - mean * mean
            std_dev = math.sqrt(max(0.0, variance))
            if weight + 1e-9 < 3.0:
                rejected, reason = True, "insufficient effective samples"
                bins_rejected += 1
            elif std_dev > 5.0:
                rejected, reason = True, "standard deviation"
                bins_rejected += 1
            elif abs(mean) <= 3.0:
                rejected, reason = False, "within threshold"
            else:
                rejected, reason = False, None
            correction = mean if not rejected and abs(mean) > 3.0 else 0.0
            corrections[row][column] = correction
            diagnostic_row.append({
                "sampleCount": count, "mean": mean, "stdDev": std_dev,
                "correction": correction, "effectiveWeight": weight,
                "rejected": rejected, "reason": reason,
            })
        diagnostics.append(diagnostic_row)

    return {
        "rpmAxis": FUEL_RPM,
        "loadAxis": FUEL_LOAD,
        "totalSamplesProcessed": len(rows),
        "samplesFilteredOut": filtered,
        "transientFilteredOut": transient_filtered,
        "binsWithData": bins_with_data,
        "binsRejected": bins_rejected,
        "corrections": corrections,
        "diagnostics": diagnostics,
    }


def nearest_index(values: list[float], value: float) -> int:
    index = bisect.bisect_left(values, value)
    if index >= len(values):
        return len(values) - 1
    if index > 0:
        fraction = (value - values[index - 1]) / (values[index] - values[index - 1])
        if fraction < 0.5:
            index -= 1
    return index


def ieee_div(numerator: float, denominator: float) -> float:
    """Match JVM floating-point division for zero denominators."""
    if denominator != 0.0:
        return numerator / denominator
    if numerator == 0.0:
        return math.nan
    return math.copysign(math.inf, numerator * math.copysign(1.0, denominator))


def monotone_cubic(xs: list[float], ys: list[float], value: float) -> float:
    count = len(xs)
    if count == 0:
        return 0.0
    if count == 1:
        return ys[0]
    if value <= xs[0]:
        return ys[0]
    if value >= xs[-1]:
        return ys[-1]
    if count == 2:
        fraction = ieee_div(value - xs[0], xs[1] - xs[0])
        return ys[0] + fraction * (ys[1] - ys[0])
    deltas = [ieee_div(ys[index + 1] - ys[index], xs[index + 1] - xs[index])
              for index in range(count - 1)]
    tangents = [0.0] * count
    tangents[0], tangents[-1] = deltas[0], deltas[-1]
    for index in range(1, count - 1):
        if deltas[index - 1] * deltas[index] <= 0.0:
            tangents[index] = 0.0
        else:
            tangents[index] = ieee_div(
                2.0 * deltas[index - 1] * deltas[index],
                deltas[index - 1] + deltas[index],
            )
    for index, delta in enumerate(deltas):
        if abs(delta) < 1e-30:
            tangents[index] = tangents[index + 1] = 0.0
        else:
            alpha = ieee_div(tangents[index], delta)
            beta = ieee_div(tangents[index + 1], delta)
            radius = alpha * alpha + beta * beta
            if radius > 9.0:
                scale = 3.0 / math.sqrt(radius)
                tangents[index] = scale * alpha * delta
                tangents[index + 1] = scale * beta * delta
    interval = bisect.bisect_left(xs, value) - 1
    interval = max(0, min(interval, count - 2))
    x0, x1 = xs[interval], xs[interval + 1]
    y0, y1 = ys[interval], ys[interval + 1]
    m0, m1 = tangents[interval], tangents[interval + 1]
    width = x1 - x0
    t = ieee_div(value - x0, width)
    t2, t3 = t * t, t * t * t
    return ((2 * t3 - 3 * t2 + 1) * y0
            + (t3 - 2 * t2 + t) * width * m0
            + (-2 * t3 + 3 * t2) * y1
            + (t3 - t2) * width * m1)


def ldrpid_rows(data: dict[str, list[float]]) -> list[dict[str, float]]:
    required = ["time", "rpm", "throttle", "baro", "boost_actual"]
    size = min(len(data.get(key, [])) for key in required)
    rows = []
    for index in range(size):
        row = {key: data[key][index] for key in required}
        wgdc_values = data.get("wgdc", [])
        wgdc_alt_values = data.get("wgdc_alt", [])
        wgdc = wgdc_values[index] if index < len(wgdc_values) else None
        if wgdc is None and index < len(wgdc_alt_values):
            wgdc = wgdc_alt_values[index]
        if any(value is None for value in row.values()) or wgdc is None:
            continue
        requested_values = data.get("boost_requested", [])
        row["wgdc"] = wgdc
        row["boost_requested"] = (
            requested_values[index] if index < len(requested_values) else None
        )
        rows.append(row)
    return rows


def interpolate_non_linear(table: list[list[float]], counts: list[list[int]]) -> None:
    for row_index, row in enumerate(table):
        filled = [index for index, count in enumerate(counts[row_index]) if count > 0]
        if filled:
            first, last = filled[0], filled[-1]
            if len(filled) >= 3:
                known_x = [LDR_DUTY[index] for index in filled]
                known_y = [row[index] for index in filled]
                for index in range(first + 1, last):
                    if row[index] == 0.0:
                        row[index] = monotone_cubic(known_x, known_y, LDR_DUTY[index])
            else:
                for index in range(first + 1, last):
                    if row[index] != 0.0:
                        continue
                    left = next((candidate for candidate in reversed(filled) if candidate < index), None)
                    right = next((candidate for candidate in filled if candidate > index), None)
                    if left is not None and right is not None:
                        fraction = ((LDR_DUTY[index] - LDR_DUTY[left])
                                    / (LDR_DUTY[right] - LDR_DUTY[left]))
                        row[index] = row[left] + fraction * (row[right] - row[left])
            for index in range(last + 1, len(row)):
                if row[index] != 0.0:
                    continue
                previous = next((candidate for candidate in reversed(filled)
                                 if candidate < last), None)
                if previous is not None:
                    slope = ((row[last] - row[previous])
                             / (LDR_DUTY[last] - LDR_DUTY[previous]))
                    row[index] = max(
                        row[last], row[last] + slope * (LDR_DUTY[index] - LDR_DUTY[last])
                    )
                else:
                    row[index] = row[last] * (1.0 + 0.02 * (index - last))
            for index in range(first):
                if row[index] == 0.0:
                    row[index] = max(
                        0.1,
                        row[first] * max(LDR_DUTY[index], 1.0) / max(LDR_DUTY[first], 1.0),
                    )
            for index in range(len(row)):
                if counts[row_index][index] == 0 and (
                    row[index] <= 0.0 or not math.isfinite(row[index])
                ):
                    row[index] = 0.1
            for index in range(1, len(row)):
                if counts[row_index][index] == 0 and row[index] < row[index - 1]:
                    row[index] = row[index - 1] + 0.01


def ldrpid_oracle(data: dict[str, list[float]]) -> dict[str, Any]:
    rows = ldrpid_rows(data)
    pressure = [[0.0 for _ in LDR_DUTY] for _ in LDR_RPM]
    counts = [[0 for _ in LDR_DUTY] for _ in LDR_RPM]
    for row in rows:
        if row["throttle"] < 80.0:
            continue
        relative = row["boost_actual"] - row["baro"]
        if relative <= 0.0:
            continue
        rpm_index = nearest_index(LDR_RPM, row["rpm"])
        duty_index = nearest_index(LDR_DUTY, row["wgdc"])
        pressure[rpm_index][duty_index] += relative
        counts[rpm_index][duty_index] += 1
    non_linear = [
        [
            pressure[row][column] / counts[row][column] * 0.0145038
            if counts[row][column] else 0.0
            for column in range(len(LDR_DUTY))
        ]
        for row in range(len(LDR_RPM))
    ]
    interpolate_non_linear(non_linear, counts)

    supported_rows = [index for index, row in enumerate(counts) if sum(row) > 0]
    if len(supported_rows) >= 2:
        for row in range(len(non_linear)):
            if row in supported_rows:
                continue
            lower = next((item for item in reversed(supported_rows) if item < row), None)
            upper = next((item for item in supported_rows if item > row), None)
            if lower is None or upper is None:
                continue
            fraction = ((LDR_RPM[row] - LDR_RPM[lower])
                        / (LDR_RPM[upper] - LDR_RPM[lower]))
            for column in range(len(LDR_DUTY)):
                non_linear[row][column] = (
                    non_linear[lower][column]
                    + fraction * (non_linear[upper][column] - non_linear[lower][column])
                )

    linear = []
    for row in non_linear:
        supported = [value for value in row if value > 0.0 and math.isfinite(value)]
        if not supported:
            linear.append([0.0] * len(row))
        else:
            minimum, maximum = min(supported), max(supported)
            linear.append([
                minimum + (maximum - minimum) * index / (len(row) - 1)
                for index in range(len(row))
            ])

    kfldrl = []
    for row_index in range(len(non_linear)):
        pairs = sorted(
            (boost, duty) for boost, duty in zip(non_linear[row_index], LDR_DUTY)
            if boost > 0.0 and math.isfinite(boost)
        )
        if len(pairs) < 2:
            kfldrl.append([30.0] * len(LDR_DUTY))
        else:
            xs, ys = [p[0] for p in pairs], [p[1] for p in pairs]
            kfldrl.append([
                0.0 if math.isnan(value) else value
                for value in (monotone_cubic(xs, ys, target)
                              for target in linear[row_index])
            ])

    supported_boost = [
        value * 68.9476 for row in linear for value in row
        if value > 0.0 and math.isfinite(value)
    ]
    if not supported_boost:
        kfldimx_x = KFLDIMX_NATIVE_X.copy()
        kfldimx = [[30.0] * len(kfldimx_x) for _ in LDR_RPM]
    else:
        observed_min = math.floor(min(supported_boost) / 100.0) * 100.0
        observed_max = math.ceil(max(supported_boost) / 100.0) * 100.0
        native_min, native_max = min(KFLDIMX_NATIVE_X), max(KFLDIMX_NATIVE_X)
        overlaps = observed_max > native_min and observed_min < native_max
        if overlaps:
            minimum, maximum = max(observed_min, native_min), min(observed_max, native_max)
            kfldimx_x = [
                minimum + (maximum - minimum) * index / (len(KFLDIMX_NATIVE_X) - 1)
                for index in range(len(KFLDIMX_NATIVE_X))
            ]
        else:
            kfldimx_x = KFLDIMX_NATIVE_X.copy()
        kfldimx = []
        for row_index, row in enumerate(linear):
            pairs = sorted(
                (boost * 68.9476, duty) for boost, duty in zip(row, LDR_DUTY) if boost > 0.0
            )
            if len(pairs) < 2:
                kfldimx.append([30.0] * len(kfldimx_x))
            else:
                xs, ys = [p[0] for p in pairs], [p[1] for p in pairs]
                kfldimx.append([monotone_cubic(xs, ys, target) for target in kfldimx_x])

    if supported_rows:
        first, last = supported_rows[0], supported_rows[-1]
        for row in range(len(LDR_RPM)):
            if row < first or row > last:
                kfldrl[row] = [30.0] * len(LDR_DUTY)
                kfldimx[row] = [30.0] * len(kfldimx_x)

    diagnostics = []
    for rpm_index, rpm in enumerate(LDR_RPM):
        matching = [
            row for row in rows
            if row["throttle"] >= 80.0 and nearest_index(LDR_RPM, row["rpm"]) == rpm_index
        ]
        target_rows = [row for row in matching if finite(row["boost_requested"])]
        errors = [row["boost_actual"] - row["boost_requested"] for row in target_rows]
        diagnostics.append({
            "rpm": rpm,
            "sampleCount": len(matching),
            "measuredDutyCells": sum(1 for count in counts[rpm_index] if count > 0),
            "averageAbsolutePressureErrorMbar": (
                sum(abs(error) for error in errors) / len(errors) if errors else None
            ),
            "maximumOvershootMbar": max(0.0, max(errors)) if errors else None,
            "percentWithinTolerance": (
                sum(1 for error in errors if abs(error) <= 50.0) * 100.0 / len(errors)
                if errors else None
            ),
        })

    return {
        "rpmAxis": LDR_RPM,
        "dutyAxis": LDR_DUTY,
        "nonLinear": non_linear,
        "linear": linear,
        "kfldrl": kfldrl,
        "kfldimxXAxis": kfldimx_x,
        "kfldimx": kfldimx,
        "sampleCounts": counts,
        "diagnostics": diagnostics,
    }


def rounded(value: Any) -> Any:
    if isinstance(value, float):
        if math.isnan(value) or math.isinf(value):
            raise ValueError(f"non-finite oracle output: {value}")
        return round(value, 12)
    if isinstance(value, list):
        return [rounded(item) for item in value]
    if isinstance(value, dict):
        return {key: rounded(item) for key, item in value.items()}
    return value


def build_fixture(path: Path) -> dict[str, Any]:
    data = read_ds1(path)
    return rounded({
        "file": path.name,
        "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        "pfi": pfi_oracle(data),
        "fuelTrim": fuel_trim_oracle(data),
        "ldrpid": ldrpid_oracle(data),
    })


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--logs", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    paths = sorted(args.logs.glob("*.csv"))
    if not paths:
        raise SystemExit(f"No CSV files found under {args.logs}")
    document = {
        "schemaVersion": 1,
        "oracle": "tools/golden/med17_oracle.py:v1",
        "fixtures": [build_fixture(path) for path in paths],
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(document, indent=2, sort_keys=True) + "\n")


if __name__ == "__main__":
    main()
