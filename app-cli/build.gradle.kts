plugins {
    kotlin("jvm")
    id("buildsrc.convention.kotlin-jvm")
}

dependencies {
    implementation(project(":engine"))
}

tasks.register<JavaExec>("runCli") {
    group = "application"
    description = "Runs the command-line front-end directly."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.instagene.app.cli.CliMainKt")
}
