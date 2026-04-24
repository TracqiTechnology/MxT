# MxT 3.0.0-beta — The "Four Platforms Walk Into a Bar" Release

We renamed the app. ME7Tuner is now **MxT** — because it tunes ME7, MED17, MED9, and Motronic, and "ME7MED17MED9MotronicTuner" wasn't going to fit in anyone's taskbar. 163 new Kotlin files. 208 files changed. 36,106 lines added. 10 commits. Four ECU platforms. One questionable decision after another, all of them correct.

This is a beta. Some of it will set your car on fire. We wrote a disclaimer about it. It has a button that says "I'll Stick to ME7."

---

## Four Platforms, One App

MxT now supports four Bosch ECU families. Each has a stability level, because honesty is a feature.

| Platform | ECU Family | Stability | What It Covers |
|----------|-----------|-----------|----------------|
| **ME7** | Bosch ME7.x | **Stable** | MAF-based air metering, port injection. The one you trust. |
| **MED17** | Bosch MED17.x | **Beta** | Pressure-based air metering, dual injection (port + direct). The one that's almost ready. |
| **MED9** | Bosch MED9.x | **Alpha** | MAF-based, direct injection. The one we're still figuring out. |
| **Motronic** | Motronic 3.8x-5.9x | **Alpha** | MAF-based, port injection. The one your dad's Audi runs. |

### 9 Bundled Profiles- Optimizer/PLSOL "no data" — Tests pass with our example logs (530 WOT entries). Ask for customer's specific CSV file.
- Tab/Enter commit — MapTable handles this correctly. Ask which specific field doesn't commit.
- ~

| # | Profile | Platform |
|---|---------|----------|
| 1 | Audi B5 S4 2.7T — MBox | ME7 |
| 2 | Audi B5 RS4 2.7T — ABox | ME7 |
| 3 | Audi/VW 1.8T 150hp — ME7.5 | ME7 |
| 4 | VW Golf/Jetta/Bora 1.8T — ME7.5.5 | ME7 |
| 5 | Audi TT / A4 1.8T 180-225hp | ME7 |
| 6 | Audi B5 A4 / C5 A6 2.7T — MBox Variant | ME7 |
| 7 | Audi RS3 / TTRS 2.5T TFSI — MED17.1.62 | MED17 |
| 8 | VW Golf V GTI 2.0 TFSI — MED9.1 | MED9 |
| 9 | VW/Audi 1.8T AGU — Motronic 3.8.3 | Motronic |

Platform switching is a segmented button in the navigation rail. Click it and the calibration tabs reorganize themselves — tabs that don't apply to your platform disappear, map names update to the platform-specific equivalents (KFMIOP becomes KFLMIOP on MED17, KFMDOPT on Motronic), and the profile system maps everything through `.mxtprofile.json` definition files. It's the kind of thing that sounds simple until you realize every screen, every preference, and every map picker had to become platform-aware.

### Platform-Specific Map Names

Because Bosch couldn't agree on naming conventions across a 20-year product line:

| Calibration | ME7 | MED17 | MED9 | Motronic |
|-------------|-----|-------|------|----------|
| Torque to Load | KFMIOP | KFLMIOP | KFMIOP | KFMDOPT |
| Load to Torque | KFMIRL | KFLMIRL | KFMIRL | — |
| Optimal Timing | KFZWOP | KFZWOP | KFZWOP | KFZWOPT |
| Throttle Transition | KFVPDKSD | — | KFVPDKLD | — |
| Alpha-N | WDKUGDN | — | WDKUGDN | KFTLWS |

---

## Built-In Data Logger

MxT now has a native data logger. No external tools required — well, you still need a cable and an ECU that's willing to talk to you.

### Three Logger Backends

| Mode | Protocol | Platforms | Hardware |
|------|----------|-----------|----------|
| ME7Logger.exe | K-line (spawns external process) | ME7 (Windows) | VAG-COM cable |
| Native KWP2000 | K-line via jSerialComm | ME7, MED9, Motronic | Any K-line adapter |
| Native UDS | CAN via SLCAN/PCAN | MED17 | SLCAN or PCAN-USB adapter |

### What It Does

- **Real-time charting** with proper axes, grid lines, and nice tick values — not the "rectangles on a canvas" charting you've seen in other tools
- **Combined view** (all signals on one plot) or **individual charts** (one per variable) — toggle with a segmented button
- **CSV export** in ME7Logger-compatible format with signal names, units, and aliases
- **Mock replay mode** for development and testing — loads recorded CSV files and plays them back through the full charting pipeline

### Secret Dev Mode

`Ctrl+Shift+D` activates developer mode in the Data Logger. You didn't hear it from us.

---

## RAM Sniffer

New tool for discovering RAM addresses in unknown ECU binaries. Because not every ECU has a published DAMOS, and sometimes you have to find the addresses yourself.

**How it works:**
1. Load a reference binary with known addresses (from a DAMOS .dam file or .ecu file)
2. Load the target binary you want to map
3. The sniffer builds byte-pattern signatures around known addresses and scans the target binary for matches
4. Export the discovered addresses as ME7Logger-compatible `.ecu` and `.cfg` files

Supports all four platforms. Uses configurable signature windows and confidence scoring. The `SignatureBuilder` generates position-independent byte signatures, and the `SignatureScanner` uses them to find relocated addresses across ROM versions.

---

## A2L to ECU Generator

New tool under the TOOLS navigation rail. Takes a DAMOS A2L definition file and generates ME7Logger-compatible `.ecu` and `.cfg` files from it. Parses `COMPU_METHOD` and `MEASUREMENT` blocks from A2L XML, builds the signal catalog, and exports with confidence filtering.

If you have an A2L for your ECU, you can go from "I have a PDF from Bosch" to "I'm logging real-time data" without writing a single address by hand.

---

## Full KP Binary Parsing

Remember last release when we said "the WinOLS binary format is proprietary and has no public specification" and "guesswork is not how you build tools that write to engine binaries"? We reverse-engineered it anyway.

Cross-referencing 25 matched KP records against the WinOLS CSV ground truth, we decoded the full binary record layout:

- **Dimensions** (cols x rows) — 100% accuracy on tested records
- **Scaling factors** (float64) — 81% exact, rest within 2x
- **Z-axis addresses** — 95% match
- **Bit width** — derived from address ranges, 95% accurate
- **X/Y axis units, scales, addresses** — 100% on all tested records

**An XDF file, WinOLS CSV export, or KP file is now sufficient for binary reading and writing.** Any combination works. MxT merges definitions with priority: XDF > CSV > KP. Load a `.kp` file with no XDF and you can still read and write maps.

We also fixed a bug in the old parser: compound KP records (up to 122KB each) can contain dozens of maps, but the old code only extracted the first one. The largest record in the reference fixture contained 22 maps. We were returning 1. `find()` to `findAll()`. Sometimes the fix is embarrassing.

---

## DS1 Scalar Override Support

MED17 with Dyno Spectrum DS1 reduces certain maps to single-value scalars and bypasses the native engine load logic. MxT now detects this automatically:

- **KFMIOP/KFLMIOP** — When the map is 1x1 with empty axes, the UI switches to scalar mode with a simplified single-value input
- **KFMIRL/KFLMIRL** — Rescales along its own load axis to the target max load
- **KFZW multi-switch maps** — DS1 supports up to 6 fuel-blend ignition maps (Gasoline 0/1/2, Ethanol 0/1/2). Unlimited map slots with `KfzwSwitchMapPreferences`. Rescale them all simultaneously.

DS1 info banners appear when scalar mode is detected. You'll know.

---

## New Visualization

### Timing Charts

Three new chart types for ignition timing analysis, available in KFZW, KFZWOP, and KFMIRL screens:

| Chart | What It Shows |
|-------|--------------|
| **Delta Heatmap** | Original vs. calculated timing difference as a color gradient — blue (retarded) through neutral to red (advanced) |
| **Load Slices** | Timing curves at specific load breakpoints |
| **Contour Chart** | Iso-timing contour lines via marching squares algorithm. Yes, we implemented marching squares for an ECU tuning app. |

---

## Stability Badges & Disclaimer

Every non-stable feature gets a badge: **BETA** (yellow) or **ALPHA** (red). These appear on calibration tabs and in the navigation rail. You can't miss them. We don't want you to miss them.

On first launch with non-ME7 platforms enabled, you'll see a disclaimer titled **"A Word About Features That Might Set Your Car on Fire."** It explains what's stable, what's beta, what's alpha, and gives you two choices:

- **"I'll Stick to ME7"** — Hides all non-stable features
- **"Got It, Show Me Everything"** — Shows everything, badges and all

---

## Diagnostic Collector

`Help -> Report Issue` now copies a diagnostic snapshot to your clipboard:

```markdown
| Field | Value |
|-------|-------|
| Version | 2.0.1 |
| OS | macOS 15.3.1 (aarch64) |
| Java | 17.0.x (GraalVM) |
| Platform | ME7 |
| BIN | 8D0907551M.bin |
| XDF | 8D0907551M-16bit.xdf |
| Maps | 393 |
| Profile | Audi B5 S4 2.7T |
```

Paste it in the GitHub issue. We'll know exactly what you're running.

---

## CI/CD: Pre-Release Pipeline

New GitHub Actions workflow for the `develop` branch:

1. Run all tests
2. Compute beta version from the latest stable tag (`v2.0.2-beta.14` format)
3. Build native packages on all three platforms in parallel (DMG, MSI, DEB)
4. Create a GitHub pre-release, clean up old beta artifacts
5. Upload everything

Merges to `develop` that don't pass tests don't ship. Merges that pass tests ship automatically. Beta testers get fresh builds without anyone touching a button.

---

## GitHub Issue Templates

Three structured issue templates so bug reports stop being "it doesn't work":

- **Bug Report** — Description, steps to reproduce, environment snapshot (from the diagnostic collector), severity dropdown
- **Feature Request** — Problem description, proposed solution, ECU platform checkboxes, calibration area dropdown
- **Calibration Data Contribution** — For community members sharing BINs, XDFs, A2Ls, DAMOS files, logs, and profiles

---

## Documentation

New and updated:

- **[ME7 Calibration Guide](../documentation/me7-calibration-guide.md)** — Complete ME7 workflow with screenshots
- **[MED17 Calibration Guide](../documentation/med17-calibration-guide.md)** — Complete MED17 workflow (dual injection, fuel trim, DS1)
- **[A2L Pipeline & Data Logger Architecture](../technical/a2l-ecu-pipeline.md)** — A2L parsing, ECU file generation, and logger protocol docs
- **[me7-kp-format.md](../technical/me7/me7-kp-format.md)** — Full decoded KP binary record layout with accuracy stats
- Updated calibration guide screenshots for the new Material 3 theme

---

## The Numbers

| Metric | Value |
|--------|-------|
| New Kotlin files | 163 |
| Files changed | 208 (.kt only) |
| Lines added | 36,106 |
| Lines deleted | 826 |
| Bundled profiles | 9 (was 6) |
| ECU platforms | 4 (was 1.5) |
| Test count | ~1,085 |
| New test files | ~60 |
| Logger backends | 3 |
| Communication protocols | K-line, CAN, SLCAN, PCAN |
| Charts implemented from scratch | Still all of them, plus 3 more |

---

## Compatibility

- **BIN files:** fully compatible
- **XDF files:** fully compatible
- **KP files:** now fully parsed (not just hints)
- **Log files:** fully compatible + new native logger CSV format
- **Profiles:** new `.mxtprofile.json` format (bundled profiles cover all 4 platforms)
- **User preferences:** persist across upgrades

---

## Breaking Changes

- **App renamed:** ME7Tuner -> MxT. Same app. Different name. Because it tunes more than ME7 now.
- **Theme class renamed:** `ME7TunerTheme` -> `MxTTheme`. If you were importing it... you weren't. But now you know.

---

MxT is free software. It comes with no warranty. If you plug a K-line cable into an ECU running Motronic 3.8.3 and the alpha-quality native logger does something unexpected, that's between you, the logger, and whatever's left of your intake manifold.

The disclaimer warned you. The badges warned you. We warned you.

Built with mass quantities of coffee and a concerning amount of hex editor staring by TracQi Technology.
