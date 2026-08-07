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

tasks.test {
    useJUnitPlatform()
    // Swing smoke tests construct components without a display.
    systemProperty("java.awt.headless", "true")
    // Keep CLI tests from polluting stdout of the Gradle console excessively.
    testLogging {
        showStandardStreams = false
    }
}
