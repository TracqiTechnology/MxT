# MED17 real-log acceptance goldens

`real-log-acceptance-v1.json` records the complete PFI split, fuel-trim, and
LDRPID outputs for every public MED17 CSV fixture under `src/test/resources/logs`.
The expected values come from `tools/golden/med17_oracle.py`, an independent
Python implementation that does not import or invoke MxT production code.

The normal test task only reads this file. It never regenerates or updates
expected values. To propose an intentional behavior change, regenerate it
explicitly from the repository root:

```shell
python3 tools/golden/med17_oracle.py \
  --logs src/test/resources/logs \
  --output src/test/resources/golden/med17/real-log-acceptance-v1.json
```

Review the oracle change, input hashes, and resulting JSON diff together.
Changing production code and mechanically accepting a new golden in the same
review is not sufficient evidence that the new customer behavior is correct.
