# MED17 Calibration Guide

This guide covers calibration workflows for Bosch MED17 ECUs — Audi RS3/TTRS 2.5T TFSI (EA855 EVO), 4.0T TFSI, 5.2 V10, and related platforms. For ME7 (B5 S4 2.7T, 1.8T), see the [ME7 Calibration Guide](me7-calibration-guide.md). For Motronic or MED9, see the [Motronic](../technical/motronic/motronic-tuning-workflow.md) and [MED9](../technical/med9/med9-tuning-workflow.md) tuning workflows.

Calibration order matters. Start with fueling (both port and direct injectors), then torque/load tables, then boost PID. MED17's adaptive VE model eliminates the need for manual MAF scaling, and the throttle control architecture is handled internally — so the tools that apply to ME7 (MLHFM, KFVPDKSD, WDKUGDN, Alpha-N) are not covered here. One less thing to calibrate, one less thing to get wrong.

This guide is the companion to the [README](../README.md#stage-2-calibration). Where the README tells you *what* each tool does and *whether* you need it, this guide tells you *how* — step by step, with screenshots, algorithms, and the kind of detail that only matters when you're actually doing the work.

---

## MED17 vs ME7: What's Different

MED17 follows the same torque-based architecture as ME7 — torque request, load request, pressure target, boost control. The driver model, torque monitoring, and intervention logic are conceptually identical. The differences are in the details.

| Feature | ME7 | MED17 |
|---------|-----|-------|
| Injection | Single bank | Dual (port + direct) |
| MAF Scaling | MLHFM linearization curve | Not applicable (adaptive VE model via `fupsrl_w`) |
| VE Model | KFURL/KFPBRK (static maps) | Adaptive (`fupsrls_w` / `pbrint_w`) |
| Throttle Model | KFVPDKSD + WDKUGDN | Different architecture (not calibratable here) |
| Alpha-N | msdk_w vs mshfm_w diagnostic | Not applicable |
| Torque Tables | KFMIOP / KFMIRL | KFLMIOP / KFLMIRL (same math, different names) |
| Fuel Trim | Closed/Open Loop (MAF correction via O2) | rk_w STFT/LTFT analysis (Fuel Trim tab) |
| Boost PID | KFLDRL / KFLDIMX | Same maps, same PID — but non-linear response with aftermarket hardware |
| Log Format | ME7Logger CSV | DS1 CSV |

The most significant architectural difference is the adaptive VE model. ME7 uses static maps (KFURL, KFPBRK) to convert between pressure and load — if you change hardware, you have to recalibrate those maps manually. MED17 replaces this with an adaptive volumetric efficiency value (`fupsrls_w`, approximately 0.091 for the 2.5T at standard conditions) that the ECU adjusts continuously. This means you never have to manually scale the MAF or fiddle with VE correction factors. The ECU handles it. When the adaptive model drifts (hardware changes, altitude, aging), the ECU compensates through fuel trims — and the Fuel Trim tool corrects those.

MxT automatically shows only the tabs relevant to your platform. Select your platform (ME7, MED17, Motronic, or MED9) in the Configuration tab.

---

## Do I Need Calibration? (MED17 Hardware Reference)

The 2.5T cars make insane power very easily and get into high load territory fast. The EA855 EVO responds aggressively to bolt-on modifications — a downpipe and tune can push you well past the stock load range. It's the kind of engine that rewards bolt-ons so generously that people forget to recalibrate the ECU until something stops adding up. Once you're running aftermarket turbos, you'll need to rescale KFLMIOP/KFLMIRL and redo the entire PID setup (KFLDRL + every table in the PID chain) because the stock PID was linearized for a turbo that doesn't exist on your car anymore.

**When you need the Optimizer only (no calibration):**
- Stock or lightly modified turbo (stock frame turbo with tune)
- Stock MAP sensor range is adequate
- Boost and load are within the stock table axes

**When you need full calibration:**
- Aftermarket turbo that exceeds the stock MAP sensor range
- Load exceeds the KFLMIOP axis ceiling
- LDRPID pre-control is completely wrong for the new turbo response
- You've changed injectors on either bank (port or direct or both)

---

## Table of Contents

1. [Fueling (Dual Injection — KRKTE_PFI & KRKTE_GDI)](#fueling-dual-injection--krkte_pfi--krkte_gdi)
2. [Fuel Trim (rk_w Correction)](#fuel-trim-rk_w-correction)
3. [PLSOL (Pressure to Load Conversion)](#plsol-pressure-to-load-conversion)
4. [KFLMIOP (Load/Fill to Torque)](#kflmiop-loadfill-to-torque)
5. [KFLMIRL (Torque to Load/Fill)](#kflmirl-torque-to-loadfill)
6. [KFZWOP (Optimal Ignition Timing)](#kfzwop-optimal-ignition-timing)
7. [KFZW/2 (Ignition Timing)](#kfzw2-ignition-timing)
8. [LDRPID (Feed-Forward PID)](#ldrpid-feed-forward-pid)
9. [Optimizer (MED17)](#optimizer-med17)

---

## Fueling (Dual Injection — KRKTE_PFI & KRKTE_GDI)

The 2.5T TFSI (and other MED17 dual-fuel platforms) fire both port injectors and direct injectors simultaneously. Each bank has its own injector constant (KRKTE_PFI / KRKTE_GDI) and its own dead time curve (TVUB_PFI / TVUB_GDI). If you upgrade injectors on either bank, you need to recalibrate that bank's constants independently.

ME7 has one injector bank, one KRKTE, one TVUB. MED17 doubles everything. The math is the same, you just do it twice. Welcome to dual injection.

### Port Injector Scaling

The port injectors run at low fuel pressure (typically 4.0 bar on the 2.5T). The KRKTE_PFI calculation is identical to ME7's KRKTE formula:

```
KRKTE_PFI = 50.2624 * Vhzyl / Qstat_PFI
```

Where `Vhzyl` is the cylinder volume in cm3 and `Qstat_PFI` is the port injector's static flow rate in cm3/s at the rated pressure.

When swapping port injectors:

```
KRKTE_PFI_new = KRKTE_PFI_old * (old_flow / new_flow) * sqrt(P_new / P_old)
```

The Bernoulli pressure correction (`sqrt(P_new / P_old)`) matters if you're changing fuel pressure. If both injectors are rated at 4.0 bar, this factor is 1.0.

#### TVUB (Port Dead Time)

Port injectors have opening delay (Ventilverzugszeit) that varies with battery voltage. Enter the manufacturer's dead time spec — if you only have the value at 14V, the calculator estimates the full curve using 1/V scaling.

### Direct Injector Scaling

The direct injectors run at much higher fuel pressure — 200 bar on the 2.5T. The KRKTE_GDI calculation is the same formula, but the fuel pressure correction is more significant because the operating pressure range is wider.

```
KRKTE_GDI_new = KRKTE_GDI_old * (old_flow / new_flow) * sqrt(P_new / P_old)
```

On the 2.5T, the stock GDI pressure is typically 200 bar. If you're running upgraded high-pressure fuel pumps pushing 250+ bar, the `sqrt(P_new / P_old)` correction becomes meaningful.

### Fuel Split Calculator

Given a load/RPM operating point, the split calculator shows the fuel share between port and direct injection. The 2.5T varies the split ratio across the operating map — at idle and cruise it's mostly port injection, at high load/RPM it shifts toward direct injection for charge cooling benefits.

If you've changed injector sizes on one bank but not the other, or if you're running significantly different fuel pressures between banks, the effective fuel split changes. The calculator helps you verify that the total fuel delivery matches the commanded fuel mass at key operating points.

### Usage

1. Open the **Dual Injection** tab (visible only in MED17 mode)
2. **Port Injector** sub-tab: Enter stock and new port injector specs (flow rate, pressure, dead time at 14V)
3. **Direct Injector** sub-tab: Enter stock and new direct injector specs
4. **Split Calculator** sub-tab: Enter an RPM and load point to see the fuel share breakdown
5. Apply the calculated KRKTE_PFI, KRKTE_GDI, and TVUB to your BIN

<img src="/documentation/images/med17/dual_injection.png" width="800">

---

## Fuel Trim (rk_w Correction)

ME7 relies on MAF linearization (MLHFM) to get air metering right — if the MAF drifts, you correct the linearization curve. MED17 doesn't work this way. The adaptive VE model (`fupsrls_w`) handles air metering internally, and when it drifts, the ECU compensates through short-term and long-term fuel trims (STFT/LTFT). Persistent trim offsets indicate that the base `rk_w` (relative fuel mass factor) map is wrong for the current hardware.

The Fuel Trim tab is the MED17 equivalent of the ME7 Closed Loop MLHFM workflow. Instead of correcting a MAF linearization curve, it corrects the `rk_w` multiplicative fuel correction map.

### Algorithm

The FuelTrimAnalyzer works as follows:

1. **Parse logs:** Load one or more DS1 CSV files containing fuel trim data
2. **Bin samples:** Each sample's RPM (`nmot_w`) and load (`rl_w`) are snapped to the nearest `rk_w` grid cell. The combined trim `(STFT - 1.0) + (LTFT - 1.0)` is accumulated per cell as a percentage
3. **Average:** Cells with at least 3 samples get an average correction. Cells below the sample threshold are left uncorrected
4. **Apply:** The corrected `rk_w` value is `original_value * (1 + correction% / 100)` — positive correction adds fuel, negative removes it
5. **Flag outliers:** Cells where the average trim exceeds +/-3% are flagged with warnings and sample counts

### What to Log (DS1)

| Signal | Purpose |
|--------|---------|
| `nmot_w` | RPM axis for binning |
| `rl_w` | Load axis for binning |
| `fr_w` (or `frm_w`) | Short-term fuel trim |
| `fra_w` | Long-term fuel trim |

Log at a variety of RPM and load conditions — idle, cruise, part-throttle, light boost. The more operating points covered, the more complete the correction. Multiple log files can be loaded simultaneously.

### Usage

1. Select the `rk_w` map definition in the Configuration tab
2. Open the **Fuel Trim** tab (visible only in MED17 mode)
3. Load one or more DS1 CSV logs using the file picker
4. Review the per-bin average trims — look for systematic patterns (e.g., consistently rich at high RPM, lean at low load)
5. Review the corrected `rk_w` output map
6. Write the corrected `rk_w` to your BIN

<img src="/documentation/images/med17/fuel_trim_med17.png" width="800">

### When to Use

- **After hardware changes** that affect volumetric efficiency: turbo upgrade, intercooler swap, intake modifications, cam changes
- **When trims are persistently off-center** across the operating range — if the ECU is always adding or removing 5%+ fuel in certain cells, bake that correction into the base map
- **Iteratively** — like Closed Loop MLHFM on ME7, this is a log-correct-flash-repeat process. One round gets you close; two or three rounds get you tight

---

## PLSOL (Pressure to Load Conversion)

PLSOL is your reality-check calculator. It answers the question every tuner eventually asks: "how much boost do I actually need to hit X% load?" — and, by extension, "can my hardware even do that?"

The model is deliberately simple. The *only* parameter that affects load is pressure. Barometric pressure, intake air temperature, and the pressure-to-load conversion factor are all treated as constants. This means the model says 16 psi is 16 psi no matter what turbo produces it — a stock turbo making 16 psi and a GT35R making 16 psi produce the same load at the same RPM.

Despite the simplicity, this model is remarkably consistent with real-world results. The reason it works is that the ECU *itself* uses this simplified model — so even if the physics are more complex, the ECU's behavior follows this math.

You can edit barometric pressure, intake air temperature, and the pressure-to-load conversion to see how the ECU would respond to these parameters changing in the real world.

MED17's live VE value (`fupsrls_w`) is approximately 0.091 for the 2.5T TFSI at standard conditions — use this when estimating pressure-to-load conversions for the EA855 EVO platform.

### Log Overlay

PLSOL supports loading a directory of DS1 logs to overlay actual WOT data points on the pressure/load chart. The logged points appear in green alongside the theoretical curves — so you can see exactly where your hardware is operating relative to the model. On MED17, the overlay extracts the mean `fupsrls_w` (≈ KFURL) directly from the logs and auto-fills it into the calculator. No guessing, no manual entry.

<img src="/documentation/images/med17/plsol_med17.png" width="800">

### PLSOL -> Airflow (Pressure to Airflow)

MxT calculates estimated airflow for a given load based on engine displacement (in liters) and RPM. This tells you whether your intake system can support the load you're requesting.

<img src="/documentation/images/plsol_airflow.png" width="800">

### PLSOL -> Power (Pressure to Horsepower)

MxT calculates estimated horsepower for a given load based on engine displacement (in liters) and RPM.

<img src="/documentation/images/plsol_power.png" width="800">

---

## KFLMIOP (Load/Fill to Torque)

KFLMIOP is the optimum torque table — and it's the map that confuses more people than any other. Most of that confusion comes from the word "torque," because KFLMIOP doesn't contain torque values in any physical unit. It contains *normalized* torque — a value between 0% and 100% that represents the ratio of current load to the maximum load the MAP sensor can see.

### DS1 Scalar Mode

On MED17 with DS1 aftermarket calibrations, KFLMIOP is often reduced to a **scalar constant** (a 1x1 map with no axes). DS1 bypasses the native torque-demand architecture entirely — it uses only the boost pressure target as the output regulator, so the full torque normalization table is unnecessary. MxT auto-detects this condition and switches to scalar rescaling mode. Instead of the full table rescale workflow, you just enter a target max load percentage and MxT handles the rest. The KFLMIOP scalar is typically set to 400% on stock DS1 setups.

Understanding this normalization is the key to everything that follows. Get it right and the rest of the calibration tables fall into place. Get it wrong and you'll spend weeks chasing torque monitoring interventions that make no sense.

Note that KFLMIRL is the inverse of KFLMIOP, not the other way around.

### Tuning Philosophy

KFLMIOP describes the optimum engine torque at each load/RPM point, but it does so as a normalization relative to the MAP sensor's maximum measurement capability.

"Torque" here is a value between 0 and 1 (or 0% and 100%). Each cell in KFLMIOP represents: *at this load and RPM, what fraction of the MAP sensor's maximum theoretical load does optimum torque represent?*

This is the part that breaks when you change hardware. If you upgrade to bigger turbos that can produce more pressure than the MAP sensor can measure, you need a bigger MAP sensor — and the moment you change the MAP sensor limit, *the denominator changes for every cell in the table*. KFLMIOP needs to be rescaled, not extrapolated.

MxT handles this by taking your new maximum MAP pressure, rescaling the load axis via PLSOL, and then renormalizing the torque values. KFLMIOP can also be converted to a boost table via the PLSOL calculation after you've derived peak load. Unless you have access to a dyno, there's no way to derive OEM-quality KFLMIOP for your specific hardware. MxT's rescaling is a principled approximation — better than extrapolating or just widening the axis and hoping for the best.

Additional empirical tuning points:

* Any part of KFLMIOP (load/RPM range) that can only be reached above ~60% wped_w is unrestricted and can be raised to keep mimax high such that requested load does not get capped.
* Ensure that mibas remains below miszul to avoid intervention (which you will see in mizsolv) by lowering KFLMIOP in areas reachable by actual measured load. This mainly becomes a concern in the middle of KFLMIOP.

### Usage

* On the left side, MxT analyzes the current KFLMIOP and estimates the MAP sensor upper limit and the real-world pressure limit of the existing calibration
* Based on the analysis, a boost table is derived and viewable under the "Boost" tab
* On the right side, input your new MAP sensor upper limit and desired pressure limit
* MxT outputs a rescaled KFLMIOP table and axis
* Copy the KFLMIOP table and axis to other tables (KFLMIRL/KFZWOP/KFZW) to generate corresponding maps

<img src="/documentation/images/med17/kfmiop_med17.png" width="800">

Note that KFLMIOP also produces axes for KFLMIOP, KFZWOP, and KFZW so you can scale your ignition timing correctly.

---

## KFLMIRL (Torque to Load/Fill)

KFLMIRL is the inverse of KFLMIOP. It exists entirely as a lookup optimization — so the ECU doesn't have to search KFLMIOP every time it wants to convert a torque request into a load request. If KFLMIOP says "at this load and RPM, optimum torque is X%," then KFLMIRL says "for X% torque at this RPM, the required load is Y%."

This means KFLMIRL is mechanically derived from KFLMIOP. If you change KFLMIOP, you must regenerate KFLMIRL or the ECU's torque-to-load conversion won't match its load-to-torque conversion. The ECU will notice this inconsistency and it will not be subtle about letting you know.

### DS1 Scalar Mode

When KFLMIOP is a DS1 scalar, KFLMIRL is rescaled along its own load axis to the target max load ceiling. MxT detects the scalar condition automatically and presents a simplified interface — enter the target max load (defaults to the KFLMIOP scalar value) and the rescaled KFLMIRL is generated. No table inversion needed when the input is a single number.

<img src="/documentation/images/med17/kfmirl_med17.png" width="800">

### Algorithm

Inverts the input for the output. That's it. It's a matrix inversion, not a personality test.

### Usage

* KFLMIOP is the input and KFLMIRL is the output
* KFLMIOP from the binary will be displayed by default
* Optionally modify KFLMIOP to the desired values
* KFLMIOP will be inverted to produce KFLMIRL on the left side

---

## KFZWOP (Optimal Ignition Timing)

If you modified KFLMIRL/KFLMIOP, you need to extend the KFZWOP axis to cover the new load range. KFZWOP defines the ignition advance at which the engine produces maximum torque (MBT) for each load/RPM point. If your load axis doesn't reach your actual operating range, the ECU is flying blind on timing.

<img src="/documentation/images/med17/kfzwop_med17.png" width="800">

### Algorithm

The input KFZWOP is extrapolated to the input KFZWOP x-axis range (engine load from generated KFLMIOP).

**Pay very close attention to the output.** Extrapolation works reasonably for linear functions. Ignition timing as a function of load is *not* a linear function — at high loads, optimal timing decreases rapidly to avoid detonation, and a linear extrapolation will happily suggest advancing timing into knock territory. The math doesn't know this. You do. Examine the output and make sure it's physically reasonable before using it. You will almost certainly have to rework the high-load end of the output manually.

If you extrapolate ignition timing and flash it without checking, you deserve whatever happens next.

### Usage

* Copy and paste your KFZWOP and the x-axis load range generated from KFLMIOP
* Copy and paste the output KFZWOP directly into TunerPro

---

## KFZW/2 (Ignition Timing)

Same story as KFZWOP — if you modified KFLMIRL/KFLMIOP, you need to extend KFZW/2 to cover the new load range. KFZW defines the *actual* ignition timing map (as opposed to KFZWOP's *optimal* timing), so the same warnings about extrapolation apply — arguably more so, since KFZW is what the ECU actually fires the spark plugs with.

<img src="/documentation/images/med17/kfzw_med17.png" width="800">

### Algorithm

The input KFZW/2 is extrapolated to the input KFZW/2 x-axis range (engine load from generated KFLMIOP).

**Pay very close attention to the output.** Same warning as KFZWOP — extrapolation of a non-linear function will produce garbage at the extremes. Optimal ignition advance does not increase linearly with load. At high loads, it decreases. A linear extrapolation doesn't know this and will suggest timing values that will detonate your engine. Check the output. Rework the high-load cells manually. This is not optional.

### Multi-Switch Mode (DS1 Fuel Blend Maps)

On MED17 with DS1 aftermarket calibrations, KFZW uses a map-switch architecture. DS1 overwrites the native KFZW with numbered switch maps — up to 6 fuel-blend variants:

| Switch Position | Fuel Blend |
|----------------|------------|
| Map 0 | Gasoline 0 |
| Map 1 | Gasoline 1 |
| Map 2 | Gasoline 2 |
| Map 3 | Ethanol 0 |
| Map 4 | Ethanol 1 |
| Map 5 | Ethanol 2 |

MxT detects the DS1 scalar condition (KFLMIOP is a 1x1 map) and automatically enables multi-switch mode. You can add each switch map from your XDF, rescale them all to a new max load ceiling simultaneously, compare them side-by-side in tabs, and write all of them back to the binary in one operation.

This matters because when you rescale the load axis, *every* ignition map needs to match — not just the one you're looking at. Having all six maps visible and rescalable at once prevents the "I rescaled Gasoline 0 but forgot about Ethanol 2" class of mistakes. Those mistakes end with knock.

### Usage

* Copy and paste your KFZW/2 and the x-axis load range generated from KFLMIOP
* Copy and paste the output KFZW/2 directly into TunerPro
* **DS1 multi-switch:** Click "Add Switch Map" to add each fuel-blend map from your XDF, enter the new max load ceiling, and click "Write KFZW Switch Maps" to write them all at once

---

## LDRPID (Feed-Forward PID)

The stock boost controller is a PID loop that fights the wastegate. It works, but it's reactive — it waits for boost error and then corrects. A feed-forward (pre-control) factor tells the PID *approximately* what duty cycle to start with at each RPM and boost level, so the PID only has to handle small corrections instead of doing all the work from scratch. The result is faster spool, less overshoot, and more consistent boost curves.

This is one of the highest-value calibrations you can do. Highly recommended for any setup — stock or modified.

The linearization process is tedious to do by hand — you need WOT pulls at various fixed duty cycles, manual duty cycle vs. boost pressure plotting, and careful interpolation. MxT automates most of this. You provide the logs; it provides the linearization table.

<img src="/documentation/images/med17/ldrpid_med17.png" width="800">

### Algorithm

The algorithm fits a one-dimensional polynomial to the duty cycle vs. boost relationship from logged data. This produces a smoother result from far more data — MxT can parse millions of data points across dozens of log files, versus the handful of points you'd get from doing it manually.

### What to Log (DS1)

| Signal | Description |
|--------|-------------|
| `nmot_w` | Engine speed (RPM) |
| `psrg_w` | Actual absolute manifold pressure (mbar) |
| `pu_w` | Barometric pressure (mbar) |
| `wdkba` | Throttle plate angle (degrees) |
| `tvldste_w` | Final wastegate duty cycle (%) |
| `gangi` | Selected gear |

MxT's adapter layer handles the translation from DS1 signal names to the internal equivalents automatically — configure the MED17 headers in the Configuration tab and load your DS1 logs.

### Usage

Do as many WOT pulls as possible, starting from the lowest RPM you can manage to the highest. You want a mix of pulls at fixed N75 duty cycles (for building the base linearization curve) and "real world" duty cycles (for validating the result). Different gears, different conditions — more data, better fit.

Put all of your logs in a single directory and select it in MxT with "Load MED17 Logs."

Then wait. Parsing thousands of WOT data points and fitting the polynomial takes time — especially if you've been thorough about logging.

The linearized duty cycle will be output in KFLDRL. It may not be perfect out of the box — boost control linearization is sensitive to exhaust housing characteristics, wastegate spring rates, and turbo-to-turbo variation. Some manual adjustment is usually needed to get the final result dialed in.

For feed-forward pre-control, MxT will also output a new KFLDIMX and x-axis based on estimations from the linearized boost table. This is a ballpark — a starting point for the feed-forward map, not a finished product. Expect to iterate.

One practical tip: at RPM ranges where the turbo can't produce enough boost to crack the wastegates, request 95% duty cycle. There's no reason to be conservative when you're below the wastegate cracking pressure — you want the turbo spooling as hard as possible.

### Why LDRPID Matters on the 2.5T

The 2.5T with aftermarket turbos is where LDRPID really earns its keep. The stock PID was linearized for a turbo that doesn't exist on your car anymore — the boost response is completely non-linear, the PID hunts and overshoots, and you're left chasing boost control issues across the entire RPM range. LDRPID rewrites the pre-control table from your actual logged data, giving the PID a known-good starting point at every RPM breakpoint. This alone can save you dozens of log-and-reflash iterations.

---

## Optimizer (MED17)

The Optimizer is where MxT goes from "useful calculator" to "how did we live without this."

It's a suggestion engine that analyzes WOT (Wide Open Throttle) logs and recommends corrections to the boost control maps so that **actual pressure tracks requested pressure** and **actual load tracks the maximum specified load target**.

The core philosophy is that the ECU's internal physical model — converting between pressure and load — is mathematically sound. If the base maps are calibrated correctly, the ECU's requested values should match reality (barring mechanical limitations such as turbo overspooling, knock limiting, boost leaks, etc.). When there is a discrepancy, the Optimizer identifies exactly *where* the error is and suggests specific map changes to fix it.

### Prerequisites

Before using the Optimizer, you should have already:

1. **Calibrated KRKTE_PFI and KRKTE_GDI** (Dual Injection tab) for both injector banks
2. **Defined KFLMIOP/KFLMIRL** (torque and load tables) scaled for your hardware
3. **Rough KFLDRL baseline** from the LDRPID tool or manual tuning

MED17's adaptive VE model (`fupsrls_w`) eliminates the need for manual MAF scaling — one less thing to worry about.

### How It Works

The Optimizer operates in three phases. Each one builds on the last — don't skip ahead.

#### Phase 1: Boost Control (requested pressure vs. actual pressure -> KFLDRL / KFLDIMX)

Before load can be accurate, the turbo must hit the pressure the ECU is requesting. If `psrg_w` (actual pressure) does not equal `pvds_w` (requested pressure), the wastegate pre-control (KFLDRL / KFLDIMX) needs adjustment.

**Algorithm:**

1. Filter WOT data (throttle angle >= minimum threshold, default 80 degrees)
2. For each RPM breakpoint in KFLDRL, find log rows where the PID successfully matched actual boost to requested boost (within the MAP tolerance, default +/-30 mbar)
3. At those stable-boost data points, capture the average WGDC (`tvldste_w`) the ECU was actually outputting
4. Suggest replacing each KFLDRL cell with that observed WGDC
5. Derive KFLDIMX by multiplying the suggested KFLDRL values by (1 + overhead%), default 108%, giving the PID room to operate

**Interpretation:** "At 4000 RPM, you requested 2200 mbar. To hit that, the ECU ultimately used 65% WGDC. The suggested KFLDRL at 4000 RPM is 65%."

#### Phase 2: VE Model (actual load vs. requested load)

Once Phase 1 is complete (actual pressure tracks requested pressure), the Optimizer evaluates whether the load is correct. If actual load (`rl_w`) consistently misses requested load (`rlsol_w`), there may be a calibration or mechanical issue.

**Algorithm:**

1. Prerequisite: Only analyze data points where boost is on-target (`|psrg_w - pvds_w| <= tolerance`)
2. At each RPM breakpoint, compute the load ratio: `requestedLoad / actualLoad`

MED17 does not use a static KFPBRK VE model — the adaptive `fupsrls_w` replaces it. Phase 2 on MED17 still analyzes the load ratio but focuses on validating that the adaptive model is converging correctly. If the load ratio is persistently off, it may indicate a mechanical issue rather than a calibration problem.

#### Phase 3: Intervention Check (Torque Limiters)

Sometimes the maximum load target won't be reached because a torque monitor or intervention is secretly capping the request before it ever reaches the boost controller. These are the invisible walls that make you think your boost control is broken when it's actually working perfectly — it's just being told to target less than you think.

**Algorithm:**

1. Compare `rlsol_w` (the final load request) against the configured LDRXN target
2. If `rlsol_w < LDRXN * 0.95` during WOT, flag a **Torque Intervention Warning**
3. If `psrg_w` consistently falls more than 50 mbar below `pvds_w`, flag a **Boost Target Not Reached** warning

### What to Log (DS1)

| Signal | Description |
|--------|-------------|
| `nmot_w` | Engine speed (RPM) |
| `wdkba` | Throttle plate angle (degrees) |
| `tvldste_w` | Final wastegate duty cycle (%) |
| `pu_w` | Barometric pressure (mbar) |
| `psrg_w` | Actual absolute manifold pressure (mbar) |
| `pvds_w` | Requested manifold pressure (mbar) |
| `rlsol_w` | Requested load (%) |
| `rl_w` | Actual engine load (%) |

Get WOT pulls across the full RPM range. More data points produce better suggestions. DS1 logs work identically to ME7Logger logs — load a single file or a directory.

### Usage

#### Step 1: Configure Maps and Parameters

1. Go to the **Configuration** tab
2. Select map definitions for KFLDRL and KFLDIMX
3. Ensure the log header definitions are configured for DS1 signals

#### Step 2: Collect WOT Logs

Log the signals listed above during WOT pulls with DS1. Get pulls across the full RPM range — more data points produce better suggestions.

#### Step 3: Load and Analyze

1. Go to the **Optimizer** tab
2. Adjust the parameters if needed:
   * **MAP Tolerance (mbar):** How close actual boost must be to requested boost for a data point to count as "on-target" (default: 30 mbar)
   * **LDRXN Target (%):** Your maximum specified load target for the intervention check
   * **KFLDIMX Overhead (%):** How much headroom above KFLDRL to give the PID I-limiter (default: 8%)
   * **Min Throttle Angle:** Minimum throttle angle to qualify as WOT data (default: 80 degrees)
3. Click **"Load MED17 Log File"** for a single log, or **"Load MED17 Log Directory"** to process multiple logs at once

The Optimizer automatically detects the platform and uses the MED17 analyzer, which adapts the three-phase algorithm for MED17's signal names and adaptive VE model.

#### Step 4: Review Results

**Boost Control Tab:** Shows the current KFLDRL and KFLDIMX maps side-by-side with the suggested corrections. If the suggestions look reasonable, click **"Write KFLDRL"** or **"Write KFLDIMX"** to write directly to the BIN file.

**VE Model Tab:** Shows the load ratio analysis. On MED17, the adaptive `fupsrls_w` handles VE correction internally, so persistent load ratio errors at this stage typically point to a mechanical issue (boost leak, wastegate issue, turbo limitation) rather than a map that needs editing.

**Charts Tab:** Visual comparison of requested vs. actual values:

* **Pressure vs RPM:** Scatter plot of requested and actual pressure over RPM — shows how well the boost controller is tracking
* **Pressure Error vs RPM:** Shows the difference between requested and actual pressure at each data point — positive values mean the ECU is requesting more boost than is being delivered
* **Load vs RPM:** Scatter plot of requested and actual load over RPM — shows how well the VE model is tracking
* **Load Ratio vs RPM:** Shows `requestedLoad / actualLoad` at each data point — 1.0 is perfect, values above 1.0 mean the ECU expected more load than was achieved

**Warnings Tab:** Displays any detected issues:

* **Torque Intervention Detected:** `rlsol_w` is significantly below the LDRXN target, meaning a torque limiter (KFLMIOP, etc.) is capping the load request before it reaches the boost controller. You need to scale your torque maps higher.
* **Boost Target Not Reached:** Actual pressure is consistently below requested pressure. This may indicate a mechanical limitation (turbo spool, wastegate, boost leak) or an incorrect KFLDRL base duty cycle.

A summary statistics panel shows data point counts, RPM range, average/max pressure error, average load ratio, and average WGDC.

<img src="/documentation/images/med17/optimizer_med17.png" width="800">

#### Step 5: Iterate

The Optimizer is designed for iterative use. Rome wasn't built in one WOT pull.

1. **First pass:** Fix boost control (Phase 1). Write the suggested KFLDRL and KFLDIMX, then take new logs.
2. **Second pass:** With boost on-target, verify the load ratio (Phase 2). If the adaptive VE model is converging, you're done. If not, investigate mechanical causes — persistent load ratio errors on MED17 point to hardware problems, not map problems. The adaptive model is doing its job; something in the real world isn't.
3. **Verify:** On the final pass, both pressure and load charts should show tight tracking between requested and actual values. Warnings should be clear.

If the maps are calibrated correctly, requested pressure should match actual pressure and requested load should match actual load — the ECU's physical model just works. That's the whole point. The ECU was right all along — you just had to give it the right numbers.

---

*MxT is free software. It comes with no warranty. If you send 25 psi into a motor that can handle 15 psi because you didn't read the output, that's between you and your engine builder.*

*Built with mass quantities of coffee by [TracQi Technology](https://github.com/TracqiTechnology).*
