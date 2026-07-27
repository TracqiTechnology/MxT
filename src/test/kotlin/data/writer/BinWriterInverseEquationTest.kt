package data.writer

import kotlin.test.*

/**
 * Unit tests for [BinWriter.buildInverseEquation].
 *
 * The inverse equation converts engineering-unit values back to raw integers
 * for writing to the BIN file.  Getting this wrong means writing garbage to
 * the ECU — these tests verify every pattern the builder handles.
 */
class BinWriterInverseEquationTest {

    // ── Pattern: A * X ──────────────────────────────────────────────────

    @Test
    fun `A times X produces X div A`() {
        assertEquals("X / 0.023438", BinWriter.buildInverseEquation("0.023438 * X", "X"))
    }

    @Test
    fun `integer A times X`() {
        assertEquals("X / 10.0", BinWriter.buildInverseEquation("10.0 * X", "X"))
    }

    @Test
    fun `negative A times X`() {
        assertEquals("X / -1.5", BinWriter.buildInverseEquation("-1.5 * X", "X"))
    }

    // ── Pattern: A * X + B / A * X - B ──────────────────────────────────

    @Test
    fun `A times X plus B`() {
        assertEquals("(X - 18.0) / 0.75", BinWriter.buildInverseEquation("0.75 * X + 18.0", "X"))
    }

    @Test
    fun `A times X minus B`() {
        assertEquals("(X - -18.0) / 0.75", BinWriter.buildInverseEquation("0.75 * X - 18.0", "X"))
    }

    // ── Pattern: X * A ──────────────────────────────────────────────────

    @Test
    fun `X times A produces X div A`() {
        assertEquals("X / 0.01", BinWriter.buildInverseEquation("X * 0.01", "X"))
    }

    @Test
    fun `X times A plus B`() {
        assertEquals("(X - 5.0) / 2.0", BinWriter.buildInverseEquation("X * 2.0 + 5.0", "X"))
    }

    @Test
    fun `X times A minus B`() {
        assertEquals("(X - -5.0) / 2.0", BinWriter.buildInverseEquation("X * 2.0 - 5.0", "X"))
    }

    // ── Pattern: X + B / X - B ──────────────────────────────────────────

    @Test
    fun `X plus B produces X minus B`() {
        assertEquals("X - 100.0", BinWriter.buildInverseEquation("X + 100.0", "X"))
    }

    @Test
    fun `X minus B produces X plus B`() {
        assertEquals("X + 48.0", BinWriter.buildInverseEquation("X - 48.0", "X"))
    }

    // ── Pattern: X / A ──────────────────────────────────────────────────

    @Test
    fun `X div A produces X times A`() {
        assertEquals("X * 128.0", BinWriter.buildInverseEquation("X / 128.0", "X"))
    }

    @Test
    fun `X div small float`() {
        assertEquals("X * 0.01", BinWriter.buildInverseEquation("X / 0.01", "X"))
    }

    // ── Identity / passthrough ──────────────────────────────────────────

    @Test
    fun `identity equation returns X`() {
        assertEquals("X", BinWriter.buildInverseEquation("X", "X"))
    }

    @Test
    fun `blank equation returns X`() {
        assertEquals("X", BinWriter.buildInverseEquation("", "X"))
    }

    // ── Non-standard varId ──────────────────────────────────────────────

    @Test
    fun `non-standard varId is normalised to X`() {
        // XDF files sometimes use Z or other var names
        assertEquals("X / 0.75", BinWriter.buildInverseEquation("0.75 * Z", "Z"))
    }

    @Test
    fun `varId named RAW is normalised`() {
        assertEquals("X / 10.0", BinWriter.buildInverseEquation("10.0 * RAW", "RAW"))
    }

    // ── Scientific notation ─────────────────────────────────────────────

    @Test
    fun `scientific notation A times X`() {
        // 1.5e-3 is parsed to 0.0015 by the regex
        assertEquals("X / 0.0015", BinWriter.buildInverseEquation("1.5e-3 * X", "X"))
    }

    @Test
    fun `scientific notation X div A`() {
        assertEquals("X * 1000.0", BinWriter.buildInverseEquation("X / 1e3", "X"))
    }

    // ── Composite affine / unsupported non-linear ───────────────────────

    @Test
    fun `composite affine expression is inverted`() {
        val inverse = BinWriter.buildInverseEquation("(X + 40) / 0.75", "X")
        assertTrue(inverse.startsWith("(X - "))
        assertTrue(inverse.contains(") / 1.333333"))
    }

    @Test
    fun `multiply after division affine expression is inverted`() {
        val inverse = BinWriter.buildInverseEquation("X / 16384 * 100", "X")
        assertTrue(inverse.startsWith("(X - 0.0) / "))
    }

    @Test
    fun `expression with nonlinear function fails closed`() {
        assertFailsWith<IllegalArgumentException> {
            BinWriter.buildInverseEquation("Math.sqrt(X)", "X")
        }
    }

    // ── ME7 real-world equations ────────────────────────────────────────

    @Test
    fun `KFZW typical equation 0_75 times X minus 48`() {
        // KFZW: 0.75 * X - 48  → inverse should be (X + 48) / 0.75
        assertEquals("(X - -48.0) / 0.75", BinWriter.buildInverseEquation("0.75 * X - 48.0", "X"))
    }

    @Test
    fun `KFLDRL typical equation 0_390625 times X`() {
        assertEquals("X / 0.390625", BinWriter.buildInverseEquation("0.390625 * X", "X"))
    }

    @Test
    fun `KFMIRL typical equation 0_023438 times X`() {
        assertEquals("X / 0.023438", BinWriter.buildInverseEquation("0.023438 * X", "X"))
    }

    @Test
    fun `RPM axis typical equation 40 times X`() {
        assertEquals("X / 40.0", BinWriter.buildInverseEquation("40.0 * X", "X"))
    }
}
