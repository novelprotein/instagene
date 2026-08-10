plugins {
    // The Kotlin DSL plugin provides a convenient way to develop convention plugins.
    // Convention plugins are located in `src/main/kotlin`, with the file extension `.gradle.kts`,
    // and are applied in the project's `build.gradle.kts` files as required.
    `kotlin-dsl`
}

kotlin {
    // buildSrc is a nested build: its own settings files (settings.gradle.kts next
    // to this script) applies the foojay resolver, so this toolchain request is
    // served by explicit toolchain repositories instead of the deprecated
    // auto-provisioning path (the locally installed Ubuntu JDK 21 is a JRE and
    // lacks a compiler, hence the explicit pin).
    jvmToolchain(21)
}

dependencies {
    // Add a dependency on the Kotlin Gradle plugin, so that convention plugins can apply it.
    implementation(libs.kotlinGradlePlugin)
}
