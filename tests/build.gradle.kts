
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
}

// Read via Gradle's providers so the IDE resolves them in the script (System.getProperty is a
// java.lang import that the Kotlin build-script analyzer cannot always resolve). Passed as
// -Dinstagene.heap=4g on the Gradle command line.
val instageneHeap = providers.systemProperty("instagene.heap").orNull ?: "512m"
val instagenePerf = providers.systemProperty("instagene.perf").isPresent || project.hasProperty("perf")

tasks.test {

    maxHeapSize = instageneHeap
    useJUnitPlatform()
    // Swing smoke tests construct components without a display.
    systemProperty("java.awt.headless", "true")
    // Forward the opt-in performance benchmark flag to the test JVM.
    if (instagenePerf) {
        systemProperty("instagene.perf", "true")
    }
    // Keep CLI tests from polluting stdout of the Gradle console excessively.
    testLogging {
        showStandardStreams = false
    }
}
