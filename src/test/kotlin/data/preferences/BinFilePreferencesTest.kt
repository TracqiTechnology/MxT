package data.preferences

import data.preferences.bin.BinFilePreferences
import data.preferences.xdf.XdfFilePreferences
import data.preferences.kp.KpFilePreferences
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File
import java.util.prefs.Preferences

class BinFilePreferencesTest {
    private lateinit var savedFile: File

    @BeforeEach
    fun saveState() {
        savedFile = BinFilePreferences.getStoredFile()
    }

    @AfterEach
    fun cleanup() {
        if (savedFile.path.isEmpty()) BinFilePreferences.clear()
        else BinFilePreferences.setFile(savedFile)
    }

    @Test
    fun `setFile persists path to backing store`() {
        val testFile = File.createTempFile("pref_test_", ".bin")
        try {
            BinFilePreferences.setFile(testFile)

            val freshPrefs = Preferences.userRoot().node(BinFilePreferences::class.java.name)
            freshPrefs.sync()
            val storedPath = freshPrefs.get("file_path_key", "")

            assertEquals(testFile.absolutePath, storedPath)
        } finally {
            testFile.delete()
        }
    }

    @Test
    fun `setFile updates StateFlow`() {
        val testFile = File.createTempFile("pref_test_", ".bin")
        try {
            BinFilePreferences.setFile(testFile)
            assertEquals(testFile.absolutePath, BinFilePreferences.file.value.absolutePath)
        } finally {
            testFile.delete()
        }
    }

    @Test
    fun `clear resets StateFlow and backing store`() {
        val testFile = File.createTempFile("pref_test_", ".bin")
        try {
            BinFilePreferences.setFile(testFile)
            BinFilePreferences.clear()

            assertEquals("", BinFilePreferences.file.value.path)
            assertEquals("", BinFilePreferences.getStoredFile().path)
        } finally {
            testFile.delete()
        }
    }

    @Test
    fun `getStoredFile reads from backing store not StateFlow`() {
        val testFile = File.createTempFile("pref_test_", ".bin")
        try {
            BinFilePreferences.setFile(testFile)
            val stored = BinFilePreferences.getStoredFile()
            assertEquals(testFile.absolutePath, stored.absolutePath)
        } finally {
            testFile.delete()
        }
    }
}

class XdfFilePreferencesTest {
    private lateinit var savedFile: File

    @BeforeEach
    fun saveState() {
        savedFile = XdfFilePreferences.getStoredFile()
    }

    @AfterEach
    fun cleanup() {
        if (savedFile.path.isEmpty()) XdfFilePreferences.clear()
        else XdfFilePreferences.setFile(savedFile)
    }

    @Test
    fun `setFile persists path to backing store`() {
        val testFile = File.createTempFile("pref_test_", ".xdf")
        try {
            XdfFilePreferences.setFile(testFile)

            val freshPrefs = Preferences.userRoot().node(XdfFilePreferences::class.java.name)
            freshPrefs.sync()
            val storedPath = freshPrefs.get("file_path_key", "")

            assertEquals(testFile.absolutePath, storedPath)
        } finally {
            testFile.delete()
        }
    }

    @Test
    fun `setFile updates StateFlow`() {
        val testFile = File.createTempFile("pref_test_", ".xdf")
        try {
            XdfFilePreferences.setFile(testFile)
            assertEquals(testFile.absolutePath, XdfFilePreferences.file.value.absolutePath)
        } finally {
            testFile.delete()
        }
    }

    @Test
    fun `clear resets StateFlow and backing store`() {
        val testFile = File.createTempFile("pref_test_", ".xdf")
        try {
            XdfFilePreferences.setFile(testFile)
            XdfFilePreferences.clear()

            assertEquals("", XdfFilePreferences.file.value.path)
            assertEquals("", XdfFilePreferences.getStoredFile().path)
        } finally {
            testFile.delete()
        }
    }

    @Test
    fun `getStoredFile reads from backing store not StateFlow`() {
        val testFile = File.createTempFile("pref_test_", ".xdf")
        try {
            XdfFilePreferences.setFile(testFile)
            val stored = XdfFilePreferences.getStoredFile()
            assertEquals(testFile.absolutePath, stored.absolutePath)
        } finally {
            testFile.delete()
        }
    }
}

class KpFilePreferencesTest {
    private lateinit var savedFile: File

    @BeforeEach
    fun saveState() {
        savedFile = KpFilePreferences.getStoredFile()
    }

    @AfterEach
    fun cleanup() {
        if (savedFile.path.isEmpty()) KpFilePreferences.clear()
        else KpFilePreferences.setFile(savedFile)
    }

    @Test
    fun `setFile persists path to backing store`() {
        val testFile = File.createTempFile("pref_test_", ".kp")
        try {
            KpFilePreferences.setFile(testFile)

            val freshPrefs = Preferences.userRoot().node(KpFilePreferences::class.java.name)
            freshPrefs.sync()
            val storedPath = freshPrefs.get("kp_file_path_key", "")

            assertEquals(testFile.absolutePath, storedPath)
        } finally {
            testFile.delete()
        }
    }

    @Test
    fun `setFile updates StateFlow`() {
        val testFile = File.createTempFile("pref_test_", ".kp")
        try {
            KpFilePreferences.setFile(testFile)
            assertEquals(testFile.absolutePath, KpFilePreferences.file.value.absolutePath)
        } finally {
            testFile.delete()
        }
    }

    @Test
    fun `clear resets StateFlow and backing store`() {
        val testFile = File.createTempFile("pref_test_", ".kp")
        try {
            KpFilePreferences.setFile(testFile)
            KpFilePreferences.clear()

            assertEquals("", KpFilePreferences.file.value.path)
            assertEquals("", KpFilePreferences.getStoredFile().path)
        } finally {
            testFile.delete()
        }
    }

    @Test
    fun `getStoredFile reads from backing store not StateFlow`() {
        val testFile = File.createTempFile("pref_test_", ".kp")
        try {
            KpFilePreferences.setFile(testFile)
            val stored = KpFilePreferences.getStoredFile()
            assertEquals(testFile.absolutePath, stored.absolutePath)
        } finally {
            testFile.delete()
        }
    }
}
