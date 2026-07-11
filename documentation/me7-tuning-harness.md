# ME7 Automated Tuning Harness

## Overview

The ME7 tuning harness is a set of CLI tools that enable AI agents (or humans) to
analyze ME7Logger data, diagnose calibration issues, and generate corrective map
values — without needing the MxT GUI or deep ECU domain expertise.

The harness chains five tools:

| Tool | What it does | Key outputs |
|------|-------------|-------------|
| **MxT CLI** (`bin/mxtcli`) | Optimizer, MLHFM correction, KRKTE | KFLDRL, KFLDIMX, MLHFM corrections |
| **ecuxPlot CLI** | SAE-corrected WOT stats, boost efficiency | HP/PSI, DA-normalized power, trends |
| **me7-knock-analyze** | Per-cylinder knock retard (dwkrz) | KFZWOP timing recommendations |
| **me7-bin-write** | Apply corrections to ECU binary | Corrected BIN + changelog |
| **me7-bin-compare** | Compare two binaries at map level | Per-map deltas with physical values |

## Quick Start

```bash
# Full analysis
bin/me7-tune-analyze \
  --xdf /path/to/definition.xdf \
  --bin /path/to/current.bin \
  --logs /path/to/me7logger/logs/

# With baseline comparison
bin/me7-tune-analyze \
  --xdf /path/to/definition.xdf \
  --bin /path/to/current.bin \
  --logs /path/to/current-logs/ \
  --baseline-bin /path/to/known-good.bin \
  --baseline-logs /path/to/known-good-logs/

# Knock analysis only
python3 bin/me7-knock-analyze /path/to/logs/
```

## What Each Phase Does

### Phase 1: ECU Map Extraction
Loads XDF + BIN via MxT CLI, exports 13 key maps as CSV files.

**Key maps:** KFLDRL, KFLDIMX, KFPBRK, KFMIOP, KFMIRL, MLHFM, KFURL, KFZWOP, KFZW, LAMFA, KRKTE, LDRXN, KFLF

### Phase 1b: Baseline Comparison (optional)
If `--baseline-bin` is provided, extracts the same maps from the baseline and produces a unified diff showing exactly what changed.

### Phase 2: MxT Optimizer
Runs the 6-phase boost/load optimizer on all WOT data:
1. WOT detection and filtering
2. Pressure error analysis (pssol vs pvdks)
3. KFLDRL feedforward duty cycle suggestions
4. KFLDIMX integral term limit suggestions
5. Mechanical limit detection
6. Safety event counting (overcharge, fallback, knock)

**Output:** Corrected KFLDRL and KFLDIMX tables, pressure error statistics, safety alerts.

### Phase 3: KRKTE Injection Constant
Calculates the theoretical KRKTE (primary injection constant in ms/%) for the given engine configuration and compares it to the value in the BIN.

**Parameters:** displacement, injector size, fuel type (affects stoich AFR and fuel density).

### Phase 4: MLHFM Correction
Runs closed-loop fuel trim correction on the MAF linearization table. Uses STFT + LTFT data from part-throttle driving to identify MAF sensor scaling errors.

**Requires:** Logs with fuel trim data (`fr_w`, `fra_w`, `uhfm_w`, `B_lr` columns). These are typically separate from WOT logs — the ME7Logger config needs STFT/LTFT channels enabled.

### Phase 5: Mechanical Limits
Checks whether hardware limits are reached during WOT:
- MAF sensor saturation (g/s and voltage clipping)
- Injector duty cycle maxed
- Turbo WGDC maxed (wastegate fully closed = can't make target boost)
- MAP sensor maxed (5180 mod: 5-bar = ~5000 mbar max)

### Phase 6: ecuxPlot WOT Analysis
Loads logs into ecuxPlot and runs:
- Per-run statistics (max WHP, WTQ, boost, IAT, FATS time)
- SAE J1349 correction (normalizes to sea-level standard conditions)
- Boost efficiency (HP/PSI — how well boost converts to power)
- Pressure-based analysis (HP/mBar — MAF-independent)
- Chronological trends (is power improving or degrading?)
- Density altitude normalization

### Phase 7: Per-Cylinder Knock Analysis
Parses raw ME7Logger CSVs for `dwkrz1_w` through `dwkrz6_w` columns and:
- Maps knock severity by RPM × Load zones
- Identifies bank-specific patterns (V6: bank 1 = cyl 1-3, bank 2 = cyl 4-6)
- Correlates knock with temperature
- Generates specific KFZWOP timing reduction recommendations

## Output Structure

```
/tmp/me7-analysis/YYYYMMDD_HHMMSS/
├── REPORT.md                    ← Synthesis report (start here)
├── phase1_ecu.txt               ← ECU load + map listing
├── phase1b_baseline.txt         ← Baseline ECU (if --baseline-bin)
├── phase2_optimizer.txt         ← Optimizer results
├── phase3_krkte.txt             ← KRKTE calculation
├── phase4_mlhfm.txt             ← MLHFM correction
├── phase5_limits.txt            ← Mechanical limits
├── phase6_ecuxplot.txt          ← ecuxPlot analysis
├── phase7_knock.txt             ← Knock report (human readable)
├── phase7_knock.json            ← Knock report (machine readable)
├── map_deltas.txt               ← Baseline diff (if --baseline-bin)
├── KFLDRL.csv                   ← Exported map CSVs
├── KFLDIMX.csv
├── KFZWOP.csv
├── KFZW.csv
├── MLHFM.csv
├── LAMFA.csv
├── KRKTE.csv
├── KFPBRK.csv
├── KFMIOP.csv
├── KFMIRL.csv
├── KFURL.csv
├── LDRXN.csv
├── KFLF.csv
├── baseline_KFLDRL.csv          ← Baseline maps (if --baseline-bin)
└── ...
```

## Convergence Criteria

The tune is "converged" when:

| Metric | Target | How to check |
|--------|--------|--------------|
| Per-cylinder knock (dwkrz) | < 2° at all RPM | `phase7_knock.txt` |
| Pressure error (pssol) | < 100 mbar mean | `phase2_optimizer.txt` |
| SAE-corrected HP/PSI | > 10.0 | `phase6_ecuxplot.txt` |
| Torque intervention rate | < 15% | `phase2_optimizer.txt` |
| MLHFM correction | < 3% delta | `phase4_mlhfm.txt` |
| Fuel trims (STFT+LTFT) | < ±5% | `phase4_mlhfm.txt` |
| No mechanical limits | All clear | `phase5_limits.txt` |

## Iterative Tuning Workflow

```
┌─────────────────────────────────────────────────┐
│  1. ANALYZE: Run me7-tune-analyze               │
│     → Diagnose issues, generate corrections     │
├─────────────────────────────────────────────────┤
│  2. COMPARE: Run me7-bin-compare (optional)     │
│     → Understand how current differs from known │
│     → good baseline (e.g., Denver v2.7.4)       │
├─────────────────────────────────────────────────┤
│  3. WRITE: Run me7-bin-write                    │
│     → Apply corrections to new BIN              │
│     → Restore maps, apply optimizer, IAT taper  │
├─────────────────────────────────────────────────┤
│  4. VERIFY: Run me7-bin-compare on output       │
│     → Confirm only expected maps changed        │
├─────────────────────────────────────────────────┤
│  5. FLASH + LOG: Flash ECU, collect new logs    │
│     → WOT pulls + part-throttle driving         │
├─────────────────────────────────────────────────┤
│  6. Re-run from step 1 until converged          │
└─────────────────────────────────────────────────┘
```

### Typical iteration count by issue:

| Issue | Iterations | Why |
|-------|-----------|-----|
| KFZWOP timing | 1-2 | One reduction usually sufficient; verify with 1 log session |
| KFLDRL feedforward | 3-5 | PID needs to converge; each iteration refines duty cycle |
| MLHFM scaling | 2-3 | Fuel trims guide correction, but each correction shifts operating point |
| KFLDIMX limits | 1-2 | Typically set alongside KFLDRL |

### End-to-End Example (sea-level adaptation)

```bash
# 1. Analyze current tune against logs
bin/me7-tune-analyze \
  --xdf ~/Dropbox/S4/8D0907551M-latest/8D0907551M-20190711.xdf \
  --bin ~/Dropbox/S4/tunes/kaleb/v3.2.1/mmodified_v321.bin \
  --logs ~/Dropbox/S4/ME7Logger/logs/ \
  --baseline-bin ~/Dropbox/S4/tunes/kaleb/v2.7.4/mmodified_v274.bin

# 2. Compare Denver (known good) vs Washington (current)
python3 bin/me7-bin-compare \
  --xdf ~/Dropbox/S4/8D0907551M-latest/8D0907551M-20190711.xdf \
  --base ~/Dropbox/S4/tunes/kaleb/v2.7.4/mmodified_v274.bin \
  --target ~/Dropbox/S4/tunes/kaleb/v3.2.1/mmodified_v321.bin \
  --cells

# 3. Write corrected binary
python3 bin/me7-bin-write \
  --xdf ~/Dropbox/S4/8D0907551M-latest/8D0907551M-20190711.xdf \
  --bin ~/Dropbox/S4/tunes/kaleb/v3.2.1/mmodified_v321.bin \
  --out ~/Dropbox/S4/tunes/kaleb/v3.2.2/mmodified_v322.bin \
  --verify \
  --restore KFLAMKRL --from ~/Dropbox/S4/tunes/kaleb/v2.7.4/mmodified_v274.bin \
  --restore KFZWWLNM --from ~/Dropbox/S4/tunes/kaleb/v2.7.4/mmodified_v274.bin \
  --apply-optimizer /tmp/me7-analysis/*/phase2_optimizer.txt \
  --iat-taper KFTARX --profile 1.02,1.02,0.97,0.97,0.92,0.75,0.75,0.60

# 4. Verify output
python3 bin/me7-bin-compare \
  --xdf ~/Dropbox/S4/8D0907551M-latest/8D0907551M-20190711.xdf \
  --base ~/Dropbox/S4/tunes/kaleb/v3.2.1/mmodified_v321.bin \
  --target ~/Dropbox/S4/tunes/kaleb/v3.2.2/mmodified_v322.bin \
  --cells

# 5. Flash, log, repeat from step 1 with new logs
```

## 5180 MAP Sensor Mod Considerations

Vehicles with the 5180 MAP sensor modification have extended load ranges (up to 300%+ vs stock 191%). The harness accounts for this:

- **KFZWOP/KFZW axis mismatch:** KFZW may stop at ~191% load while KFZWOP extends to 376%. The knock analysis covers the full range.
- **Extreme loads:** At 250-330% load, even small timing errors cause severe knock because cylinder pressures are extremely high.
- **MAP sensor headroom:** The 5-bar sensor reads to ~5000 mbar, so there's plenty of headroom. The mechanical limit detector confirms this.

## Individual Tool Usage

### MxT CLI

```bash
# REPL mode
cd ~/Projects/ME7Tuner && ./gradlew -q cli

# One-shot
echo 'load-ecu x.xdf x.bin
map KFZWOP
quit' | ./gradlew -q cli

# Available commands
load-ecu <xdf> <bin>          # Load ECU files
load-logs <dir> [--type T]    # Load logs (optimizer|closed|open|ldrpid)
maps [filter]                 # List maps
map <name>                    # Show map values
optimize                      # Run optimizer
mlhfm-correct [--mode M]     # MAF correction (closed|open)
krkte [--displacement N]      # Injection constant
limits                        # Mechanical limits
summary                       # Session overview
export <map> <file.csv>       # Export map to CSV
```

### ecuxPlot CLI

```bash
# REPL mode
cd ~/Projects/ecuxplot && java -cp "build/classes:lib/*" org.nyet.ecuxplot.ECUxCLI

# Available commands
load <name> <dir>             # Load log directory as named group
groups                        # List loaded groups
summary [group]               # Aggregate stats
runs <group>                  # Per-run data
compare <g1> <g2>             # Delta comparison
sae-compare                   # SAE J1349 corrected comparison
da-normalize <metric>         # Density altitude normalization
efficiency <metric>           # Boost efficiency (metric/PSI)
pressure                      # Pressure-based HP/mBar
trend <group> <metric>        # Chronological trend
correlate <g> <x> <y>         # Partial correlation
metrics                       # List metric names
run-detail <g> <file>         # Single-run detail
```

### Knock Analysis

```bash
# Human report
python3 bin/me7-knock-analyze /path/to/logs/

# JSON output
python3 bin/me7-knock-analyze /path/to/logs/ --json

# Save JSON report
python3 bin/me7-knock-analyze /path/to/logs/ --output report.json

# Custom RPM range
python3 bin/me7-knock-analyze /path/to/logs/ --rpm-min 2000 --rpm-max 7000
```

### Binary Writer

```bash
# Restore maps from a reference binary
python3 bin/me7-bin-write \
  --xdf def.xdf --bin current.bin --out corrected.bin \
  --restore KFLAMKRL --from reference.bin \
  --restore KFZWWLNM --from reference.bin

# Apply MxT optimizer suggestions
python3 bin/me7-bin-write \
  --xdf def.xdf --bin current.bin --out corrected.bin \
  --apply-optimizer /tmp/me7-analysis/*/phase2_optimizer.txt

# Apply knock analysis timing reductions
python3 bin/me7-bin-write \
  --xdf def.xdf --bin current.bin --out corrected.bin \
  --apply-knock /tmp/me7-analysis/*/phase7_knock.json

# Write IAT load taper (one factor per IAT axis breakpoint, cold→hot)
python3 bin/me7-bin-write \
  --xdf def.xdf --bin current.bin --out corrected.bin \
  --iat-taper KFTARX --profile 1.02,1.02,0.97,0.97,0.92,0.75,0.75,0.60

# Set a map from CSV values
python3 bin/me7-bin-write \
  --xdf def.xdf --bin current.bin --out corrected.bin \
  --set-map KFZWOP --csv corrected_kfzwop.csv

# Combine multiple actions in one command
python3 bin/me7-bin-write \
  --xdf def.xdf --bin current.bin --out corrected.bin --verify \
  --restore KFLAMKRL --from reference.bin \
  --apply-optimizer /tmp/me7-analysis/*/phase2_optimizer.txt \
  --iat-taper KFTARX --profile 1.02,1.02,0.97,0.97,0.92,0.75,0.75,0.60

# Batch changes from JSON spec
python3 bin/me7-bin-write \
  --xdf def.xdf --bin current.bin --out corrected.bin \
  --apply-changes changes.json

# Options
--dry-run    # Show changes without writing
--verify     # Re-read and verify after writing
--verbose    # Show per-cell details
--changelog  # Custom changelog path (default: <out>.log)
```

### Binary Comparator

```bash
# Compare two binaries — show only changed maps
python3 bin/me7-bin-compare \
  --xdf def.xdf --base old.bin --target new.bin

# Show per-cell deltas
python3 bin/me7-bin-compare \
  --xdf def.xdf --base old.bin --target new.bin --cells

# Compare specific maps only
python3 bin/me7-bin-compare \
  --xdf def.xdf --base old.bin --target new.bin \
  --maps KFLAMKRL,KFZWOP,KFLDRL,KFTARX --cells

# Show all maps including identical
python3 bin/me7-bin-compare \
  --xdf def.xdf --base old.bin --target new.bin --all --summary

# JSON output for programmatic use
python3 bin/me7-bin-compare \
  --xdf def.xdf --base old.bin --target new.bin --json

# Only report cells with > 5% change
python3 bin/me7-bin-compare \
  --xdf def.xdf --base old.bin --target new.bin --cells --threshold 5
```

## Agent Integration

For AI agents using this harness, here is the complete tuning workflow:

### Step-by-Step Agent Protocol

1. **Run `me7-tune-analyze`** with the current XDF, BIN, and log directory
2. **Read `REPORT.md`** in the analysis output directory
3. **Interpret each phase** — extract actionable corrections:
   - `phase2_optimizer.txt` → KFLDRL/KFLDIMX values (boost control)
   - `phase7_knock.json` → KFZWOP timing reductions (knock safety)
   - `phase4_mlhfm.txt` → MLHFM corrections (MAF scaling)
   - `phase5_limits.txt` → Hardware limit warnings
   - `phase6_ecuxplot.txt` → Overall power/efficiency assessment
4. **Compare against baseline** using `me7-bin-compare` if a known-good binary exists
5. **Write corrected binary** using `me7-bin-write`:
   - `--restore` maps from baseline where applicable (e.g., KFLAMKRL, KFZWWLNM)
   - `--apply-optimizer` for KFLDRL/KFLDIMX from phase 2
   - `--apply-knock` for KFZWOP reductions from phase 7
   - `--iat-taper` for IAT-based load protection (KFTARX)
   - `--set-map` for custom values from CSV
6. **Verify with `me7-bin-compare`** — confirm only expected maps changed
7. **User flashes ECU and collects new logs**
8. **Repeat from step 1** until convergence criteria met

### Decision Framework

| Finding | Action | Tool |
|---------|--------|------|
| Knock > 2° on specific cylinders | Reduce KFZWOP at knock RPM/load zones | `me7-bin-write --apply-knock` |
| Pressure error > 100 mbar | Apply optimizer KFLDRL/KFLDIMX | `me7-bin-write --apply-optimizer` |
| MLHFM correction > 3% | Apply corrected MLHFM table | `me7-bin-write --set-map MLHFM` |
| SAE HP/PSI < 10.0 | Check knock, KFLDRL, MLHFM | Analysis only |
| Torque intervention > 15% | KFMIOP/KFMIRL mismatch or KFZWOP too aggressive | Analysis only |
| Bank-specific knock | Check hardware (WI, exhaust, intercooler) | Report to user |
| Map neutered vs baseline | Restore from baseline | `me7-bin-write --restore` |
| No IAT load taper | Add KFTARX protection | `me7-bin-write --iat-taper` |

### Version Management

When writing new binaries, follow the versioning convention:
- Binaries live at `~/Dropbox/S4/tunes/kaleb/v{major}.{minor}.{patch}/`
- Increment the patch version for each iteration (v3.2.1 → v3.2.2 → v3.2.3)
- Each version directory should contain the BIN file and a `.log` changelog
- The changelog is automatically generated by `me7-bin-write`

### JSON Changes File Format

For complex or repeatable change sets, use a JSON changes file:

```json
{
  "description": "v3.2.2 — Sea-level adaptation + knock safety",
  "base": "v3.2.1/mmodified_v321.bin",
  "changes": [
    {"action": "restore", "map": "KFLAMKRL", "from": "v2.7.4/mmodified_v274.bin"},
    {"action": "restore", "map": "KFZWWLNM", "from": "v2.7.4/mmodified_v274.bin"},
    {"action": "optimizer", "file": "phase2_optimizer.txt"},
    {"action": "knock", "file": "phase7_knock.json"},
    {"action": "iat_taper", "map": "KFTARX",
     "profile": [1.02, 1.02, 0.97, 0.97, 0.92, 0.75, 0.75, 0.60]}
  ]
}
```

### Binary Format Notes

- ME7.1 ECU binaries are **little-endian** (C166 processor)
- 16-bit values use `<H` (not `>H`) in Python struct
- XDF mmedtypeflags bit 1 = LSB-first (little-endian)
- 8-bit values are endian-neutral
- Map z-data is stored row-major (RPM rows × load/pressure columns)
- XDF equations may use quirky format: `0.75 * X+ -48.000000` (the tools handle this)

### Density Altitude Awareness

The same tune behaves differently at different density altitudes. When analyzing:
- **Compare HP/PSI** (boost efficiency) not raw HP — it normalizes for weather
- **Use SAE-corrected values** to remove weather effects from comparisons
- **A tune that works at DA 6000 ft may knock at DA -1500 ft** — the 25% air density difference is enormous
- **Temperature matters:** A marginal tune in April (19°C) becomes catastrophic in August (25°C)

### Safety Constraints

Never recommend corrections that:
- Increase timing at loads > 250% without knock data showing margin
- Reduce fuel enrichment (LAMFA) while knock is present — rich mixture cools charge
- Exceed 95% injector duty cycle
- Exceed 95% wastegate duty cycle (can't make more boost)
- Push MAP sensor above 4500 mbar (5-bar sensor safety margin)
