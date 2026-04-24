package domain.model.krkte

import kotlin.test.*

/**
 * Tests for [KrkteCalculator] — injector constant calculation from first principles.
 */
class KrkteCalculatorTest {

    @Test
    fun `calculateKrkte with RS3 2_5T parameters produces reasonable value`() {
        // RS3 2.5T profile: 5 cylinders, 2.48L → 0.496L per cylinder = 0.496 dm³
        // Port injectors: 220 cc/min @ 4.0 bar → flow rate in cc/min
        // Gasoline: 0.755 g/cm³, Stoich: 14.7
        val krkte = KrkteCalculator.calculateKrkte(
            airDensityGramsPerDecimetersCubed = 1.293,     // Standard air density
            cylinderDisplacementDecimetersCubed = 0.496,   // 496 cc = 0.496 dm³
            fuelInjectorSizeCubicCentimeters = 220.0,      // 220 cc/min PFI
            gasolineGramsPerCubicCentimeter = 0.755,
            stoichiometricAirFuelRatio = 14.7
        )

        // KRKTE is ms/% — typical values for port injection are 0.1–0.5 ms/%
        assertTrue(krkte > 0.0, "KRKTE should be positive")
        assertTrue(krkte in 0.01..2.0, "KRKTE $krkte should be in reasonable range 0.01–2.0 ms/%")
    }

    @Test
    fun `calculateKrkte larger injector produces smaller constant`() {
        // Larger injector flows more fuel per ms → needs less time per load % → smaller KRKTE
        val smallInjector = KrkteCalculator.calculateKrkte(
            airDensityGramsPerDecimetersCubed = 1.293,
            cylinderDisplacementDecimetersCubed = 0.496,
            fuelInjectorSizeCubicCentimeters = 220.0,
            gasolineGramsPerCubicCentimeter = 0.755,
            stoichiometricAirFuelRatio = 14.7
        )

        val largeInjector = KrkteCalculator.calculateKrkte(
            airDensityGramsPerDecimetersCubed = 1.293,
            cylinderDisplacementDecimetersCubed = 0.496,
            fuelInjectorSizeCubicCentimeters = 440.0,
            gasolineGramsPerCubicCentimeter = 0.755,
            stoichiometricAirFuelRatio = 14.7
        )

        assertTrue(largeInjector < smallInjector,
            "Larger injector ($largeInjector) should produce smaller KRKTE than smaller injector ($smallInjector)")
    }

    @Test
    fun `calculateKrkte larger displacement produces larger constant`() {
        // Larger cylinder needs more fuel for the same load % → larger KRKTE
        val small = KrkteCalculator.calculateKrkte(
            airDensityGramsPerDecimetersCubed = 1.293,
            cylinderDisplacementDecimetersCubed = 0.400,
            fuelInjectorSizeCubicCentimeters = 220.0,
            gasolineGramsPerCubicCentimeter = 0.755,
            stoichiometricAirFuelRatio = 14.7
        )

        val large = KrkteCalculator.calculateKrkte(
            airDensityGramsPerDecimetersCubed = 1.293,
            cylinderDisplacementDecimetersCubed = 0.600,
            fuelInjectorSizeCubicCentimeters = 220.0,
            gasolineGramsPerCubicCentimeter = 0.755,
            stoichiometricAirFuelRatio = 14.7
        )

        assertTrue(large > small,
            "Larger displacement ($large) should produce larger KRKTE than smaller ($small)")
    }

    @Test
    fun `calculateKrkte is deterministic`() {
        val result1 = KrkteCalculator.calculateKrkte(1.293, 0.496, 220.0, 0.755, 14.7)
        val result2 = KrkteCalculator.calculateKrkte(1.293, 0.496, 220.0, 0.755, 14.7)
        assertEquals(result1, result2, 0.0, "Same inputs should produce identical output")
    }

    @Test
    fun `calculateKrkte matches Funktionsrahmen factory KRKATE for EA855 GDI`() {
        // Funktionsrahmen MED17.1.62 states KRKATE = 0.0367 ms/%
        // Formula: KRKATE = (rho0Luft * Vhzyl) / (100 * Lst * Normmk * 1.05 * Qstat)
        // Where Qstat = 679.3 g/min at n-heptane → 944.7 cc/min at gasoline
        // (injectorSize_cc = Qstat_heptane * 1.05 / gasolineDensity)
        //
        // Our formula uses cc/min at gasoline + density instead of the 1.05 factor.
        // They are mathematically equivalent — this test proves it.
        //
        // NOTE on circular reasoning: This test matches the FR value because we back-calculated
        // the cc/min input from the FR's heptane flow using 0.755 g/cc. If Bosch actually used
        // 0.7135 g/cc internally, the cc/min input would differ and so would the result.
        // This test validates formula consistency, not the correctness of the density assumption.
        // See also: `calculateKrkte with 0_7135 density convention` for the alternative.
        val krkate = KrkteCalculator.calculateKrkte(
            airDensityGramsPerDecimetersCubed = 1.293,     // rho0Luft
            cylinderDisplacementDecimetersCubed = 0.496,   // Vhzyl (2.48L / 5 cyl)
            fuelInjectorSizeCubicCentimeters = 944.7,      // Qstat_heptane * 1.05 / 0.755
            gasolineGramsPerCubicCentimeter = 0.755,        // rho0KS from Funktionsrahmen BGKV section
            stoichiometricAirFuelRatio = 14.7               // Lst
        )

        // Funktionsrahmen reference: 0.0367 ms/%
        // Allow ±0.5% tolerance for floating point rounding (Normmk = 1/60000 vs 1.6667e-5)
        assertEquals(0.0367, krkate, 0.0002,
            "KRKATE should match Funktionsrahmen factory value of 0.0367 ms/%")
    }

    @Test
    fun `calculateKrkte with 0_7135 density convention produces different result`() {
        // The ME7.5 guide and FR KRKATE section derive the 1.05 valve correction factor
        // from 0.7135 g/cc (petrol density at 15°C): 0.7135 / 0.6795 ≈ 1.05
        // If we use 0.7135 instead of 0.755, the same heptane Qstat converts to a
        // different cc/min value and produces a DIFFERENT KRKATE.
        //
        // This is the "conservative" approach: errors lean rich rather than lean,
        // which is safer for modified injector setups.
        val qstatHeptane = 679.3  // g/min at n-heptane (from FR)
        val injCcAt7135 = qstatHeptane * 1.05 / 0.7135  // ≈ 999.5 cc/min

        val krkate = KrkteCalculator.calculateKrkte(
            airDensityGramsPerDecimetersCubed = 1.293,
            cylinderDisplacementDecimetersCubed = 0.496,
            fuelInjectorSizeCubicCentimeters = injCcAt7135,
            gasolineGramsPerCubicCentimeter = 0.7135,
            stoichiometricAirFuelRatio = 14.7
        )

        // With 0.7135 density, KRKATE should be SMALLER than with 0.755
        // (higher effective injector flow / lower density → less on-time per load %)
        val krkateAt755 = KrkteCalculator.calculateKrkte(1.293, 0.496, 944.7, 0.755, 14.7)
        assertTrue(krkate < krkateAt755,
            "KRKATE at 0.7135 ($krkate) should be smaller than at 0.755 ($krkateAt755)")

        // Verify the formula is still self-consistent at this density
        val expected = (1.293 * 0.496) / (100.0 * 1.6667e-5 * 14.7 * injCcAt7135 * 0.7135)
        assertEquals(expected, krkate, 1e-10,
            "Formula should still be self-consistent at 0.7135 density")
    }

    @Test
    fun `calculateKrkte matches ME7 magic constant 50_2624`() {
        // ME7 Funktionsrahmen: KRKTE = 50.2624 * Vhzyl / Qstat
        // Where Qstat is in g/min (mass flow at n-heptane), NOT cc/min.
        // For a 220 cc/min (at gasoline) PFI injector:
        //   Qstat_mass = injSize_cc * gasDensity / 1.05 = 220 * 0.755 / 1.05 = 158.1 g/min
        // Our formula uses cc/min at gasoline + density directly, which is equivalent.
        val krkte = KrkteCalculator.calculateKrkte(
            airDensityGramsPerDecimetersCubed = 1.293,
            cylinderDisplacementDecimetersCubed = 0.496,
            fuelInjectorSizeCubicCentimeters = 220.0,
            gasolineGramsPerCubicCentimeter = 0.755,
            stoichiometricAirFuelRatio = 14.7
        )

        // Convert cc/min at gasoline to Qstat in g/min at n-heptane for magic constant
        val qstatMass = 220.0 * 0.755 / 1.05
        val me7Expected = 50.2624 * 0.496 / qstatMass
        assertEquals(me7Expected, krkte, 0.001,
            "KRKTE should match ME7 magic constant formula: 50.2624 * Vhzyl / Qstat_mass")
    }
}
