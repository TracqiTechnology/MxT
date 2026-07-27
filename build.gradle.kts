import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

val appVersion = project.property("app.version") as String
val pkgVersion = project.findProperty("pkg.version") as String?
    ?: appVersion.replace(Regex("-.*"), "")

repositories {
    mavenCentral()
    google()
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

@Suppress("DEPRECATION")
dependencies {
    // Compose Desktop — use explicit platform dep on CI so uber JARs bundle skiko natives correctly
    if (System.getenv("CI") != null) {
        val osName = System.getProperty("os.name").lowercase()
        val osArch = System.getProperty("os.arch").lowercase()
        when {
            osName.contains("win") -> implementation(compose.desktop.windows_x64)
            osName.contains("mac") && osArch.contains("aarch64") -> implementation(compose.desktop.macos_arm64)
            osName.contains("mac") -> implementation(compose.desktop.macos_x64)
            else -> implementation(compose.desktop.linux_x64)
        }
    } else {
        implementation(compose.desktop.currentOs)
    }
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.1")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")

    // Kept from original project
    implementation("org.apache.commons:commons-math3:3.6.1")
    implementation("org.apache.commons:commons-csv:1.8")
    implementation("org.graalvm.js:js:22.3.4")
    implementation("org.graalvm.js:js-scriptengine:22.3.4")
    implementation("org.jdom:jdom2:2.0.6.1")

    // Serial port communication (native KWP2000/UDS logger)
    implementation("com.fazecast:jSerialComm:2.10.4")

    // Removed: jfreechart, rxjava, flatlaf, flatlaf-intellij-themes

    // Testing
    testImplementation(kotlin("test"))
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
    testImplementation(compose.uiTest)
}

tasks.withType<Test> {
    useJUnitPlatform()
    // The app uses process-wide singleton state and java.util.prefs. Keep tests
    // deterministic and prevent them from reading or modifying the developer's
    // real desktop preferences.
    maxParallelForks = 1
    val isolatedPreferences = layout.buildDirectory.dir("test-preferences/$name")
    systemProperty("java.util.prefs.userRoot", isolatedPreferences.get().asFile.absolutePath)
    systemProperty("java.util.prefs.systemRoot", isolatedPreferences.get().asFile.absolutePath)
    doFirst {
        val directory = isolatedPreferences.get().asFile
        check(!directory.exists() || directory.deleteRecursively()) {
            "Could not clear isolated test preferences at ${directory.absolutePath}"
        }
        check(directory.mkdirs()) {
            "Could not create isolated test preferences at ${directory.absolutePath}"
        }
    }
}

tasks.register<JavaExec>("screenshots") {
    mainClass.set("ScreenshotHarnessKt")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = projectDir
}

tasks.register<JavaExec>("cli") {
    mainClass.set("cli.MxTCliKt")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = projectDir
    standardInput = System.`in`
    // GraalVM Truffle needs module opens for XDF formula evaluation
    jvmArgs = listOf(
        "--add-opens", "java.base/java.lang=ALL-UNNAMED",
        "--add-opens", "java.base/java.nio=ALL-UNNAMED",
        "--add-opens", "java.base/java.io=ALL-UNNAMED",
        "--add-opens", "java.base/java.util=ALL-UNNAMED"
    )
}

val generatedVersionResources = layout.buildDirectory.dir("generated/versionResources")
sourceSets.main {
    resources.srcDir(generatedVersionResources)
}

tasks.register("generateVersionFile") {
    val outputFile = generatedVersionResources.map { it.file("version.txt") }
    inputs.property("appVersion", appVersion)
    outputs.file(outputFile)
    doLast {
        outputFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(appVersion)
        }
    }
}

tasks.named("processResources") { dependsOn("generateVersionFile") }

compose.desktop {
    application {
        mainClass = "MainKt"

        // GraalVM Truffle needs module opens on stock Temurin JDK
        jvmArgs += listOf(
            "--add-opens", "java.base/java.lang=ALL-UNNAMED",
            "--add-opens", "java.base/java.nio=ALL-UNNAMED",
            "--add-opens", "java.base/java.io=ALL-UNNAMED",
            "--add-opens", "java.base/java.util=ALL-UNNAMED",
        )

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "MxT"
            packageVersion = pkgVersion
            description = "Mx Tuner — Motronic ECU Calibration Tool"

            includeAllModules = true

            macOS {
                bundleID = "com.tracqi.mxt"
                iconFile.set(project.file("src/main/resources/icons/icon.icns"))
            }

            windows {
                upgradeUuid = "e4a5b6c7-d8e9-4f0a-b1c2-d3e4f5a6b7c8"
                iconFile.set(project.file("src/main/resources/icons/icon.ico"))
                // jpackage creates no launcher shortcuts by default — opt in explicitly.
                menu = true          // Start Menu entry
                menuGroup = "MxT"    // grouped under a MxT folder
                shortcut = true      // Desktop shortcut
            }

            linux {
                iconFile.set(project.file("src/main/resources/icons/icon.png"))
                // Like Windows, the .deb registers no app-menu entry unless opted in.
                shortcut = true
                menuGroup = "MxT"
                appCategory = "Utility"
            }
        }
    }
}
