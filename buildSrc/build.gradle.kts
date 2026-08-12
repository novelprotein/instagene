plugins {
    `kotlin-dsl`
}

kotlin {
    // buildSrc is a nested build. Its settings file applies the Foojay resolver,
    // so explicit toolchain repositories serve this request instead of Gradle's
    // deprecated auto-provisioning path (the locally installed Ubuntu JDK 21 is a JRE and
    // lacks a compiler, hence the explicit pin).
    jvmToolchain(21)
}

dependencies {
    // Makes the Kotlin plugin available to convention plugins.
    implementation(libs.kotlinGradlePlugin)
}
