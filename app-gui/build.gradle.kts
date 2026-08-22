import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JvmVendorSpec
import org.panteleyev.jpackage.ImageType
import org.panteleyev.jpackage.JPackageTask
import java.util.jar.JarFile

plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlinPluginSerialization)
    alias(libs.plugins.jpackagePlugin)
}

dependencies {
    implementation(project(":engine"))
    implementation(libs.kotlinxSerialization)
    implementation(libs.flatlaf)
    implementation(libs.flatlafIntelliJ)
    implementation(libs.jfreechart)
}

tasks.register<JavaExec>("runGui") {
    group = "application"
    description = "Runs the Swing desktop front-end directly."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.instagene.app.gui.GuiMainKt")
    // Genome-scale FASTA files need a large heap; the JVM default (25% of RAM)
    // can be exhausted by multi-GB files. Override with -Pinstagene.heap=4g.
    val heap = providers.gradleProperty("instagene.heap").orNull
        ?: providers.systemProperty("instagene.heap").orNull
        ?: "8g"
    jvmArgs("-Xmx$heap")
}

// ------------------------------------------------------------------ packaging

// jpackage cannot cross-compile: each native installer is built on its own OS
// (Windows .msi, Linux .deb/.rpm). The image type and destination
// are driven by -PjpackageType (or the backward-compatible jpackage.type) and
// -Pjpackage.dest so one definition serves the whole CI matrix; the default
// builds the OS-native image locally. The dotless alias is safe in PowerShell.
val jpackageType = providers.gradleProperty("jpackage.type")
    .orElse(providers.gradleProperty("jpackageType"))
    .map { it.uppercase() }
    .orElse("DEFAULT")
val defaultJpackageDest = jpackageType.map { if (it == "APP_IMAGE") "jpackage/app-image-dist" else "jpackage/dist" }
val jpackageDest = providers.gradleProperty("jpackage.dest").orElse(defaultJpackageDest)
val instaGeneVersion = providers.gradleProperty("instagene.version").orElse("0.0.0")

// Fixed jar name so --main-jar is stable regardless of Gradle's archive naming.
tasks.jar {
    archiveFileName = "instagene.jar"
}

// A portable java -jar distribution. Keep this separate from the thin
// instagene.jar above: jpackage expects dependencies as individual input jars,
// while users downloading a CI artifact need one self-contained file.
val standaloneRuntimeClasspath = configurations.runtimeClasspath
val standaloneJar = tasks.register<Jar>("standaloneJar") {
    group = "distribution"
    description = "Builds a self-contained, runnable InstaGene GUI jar."
    // Expanding resolved files with zipTree does not retain their producing
    // task dependencies, so carry the configuration's build dependencies
    // explicitly. This guarantees project dependency jars exist on clean CI.
    dependsOn(tasks.classes, standaloneRuntimeClasspath)
    archiveFileName = "instagene-gui.jar"
    destinationDirectory = layout.buildDirectory.dir("distributions")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = "org.instagene.app.gui.GuiMainKt"
    }
    from(sourceSets.main.get().output)
    from({
        standaloneRuntimeClasspath.get()
            .map { zipTree(it) }
    })
    // Dependency signatures are invalid once their contents are repackaged.
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "META-INF/INDEX.LIST")
}

val verifyStandaloneJar = tasks.register("verifyStandaloneJar") {
    group = "verification"
    description = "Verifies the standalone GUI jar manifest and bundled runtime classes."
    dependsOn(standaloneJar)
    val archive = standaloneJar.flatMap { it.archiveFile }
    inputs.file(archive)
    doLast {
        val file = archive.get().asFile
        check(file.isFile) { "Missing standalone GUI jar: $file" }
        JarFile(file).use { jar ->
            check(jar.manifest.mainAttributes.getValue("Main-Class") == "org.instagene.app.gui.GuiMainKt") {
                "Standalone GUI jar has no runnable Main-Class"
            }
            listOf(
                "org/instagene/app/gui/GuiMainKt.class",
                "org/instagene/core/NcbiClient.class",
                "kotlin/jvm/internal/Intrinsics.class",
                "kotlinx/serialization/json/Json.class",
                "com/formdev/flatlaf/FlatLightLaf.class",
            ).forEach { entry ->
                check(jar.getJarEntry(entry) != null) { "Standalone GUI jar is missing $entry" }
            }
        }
    }
}

// Everything jpackage needs in one flat input dir: the app jar plus its runtime deps.
val prepareJpackageInput = tasks.register<Sync>("prepareJpackageInput") {
    description = "Copies the app jar and runtime dependencies into the flat jpackage input directory."
    from(sourceSets.main.get().output)
    from(tasks.jar)
    from(configurations.runtimeClasspath)
    into(layout.buildDirectory.dir("jpackage/input"))
}

val verifyJpackageInput = tasks.register("verifyJpackageInput") {
    group = "verification"
    description = "Verifies that the packaged GUI jar contains the current GenBank fetch controls."
    dependsOn(prepareJpackageInput)
    val packagedJar = layout.buildDirectory.file("jpackage/input/instagene.jar")
    inputs.file(packagedJar)
    doLast {
        val file = packagedJar.get().asFile
        check(file.isFile) { "Missing packaged GUI jar: $file" }
        JarFile(file).use { jar ->
            val panelClass = jar.getJarEntry("org/instagene/app/gui/analysis/NcbiAnalysisPanel.class")
                ?: error("Packaged GUI jar is stale: NcbiAnalysisPanel.class is missing")
            val bytecode = jar.getInputStream(panelClass).use { it.readBytes() }.toString(Charsets.ISO_8859_1)
            check("ncbiSharedQuery" in bytecode) {
                "Packaged GUI jar is stale: shared NCBI/GenBank query control is missing"
            }
        }
    }
}

// A stable app image name for the Linux and Windows zip fallback.
val osIs = { needle: String ->
    providers.systemProperty("os.name").map { it.lowercase().contains(needle) }
}
val appImageName = "InstaGene"

val packagingJavaLauncher = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(21))
    vendor.set(JvmVendorSpec.GRAAL_VM)
}

fun macCompatibleJpackageVersion(version: String): String {
    require(Regex("""\d+(\.\d+){0,2}""").matches(version)) {
        "macOS jpackage app-version must be one to three dot-separated integers: $version"
    }
    return version.split(".")
        .mapIndexed { index, part -> if (index == 0 && part.toInt() <= 0) "1" else part }
        .joinToString(".")
}

fun JPackageTask.configureInstaGenePackage() {
    dependsOn(verifyJpackageInput, tasks.jar)

    // jpackage links a runtime image with the JDK it runs on; pin it to the
    // same toolchain as the rest of the build so it cannot fall back to a
    // different JAVA_HOME (for example, a runtime without packaging modules).
    javaLauncher = packagingJavaLauncher

    appName = "InstaGene"
    appVersion = instaGeneVersion.get()
    mainClass = "org.instagene.app.gui.GuiMainKt"
    mainJar = "instagene.jar"
    input = layout.buildDirectory.dir("jpackage/input")
    vendor = "InstaGene"
    appDescription = "DNA/RNA editing and plasmid construction."
    copyright = "InstaGene contributors"
    javaOptions = listOf("-Xmx8g")
    // The plugin logs the jpackage process output at Gradle INFO level. Keep
    // native verbose output available so CI can expose packaging errors.
    verbose = true
}

tasks.jpackage {
    configureInstaGenePackage()
    destination = layout.buildDirectory.dir(jpackageDest)
    type = jpackageType.map { ImageType.valueOf(it) }

    // Low-friction installs: shortcuts on supported native platforms, no icons.
    // jpackage rejects OS-specific options when the package type is not the
    // matching native type (e.g. --linux-shortcut with --type app-image), so
    // each OS group is only applied for its package types (or the native
    // DEFAULT of that OS).
    val useWindowsOpts = providers.zip(jpackageType, osIs("windows")) { t, isWin ->
        t in setOf("MSI", "EXE") || (t == "DEFAULT" && isWin)
    }
    // jpackage validates each package type: DEB accepts --linux-deb-maintainer,
    // while RPM accepts --linux-rpm-license-type, so these groups must not mix.
    val useDebOpts = providers.zip(jpackageType, osIs("linux")) { t, isLinux ->
        t == "DEB" || (t == "DEFAULT" && isLinux)
    }
    val useRpmOpts = jpackageType.map { it == "RPM" }
    val useLinuxCommonOpts = providers.zip(useDebOpts, useRpmOpts) { deb, rpm -> deb || rpm }
    val useMacOpts = providers.zip(jpackageType, osIs("mac")) { t, isMac ->
        t in setOf("DMG", "PKG") || (t == "DEFAULT" && isMac)
    }
    appVersion = providers.zip(instaGeneVersion, useMacOpts) { version, useMac ->
        if (useMac) macCompatibleJpackageVersion(version) else version
    }.get()
    linuxShortcut = useLinuxCommonOpts
    linuxPackageName = useLinuxCommonOpts.map { if (it) "instagene" else null }
    linuxAppCategory = useLinuxCommonOpts.map { if (it) "Science" else null }
    linuxDebMaintainer = useDebOpts.map { if (it) "instagene@novelprotein.github.io" else null }
    linuxRpmLicenseType = useRpmOpts.map { if (it) "MIT" else null }
    winMenu = useWindowsOpts
    winShortcut = useWindowsOpts
    winPerUserInstall = useWindowsOpts
    macPackageIdentifier = useMacOpts.map { if (it) "io.novelprotein.instagene" else null }

    // Add the terminal wrapper after packaging: the 'instagene' script into the
    // app image root (APP_IMAGE), and /usr/bin/instagene into the .deb. The
    // tasks no-op for other package types.
    finalizedBy("injectAppImageLauncher", "repackDebWithLauncher")
}

// A terminal wrapper (`instagene`) so the desktop app can be launched from a
// shell: the CLI's `instagene gui` finds it on PATH, and users can just type
// `instagene`. jpackage only generates a launcher whose name matches the app
// (InstaGene), so the wrapper is injected after packaging.
val linuxLauncherScript = layout.projectDirectory.file("src/dist/linux/instagene")
val linuxDebRepackScript = layout.projectDirectory.file("src/dist/linux/repack-deb")

// APP_IMAGE: drop the wrapper into the app image root, next to bin/InstaGene.
// The script resolves its own directory and execs bin/InstaGene, so users can
// run ./instagene (or add the app image dir to PATH) straight after unzipping.
val injectAppImageLauncher = tasks.register("injectAppImageLauncher") {
    group = "distribution"
    description = "Adds the 'instagene' terminal wrapper to the jpackage app image."
    dependsOn(tasks.jpackage)
    val scriptFile = linuxLauncherScript.asFile
    val launcherFile = tasks.jpackage.map { it.destination.get().dir(appImageName).file("instagene") }
    val isAppImage = jpackageType.map { it == "APP_IMAGE" }
    inputs.file(scriptFile)
    outputs.file(launcherFile)
    onlyIf { isAppImage.get() }
    doLast {
        val destination = launcherFile.get().asFile
        scriptFile.copyTo(destination, overwrite = true)
        destination.setExecutable(true, false)
    }
}

// DEB: jpackage has no hook for PATH-level binaries, so repack the generated
// .deb to add /usr/bin/instagene (the wrapper falls back to /opt/instagene).
// Use Gradle's built-in Exec task instead of a buildSrc task type: this keeps
// the Kotlin DSL script directly understandable by both Gradle and IntelliJ.
val repackDebWithLauncher = tasks.register<Exec>("repackDebWithLauncher") {
    group = "distribution"
    description = "Repacks the built .deb to add /usr/bin/instagene."
    dependsOn(tasks.jpackage)
    val isDeb = jpackageType.map { it == "DEB" }
    val isLinux = osIs("linux")
    val packageDirectory = tasks.jpackage.flatMap { it.destination }
    val repackDirectory = layout.buildDirectory.dir("jpackage/deb-repack")
    inputs.file(linuxDebRepackScript)
    inputs.file(linuxLauncherScript)
    inputs.dir(packageDirectory)
    outputs.dir(repackDirectory)
    outputs.upToDateWhen { false }
    commandLine(
        linuxDebRepackScript.asFile.absolutePath,
        packageDirectory.get().asFile.absolutePath,
        linuxLauncherScript.asFile.absolutePath,
        repackDirectory.get().asFile.absolutePath,
    )
    onlyIf { isDeb.get() && isLinux.get() }
}

// The low-friction fallback: a plain zip of the app image, so users get a
// double-click app without installing anything (run with -PjpackageType=APP_IMAGE).
tasks.register<Zip>("jpackageAppImageZip") {
    group = "distribution"
    description = "Zips the jpackage app image (run with -PjpackageType=APP_IMAGE first)."
    dependsOn(tasks.jpackage, injectAppImageLauncher)
    archiveFileName = "instagene-app-image.zip"
    destinationDirectory = layout.buildDirectory.dir("jpackage")
    from(tasks.jpackage.flatMap { it.destination.dir(appImageName) })
}
