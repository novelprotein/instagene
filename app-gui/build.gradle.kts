plugins {
    kotlin("jvm")
    id("buildsrc.convention.kotlin-jvm")
}

dependencies {
    implementation(project(":engine"))
    implementation(libs.flatlaf)
    implementation(libs.flatlafIntelliJ)
}

tasks.register<JavaExec>("runGui") {
    group = "application"
    description = "Runs the Swing desktop front-end directly."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.instagene.app.gui.GuiMainKt")
    // Genome-scale FASTA files need a big heap; the JVM default (25% of RAM)
    // OOMs silently on multi-GB files. Override with -Pinstagene.heap=4g.
    val heap = providers.gradleProperty("instagene.heap").orNull
        ?: providers.systemProperty("instagene.heap").orNull
        ?: "8g"
    jvmArgs("-Xmx$heap")
}
