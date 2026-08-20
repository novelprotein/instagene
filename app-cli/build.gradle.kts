plugins {
    id("buildsrc.convention.kotlin-jvm")
    application
    alias(libs.plugins.graalvmNative)
}

graalvmNative {
    binaries {
        named("main") {
            mainClass.set("org.instagene.app.cli.CliMainKt")
            imageName.set("instagene")
            buildArgs.addAll(
                "-H:+ReportExceptionStackTraces",
                "--no-fallback",
                "-march=compatibility",
                "--initialize-at-run-time=kotlin",
            )
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