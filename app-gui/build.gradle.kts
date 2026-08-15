import buildsrc.tasks.MacPackageVersion
import buildsrc.tasks.VerifyMacPackageMetadata
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JvmVendorSpec
import org.panteleyev.jpackage.ImageType
import org.panteleyev.jpackage.JPackageTask
import java.util.jar.JarFile

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
// (Windows .msi, macOS .dmg, Linux .deb/.rpm). The image type and destination
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

// macOS rejects CFBundleVersion values beginning with zero. Keep the public
// marketing version in CFBundleShortVersionString while supplying jpackage a
// positive internal bundle version. The override supports future release
// policies without creating a second required version setting.
val macBundleVersion = providers.gradleProperty("instagene.macBundleVersion")
    .orElse(instaGeneVersion.map(MacPackageVersion::defaultFor))
    .map(MacPackageVersion::requireValid)

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
            val panelClass = jar.getJarEntry("org/instagene/app/gui/tool/NcbiAnalysisPanel.class")
                ?: error("Packaged GUI jar is stale: NcbiAnalysisPanel.class is missing")
            val bytecode = jar.getInputStream(panelClass).use { it.readBytes() }.toString(Charsets.ISO_8859_1)
            check("ncbiSharedQuery" in bytecode) {
                "Packaged GUI jar is stale: shared NCBI/GenBank query control is missing"
            }
        }
    }
}

// A stable app image name per OS for the zip fallback: InstaGene / InstaGene.app.
val osIs = { needle: String ->
    providers.systemProperty("os.name").map { it.lowercase().contains(needle) }
}
val appImageName = osIs("mac").map { if (it) "InstaGene.app" else "InstaGene" }

val packagingJavaLauncher = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(21))
    vendor.set(JvmVendorSpec.ADOPTIUM)
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
    // native verbose output available so CI can expose the real macOS error.
    verbose = true
}

tasks.jpackage {
    configureInstaGenePackage()
    destination = layout.buildDirectory.dir(jpackageDest)
    type = jpackageType.map { ImageType.valueOf(it) }

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

// Build the macOS application bundle separately from the DMG. The JDK's DMG
// path relies on additional Finder/AppleScript behavior that is brittle on
// hosted runners; hdiutil can wrap the completed app image directly.
val macJpackageResources = layout.buildDirectory.dir("jpackage/mac-resources")
val prepareMacJpackageResources = tasks.register<Sync>("prepareMacJpackageResources") {
    group = "distribution"
    description = "Generates macOS jpackage resources with the public marketing version."
    inputs.property("instagene.version", instaGeneVersion)
    from(layout.projectDirectory.dir("src/jpackage/macos")) {
        expand("INSTAGENE_MARKETING_VERSION" to instaGeneVersion.get())
    }
    into(macJpackageResources)
}

val verifyMacPackageMetadata = tasks.register<VerifyMacPackageMetadata>("verifyMacPackageMetadata") {
    group = "verification"
    description = "Verifies macOS marketing and bundle version metadata."
    dependsOn(prepareMacJpackageResources)
    val generatedTemplate = macJpackageResources.map { it.file("Info-lite.plist.template") }
    plistTemplate = generatedTemplate
    marketingVersion = instaGeneVersion
    bundleVersion = macBundleVersion
}

val macAppImage = tasks.register<JPackageTask>("macAppImage") {
    group = "distribution"
    description = "Builds the unsigned macOS InstaGene.app image."
    configureInstaGenePackage()
    dependsOn(verifyMacPackageMetadata)
    appVersion = macBundleVersion.get()
    type = ImageType.APP_IMAGE
    destination = layout.buildDirectory.dir("jpackage/mac-app-image")
    resourceDir = macJpackageResources
}

tasks.check {
    dependsOn(verifyMacPackageMetadata)
}

val macDmgStage = layout.buildDirectory.dir("jpackage/mac-dmg-stage")
val stagedMacApp = macDmgStage.map { it.dir("InstaGene.app") }
val macApplicationsLinkTarget = "/Applications"
val macDmgOutput = layout.buildDirectory.file(
    instaGeneVersion.map { "jpackage/dist/InstaGene-$it.dmg" },
)

val cleanMacDmgStage = tasks.register<Delete>("cleanMacDmgStage") {
    delete(macDmgStage)
}

val prepareMacDmgStage = tasks.register<Exec>("prepareMacDmgStage") {
    group = "distribution"
    description = "Stages InstaGene.app and the Applications shortcut for the DMG."
    dependsOn(macAppImage, cleanMacDmgStage)

    val sourceApp = macAppImage.flatMap { it.destination.dir("InstaGene.app") }
    inputs.dir(sourceApp)

    if (!System.getProperty("os.name").lowercase().contains("mac")) {
        commandLine(
            "/bin/bash", "-c",
            "echo 'prepareMacDmgStage can only run on macOS' >&2; exit 1",
        )
    } else {
        commandLine(
            "/bin/bash", "-eu", "-o", "pipefail", "-c",
            """
                /bin/mkdir -p "${'$'}2"
                /usr/bin/ditto "${'$'}1" "${'$'}3"
                /bin/ln -s "$macApplicationsLinkTarget" "${'$'}2/Applications"
            """.trimIndent(),
            "prepareMacDmgStage",
            sourceApp.get().asFile.absolutePath,
            macDmgStage.get().asFile.absolutePath,
            stagedMacApp.get().asFile.absolutePath,
        )
    }
}

tasks.register<Exec>("macDmg") {
    group = "distribution"
    description = "Builds the unsigned macOS DMG using the packaged InstaGene.app image."
    dependsOn(prepareMacDmgStage)
    inputs.dir(stagedMacApp).withPropertyName("stagedMacApp")
    inputs.property("applicationsLinkTarget", macApplicationsLinkTarget)
    outputs.file(macDmgOutput)

    if (!System.getProperty("os.name").lowercase().contains("mac")) {
        commandLine(
            "/bin/bash", "-c",
            "echo 'macDmg can only run on macOS' >&2; exit 1",
        )
    } else {
        commandLine(
            "/bin/bash", "-eu", "-o", "pipefail", "-c",
            """
                /bin/mkdir -p "${'$'}1"
                /usr/bin/hdiutil create -volname InstaGene -srcfolder "${'$'}2" -ov -format UDZO "${'$'}3"
            """.trimIndent(),
            "macDmg",
            macDmgOutput.get().asFile.parentFile.absolutePath,
            macDmgStage.get().asFile.absolutePath,
            macDmgOutput.get().asFile.absolutePath,
        )
    }
}

// The low-friction fallback: a plain zip of the app image, so users get a
// double-click app without installing anything (run with -PjpackageType=APP_IMAGE).
tasks.register<Zip>("jpackageAppImageZip") {
    group = "distribution"
    description = "Zips the jpackage app image (run with -PjpackageType=APP_IMAGE first)."
    dependsOn(tasks.jpackage)
    archiveFileName = "instagene-app-image.zip"
    destinationDirectory = layout.buildDirectory.dir("jpackage")
    from(tasks.jpackage.flatMap { it.destination.dir(appImageName) })
}
