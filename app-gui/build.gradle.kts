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
}
