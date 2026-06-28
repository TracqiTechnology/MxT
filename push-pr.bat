@echo off
cd /d D:\MxT
echo === Git Status ===
git status --short
echo.
echo === Creating branch ===
git checkout -b fix/med9-kfmiop-axis-swap-binwriter-columndir
echo.
echo === Staging changes ===
git add src/main/kotlin/data/writer/BinWriter.kt
git add src/main/kotlin/ui/screens/kfmiop/KfmiopScreen.kt
git add src/main/kotlin/ui/screens/configuration/ConfigurationScreen.kt
echo.
echo === Committing ===
git commit -m "fix(MED9): correct KFMIOP axis convention and BinWriter COLUMN_DIR write

KFMIOP axis swap (KfmiopScreen.kt):
- MED9 KFMIOP stores xAxis=RPM (nmot_w, 16pts), yAxis=load (rl_w, 11pts),
  which is the opposite of what Kfmiop.calculateKfmiop() expects
  (xAxis=load, yAxis=RPM). This caused garbage values in the calculator
  (MAP Sensor Max showing ~64729 mbar, Rescaled Load Axis showing RPM values).
- Add normalizeKfmiopAxes(): swaps xAxis/yAxis and transposes zAxis when
  xAxis.last() > 500 (RPM heuristic). ME7 passes through unchanged.
- Add denormalizeKfmiopAxes(): restores original binary layout before BinWriter.write.
- All calculations, display, and editedYAxis now use normalizedKfmiop.

BinWriter COLUMN_DIR fix (BinWriter.kt):
- z-data was always written row-by-row regardless of isColumnMajor.
  For COLUMN_DIR maps (like MED9 KFMIOP) this broke the round-trip
  since BinParser reads column-major and transposes to row-major Map3d.
- Fixed: when axis.isColumnMajor, iterate column-by-column so binary
  layout matches what BinParser expects on next read.

ConfigurationScreen.kt — MED9.1 map name corrections:
- KFPBRK, KFPBRKNW, KFPRG: restricted to ME7 only. These VE-model maps
  (combustion pressure correction, residual gas) do not exist in MED9.1.
- KFFWTBR: restricted to ME7. Added new KFWTBR entry for MED9 — same
  function (charge temperature blending factor Tans/Tmot), different name
  prefix in MED9.1 firmware (confirmed via A2L CHARACTERISTIC search).
  Both entries share KffwtbrPreferences."
echo.
echo === Pushing ===
git push -u origin fix/med9-kfmiop-axis-swap-binwriter-columndir
echo.
echo === Done. Create PR at: https://github.com/TracqiTechnology/MxT/compare/fix/med9-kfmiop-axis-swap-binwriter-columndir ===
pause
