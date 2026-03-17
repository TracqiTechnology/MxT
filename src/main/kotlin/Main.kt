import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import data.parser.bin.BinParser
import data.parser.csv.WinOlsCsvParser
import data.parser.kp.KpHintParser
import data.parser.xdf.XdfParser
import data.preferences.MapPreferenceManager
import data.preferences.bin.BinFilePreferences
import data.preferences.csv.WinOlsCsvFileChooserPreferences
import data.preferences.csv.WinOlsCsvFilePreferences
import data.preferences.disclaimer.AlphaBetaDisclaimerPreferences
import data.preferences.eula.EulaPreferences
import data.preferences.filechooser.BinFileChooserPreferences
import data.preferences.filechooser.XdfFileChooserPreferences
import data.preferences.kp.KpFileChooserPreferences
import data.preferences.kp.KpFilePreferences
import data.preferences.logheaderdefinition.LogHeaderPreference
import data.preferences.platform.EcuPlatformPreference
import data.preferences.xdf.XdfFilePreferences
import data.profile.ProfileManager
import data.diagnostics.DiagnosticCollector
import ui.components.AlphaBetaDisclaimer
import ui.components.EulaDialog
import ui.navigation.MxTApp
import ui.theme.MxTTheme
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.net.URI
import java.util.Locale

fun main() {
    Locale.setDefault(Locale.ENGLISH)
    System.setProperty("apple.awt.application.appearance", "NSAppearanceNameDarkAqua")

    // Initialize data layer flows
    XdfParser.init()
    BinParser.init()
    KpHintParser.init()
    WinOlsCsvParser.init()
    LogHeaderPreference.loadHeaders()

    application {
        var eulaAccepted by remember { mutableStateOf(EulaPreferences.accepted) }

        if (!eulaAccepted) {
            Window(
                onCloseRequest = ::exitApplication,
                title = "TracQi MxT — License Agreement",
                state = rememberWindowState(width = 800.dp, height = 700.dp),
            ) {
                MxTTheme {
                    EulaDialog(
                        onAccept = {
                            EulaPreferences.accepted = true
                            eulaAccepted = true
                        },
                        onDecline = ::exitApplication,
                    )
                }
            }
            return@application
        }

        var disclaimerAccepted by remember { mutableStateOf(AlphaBetaDisclaimerPreferences.accepted) }

        if (!disclaimerAccepted) {
            Window(
                onCloseRequest = ::exitApplication,
                title = "TracQi MxT — Feature Stability",
                state = rememberWindowState(width = 800.dp, height = 700.dp),
            ) {
                MxTTheme {
                    AlphaBetaDisclaimer(
                        onAccept = {
                            AlphaBetaDisclaimerPreferences.accepted = true
                            disclaimerAccepted = true
                        },
                        onStickToME7 = {
                            AlphaBetaDisclaimerPreferences.accepted = true
                            disclaimerAccepted = true
                        },
                    )
                }
            }
            return@application
        }

        val binFile by BinFilePreferences.file.collectAsState()
        val xdfFile by XdfFilePreferences.file.collectAsState()
        val kpFile by KpFilePreferences.file.collectAsState()
        val csvFile by WinOlsCsvFilePreferences.file.collectAsState()

        val title = remember(binFile, xdfFile, kpFile, csvFile) {
            val platformLabel = EcuPlatformPreference.platform.shortName
            val kpSuffix = if (kpFile.exists()) " | WinOLS KP - ${kpFile.name}" else ""
            val csvSuffix = if (csvFile.exists()) " | WinOLS CSV - ${csvFile.name}" else ""
            "TracQi MxT ($platformLabel) - ${binFile.name} | XDF File - ${xdfFile.name}$kpSuffix$csvSuffix"
        }

        Window(
            onCloseRequest = ::exitApplication,
            title = title,
            state = rememberWindowState(width = 1480.dp, height = 1080.dp)
        ) {
            MenuBar {
                Menu("File") {
                    Item("Open Bin...") {
                        val file = openFileDialog(window, "Open Bin File", "bin", BinFileChooserPreferences.lastDirectory)
                        if (file != null) {
                            BinFilePreferences.setFile(file)
                            BinFileChooserPreferences.lastDirectory = file.parent
                        }
                    }
                }
                Menu("XDF") {
                    Item("Select XDF...") {
                        val file = openFileDialog(window, "Select XDF File", "xdf", XdfFileChooserPreferences.lastDirectory)
                        if (file != null) {
                            MapPreferenceManager.clear()
                            XdfFilePreferences.setFile(file)
                            XdfFileChooserPreferences.lastDirectory = file.parent
                        }
                    }
                }
                Menu("WinOLS") {
                    Item("Open KP File...") {
                        val file = openFileDialog(window, "Open WinOLS KP File", "kp", KpFileChooserPreferences.lastDirectory)
                        if (file != null) {
                            KpFilePreferences.setFile(file)
                            KpFileChooserPreferences.lastDirectory = file.parent
                        }
                    }
                    Item("Clear KP File") {
                        KpFilePreferences.clear()
                    }
                    Item("Open WinOLS CSV Export...") {
                        val file = openFileDialog(window, "Open WinOLS CSV Export", "csv", WinOlsCsvFileChooserPreferences.lastDirectory)
                        if (file != null) {
                            WinOlsCsvFilePreferences.setFile(file)
                            WinOlsCsvFileChooserPreferences.lastDirectory = file.parent
                        }
                    }
                    Item("Clear CSV Export") {
                        WinOlsCsvFilePreferences.clear()
                    }
                }
                Menu("Profiles") {
                    Item("Load Profile...") {
                        val file = openFileDialog(window, "Load Profile", "mxtprofile.json", "")
                        if (file != null) {
                            runCatching {
                                val profile = ProfileManager.loadFromFile(file)
                                ProfileManager.applyProfile(profile)
                                ProfileManager.addUserProfile(profile)
                            }
                        }
                    }
                    Item("Save Profile...") {
                        javax.swing.SwingUtilities.invokeLater {
                            val name = javax.swing.JOptionPane.showInputDialog(
                                window,
                                "Profile name:",
                                "Save Profile",
                                javax.swing.JOptionPane.PLAIN_MESSAGE
                            )
                            if (name != null && name.isNotBlank()) {
                                val dialog = FileDialog(window, "Save Profile", FileDialog.SAVE)
                                dialog.file = "${name.replace(Regex("[^a-zA-Z0-9_ -]"), "")}.mxtprofile.json"
                                dialog.isVisible = true
                                val dir = dialog.directory
                                val fileName = dialog.file
                                if (dir != null && fileName != null) {
                                    runCatching {
                                        val profile = ProfileManager.exportCurrentProfile(name)
                                        ProfileManager.saveToFile(profile, File(dir, fileName))
                                    }
                                }
                            }
                        }
                    }
                }
                Menu("Preferences") {
                    Item("Reset Preferences") {
                        MapPreferenceManager.clear()
                        LogHeaderPreference.loadHeaders()
                        XdfFilePreferences.clear()
                        BinFilePreferences.clear()
                        XdfFileChooserPreferences.clear()
                        BinFileChooserPreferences.clear()
                        KpFilePreferences.clear()
                        KpFileChooserPreferences.clear()
                    }
                }
                Menu("Help") {
                    Item("Report Issue...") {
                        val diagnostics = DiagnosticCollector.collect()
                        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                        clipboard.setContents(StringSelection(diagnostics), null)
                        if (Desktop.isDesktopSupported()) {
                            Desktop.getDesktop().browse(
                                URI("https://github.com/TracqiTechnology/MxT/issues/new?template=bug_report.yml")
                            )
                        }
                    }
                }
            }

            MxTTheme {
                MxTApp()
            }
        }
    }
}

private fun openFileDialog(parent: Frame, title: String, extension: String, initialDir: String): File? {
    val dialog = FileDialog(parent, title, FileDialog.LOAD)
    dialog.setFilenameFilter { _, name -> name.endsWith(".$extension", ignoreCase = true) }
    if (initialDir.isNotEmpty()) dialog.directory = initialDir
    dialog.isVisible = true
    val dir = dialog.directory
    val file = dialog.file
    return if (dir != null && file != null) File(dir, file) else null
}
