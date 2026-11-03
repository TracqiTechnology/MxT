package support

import java.util.prefs.Preferences
import kotlin.test.*

class InMemoryPreferencesFactoryTest {
    @Test
    fun `test workers use private roots and preserve node lifecycle semantics`() {
        assertTrue(Preferences.userRoot().javaClass.name.contains("InMemoryPreferencesFactory"))
        val first = InMemoryPreferencesFactory()
        val second = InMemoryPreferencesFactory()
        first.userRoot().node("calibration").put("header", "rpm")
        first.userRoot().flush()
        assertEquals("rpm", first.userRoot().node("calibration").get("header", null))
        assertNull(first.systemRoot().node("calibration").get("header", null))
        assertNull(second.userRoot().node("calibration").get("header", null))
        first.userRoot().node("calibration").removeNode()
        assertFalse(first.userRoot().nodeExists("calibration"))
        assertNull(first.userRoot().node("calibration").get("header", null))
    }
}
