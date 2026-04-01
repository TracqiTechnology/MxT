package domain.model.presets

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PresetsTest {

    // ── Engine presets ──────────────────────────────────────────

    @Test
    fun `engine presets list is non-empty`() {
        assertTrue(EnginePresets.all.isNotEmpty())
    }

    @Test
    fun `all engine presets have positive displacement`() {
        EnginePresets.all.forEach {
            assertTrue(it.displacementCc > 0, "${it.name} displacement must be > 0")
        }
    }

    @Test
    fun `all engine presets have valid cylinder count`() {
        EnginePresets.all.forEach {
            assertTrue(it.cylinderCount in 1..16, "${it.name} cylinders must be 1–16")
        }
    }

    @Test
    fun `engine preset names are unique`() {
        val names = EnginePresets.all.map { it.name }
        assertEquals(names.size, names.distinct().size)
    }

    // ── Fuel presets ────────────────────────────────────────────

    @Test
    fun `fuel presets list is non-empty`() {
        assertTrue(FuelPresets.all.isNotEmpty())
    }

    @Test
    fun `all fuel presets have positive density`() {
        FuelPresets.all.forEach {
            assertTrue(it.densityGPerCc > 0, "${it.name} density must be > 0")
        }
    }

    @Test
    fun `all fuel presets have positive stoich AFR`() {
        FuelPresets.all.forEach {
            assertTrue(it.stoichAfr > 0, "${it.name} AFR must be > 0")
        }
    }

    @Test
    fun `fuel blend E0 matches pure gasoline`() {
        val e0Blend = FuelPresets.blend(0.0)
        assertEquals(FuelPresets.E0.densityGPerCc, e0Blend.densityGPerCc, 1e-9)
        assertEquals(FuelPresets.E0.stoichAfr, e0Blend.stoichAfr, 1e-9)
    }

    @Test
    fun `fuel blend E100 matches pure ethanol`() {
        val e100Blend = FuelPresets.blend(100.0)
        assertEquals(FuelPresets.E100.densityGPerCc, e100Blend.densityGPerCc, 1e-9)
        assertEquals(FuelPresets.E100.stoichAfr, e100Blend.stoichAfr, 1e-9)
    }

    @Test
    fun `fuel blend E50 is between E0 and E100`() {
        val e50 = FuelPresets.blend(50.0)
        assertTrue(e50.densityGPerCc > FuelPresets.E0.densityGPerCc)
        assertTrue(e50.densityGPerCc < FuelPresets.E100.densityGPerCc)
        assertTrue(e50.stoichAfr < FuelPresets.E0.stoichAfr)
        assertTrue(e50.stoichAfr > FuelPresets.E100.stoichAfr)
        assertEquals("E50", e50.name)
    }

    // ── Injector presets ────────────────────────────────────────

    @Test
    fun `injector presets list is non-empty`() {
        assertTrue(InjectorPresets.all.isNotEmpty())
    }

    @Test
    fun `all injector presets have positive flow rate`() {
        InjectorPresets.all.forEach {
            assertTrue(it.flowRateCcPerMin > 0, "${it.name} flow rate must be > 0")
        }
    }

    @Test
    fun `all injector presets have positive fuel pressure`() {
        InjectorPresets.all.forEach {
            assertTrue(it.fuelPressureBar > 0, "${it.name} fuel pressure must be > 0")
        }
    }

    @Test
    fun `GDI injectors have higher pressure than PFI`() {
        val gdiMin = InjectorPresets.byType(InjectorType.GDI).minOf { it.fuelPressureBar }
        val pfiMax = InjectorPresets.byType(InjectorType.PFI).maxOf { it.fuelPressureBar }
        assertTrue(gdiMin > pfiMax, "GDI pressure ($gdiMin) must exceed PFI pressure ($pfiMax)")
    }

    @Test
    fun `byEngine returns only presets for that engine`() {
        val ea855 = InjectorPresets.byEngine("EA855 2.5T")
        assertTrue(ea855.isNotEmpty())
        ea855.forEach { assertEquals("EA855 2.5T", it.engine) }
    }

    @Test
    fun `byType returns only presets of that type`() {
        val gdi = InjectorPresets.byType(InjectorType.GDI)
        val pfi = InjectorPresets.byType(InjectorType.PFI)
        assertTrue(gdi.isNotEmpty())
        assertTrue(pfi.isNotEmpty())
        gdi.forEach { assertEquals(InjectorType.GDI, it.type) }
        pfi.forEach { assertEquals(InjectorType.PFI, it.type) }
    }

    @Test
    fun `byEngineAndType filters correctly`() {
        val result = InjectorPresets.byEngineAndType("EA855 2.5T", InjectorType.GDI)
        assertTrue(result.isNotEmpty())
        result.forEach {
            assertEquals("EA855 2.5T", it.engine)
            assertEquals(InjectorType.GDI, it.type)
        }
    }

    @Test
    fun `all injector presets have engine and type set`() {
        InjectorPresets.all.forEach {
            assertTrue(it.engine.isNotBlank(), "${it.name} must have an engine")
        }
    }

    // ── Injector preset correctness (Phase 2 — customer feedback) ──

    @Test
    fun `DAZA GDI has smaller flow rate than DNWA GDI`() {
        // DAZA (FL 400PS) has smaller DI injectors than DNWA (PFL 367PS).
        // Smaller injector → higher KRKATE (0.0322 > 0.0260).
        assertTrue(
            InjectorPresets.DAZA_GDI.flowRateCcPerMin < InjectorPresets.DNWA_GDI.flowRateCcPerMin,
            "DAZA GDI (${InjectorPresets.DAZA_GDI.flowRateCcPerMin}) must have lower flow than DNWA GDI (${InjectorPresets.DNWA_GDI.flowRateCcPerMin})"
        )
    }

    @Test
    fun `GDI presets use 240 bar nominal HPFP pressure`() {
        InjectorPresets.byType(InjectorType.GDI).forEach {
            assertEquals(240.0, it.fuelPressureBar, "${it.name} must use 240 bar (HPFP nominal)")
        }
    }

    @Test
    fun `PFI presets use 4 bar absolute pressure`() {
        // Port fuel rail runs at 3 bar gauge = 4 bar absolute
        InjectorPresets.byType(InjectorType.PFI).forEach {
            assertEquals(4.0, it.fuelPressureBar, "${it.name} must use 4.0 bar (3 bar gauge + 1 bar atm)")
        }
    }

    @Test
    fun `DAZA and DNWA PFI have identical flow rate — same part number`() {
        // Customer confirms DAZA and DNWA port injectors are identical parts.
        assertEquals(
            InjectorPresets.DAZA_PFI.flowRateCcPerMin,
            InjectorPresets.DNWA_PFI.flowRateCcPerMin,
            "DAZA and DNWA PFI must have identical flow (same part)"
        )
    }

    @Test
    fun `DualInjectionConfig defaults to 240 bar DI pressure`() {
        val config = data.profile.DualInjectionConfig()
        assertEquals(240.0, config.directInjectorFuelPressureBar,
            "Default DI pressure must be 240 bar (HPFP nominal), not 200")
    }

    // ── Fuel blend with custom endpoints ─────────────────────────

    @Test
    fun `custom blend endpoints produce different results`() {
        val customE0 = FuelPreset("Custom E0", 0.75, 14.5)
        val customE100 = FuelPreset("Custom E100", 0.80, 8.5)
        val defaultBlend = FuelPresets.blend(50.0)
        val customBlend = FuelPresets.blend(50.0, customE0, customE100)
        assertNotEquals(defaultBlend.densityGPerCc, customBlend.densityGPerCc)
        assertNotEquals(defaultBlend.stoichAfr, customBlend.stoichAfr)
    }

    @Test
    fun `custom blend at 0 pct returns e0 values`() {
        val customE0 = FuelPreset("Custom E0", 0.75, 14.5)
        val customE100 = FuelPreset("Custom E100", 0.80, 8.5)
        val result = FuelPresets.blend(0.0, customE0, customE100)
        assertEquals(0.75, result.densityGPerCc, 1e-9)
        assertEquals(14.5, result.stoichAfr, 1e-9)
    }

    @Test
    fun `custom blend at 100 pct returns e100 values`() {
        val customE0 = FuelPreset("Custom E0", 0.75, 14.5)
        val customE100 = FuelPreset("Custom E100", 0.80, 8.5)
        val result = FuelPresets.blend(100.0, customE0, customE100)
        assertEquals(0.80, result.densityGPerCc, 1e-9)
        assertEquals(8.5, result.stoichAfr, 1e-9)
    }
}
