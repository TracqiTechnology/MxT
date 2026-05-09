# ME7 Tuning Protocol — Agent Decision Framework

This document defines the structured workflow for AI-driven ECU calibration of ME7.x ECUs. It is the decision framework for `me7-tune-copilot` and any agent performing calibration work.

## Tool Inventory

| Tool | Purpose | Input | Output |
|------|---------|-------|--------|
| `bin/me7-tune-copilot` | Master orchestrator | XDF + BIN + logs | Analysis + corrections |
| `bin/me7-safety-check` | Safety validator | XDF + BIN | Pass/fail + findings |
| `bin/me7-log-ingest` | Log → DuckDB | CSV log dir | DuckDB database |
| `bin/me7-knowledge-graph` | Causal graph | Tune dir + XDF | Kùzu database |
| `bin/me7-registry` | Metadata + convergence | Version + metrics | SQLite database |
| `bin/me7-query` | Unified queries | SQL/Cypher | Structured results |
| `bin/me7-bin-write` | Binary writer | XDF + BIN + changes | New BIN |
| `bin/me7-bin-compare` | Binary diff | Two BINs + XDF | Map-level changes |
| `bin/me7-tune-analyze` | 8-phase analysis | XDF + BIN + logs | Text report |
| `bin/me7-knock-analyze` | Knock analysis | XDF + BIN + logs | Per-cylinder report |
| MxT CLI (`./gradlew cli`) | Optimizer + MLHFM + KRKTE | XDF + BIN + logs | Structured output |
| ecuxPlot CLI | SAE correction + trends | Logs | Analysis |

## Phase 0: PREFLIGHT

**Goal:** Verify binary integrity and log data quality before any analysis.

```bash
# Binary safety
bin/me7-safety-check --xdf $XDF --bin $BIN --all --format json

# Log data quality
bin/me7-safety-check --xdf $XDF --bin $BIN --preflight --logs $LOGS --format json
```

### Decision Gates

| Check | Pass | Fail Action |
|-------|------|-------------|
| Binary safety errors | 0 errors | STOP — fix binary first |
| LTFT range | ±15% | STOP — fuel system problem |
| MAP sensor saturation | <10% of WOT | STOP — hardware limit |
| WOT sample count | ≥20 | STOP — need more data |
| Wideband lean at WOT | AFR ≤ 13.5 | STOP — dangerous lean condition |

## Phase 1: ANALYZE

**Goal:** Run full analysis and capture current state.

```bash
# MxT optimizer (boost/load/VE corrections)
echo 'load-ecu $XDF $BIN
load-logs $LOGS
optimize --json
limits --json
summary --json
quit' | ./gradlew -q cli --console=plain

# If MLHFM correction needed
echo 'load-ecu $XDF $BIN
load-logs $LOGS
mlhfm-correct --mode closed
quit' | ./gradlew -q cli --console=plain

# Full analysis pipeline
bin/me7-tune-analyze --xdf $XDF --bin $BIN --logs $LOGS

# Per-cylinder knock
bin/me7-knock-analyze --xdf $XDF --bin $BIN --logs $LOGS --format json
```

### Key Metrics to Extract

| Metric | Source | Target |
|--------|--------|--------|
| Knock total (°) | me7-knock-analyze | < 3.0° |
| Pressure error (%) | optimizer | < 10% |
| LTFT mean (%) | log analysis | ±5% |
| Torque intervention (%) | optimizer chain | < 15% |
| WOT λ minimum | log analysis | > 0.80 |
| Boost stability (%) | optimizer | > 90% |
| MAF error (%) | MLHFM correction | < 5% |

## Phase 2: DIAGNOSE

**Decision tree for determining which maps to modify.**

```
IF knock_total > 3.0°:
  → Priority 1: TIMING
  → Check KFZWOP at affected RPM/load cells
  → Check KFZWWLNM (IAT bonus)
    - If IAT > 50°C during knock: reduce KFZWWLNM bonus
    - If water injection offline: significantly reduce KFZWWLNM
  → Check KFLAMKRL enrichment
    - If λ > 0.82 during knock: increase enrichment

IF pressure_error > 10%:
  → Priority 2: BOOST CONTROL
  → Apply optimizer KFLDRL corrections (feedforward)
  → Apply optimizer KFLDIMX corrections (I-term limit)
  → Check KFPBRK for VE model accuracy

IF torque_intervention > 15%:
  → Priority 3: TORQUE STRUCTURE
  → Check KFMIOP/KFMIRL alignment
  → Run KFMIRL inversion check
  → Consider load axis extension (5180 mod impact)

IF maf_error > 5%:
  → Priority 4: MAF LINEARIZATION
  → Apply MLHFM correction (but verify KRKTE first!)
  → Re-run analysis after MLHFM change

IF ltft > ±8%:
  → Priority 5: FUELING
  → Check KRKTE calculation
  → Check injector sizing (cmdKrkte in CLI)
  → Verify fuel system hardware
```

### Knowledge Graph Queries for Diagnosis

```bash
# What maps are affected by a KFMIOP change?
bin/me7-query graph "MATCH (m:Map {name:'KFMIOP'})-[:FEEDS_INTO*1..3]->(n:Map) RETURN n.name"

# What causes knock?
bin/me7-query graph "MATCH (s:Symptom)-[:AFFECTS]->(m:Map) WHERE s.name CONTAINS 'knock' RETURN s.name, m.name"

# Signal chain from load request to actual boost
bin/me7-query graph "MATCH p=(a:Map {name:'LDRXN'})-[:FEEDS_INTO*1..4]->(b:Map) RETURN [n in nodes(p) | n.name]"
```

## Phase 3: SIMULATE

**Goal:** Predict effects of proposed changes before writing.

```bash
# Simulate a single operating point
echo 'load-ecu $XDF $BIN
simulate --rpm 5500 --load 280 --baro 1013
quit' | ./gradlew -q cli

# PID simulation for boost transient
echo 'load-ecu $XDF $BIN
pid-sim --target 2500 --start 1013 --rpm 4000
quit' | ./gradlew -q cli

# What-if analysis for timing change
echo 'load-ecu $XDF $BIN
what-if KFZWOP --cell 5500,282 --from 15.0 --to 12.0
quit' | ./gradlew -q cli
```

### Simulation Decision Rules

- **KFLDRL change:** Run PID simulation → check for oscillation, check overshoot < 5%
- **KFZWOP change:** Run chain simulation → verify timing doesn't exceed safety ceiling
- **KFMIOP/KFMIRL change:** Run simulation → verify inverse relationship holds within 2%
- **KFPBRK change:** Run chain simulation → verify VE model correction doesn't exceed ±10%

## Phase 4: VALIDATE

**Goal:** Safety check proposed changes before writing.

```bash
# Safety check proposed binary
bin/me7-safety-check --xdf $XDF --bin $PROPOSED_BIN --reference $CURRENT_BIN --all --format json

# Cross-map coherence
bin/me7-safety-check --xdf $XDF --bin $PROPOSED_BIN --coherence --format json
```

### Safety Constraints

| Map | Max Value | Max Delta/Iteration | Special Rules |
|-----|-----------|--------------------|----|
| KFZWOP | 48° | 5° | High-load ceiling: 20° at 250%+, 15° at 300%+ |
| KFZW | ≤ KFZWOP | 3° | Must not exceed KFZWOP at any cell |
| KFLDRL | 95% | 15% | Must not exceed KFLDIMX |
| KFLDIMX | 102.5% | 10% | Big turbo allowance |
| KFLAMKRL | 10.5–15.0 AFR | 0.5 AFR | Never leaner than 10.5 AFR at high load |
| KFTARX | 0.0–1.1 | 0.1 | Must decrease with IAT |
| MLHFM | Monotonic | — | Must be monotonically increasing |

## Phase 5: WRITE

**Goal:** Write the corrected binary with full traceability.

```bash
# Write with safety check and verification
bin/me7-bin-write --xdf $XDF --bin $CURRENT --out $NEXT \
  --apply-optimizer $OPTIMIZER_OUTPUT \
  --safety-check --verify

# Compare what changed
bin/me7-bin-compare --xdf $XDF --old $CURRENT --new $NEXT --format json
```

### Write Traceability

```bash
# Record version
bin/me7-registry register --db $DB \
  --version $NEW_VERSION --bin $NEXT --xdf $XDF \
  --parent $CURRENT_VERSION --notes "Applied optimizer KFLDRL/KFLDIMX + KFZWOP timing"

# Record decisions
bin/me7-registry record --db $DB \
  --version $NEW_VERSION --map KFLDRL \
  --action "apply_optimizer" --reason "Pressure error 12% → target <10%" \
  --confidence 85 --agent copilot
```

## Phase 6: CONVERGENCE CHECK

**Goal:** Track progress and determine if tuning is complete.

```bash
# Record metrics after analysis
bin/me7-registry converge --db $DB --version $NEW_VERSION \
  --knock $KNOCK --pressure-error $PERR --ltft $LTFT \
  --torque-intervention $TORQUE --wot-lambda-min $LAMBDA \
  --boost-stability $STABILITY --maf-error $MAF

# Check status
bin/me7-registry status --db $DB --format json
```

### Convergence Criteria

| Score | Status | Action |
|-------|--------|--------|
| 90+ | CONVERGED | Declare success, no more iterations |
| 70-89 | PROGRESSING | Continue with identified bottleneck |
| 50-69 | DEVELOPING | Multiple issues, prioritize by safety |
| <50 | DIVERGENT | Step back, re-evaluate approach |

### State Machine

```
BASELINE → ANALYZING → DIAGNOSING → SIMULATING → VALIDATING → WRITING → CONVERGING
    ↑                                                                         ↓
    └──────────── (if not converged, loop back to ANALYZING) ────────────────┘
```

## Calibration Order

Maps must be calibrated in this order to avoid cascading errors:

1. **KRKTE** — Primary fueling constant (sets injector baseline)
2. **MLHFM** — MAF sensor linearization (corrects airflow measurement)
3. **KFMIOP/KFMIRL** — Torque/load model (sets up the torque structure)
4. **KFZWOP** — Optimal ignition timing (before boost control)
5. **KFLAMKRL** — Full-load enrichment (coupled with KFZWOP)
6. **KFLDRL/KFLDIMX** — Boost control (feedforward + I-term limit)
7. **KFPBRK** — VE model correction (fine-tuning after boost stabilizes)

## Hardware Awareness

| Component | Maps Affected | Detection |
|-----------|--------------|-----------|
| Water injection | KFZWOP, KFZWWLNM, KFLAMKRL | High IAT at WOT + excessive knock |
| Intercooler | KFZWOP, KFZWWLNM, KFTARX | IAT > 50°C sustained |
| Injectors | KRKTE, MLHFM | Injector duty > 85% |
| Turbos | KFLDRL, KFLDIMX | WGDC > 90% without target pressure |
| 5180 MAP sensor | Load axis extension | Load values > 200% |
| Ported heads | KFPBRK, KFMIOP/KFMIRL | Higher VE at high RPM |

## Example Agent Session

```
Agent: Running preflight check on v3.2.2...
  ✓ Binary passes safety (0 errors, 141 warnings)
  ✓ 60 logs available, 49 WOT events
  ✓ LTFT within ±8%, no lean excursions

Agent: Running optimizer analysis...
  WOT entries: 49
  Pressure error: 8.5% (target: <10%)
  Knock: 2.1° (target: <3°)
  Chain: 72% on target, 18% torque capped, 10% boost shortfall

Agent: Diagnosis:
  1. Torque intervention at 18% → check KFMIOP/KFMIRL
  2. Boost shortfall at 10% → apply KFLDRL corrections
  3. Knock at 2.1° → within tolerance but monitor

Agent: Simulating proposed KFLDRL changes...
  PID simulation: no oscillation, 3.1% overshoot, 420ms convergence
  ✓ Within safety limits

Agent: Writing v3.2.3...
  Applied KFLDRL (12 cells modified, avg change 4.2%)
  Applied KFLDIMX (8 cells modified)
  Safety check: ✓ PASSED
  Verified: 48 bytes changed

Agent: Convergence update:
  v3.2.3: 78.5/100 (↑5.7 from v3.2.2)
  Status: PROGRESSING
  Bottleneck: torque intervention (18% → target <15%)
  Estimate: 1-2 more iterations
```
