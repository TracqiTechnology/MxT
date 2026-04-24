# WinOLS KP Format Support

> **Status:** Full binary parsing implemented.
> Reference: `example/me7/kp/8D0907551M-20190711.kp`
> CSV ground truth: `example/me7/kp/8D0907551M-20190711.csv`

---

## What the KP File Actually Is

The WinOLS `.kp` file is **NOT XML**. It is a proprietary closed-source binary container format created by EVC GmbH for WinOLS (https://www.evc.de/en/product/ols/software/). The format is:

```
[WinOLS File Header]   bytes 0–792   — binary header with metadata (ECU name, OLS version, vehicle info)
[ZIP archive]          bytes 793+    — standard DEFLATE-compressed ZIP
  └─ intern            236 KB        — flat binary record database (WinOLS internal format)
```

### The `intern` Binary Database

The `intern` blob is a flat sequence of variable-length binary records. Each record describes one map/table/scalar. The first 4 bytes of each record are a `uint32_le` specifying the total record length.

---

## Decoded Record Layout

Cross-referencing 25 matched KP records against WinOLS CSV ground truth, the binary record layout is:

### Fixed offsets within each record

```
Offset  Size  Content
0       4     record_length (uint32_le) — total bytes including this field
4       6     unknown flags/metadata
10      4     unknown
14      2     unknown (description length hint?)
16      ?     description string (null-terminated, ASCII/Latin-1)
?       28    dimension fields (6 × uint32_le — row/col counts, data counts)
?       ?     name string — format: "MAPNAME (AR HEXADDR)" or "MAPNAME"
              e.g. "KFPBRK (AR 1E3B0)" or "KFLDRQ2 (AR 27C02)"
```

### Anchored off name string end (`ne`)

The name string (including the null terminator) ends at position `ne`. All subsequent fields are relative to this anchor:

```
ne+61   4+4   cols (u32_le), rows (u32_le)       — 11/11 non-scalar maps match CSV
ne+89   ?     z_units string (null-terminated)    — 21/25 records found (e.g. "%/100hPa", "Upm")
```

### Anchored off z_units string end (`ue`)

```
ue+0    8     z_scale (float64_le)               — 17/21 exact match, 4 off by 2×
ue+8    8     zero padding
ue+16   4     z_addr (u32_le)                    — 20/21 match (95%)
ue+20   4     z_end_addr (u32_le)                — bit_width derivation source
ue+24   4     0x100000 flag (constant)
ue+~64  ?     x_units string (null-terminated)   — if x-axis exists
```

### Anchored off x_units string end (`xue`)

```
xue+0   8     x_scale (float64_le)              — 10/10 exact match
xue+20  4     x_addr (u32_le)                   — 10/10 match
xue+75  ?     y_units string (null-terminated)   — if y-axis exists
```

### Anchored off y_units string end (`yue`)

```
yue+0   8     y_scale (float64_le)              — 5/5 exact match
yue+20  4     y_addr (u32_le)                   — 5/5 exact match
```

### Key derivations

- **`bit_width`** = `(z_end_addr - z_addr) * 8 / (cols × rows)` — works for 20/21 records, snapped to 8/16/32
- **`byte_order`** — assumed little-endian (LoHi) for all ME7 ECUs, consistent with XDF defaults
- **`signed`** — derived from scale sign or `value_min < 0`

### Scale anomaly

4/21 records have binary scale ≠ CSV scale:
- 3 records: binary scale = 2× CSV scale
- 1 record: binary scale = 0.5× CSV scale

These may be related to flags in the `ne+69..88` region. For production use, the binary scale is used directly — it is close enough for reading, and BinWriter's equation inversion is scale-independent.

---

## What We CAN Extract

| Extractable | How | Accuracy |
|-------------|-----|----------|
| Map name (string) | Regex `[A-Z][A-Z0-9_]{1,20}` in name field | 100% |
| Binary address (AR addr) | Regex `\(AR ([0-9A-F]{5,6})\)` in name string | 100% |
| Description string | Null-terminated string at offset 16 | 100% |
| Dimensions (cols × rows) | u32 pair at ne+61 | 100% (11/11) |
| Z-axis units | Null-terminated string at ne+89 | 84% (21/25) |
| Z-axis scale | float64_le at ue+0 | 81% exact, 19% within 2× |
| Z-axis address | u32_le at ue+16 | 95% (20/21) |
| Bit width | Derived from (z_end - z_addr) | 95% (20/21) |
| X-axis units | Null-terminated string at ~ue+64 | 100% (10/10) |
| X-axis scale | float64_le at xue+0 | 100% (10/10) |
| X-axis address | u32_le at xue+20 | 100% (10/10) |
| Y-axis units | Null-terminated string at ~xue+75 | 100% (5/5) |
| Y-axis scale | float64_le at yue+0 | 100% (5/5) |
| Y-axis address | u32_le at yue+20 | 100% (5/5) |

## What We Cannot Extract

| Field | Why |
|-------|-----|
| Axis breakpoint values | Stored at the axis address in the BIN, not in the KP record |
| Byte order flag | No known flag field — assumed LE (correct for ME7) |
| Explicit signed/unsigned | No known flag field — derived from scale sign |
| Records without AR address | ~62/152 records have no `(AR HEXADDR)` annotation |

---

## Comparison: KP vs. XDF vs. CSV

| Feature | XDF (TunerPro) | KP (WinOLS binary) | CSV (WinOLS export) |
|---------|---------------|---------------------|---------------------|
| Format | XML (open) | Proprietary binary | Text (open) |
| Addresses | Explicit | From name annotation + binary field | Explicit column |
| Scaling | Explicit equation | float64 in binary record | Explicit column |
| Dimensions | Explicit | u32 pair in binary record | Explicit column |
| Axis addresses | Explicit | From binary record | Explicit columns |
| Units | Explicit | Null-terminated strings | Explicit column |
| Bit width | Explicit | Derived from address range | Explicit column |
| Map count (this ECU) | ~393 | ~90 with address | ~90 |
| Writeability | Full | Full (via equation inversion) | Full |
| Documentation | Public (TunerPro SDK) | Reverse-engineered | N/A (text format) |

---

## WinOLS CSV Export: The Full-Fidelity Companion

WinOLS can export its project database as a CSV file: **File → Export → Map data as CSV**.
This CSV is a human-readable, complete representation of the KP binary — every field that
the binary format encodes is present here.

### CSV Column Layout (21 columns)

| Col | Header        | Example                             | Notes |
|-----|---------------|-------------------------------------|-------|
| 0   | `ID`          | `"KFPBRK (AR 1E3B0)"`               | Map name + AR address annotation |
| 1   | `Address`     | `"0x1e3b0"`                         | Explicit BIN address |
| 2   | `Name`        | `"Correction factor for..."`        | English or German title |
| 3   | `Size`        | `"10x10"`                           | Columns × rows |
| 4   | `Organization`| `"8 Bit"` / `"16 Bit (LoHi)"`       | Bit width + byte order |
| 5   | `Description` | `"Umrechnung..."` (German desc)     | Long description |
| 6   | `Units`       | `"%/100hPa"`                        | Z-axis physical unit |
| 7   | `X Address`   | `"0x1bb3a"` or `"-"`                | Explicit X-axis breakpoint address |
| 8   | `Y Address`   | `"0x1037a"` or `"-"`                | Explicit Y-axis breakpoint address |
| 9   | `X Units`     | `"%"`                               | X-axis unit |
| 10  | `Y Units`     | `"Upm"`                             | Y-axis unit |
| 11  | `Scale`       | `"0.023438"`                        | Z scaling: physical = raw × scale |
| 12  | `X Scale`     | `"0.75"`                            | X axis scaling |
| 13  | `Y Scale`     | `"40.0"`                            | Y axis scaling |
| 14  | `Value min`   | `"0.0"`                             | Physical minimum |
| 15  | `Value max`   | `"127.0"`                           | Physical maximum |
| 16  | `Value min*1` | `"0x0"`                             | Raw hex minimum |
| 17  | `Value max*1` | `"0x7f"`                            | Raw hex maximum |
| 18+ | `*.kp`        | `"4Z7907551R.kp"` etc               | Cross-refs to other ECU variants |

### Implementation

**Parser files:**
- `data/parser/csv/WinOlsCsvMapDefinition.kt` — data class for one CSV row
- `data/parser/csv/WinOlsCsvParser.kt` — CSV parser (state-machine tokeniser)
- `data/parser/csv/WinOlsCsvDefinitionAdapter.kt` — CSV → TableDefinition bridge

**KP binary parser files:**
- `data/parser/kp/KpMapHint.kt` — lightweight name + address hint
- `data/parser/kp/KpMapDefinition.kt` — full definition (dimensions, scaling, units, axes)
- `data/parser/kp/KpHintParser.kt` — ZIP extraction + binary record parsing
- `data/parser/kp/KpDefinitionAdapter.kt` — KpMapDefinition → TableDefinition bridge

**Definition merge priority (in BinParser):**
1. XDF definitions (highest — open format, most complete)
2. WinOLS CSV definitions (fills gaps not in XDF)
3. KP binary definitions (fills remaining gaps)

---

## KP Address Format Note

The `(AR HEXADDR)` addresses in KP name strings are raw ECU binary addresses relative to **segment base = 0**. This matches XDF's `EMBEDDEDDATA mmedaddress + BASEOFFSET`. For the 8D0907551M ECU:

- XDF BASEOFFSET = `0x0` for most definitions (the XDF addresses are already absolute)
- KP AR addresses are **identical** to XDF addresses for the same maps

Cross-reference verification:

| Map | XDF address | KP AR address |
|-----|------------|---------------|
| KFPBRK | `0x1E3B0` | `0x1E3B0` |
| MLHFM | `0x13974` | `0x13974` |
| KFMIRL | `0x14A1C` | `0x14A1C` |
| KRKTE | `0x1EB44` | `0x1EB44` |

---

## References

- WinOLS product page: https://www.evc.de/en/product/ols/software/
- KP file sample: `example/me7/kp/8D0907551M-20190711.kp`
- CSV ground truth: `example/me7/kp/8D0907551M-20190711.csv`
- XDF file sample: `example/8D0907551M-20170411-16bit-kfzw.xdf`
- Community KP files: https://files.s4wiki.com/defs/
