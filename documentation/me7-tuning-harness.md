# ME7 Automated Tuning Harness

## Overview

The ME7 tuning harness is a set of CLI tools that enable AI agents (or humans) to
analyze ME7Logger data, diagnose calibration issues, and generate corrective map
values — without needing the MxT GUI or deep ECU domain expertise.

The harness chains three tools:

| Tool | What it does | Key outputs |
|------|-------------|-------------|
| **MxT CLI** (`bin/mxtcli`) | Optimizer, MLHFM correction, KRKTE | KFLDRL, KFLDIMX, MLHFM corrections |
| **ecuxPlot CLI** | SAE-corrected WOT stats, boost efficiency | HP/PSI, DA-normalized power, trends |
| **me7-knock-analyze** | Per-cylinder knock retard (dwkrz) | KFZWOP timing recommendations |

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
│  1. Run me7-tune-analyze                        │
│     → Identify issues, generate corrections     │
├─────────────────────────────────────────────────┤
│  2. Apply corrections to BIN                    │
│     → KFZWOP timing, KFLDRL duty, MLHFM scale  │
│     → Use TunerPro or similar to write BIN      │
├─────────────────────────────────────────────────┤
│  3. Flash ECU + collect new logs                │
│     → Drive under same conditions if possible   │
│     → Collect both WOT and part-throttle logs   │
├─────────────────────────────────────────────────┤
│  4. Re-run me7-tune-analyze on new logs         │
│     → Compare against previous iteration        │
│     → Check convergence criteria                │
├─────────────────────────────────────────────────┤
│  5. Converged? → Done                           │
│     Not converged? → Go to step 2               │
└─────────────────────────────────────────────────┘
```

### Typical iteration count by issue:

| Issue | Iterations | Why |
|-------|-----------|-----|
| KFZWOP timing | 1-2 | One reduction usually sufficient; verify with 1 log session |
| KFLDRL feedforward | 3-5 | PID needs to converge; each iteration refines duty cycle |
| MLHFM scaling | 2-3 | Fuel trims guide correction, but each correction shifts operating point |
| KFLDIMX limits | 1-2 | Typically set alongside KFLDRL |

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

## Agent Integration

For AI agents using this harness:

1. **Start with `me7-tune-analyze`** — it runs everything in the right order
2. **Read `REPORT.md`** first — it tells you where to look
3. **Parse `phase7_knock.json`** for programmatic access to knock data
4. **The convergence criteria table** tells you when to stop iterating
5. **Always check mechanical limits** before applying corrections — if hardware is maxed, no software correction will help
6. **KFZWOP corrections are highest priority** — timing retard causes the most power loss
7. **KFLDRL corrections need multiple iterations** — run 3-5 log sessions to converge

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
