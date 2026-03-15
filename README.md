<p align="center">
  <img src="/documentation/images/banner.png" alt="TracQi ME7Tuner">
</p>

<p align="center">
  <a href="https://opensource.org/licenses/Apache-2.0"><img src="https://img.shields.io/badge/License-Apache_2.0-blue.svg" alt="License"></a>
  <a href="https://github.com/TracqiTechnology/ME7Tuner/releases/latest"><img src="https://img.shields.io/github/v/release/TracqiTechnology/ME7Tuner?color=green" alt="Release"></a>
  <a href="https://github.com/TracqiTechnology/ME7Tuner/actions/workflows/release.yml"><img src="https://github.com/TracqiTechnology/ME7Tuner/actions/workflows/release.yml/badge.svg?branch=master" alt="Build"></a>
  <img src="https://img.shields.io/github/downloads/TracqiTechnology/ME7Tuner/total?color=brightgreen" alt="Downloads">
  <img src="https://img.shields.io/badge/Java-17+-orange.svg" alt="Java 17+">
  <img src="https://img.shields.io/badge/Kotlin-Compose_Desktop-7F52FF.svg" alt="Kotlin">
  <img src="https://img.shields.io/badge/Platform-macOS_|_Windows_|_Linux-lightgrey.svg" alt="Platform">
</p>

ME7Tuner is a calibration and optimization tool for Bosch ME7 and MED17 ECUs. It provides calculators for fueling, injector scaling, torque/load tables, ignition timing, and boost control — plus a log-analysis Optimizer that diagnoses and corrects boost control and volumetric efficiency errors from real-world data. MED17 support adds dual injection (port + direct) calibration, fuel trim correction, and ScorpionEFI log parsing for the Audi RS3/TTRS 2.5T and related platforms.

ME7Tuner supports any ME7 or MED17 variant that has a TunerPro XDF definition file. Bundled profiles are included for the Audi B5 S4/RS4 2.7T (ME7), B5/B6 A4 1.8T, Audi TT 1.8T, VW Golf/Jetta 1.8T, and Audi RS3/TTRS 2.5T TFSI (MED17.1.62) platforms.

<img src="/documentation/images/me7Tuner.png" width="800">

# Warning

ME7Tuner is free software written by some guy on the internet. ***ME7Tuner comes with no warranty.*** Use at your own risk.

It is a certainty that ME7Tuner will produce garbage outputs at some point and you will damage your engine if you do not know what you are doing. ME7Tuner is software that *helps* you calibrate your engine. It does not calibrate your engine for you. It is not a replacement for knowledge of how to calibrate an engine. If you send 25 psi into a motor that can handle 15 psi because you didn't read the output, that's between you and your engine builder.

## Installation

ME7Tuner ships as a **native application** — no JRE required:

| Platform | Format | Notes |
|----------|--------|-------|
| **macOS** | `.dmg` | Apple Silicon native. Intel Macs run via Rosetta 2. |
| **Windows** | `.msi` | Double-click install. No JRE required. |
| **Linux** | `.deb` + `.tar.gz` | Debian package or portable archive. |
| **Cross-platform** | `.jar` | For the traditionalists. Requires Java 17+. |

Your buddy who's been "meaning to install Java" for three years can finally just run the DMG.

Download the latest release [here](https://github.com/TracqiTechnology/ME7Tuner/releases/latest).

> **JAR users:** You will need [Java 17+](https://www.oracle.com/java/technologies/downloads/) installed. Once you have it, double-click the JAR and you're off.

# So, Do I Actually Need This?

ME7Tuner has two major workflows — **Calibration** and **Optimization** — and who needs each is different.

### Optimizer: Yes, you probably need it

The Optimizer analyzes WOT logs and corrects your boost control (KFLDRL/KFLDIMX) and volumetric efficiency model (KFPBRK) so that actual pressure tracks requested pressure and actual load tracks requested load. This is useful at **any power level** — even a completely stock K03 car benefits from having an accurate VE model and properly linearized wastegate duty cycle. If your car runs ME7 or MED17 and you datalog, the Optimizer can improve your tune.

### Calibration: Only if you've changed hardware

The Calibration tools (fueling, MAF scaling, torque/load tables, ignition timing, throttle transition) are for engines where the base maps no longer match reality — typically because you've upgraded turbos, injectors, MAF housings, or increased the MAP sensor limit.

For detailed hardware reference charts, turbo compressor maps, and "how much calibration do I need?" guidance, see your platform's guide:
- **ME7:** [ME7 Hardware Reference](documentation/me7-calibration-guide.md#do-i-need-calibration-me7-hardware-reference) — includes turbo airflow charts, MAF limits, load/HP tables, and KKK compressor maps for K03 through RS2
- **MED17:** [MED17 Hardware Reference](documentation/med17-calibration-guide.md#do-i-need-calibration-med17-hardware-reference) — 2.5T-specific thresholds and aftermarket turbo context

### ME7 vs MED17: What's Different

The physics doesn't change between platforms. The ECU's opinion about how to manage it does.

| Feature | ME7 | MED17 |
|---------|-----|-------|
| **Injection** | Single bank (port only) | Dual bank (port + direct). Two KRKTEs, two TVUBs. The 2.5T fires both simultaneously and varies the split ratio across the operating map. |
| **MAF Scaling** | MLHFM linearization curve (voltage → kg/h). Your MAF is a liar; Closed Loop and Open Loop tabs help you figure out how much. | Not applicable. Adaptive VE model (`fupsrl_w`) handles air metering internally. One less thing to calibrate, one less thing to mess up. |
| **VE Model** | KFURL / KFPBRK (static maps). If you change hardware, you recalibrate these by hand — or let the Optimizer do it. | Adaptive (`fupsrls_w` / `pbrint_w`). The ECU adjusts continuously. Persistent drift shows up in fuel trims, not VE maps. |
| **Throttle Model** | KFVPDKSD (throttle-to-boost handoff) + WDKUGDN (throttle body choke point) | Different architecture — not calibratable here. MED17 handles it internally. |
| **Alpha-N** | `msdk_w` vs `mshfm_w` diagnostic with BGSRM VE model solving | Not applicable. The adaptive VE model covers this. |
| **Torque Tables** | KFMIOP / KFMIRL (load ↔ torque normalization) | KFLMIOP / KFLMIRL (same math, different map names). DS1 tunes reduce these to a scalar — ME7Tuner detects this and switches to scalar rescaling mode automatically. |
| **Ignition Timing** | KFZWOP + KFZW/2 (single map set) | KFZWOP + KFZW/2, but DS1 tunes use multi-switch mode — up to 6 fuel-blend maps (Gasoline 0/1/2, Ethanol 0/1/2) that ME7Tuner can rescale simultaneously. |
| **Fuel Trim** | Closed Loop (narrowband O2 + fuel trims) / Open Loop (wideband O2 at WOT) — both correct MLHFM | `rk_w` STFT/LTFT analysis (Fuel Trim tab). MED17's equivalent of Closed Loop MLHFM, but corrects the base fuel mass map instead of a MAF curve. |
| **PLSOL** | Pressure ↔ load sanity check. Log overlay shows WOT data points on the chart; KFURL auto-fill from `KfurlSolver.solveFromActuals()`. | Same calculator. Log overlay uses `fupsrls_w` (≈ KFURL) from ScorpionEFI logs. |
| **Boost PID** | KFLDRL / KFLDIMX — feed-forward PID linearization from logged data | Same maps, same PID algorithm — but non-linear turbo response with aftermarket hardware makes the linearization more valuable here. |
| **Optimizer** | 3-phase: boost control → VE model (KFPBRK) → intervention check. Includes MAF voltage saturation detection (4.8V ceiling) and MAP sensor auto-classification (3/4/5-bar). | 3-phase: boost control → VE model (adaptive validation) → intervention check. Phase 2 validates adaptive convergence rather than writing KFPBRK — persistent load ratio errors point to mechanical issues. |
| **Log Format** | ME7Logger CSV (`nmot`, `pvdks_w`, `pssol_w`, `ldtvm`, ...) | ScorpionEFI CSV (`nmot_w`, `psrg_w`, `pvds_w`, `tvldste_w`, ...). ME7Tuner's adapter layer translates automatically. |

ME7Tuner automatically shows only the tabs relevant to your platform. Switch between ME7 and MED17 in the Configuration tab.

# How ME7 Actually Works (Read This First)

Everything in ME7 revolves around requested load (or cylinder fill). Understand this and everything else makes sense. Skip it and you'll spend weeks chasing symptoms.

* Read [Engine load](https://s4wiki.com/wiki/Load)

Here's the signal chain: the driver pushes the accelerator pedal, which makes a torque request. That torque request gets mapped to a load request. ME7 then calculates how much pressure (boost) is required to achieve that load — and that calculation depends heavily on hardware (engine, turbo, intercooler) and weather (cold, dry air is denser than hot, humid air). Tuning ME7 means calibrating the various maps so this model accurately reflects *your* hardware in *your* conditions. If the model is wrong, ME7 knows something doesn't add up and protects the engine by pulling power at various levels of intervention.

Here's the part people miss: **no amount of hardware modifications will increase power if actual load already equals or exceeds requested load.** Bigger turbo, bigger intercooler, better exhaust — none of it matters if the ECU is already capping output. ME7 uses interventions to *decrease* actual load to match the request. You must calibrate the tune to *request more load* before you'll see more power.

ME7Tuner provides the calculations that let you get airflow, pressure, and load measurements right — so the model works instead of fighting you.

## How MED17 Works (Same Idea, Modern Execution)

MED17 follows the same torque-based architecture as ME7 — torque request → load request → pressure target → boost control. The driver model, torque monitoring, and intervention logic are conceptually identical. The differences are in the details: MED17 uses an adaptive volumetric efficiency model instead of the static KFURL/KFPBRK maps, it has dual injection (port + direct) with separate injector characterization for each bank, and the logging ecosystem is ScorpionEFI instead of ME7Logger.

If you understand ME7's signal chain, you understand MED17's. The map names change (KFMIOP → KFLMIOP, KFMIRL → KFLMIRL), the log signal names change (`pvdks_w` → `psrg_w`, `pssol_w` → `pvds_w`), but the physics doesn't.

# Workflow Overview

ME7Tuner is organized into three stages that mirror the calibration workflow:

| Stage | When You Need It | What It Does |
|-------|-----------------|--------------|
| **Configuration** | Everyone | Load BIN + XDF, select map definitions, configure log headers |
| **Calibration** | Modified engines only | Recalibrate fueling, MAF, torque/load tables, ignition, and throttle maps for new hardware |
| **Optimization** | Everyone | Analyze WOT logs and correct boost control + VE model to match requested targets |

Start with Configuration, calibrate if your hardware has changed, then optimize with real-world logs.

#### Table of Contents

**Configuration**
1. [Loading Files](#stage-1-configuration)
2. [XDF Format Support](#xdf-format-support)
3. [WinOLS KP File Support](#winols-kp-file-support)

**Calibration**
4. [Tool Catalog](#stage-2-calibration)

**Optimization**
5. [Optimizer](#stage-3-optimization)

**Tools**
6. [Data Logger](#data-logger)
7. [A2L → ECU Generator](#a2l--ecu-generator)

---

# Stage 1: Configuration

ME7Tuner works from a binary file and an XDF definition file. Load these using the menu bar:

* **File > Open Bin...** — select your ME7 binary file
* **XDF > Select XDF...** — select the matching XDF definition file

See the example binary and XDF in the `example` directory as a starting point.

### Platform Selection

ME7Tuner supports both ME7 and MED17 ECU platforms. Select your platform in the Configuration tab — the UI will automatically show only the calibration tools that apply to your ECU. ME7 shows MAF scaling, throttle transition, and alpha-N tools. MED17 shows dual injection calibration and fuel trim analysis. Shared tools (fueling, torque/load tables, ignition timing, boost PID, PLSOL, and the Optimizer) appear on both.

When you switch platforms, map definitions are filtered to match. You won't accidentally pick an ME7 map definition when working on a MED17 binary.

You will need to tell ME7Tuner what definition you want to use for *all* fields. This is necessary because many XDF files have multiple definitions for the same map using different units. ***Pay attention to the units!*** Seriously — picking the wrong unit definition is the single most common setup mistake, and ME7Tuner cannot save you from it.

ME7Tuner makes the following assumptions about units:

* KRKTE - ms/%
* MLHFM - kg/h
* KFMIOP - %
* KFMIRL - %
* KFZWOP - grad KW
* KFZW - grad KW
* KFVPDKSD - unitless
* WDKUGDN - %
* KFWDKMSN - %
* KFLDRL - %
* KFLDIMX - %
* KFPBRK - unitless (multiplier)
* KFPBRKNW - unitless (multiplier)
* KFPRG - hPa

**MED17 unit assumptions:**

* KRKTE (Port) - ms/%
* KRKTE (Direct) - ms/%
* TVUB (Port) - ms
* KFLMIOP - %
* KFLMIRL - %
* KFZWOP - grad KW
* KFZW - grad KW
* KFLDRL - %
* KFLDIMX - %

ME7Tuner automatically filters map definitions based on what is in the editable text box.

<img src="/documentation/images/configuration.png" width="800">

### Log Headers

Some tools can parse logs automatically to suggest calibrations. The catch: there are often many names for the same logged parameter, and ME7Tuner can't guess which one you're using.

You *must* define the headers for the parameters that the log parser uses here.

<img src="/documentation/images/configuration.png" width="800">

#### MED17 Log Headers (ScorpionEFI)

MED17 cars typically use ScorpionEFI for logging. The signal names differ from ME7Logger — configure these in the Log Headers section:

| Parameter | ScorpionEFI Header | ME7 Equivalent | Description |
|-----------|-------------------|----------------|-------------|
| RPM | `nmot_w` | `nmot` | Engine speed |
| Throttle Plate Angle | `wdkba` | `wdkba` | Throttle position (degrees) |
| Wastegate Duty Cycle | `tvldste_w` | `ldtvm` | Final WGDC output (%) |
| Barometric Pressure | `pu_w` | `pus_w` | Ambient barometric pressure (mbar) |
| Absolute Pressure | `psrg_w` | `pvdks_w` | Actual absolute manifold pressure (mbar) |
| Requested Pressure | `pvds_w` | `pssol_w` | ECU's requested manifold pressure (mbar) |
| Requested Load | `rlsol_w` | `rlsol_w` | ECU's requested load (%) |
| Engine Load | `rl_w` | `rl_w` | Actual measured engine load (%) |
| Live VE | `fupsrls_w` | — | Live volumetric efficiency (MED17 only) |
| Gear | `gangi` | `gangi` | Current gear |

ME7Tuner's adapter layer maps these automatically — configure the headers once in the Configuration tab and the parsers handle the translation.

<img src="/documentation/images/med17/configuration_med17.png" width="800">

## XDF Format Support

ME7Tuner implements the **full** TunerPro XDF format. This means any ECU binary that has a valid XDF file can be loaded — the parser is not limited to the B5 S4 MBox format. We reverse-engineered every field, every flag, every stride mode. The XDF spec is not publicly documented, so we had to figure it out the hard way.

### Supported ECUs

| ECU | Application | Notes |
|-----|-------------|-------|
| **ME7 MBox (8D0907551M)** | Audi B5 S4 2.7T | Primary supported ECU — example XDF included |
| **ME7 ABox** | Audi B5 S4 2.7T | Same engine family, compatible maps |
| **ME7 RS4 (8D0907551R)** | Audi B5 RS4 2.7T | Higher boost maps; same VE model |
| **ME7 1.8T (A4/TT/Golf)** | Various 1.8T platforms | Same ME7 software generation; maps compatible |
| **ME7.1** | Later Audi/VW platforms | Compatible when XDF is available |
| **MED17.1.62 (8S0907404x)** | Audi RS3 / TTRS 2.5T TFSI (EA855 EVO) | Full support — dual injection, ScorpionEFI logs |
| **MED17.1 (4.0T)** | Audi RS6/RS7/S6/S7 4.0T TFSI | Compatible when XDF is available |
| **MED17.1 (5.2 V10)** | Audi R8 / Lamborghini Huracán 5.2 V10 | Compatible when XDF is available |

XDF files for many of these can be found at [files.s4wiki.com/defs/](https://files.s4wiki.com/defs/) and the [Nefarious Motorsports forums](http://nefariousmotorsports.com/forum).

### Supported XDF Features

| Feature | Support | Notes |
|---------|---------|-------|
| `XDFHEADER` BASEOFFSET | Full | Applied to all addresses at parse time |
| `DEFAULTS` (lsbfirst, signed, float, datasizeinbits) | Full | Inherited as fallbacks when per-axis values are absent |
| `CATEGORY` name map | Full | Available for future UI filtering |
| `CATEGORYMEM` table grouping | Full | Category indices stored per-table |
| `XDFTABLE` (1-D, 2-D, 3-D maps) | Full | All axis combinations supported |
| `XDFCONSTANT` (scalar values) | Full | |
| 8/16/32-bit integer data (signed / unsigned) | Full | |
| 32-bit IEEE-754 float data | Full | |
| Little-endian byte order | Full | Default for all ME7 ECUs |
| Big-endian byte order | Full | Per-axis via `mmedtypeflags` bit 1 or `DEFAULTS lsbfirst="0"` |
| `mmedmajorstridebits` row stride / padding | Full | Negative = virtual axis (LABEL values used) |
| `mmedminorstridebits` element padding | Full | Interleaved data layouts |
| Column-major data layout (`mmedtypeflags` bit 2) | Full | Automatically transposed to row-major |
| Virtual / shared axes (negative stride) | Full | Falls back to `LABEL` breakpoint values |
| `LABEL` axis breakpoint values | Full | Used when axis has no binary address |
| `<decimalpl>` display precision | Parsed | Stored in `AxisDefinition.decimalPl` |
| `<min>` / `<max>` range hints | Parsed | Stored in `AxisDefinition.min/max` |
| `XDFPATCH` code patches | Not supported | No byte-patch UI |
| `XDFFLAG` bitfields | Not supported | No bitfield editor UI |
| `DALINK` / `uniqueid` cross-references | Not needed | All standard ME7 XDFs use `uniqueid="0x0"` |

### Write-Back (Equation Inversion)

When ME7Tuner writes a corrected map back to the binary, it analytically inverts the XDF's forward equation to convert engineering-unit values back to raw integers. No GraalVM round-trip, no numerical solver — just algebra:

| Forward Equation | Inverse Applied |
|-----------------|----------------|
| `A * X` | `X / A` |
| `A * X + B` | `(X - B) / A` |
| `A * X - B` | `(X + B) / A` |
| `X * A` | `X / A` |
| `X + B` | `X - B` |
| `X - B` | `X + B` |
| `X / A` | `X * A` |
| Anything else | `X` (pass-through — safe for identity equations) |

These cover every equation form produced by the Bosch ME7 TunerPro translators for standard map types.

## WinOLS KP File Support

ME7Tuner includes **hint-mode** support for WinOLS `.kp` ECU definition files. We reverse-engineered the proprietary binary format to make this work. You're welcome.

### What KP files are

WinOLS `.kp` files (EVC GmbH — https://www.evc.de) are **proprietary binary containers**, NOT XML. The format is:

```
[WinOLS binary header]   — WinOLS proprietary metadata
[Embedded ZIP archive]   — standard DEFLATE
  └── intern             — proprietary binary record database
```

The `intern` blob contains map definitions, but the binary layout of axes, dimensions, and scaling factors is **not publicly documented**. ME7Tuner reverse-engineered the record structure and can reliably extract map names and binary addresses, but not full axis/scaling data.

### How KP hint mode works

When you load a KP file via `WinOLS → Open KP File...`:

1. ME7Tuner parses the KP file and extracts up to ~90 map name + address pairs
2. When you open any map selection dialog (e.g. *Select KFPBRK*), ME7Tuner:
   - Shows a **hint badge** with the KP-derived description and binary address
   - **Auto-pre-selects** the XDF definition whose address matches the KP address
   - Marks the matched definition with a **KP badge** in the list

The map picker is automatically pre-filtered and pre-selected to the most likely correct definition — no more scrolling through 393 XDF entries hunting for the right one.

### KP vs XDF coverage

| | XDF | KP (hint mode) |
|-|-----|----------------|
| Map definitions | ~393 | ~90 with address, ~62 name-only |
| Axes & dimensions | Full | Not parseable |
| Scaling factors | Full | Not parseable |
| Use case | Primary source of truth | Address cross-reference aid |

**The XDF is always required for binary reading and writing.** The KP file is optional and only provides selection hints.

### Address verification

KP AR addresses and XDF addresses match perfectly for the `8D0907551M` ECU:

| Map | KP address | XDF address |
|-----|-----------|------------|
| KFPBRK | `0x1E3B0` | `0x1E3B0` |
| MLHFM | `0x13974` | `0x13974` |
| KFMIRL | `0x14A1C` | `0x14A1C` |
| KRKTE | `0x1EB44` | `0x1EB44` |
| KFKHFM | `0x10CCE` | `0x10CCE` |

### Why not full KP parsing?

The WinOLS binary format is proprietary and has no public specification. Axis dimensions, element sizes, and scaling factor offsets are at undocumented positions within each binary record. XDF files for the same ECU contain ~4x more definitions with full axis/scaling data. We extracted everything we could from the binary format — the rest would require guesswork, and guesswork is not how you build tools that write to engine binaries.

KP files available from https://files.s4wiki.com/defs/ can be used alongside the XDF files from the same source.

---

# Stage 2: Calibration

If you've modified engine hardware, the base maps in your BIN no longer match reality. This is where things get real. Calibrate in order — start with a stock binary and work through each section.

*It is critical that you calibrate primary fueling first.* This is not a suggestion. This is not a "best practice." If you skip this, everything downstream is built on a lie.

For step-by-step instructions, screenshots, and algorithm descriptions, see the platform-specific calibration guides:
- **[ME7 Calibration Guide](documentation/me7-calibration-guide.md)** — B5 S4 2.7T, 1.8T platforms
- **[MED17 Calibration Guide](documentation/med17-calibration-guide.md)** — RS3/TTRS 2.5T, EA855 EVO platforms

### Tool Catalog

| Tool | Platform | Description |
|------|:--------:|-------------|
| **[KRKTE (Primary Fueling)](documentation/me7-calibration-guide.md#fueling-krkte--injector-scaling)** | ME7 | Calculate injector constant and dead time from first principles. The foundation for everything else. |
| **[Dual Injection](documentation/med17-calibration-guide.md#fueling-dual-injection--krkte_pfi--krkte_gdi)** | MED17 | Port + direct injector scaling (KRKTE_PFI, KRKTE_GDI, TVUB) and fuel split calculator. Two banks of injectors means two banks of math. |
| **[Fuel Trim (rk_w)](documentation/med17-calibration-guide.md#fuel-trim-rk_w-correction)** | MED17 | Correct the base fuel mass map from ScorpionEFI STFT/LTFT logs. MED17's equivalent of Closed Loop MLHFM — same idea, different correction target. |
| **[Closed Loop MLHFM](documentation/me7-calibration-guide.md#closed-loop-mlhfm)** | ME7 | MAF linearization correction via narrowband O2 + fuel trims at part-throttle. |
| **[Open Loop MLHFM](documentation/me7-calibration-guide.md#open-loop-mlhfm)** | ME7 | MAF linearization correction via wideband O2 at WOT. |
| **[PLSOL](documentation/me7-calibration-guide.md#plsol---pressure-to-load-conversion)** | Both | Pressure ↔ load ↔ airflow ↔ horsepower sanity check calculator. Now with WOT log overlay — load your logs and see actual data points on the chart, with automatic KFURL auto-fill (ME7) or `fupsrls_w` extraction (MED17). |
| **[KFMIOP / KFLMIOP](documentation/me7-calibration-guide.md#kfmiop-loadfill-to-torque)** | Both | Rescale the optimum torque table for a new MAP sensor limit. On MED17 with DS1, auto-detects scalar mode and switches to single-value rescaling. |
| **[KFMIRL / KFLMIRL](documentation/me7-calibration-guide.md#kfmirl-torque-request-to-loadfill-request)** | Both | Invert KFMIOP to produce the torque-to-load lookup table. DS1 scalar mode rescales KFMIRL along its own load axis to the target max load. |
| **[KFZWOP](documentation/me7-calibration-guide.md#kfzwop-optimal-ignition-timing)** | Both | Extrapolate optimal ignition timing to the new load range. Check the output — extrapolation doesn't know about detonation. You do. |
| **[KFZW/2](documentation/me7-calibration-guide.md#kfzw2-ignition-timing)** | Both | Extrapolate ignition timing to the new load range. On MED17 with DS1, supports multi-switch mode with up to 6 fuel-blend maps (Gasoline 0/1/2, Ethanol 0/1/2) — rescale them all simultaneously. |
| **[KFVPDKSD](documentation/me7-calibration-guide.md#kfvpdksd-throttle-transition)** | ME7 | Calculate throttle-to-boost handoff pressure ratios from logged data. |
| **[WDKUGDN](documentation/me7-calibration-guide.md#wdkugdn-throttle-body-choke-point)** | ME7 | Calculate throttle body choke point from displacement. **Not** an alpha-N map — that's BGSRM's job. |
| **[Alpha-N Diagnostic](documentation/me7-calibration-guide.md#alpha-n-calibration--diagnostic-tool)** | ME7 | Compare MAF vs throttle-model airflow and identify which VE maps need calibrating. Classifies error as additive, multiplicative, or RPM-dependent. |
| **[LDRPID](documentation/me7-calibration-guide.md#ldrpid-feed-forward-pid)** | Both | Generate feed-forward PID linearization (KFLDRL/KFLDIMX) from WOT logs. One of the highest-value calibrations you can do — stock or modified. |

---

# Stage 3: Optimization

The Optimizer is where ME7Tuner goes from "useful calculator" to "how did we live without this."

It's a suggestion engine that analyzes WOT (Wide Open Throttle) logs and recommends corrections to the boost control and volumetric efficiency maps so that **actual pressure tracks pssol** (requested pressure) and **actual load tracks LDRXN** (maximum specified load).

The core philosophy is that ME7's internal physical model — converting between pressure and load via KFURL and KFPBRK — is mathematically sound. If the base maps are calibrated correctly, the ECU's requested values should match reality (barring mechanical limitations such as turbo overspooling, knock limiting, boost leaks, etc.). When there is a discrepancy, the Optimizer identifies exactly *where* the error is and suggests specific map changes to fix it. The model works — you just have to give it the right numbers.

## How It Works

The Optimizer operates in three phases. Each one builds on the last — don't skip ahead.

### Phase 1: Boost Control (pssol vs. pvdks_w → KFLDRL / KFLDIMX)

Before load can be accurate, the turbo must hit the pressure the ECU is requesting. If `pvdks_w` (actual pressure) does not equal `pssol_w` (requested pressure), the wastegate pre-control (KFLDRL / KFLDIMX) needs adjustment.

**Algorithm:**

1. Filter WOT data (throttle angle ≥ minimum threshold, default 80°)
2. For each RPM breakpoint in KFLDRL, find log rows where the PID successfully matched actual boost to requested boost (within the MAP tolerance, default ±30 mbar)
3. At those stable-boost data points, capture the average WGDC (`ldtvm`) the ECU was actually outputting
4. Suggest replacing each KFLDRL cell with that observed WGDC
5. Derive KFLDIMX by multiplying the suggested KFLDRL values by (1 + overhead%), default 108%, giving the PID room to operate

**Interpretation:** "At 4000 RPM, you requested 2200 mbar. To hit that, the ECU ultimately used 65% WGDC. The suggested KFLDRL at 4000 RPM is 65%."

### Phase 2: VE Model (rl vs. rlsol → KFPBRK corrections)

Once Phase 1 is complete (actual pressure tracks pssol), the Optimizer evaluates whether the load is correct. If your MAF is properly scaled but actual load (`rl_w`) consistently misses requested load (`rlsol_w`), the ECU's mathematical conversion between pressure and load needs adjustment via KFPBRK.

**Algorithm:**

1. Prerequisite: Only analyze data points where boost is on-target (`|pvdks_w − pssol_w| ≤ tolerance`)
2. At each RPM breakpoint, compute the load ratio: `requestedLoad / actualLoad`
3. Multiply the current KFPBRK cell values by this ratio to produce the suggested KFPBRK

**Interpretation:** A ratio of 1.05 means the ECU needs to request 5% more pressure to achieve the target load — KFPBRK is scaled up by 5% at that RPM.

MED17 does not use KFPBRK — it has an adaptive volumetric efficiency model (`fupsrls_w`). Phase 2 on MED17 still analyzes the load ratio but focuses on validating that the adaptive model is converging correctly. If the load ratio is persistently off, it typically points to a mechanical issue (boost leak, wastegate, turbo limitation) rather than a map that needs editing.

### Phase 3: Intervention Check (Torque Limiters)

Sometimes LDRXN won't be reached because a torque monitor or intervention is secretly capping the request before it ever reaches the boost controller. These are the invisible walls that make you think your boost control is broken when it's actually working perfectly — it's just being told to target less than you think.

**Algorithm:**

1. Compare `rlsol_w` (the final load request) against the configured LDRXN target
2. If `rlsol_w < LDRXN × 0.95` during WOT, flag a **Torque Intervention Warning**
3. If `pvdks_w` consistently falls more than 50 mbar below `pssol_w`, flag a **Boost Target Not Reached** warning

### Sensor Voltage Saturation Detection (ME7)

All ME7 analog sensors output 0–5 V. When a tuned engine pushes a sensor beyond its measurement range, the voltage clips and the ECU can no longer see the real value. The Optimizer detects this automatically and warns you — because garbage in, garbage out.

| Sensor | Log Signal | What Saturates | Stock Max | Upgrade Path |
|--------|-----------|----------------|-----------|--------------|
| **MAF (HFM5)** | `uhfm_w` | Airflow exceeds MLHFM top voltage bin | ~4.96 V (~370 g/s) | Rescale MLHFM for larger MAF housing |
| **MAP (3-bar)** | `pvdks_w` | Boost exceeds sensor ceiling | ~2550 mbar | Upgrade to 4-bar MAP sensor |
| **MAP (4-bar)** | `pvdks_w` | Boost exceeds sensor ceiling | ~3500 mbar | Upgrade to 5-bar MAP sensor |
| **MAP (5-bar)** | `pvdks_w` | Boost exceeds sensor ceiling | ~4500 mbar | 6-bar or dual-sensor setup |

When any sensor is saturated, the Optimizer marks solver suggestions at affected operating points as potentially unreliable. A warning banner explains which solvers are affected and why. Include `uhfm_w` (MAF voltage) in your ME7Logger configuration for full saturation detection.

## Usage

The Optimizer is designed for iterative use. Rome wasn't built in one WOT pull.

1. **First pass:** Fix boost control (Phase 1). Write the suggested KFLDRL and KFLDIMX, then take new logs.
2. **Second pass:** With boost on-target, fix the VE model (Phase 2). Write the suggested KFPBRK, then take new logs.
3. **Verify:** On the final pass, both pressure and load charts should show tight tracking between requested and actual values. Warnings should be clear.

If the maps are calibrated correctly, `pssol` should match `pvdks_w` and `rlsol` should match `rl_w` — the ECU's physical model just works. That's the whole point.

For detailed configuration (map definitions, log headers), step-by-step usage, result interpretation, and platform-specific signal names, see the calibration guides:
- **[ME7 Optimizer](documentation/me7-calibration-guide.md#optimizer)** — ME7Logger signal names, KFPBRK corrections, MAF voltage saturation
- **[MED17 Optimizer](documentation/med17-calibration-guide.md#optimizer-med17)** — ScorpionEFI signal names, adaptive VE model validation

---

# Stage 4: Tools

ME7Tuner includes standalone utilities that don't require a BIN or XDF file. Access them from the **Tools** rail on the left navigation.

## Data Logger

A built-in data logger that replaces external tools like VisualME7Logger. Connect to your ECU, log data in real time, and view results — all without leaving ME7Tuner.

<img src="/documentation/images/tools/logger_connection.png" alt="Data Logger — Connection Tab" width="800">

### Setup

1. Navigate to **Tools → Data Logger**
2. In the **Connection** tab, configure:
   - **ME7Logger Path** — path to `ME7Logger.exe` (the same binary used by VisualME7Logger)
   - **COM Port** — serial port connected to your ECU (e.g., `COM3`)
   - **ECU File** — `.ecu` characteristics file for your ECU (generate one with the A2L → ECU Generator below, or use a community `.ecu` file)
   - **CFG File** — `.cfg` log configuration specifying which variables to log
3. Click **Connect** to validate the configuration, then **Start** to begin logging

### Live Data

The **Live Data** tab displays a real-time table of all logged variables with their current values, aliases, and units. Values update on every sample from the ECU.

### Charts

The **Chart** tab provides real-time line charts with two view modes, controlled by a toggle at the top:

- **Combined** (default) — All signals overlaid on a single chart. Signals are grouped by unit and assigned to left or right Y axes, so variables with different scales (e.g., RPM vs. %) are readable simultaneously. A color-coded legend identifies each signal.
- **Individual** — Each signal gets its own dedicated chart with proper X/Y axes, grid lines, and tick labels. Scrollable when logging many variables.

Both views show time on the X axis (seconds) with automatically scaled tick intervals.

### Loading Existing Logs

You don't need a live ECU connection to use the charts. Click **Load Log File** in the Connection tab to open any ME7Logger CSV file. The data populates the Live Data and Chart tabs for offline analysis.

### Exporting

Click **Export CSV** to save the current session in ME7Logger-compatible CSV format. The exported file can be opened in ME7Tuner, VisualME7Logger, or any spreadsheet application.

### Dev Mode

Press **Ctrl+Shift+D** on the Data Logger screen to activate demo mode. This replays bundled log fixtures through the charting system at ~20 samples/second — useful for exploring the UI without an ECU connection. Press again to stop; the session auto-saves to your Desktop.

---

## A2L → ECU Generator

Convert DAMOS A2L files into ME7Logger-compatible `.ecu` and `.cfg` files. If you have a DAMOS export for your ECU, this tool generates the logging configuration files automatically — no manual editing required.

<img src="/documentation/images/tools/a2l_generator.png" alt="A2L → ECU Generator" width="800">

### Usage

1. Navigate to **Tools → A2L → ECU Generator**
2. Click **Load A2L** and select your DAMOS `.a2l` file
3. The parser extracts all loggable signals (typically 9,000–14,000+ entries) and displays them in a searchable table
4. Review and edit the metadata fields:
   - **Part Number** — ECU part number (e.g., `06F 906 056 S`)
   - **SW Number** — Software version identifier
   - **Engine ID** — Engine description (e.g., `2.0L TFSI`)
5. Click **Save .ecu + .cfg** to generate both files

### What Gets Generated

**`.ecu` file** — Complete ECU characteristics file containing every loggable variable with:
- RAM address, data size, bitmask
- Scaling formula (factor + offset, or inverse scaling)
- Human-readable alias (44 well-known signals auto-aliased)
- Unit and description from the A2L source

**`.cfg` file** — Ready-to-use log configuration with:
- Reference to the generated `.ecu` file
- **Basic Variables** preset — 18 essential tuning signals (engine speed, load, MAF, boost, fuel trims, ignition, temperatures)
- **LDRPID Variables** preset — 13 boost control signals for PID tuning

### Signal Browser

The table shows all parsed entries with columns for Name, Alias, Address, Size, Unit, Factor, and Description. Use the search field to filter by any column — useful for finding specific signals in ECUs with thousands of variables.

### Conversion Math

The generator handles two conversion types from A2L rational function coefficients:

- **Linear** (most signals): `Value = Factor × raw - Offset`
- **Inverse** (~25 signals): `Value = Factor / (raw - Offset)` — used for temperature sensors and similar non-linear conversions

For technical details on the A2L parsing pipeline and conversion formulas, see [A2L → ECU Pipeline](technical/a2l-ecu-pipeline.md).

---

*ME7Tuner is free software. It comes with no warranty. If you send 25 psi into a motor that can handle 15 psi because you didn't read the output, that's between you and your engine builder.*

*Built with mass quantities of coffee by [TracQi Technology](https://github.com/TracqiTechnology).*
