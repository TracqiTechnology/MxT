package data.preferences.a2l

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.prefs.Preferences

object A2lFilePreferences {
    private const val FILE_PATH_KEY = "file_path_key"
    private val prefs = Preferences.userRoot().node(A2lFilePreferences::class.java.name)

    private val _file = MutableStateFlow(getStoredFile())
    val file: StateFlow<File> = _file.asStateFlow()

    fun clear() {
        runCatching { prefs.clear(); prefs.flush() }
        _file.value = File("")
    }

    fun getStoredFile(): File = File(prefs.get(FILE_PATH_KEY, ""))

    fun setFile(file: File) {
        prefs.put(FILE_PATH_KEY, file.absolutePath)
        prefs.flush()
        _file.value = file
    }
}
