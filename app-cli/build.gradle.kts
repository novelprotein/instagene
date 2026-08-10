plugins {
    kotlin("jvm")
    id("buildsrc.convention.kotlin-jvm")
    // The application plugin adds installDist/distZip, so the CLI ships as a
    // plain zip (`instagene-cli.zip`: bin script + jars) on any OS.
    application
}

application {
    mainClass.set("org.instagene.app.cli.CliMainKt")
}

distributions {
    main {
        distributionBaseName = "instagene-cli"
    }
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