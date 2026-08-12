import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.panteleyev.jpackage.ImageType

plugins {
    kotlin("jvm")
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlinPluginSerialization)
    alias(libs.plugins.jpackagePlugin)
}

dependencies {
    implementation(project(":engine"))
    implementation(libs.kotlinxSerialization)
    implementation(libs.flatlaf)
    implementation(libs.flatlafIntelliJ)
}

tasks.register<JavaExec>("runGui") {
    group = "application"
    description = "Runs the Swing desktop front-end directly."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.instagene.app.gui.ui.GuiMainKt")
    // Genome-scale FASTA files need a large heap; the JVM default (25% of RAM)
    // can be exhausted by multi-GB files. Override with -Pinstagene.heap=4g.
    val heap = providers.gradleProperty("instagene.heap").orNull
        ?: providers.systemProperty("instagene.heap").orNull
        ?: "8g"
    jvmArgs("-Xmx$heap")
}

// ------------------------------------------------------------------ packaging

// jpackage cannot cross-compile: each native installer is built on its own OS
// (Windows .msi, macOS .dmg, Linux .deb/.rpm). The image type and destination
// are driven by -Pjpackage.type / -Pjpackage.dest so one definition serves the
// whole CI matrix; the default builds the OS-native image locally.
val jpackageType = providers.gradleProperty("jpackage.type").map { it.uppercase() }.orElse("DEFAULT")
val jpackageDest = providers.gradleProperty("jpackage.dest").orElse("jpackage/dist")

// Fixed jar name so --main-jar is stable regardless of Gradle's archive naming.
tasks.jar {
    archiveFileName = "instagene.jar"
}

// Everything jpackage needs in one flat input dir: the app jar plus its runtime deps.
val prepareJpackageInput = tasks.register<Sync>("prepareJpackageInput") {
    description = "Copies the app jar and runtime dependencies into the flat jpackage input directory."
    from(sourceSets.main.get().output)
    from(tasks.jar)
    from(configurations.runtimeClasspath)
    into(layout.buildDirectory.dir("jpackage/input"))
}

// A stable app image name per OS for the zip fallback: InstaGene / InstaGene.app.
val osIs = { needle: String ->
    providers.systemProperty("os.name").map { it.lowercase().contains(needle) }
}
val appImageName = osIs("mac").map { if (it) "InstaGene.app" else "InstaGene" }

tasks.jpackage {
    dependsOn(prepareJpackageInput, tasks.jar)

    // jpackage links a runtime image with the JDK it runs on; pin it to the
    // same toolchain as the rest of the build so it cannot fall back to a
    // different JAVA_HOME (for example, a runtime without packaging modules).
    javaLauncher = javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    }

    appName = "InstaGene"
    appVersion = providers.gradleProperty("instagene.version").orElse("0.0.0").get()
    mainClass = "org.instagene.app.gui.GuiMainKt"
    mainJar = "instagene.jar"
    input = layout.buildDirectory.dir("jpackage/input")
    destination = layout.buildDirectory.dir(jpackageDest)
    vendor = "InstaGene"
    appDescription = "DNA/RNA editing and plasmid construction."
    copyright = "InstaGene contributors"
    type = jpackageType.map { ImageType.valueOf(it) }
    javaOptions = listOf("-Xmx8g")

    // Low-friction installs: shortcuts on every platform, no icons (added later).
    // jpackage rejects OS-specific options when the package type is not the
    // matching native type (e.g. --linux-shortcut with --type app-image), so
    // each OS group is only applied for its package types (or the native
    // DEFAULT of that OS).
    val useWindowsOpts = providers.zip(jpackageType, osIs("windows")) { t, isWin ->
        t in setOf("MSI", "EXE") || (t == "DEFAULT" && isWin)
    }
    val useMacOpts = providers.zip(jpackageType, osIs("mac")) { t, isMac ->
        t in setOf("DMG", "PKG") || (t == "DEFAULT" && isMac)
    }
    // jpackage validates each package type: DEB accepts --linux-deb-maintainer,
    // while RPM accepts --linux-rpm-license-type, so these groups must not mix.
    val useDebOpts = providers.zip(jpackageType, osIs("linux")) { t, isLinux ->
        t == "DEB" || (t == "DEFAULT" && isLinux)
    }
    val useRpmOpts = jpackageType.map { it == "RPM" }
    val useLinuxCommonOpts = providers.zip(useDebOpts, useRpmOpts) { deb, rpm -> deb || rpm }
    linuxShortcut = useLinuxCommonOpts
    linuxPackageName = useLinuxCommonOpts.map { if (it) "instagene" else null }
    linuxAppCategory = useLinuxCommonOpts.map { if (it) "Science" else null }
    linuxDebMaintainer = useDebOpts.map { if (it) "InstaGene <instagene@novelprotein.github.io>" else null }
    linuxRpmLicenseType = useRpmOpts.map { if (it) "Apache-2.0" else null }
    winMenu = useWindowsOpts
    winShortcut = useWindowsOpts
    winPerUserInstall = useWindowsOpts
    macPackageIdentifier = useMacOpts.map { if (it) "org.instagene.InstaGene" else null }
    macSign = false
}

// The low-friction fallback: a plain zip of the app image, so users get a
// double-click app without installing anything (run with -Pjpackage.type=APP_IMAGE).
tasks.register<Zip>("jpackageAppImageZip") {
    group = "distribution"
    description = "Zips the jpackage app image (run with -Pjpackage.type=APP_IMAGE first)."
    dependsOn(tasks.jpackage)
    archiveFileName = "instagene-app-image.zip"
    destinationDirectory = layout.buildDirectory.dir("jpackage")
    from(tasks.jpackage.flatMap { it.destination.dir(appImageName) })
}
