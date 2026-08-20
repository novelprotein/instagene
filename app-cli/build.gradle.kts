import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JvmVendorSpec

plugins {
    id("buildsrc.convention.kotlin-jvm")
    // The application plugin adds installDist/distZip, so the CLI ships as a
    // plain zip (`instagene-cli.zip`: bin script + jars) on any OS.
    application
    alias(libs.plugins.graalvmNative)
}

graalvmNative {
    binaries {
        named("main") {
            mainClass.set("org.instagene.app.cli.CliMainKt")
            // The CLI is headless — no AWT, no Swing, no FlatLaf. It
            // compiles cleanly to a native image without special configs.
            imageName.set("instagene")
            buildArgs.addAll(
                "-H:+ReportExceptionStackTraces",
                "--no-fallback",
                "-march=compatibility",
            )
            javaLauncher.set(javaToolchains.launcherFor {
                languageVersion.set(JavaLanguageVersion.of(21))
                vendor.set(JvmVendorSpec.GRAAL_VM)
            })
        }
    }
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

tasks.register<JavaExec>("bench") {
    group = "application"
    description = "Run performance benchmarks. Usage: ./gradlew :app-cli:bench [-Pinput=sequence.fa]"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.instagene.app.cli.CliMainKt")
    val input = providers.gradleProperty("input").orNull
    args = if (input.isNullOrBlank()) listOf("bench") else listOf("bench", input)
}