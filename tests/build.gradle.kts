
plugins {
    id("buildsrc.convention.kotlin-jvm")
}

dependencies {
    implementation(project(":engine"))
    implementation(project(":app-cli"))
    implementation(project(":app-gui"))
    implementation(project(":app-web"))

    testImplementation(kotlin("test"))
    testImplementation(libs.junitJupiter)
    testImplementation(libs.junitJupiterParams)
    testImplementation(libs.kotlinxSerialization)
}

// Read via Gradle's providers so the IDE resolves them in the script (System.getProperty is a
// java.lang import that the Kotlin build-script analyzer cannot always resolve). Passed as
// -Dinstagene.heap=4g on the Gradle command line.
val instageneHeap = providers.systemProperty("instagene.heap").orNull ?: "512m"
val instagenePerf = providers.systemProperty("instagene.perf").isPresent || project.hasProperty("perf")
val instageneMemoryProfile = providers.systemProperty("instagene.memoryProfile").isPresent

tasks.test {

    maxHeapSize = instageneHeap
    doNotTrackState("Gradle test binary result files are internal execution output and may be unavailable after failures.")
    // Swing smoke tests construct components without a display.
    systemProperty("java.awt.headless", "true")
    // Forward the opt-in performance benchmark flag to the test JVM.
    if (instagenePerf) {
        systemProperty("instagene.perf", "true")
    }
    // Kept opt-in: this profile creates multi-megabase sequence files and trace batches.
    if (instageneMemoryProfile) {
        systemProperty("instagene.memoryProfile", "true")
    }
    // Keep CLI tests from polluting stdout of the Gradle console excessively.
    testLogging {
        showStandardStreams = false
    }
}
